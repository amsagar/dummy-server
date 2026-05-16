package com.pods.agent.ruledomain.compiler.agentic;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.compiler.CompilerPromptBuilder;
import com.pods.agent.ruledomain.compiler.trace.ExecutionTrace;
import com.pods.agent.ruledomain.compiler.trace.TraceBasedBpmnCompiler;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multi-turn, tool-using BPMN compile loop. Replaces the old single-shot
 * "stuff everything into one prompt" approach with an agent that:
 *
 * <ol>
 *   <li>Reads its instructions, the skill spec, and the recorded trace
 *       from files in a workspace via {@link CompileFileTools}.</li>
 *   <li>Decides which slices of the (potentially huge) order response it
 *       needs by calling list_files / grep / read_file.</li>
 *   <li>Calls {@code compile_write_bpmn} when it has the BPMN ready.</li>
 *   <li>Receives validator feedback on the next turn if anything fails,
 *       and revises. Max 5 attempts.</li>
 * </ol>
 *
 * <p>The result of {@link #compile} is either a validated BPMN string or
 * {@code null} (with {@code lastError} populated on the returned record).
 * The persistence / embedding / DB-save concerns stay in
 * {@link TraceBasedBpmnCompiler}; this class is only about producing a
 * passing BPMN.
 */
@Component
@Slf4j
public class AgenticTraceCompiler {

    /** Cap on the validate-and-revise loop. Each attempt is itself a
     *  Spring AI internal tool loop (the LLM may read many files inside
     *  one attempt), so this caps the outer REVISIONS only. */
    private static final int MAX_ATTEMPTS = 5;

    private final ModelProviderRouter modelProviderRouter;
    private final RuleDomainProperties props;
    private final TraceCompileWorkspace workspaceWriter;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final CompilerPromptBuilder promptBuilder;
    private final BpmnCompiler bpmnCompiler;
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper;

    public AgenticTraceCompiler(ModelProviderRouter modelProviderRouter,
                                RuleDomainProperties props,
                                TraceCompileWorkspace workspaceWriter,
                                SessionWorkspaceService sessionWorkspaceService,
                                CompilerPromptBuilder promptBuilder,
                                BpmnCompiler bpmnCompiler,
                                RuleDomainEventBus bus,
                                ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
        this.workspaceWriter = workspaceWriter;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.promptBuilder = promptBuilder;
        this.bpmnCompiler = bpmnCompiler;
        this.bus = bus;
        this.objectMapper = objectMapper;
    }

    /** Result of a compile attempt. {@code xml == null} means the loop
     *  exhausted its attempts without producing a passing BPMN; the most
     *  recent validator error is in {@code lastError}. */
    public record CompileResult(String xml,
                                String lastError,
                                int attempts,
                                long totalLatencyMs,
                                Path workspaceRoot) {
        public boolean ok() { return xml != null; }
    }

    public CompileResult compile(String sessionId,
                                 String turnId,
                                 String skillName,
                                 String skillMarkdown,
                                 SkillRuleManifest.Rule rule,
                                 ExecutionTrace slice) {
        long start = System.currentTimeMillis();
        bus.emit("rule_domain.compile.agentic.start", Map.of(
                "ruleName", rule.name(),
                "turnId", turnId == null ? "" : turnId,
                "steps", slice.toolSteps().size()));

        Path sessionRoot;
        try {
            sessionRoot = sessionWorkspaceService.getOrCreate(sessionId);
        } catch (Exception ex) {
            return failed(start, null, "Could not resolve session workspace: " + ex.getMessage());
        }

        Path compileRoot;
        try {
            compileRoot = workspaceWriter.materialize(
                    sessionRoot, safe(turnId), rule, skillName, skillMarkdown, slice,
                    buildInstructionsDoc());
        } catch (IOException ex) {
            return failed(start, null, "Could not write compile workspace: " + ex.getMessage());
        }

        ModelRef compilerRef = new ModelRef(
                props.getCompilerModel().getProviderId(),
                props.getCompilerModel().getModelId());
        ModelProviderRouter.Spec spec;
        try {
            spec = modelProviderRouter.resolve(compilerRef, true);
        } catch (Exception ex) {
            return failed(start, compileRoot, "Could not resolve compiler model: " + ex.getMessage());
        }
        ChatClient client = spec.client();

        CompileFileTools tools = new CompileFileTools(compileRoot, objectMapper);

        // Conversation history reused across revision attempts so the LLM
        // sees its own prior BPMN + the validator feedback when revising.
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(
                "Compile the BPMN for rule `" + rule.name() + "` (one rule of skill `"
                + skillName + "`). Your workspace is at: " + compileRoot + "\n\n"
                + "REQUIRED reading order:\n"
                + "  1. `compile_read_file` `index.md` — overview.\n"
                + "  2. `compile_read_file` `manifest.json` — YOUR RULE SCOPE. The `tools` "
                + "array lists every tool you may invoke. Do not exceed it.\n"
                + "  3. `compile_list_files` `trace/**` — see this rule's recorded steps.\n"
                + "  4. `compile_read_file` the FIRST trace output (typically "
                + "`trace/01-<tool>.output.json`) — your authoritative schema.\n"
                + "  5. `compile_read_file` each subsequent `trace/NN-<tool>.input.json` — "
                + "for every value passed, locate the matching value in a prior `.output.json` "
                + "and use a FEEL path. Never hardcode.\n"
                + "  6. `compile_read_file` `instructions.md` and `skill.md` for the contract "
                + "and any business rules the trace alone doesn't explain.\n\n"
                + "Constraints:\n"
                + "  - Implement ONLY this rule's slice. The skill describes a longer "
                + "workflow with other rules; do not include their tool calls.\n"
                + "  - The number of `${toolCallDelegate}` serviceTasks should match the "
                + "number of tool steps in this rule's trace.\n\n"
                + "When you have the BPMN ready, call `compile_write_bpmn` with the complete XML."));

        String systemPrompt = promptBuilder.buildSystem() + "\n\n" + agenticAddendum();

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            bus.emit("rule_domain.compile.agentic.attempt", Map.of(
                    "ruleName", rule.name(),
                    "attempt", attempt));
            tools.clearWrittenBpmn();
            String text;
            try {
                var req = client.prompt()
                        .system(systemPrompt)
                        .messages(history)
                        .toolCallbacks(tools.callbacks());
                if (spec.options() != null) req = req.options(spec.options());
                text = req.call().content();
            } catch (Exception ex) {
                log.warn("[AgenticTraceCompiler] LLM call failed (attempt {}): {}", attempt, ex.getMessage());
                lastError = "LLM call failed: " + ex.getMessage();
                break;
            }

            // The model emitted some final assistant text; preserve it so
            // revisions can refer back to its prior reasoning.
            if (text != null && !text.isBlank()) {
                history.add(new AssistantMessage(text));
            }

            String xml = tools.writtenBpmn();
            if (xml == null) {
                lastError = "did not call compile_write_bpmn";
                history.add(new UserMessage(
                        "You finished the turn without calling `compile_write_bpmn`. "
                        + "Read `instructions.md` if you haven't, then submit a BPMN by "
                        + "calling `compile_write_bpmn` with the complete XML."));
                continue;
            }

            // Run sanitize + boundary injection + every validator. If any
            // check fails, feed the error back to the LLM for revision.
            String cleaned = BpmnCompiler.sanitize(xml);
            cleaned = BpmnCompiler.injectErrorBoundaries(cleaned);

            String err = TraceBasedBpmnCompiler.validateDelegateFieldNames(cleaned);
            if (err == null) err = TraceBasedBpmnCompiler.validateInputDiscipline(cleaned);
            if (err == null) err = TraceBasedBpmnCompiler.validateLiteralGrounding(cleaned, slice);
            if (err == null) err = validateRuleToolScope(cleaned, rule, slice);
            if (err == null) err = validateArgTemplateKeys(cleaned, slice);
            if (err == null) err = bpmnCompiler.tryDeploy(cleaned, skillName, rule.name());

            if (err == null) {
                long elapsed = System.currentTimeMillis() - start;
                bus.emit("rule_domain.compile.agentic.success", Map.of(
                        "ruleName", rule.name(),
                        "attempts", attempt,
                        "latencyMs", elapsed,
                        "fileOps", tools.activityLog().size()));
                return new CompileResult(cleaned, null, attempt, elapsed, compileRoot);
            }

            lastError = err;
            bus.emit("rule_domain.compile.agentic.validator_failed", Map.of(
                    "ruleName", rule.name(),
                    "attempt", attempt,
                    "error", err));
            history.add(new UserMessage(
                    "Your submitted BPMN failed validation. Read the error carefully, fix the "
                    + "issue, then call `compile_write_bpmn` again with the corrected XML.\n\n"
                    + "Validator output:\n"
                    + err
                    + "\n\nDo NOT just resubmit the same XML — change the structure to address the "
                    + "specific problem above. Refer back to the trace files and the skill spec if needed."));
        }

        bus.emit("rule_domain.compile.agentic.exhausted", Map.of(
                "ruleName", rule.name(),
                "attempts", MAX_ATTEMPTS,
                "lastError", lastError == null ? "" : lastError));
        return failed(start, compileRoot, lastError);
    }

    private CompileResult failed(long start, Path root, String error) {
        return new CompileResult(null, error, 0, System.currentTimeMillis() - start, root);
    }

    private static String safe(String s) {
        return s == null ? "no-turn" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Reject any {@code <flowable:field name="toolName">VALUE</flowable:string>}
     * where VALUE isn't observed in this rule's recorded trace AND isn't
     * in the manifest's {@code tools} list. Catches two failure modes:
     *
     * <ol>
     *   <li>Cross-rule bleed — LLM implements other rules' work (caught
     *       by the manifest check).</li>
     *   <li>Tool hallucination — LLM invents a tool that exists in
     *       neither the manifest nor the trace (caught by the trace check,
     *       belt and suspenders since the manifest itself is LLM-derived
     *       and can be wrong).</li>
     * </ol>
     */
    private static String validateRuleToolScope(String bpmnXml,
                                                SkillRuleManifest.Rule rule,
                                                ExecutionTrace slice) {
        if (bpmnXml == null) return null;

        // Build authoritative tool set: trace ∪ manifest. Trace is the
        // empirical ground truth; manifest is the declarative ground
        // truth. Either is sufficient justification.
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        if (rule.tools() != null) allowed.addAll(rule.tools());
        java.util.Set<String> traceTools = new java.util.LinkedHashSet<>();
        if (slice != null) {
            for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
                if (step.name() != null) traceTools.add(step.name());
            }
            allowed.addAll(traceTools);
        }
        if (allowed.isEmpty()) return null; // nothing to enforce against

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"toolName\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        java.util.Set<String> outOfScope = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String name = m.group(1).trim();
            if (!allowed.contains(name)) outOfScope.add(name);
        }
        if (outOfScope.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN invokes tools that are neither in this rule's manifest scope "
                + "nor in the recorded trace. Likely causes: (a) you implemented other rules' "
                + "work in this rule, or (b) you hallucinated a tool name.\n"
                + "Rule: ").append(rule.name()).append("\n");
        msg.append("Tools observed in trace: ").append(traceTools).append("\n");
        if (rule.tools() != null) {
            msg.append("Tools declared in manifest: ").append(rule.tools()).append("\n");
        }
        msg.append("Offending toolName value(s) in the BPMN:");
        for (String t : outOfScope) msg.append("\n  - ").append(t);
        msg.append("\nFix: remove those <serviceTask> elements. Implement ONLY the tool calls "
                + "that appear in your trace/ folder.");
        return msg.toString();
    }

    /**
     * For every {@code toolCallDelegate} <serviceTask>, parse its
     * {@code argTemplate} JSON and reject any KEY that isn't in the
     * trace's recorded input for that tool name. Catches the
     * {@code {"orderId":"orderId"}} vs {@code {"ORD_ID":"orderId"}}
     * failure mode — the LLM inferred a wrong API parameter name.
     *
     * <p>Direction is one-way: BPMN keys ⊆ trace keys. We don't enforce
     * the reverse because the trace might have included optional args
     * the BPMN can legitimately omit.
     */
    private static String validateArgTemplateKeys(String bpmnXml, ExecutionTrace slice) {
        if (bpmnXml == null || slice == null) return null;

        // For each toolName in the trace, snapshot the set of input keys.
        // If a tool is called multiple times in the trace, we union keys
        // across all calls — the LLM may legitimately reuse fewer keys.
        java.util.Map<String, java.util.Set<String>> traceKeysByTool = new java.util.LinkedHashMap<>();
        for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
            if (step.name() == null || step.input() == null || !step.input().isObject()) continue;
            java.util.Set<String> keys = traceKeysByTool.computeIfAbsent(step.name(), k -> new java.util.LinkedHashSet<>());
            for (String k : step.input().propertyNames()) keys.add(k);
        }
        if (traceKeysByTool.isEmpty()) return null;

        // Walk every <serviceTask> that's a toolCallDelegate, pair its
        // toolName with its argTemplate, and diff the keys.
        java.util.regex.Pattern taskRe = java.util.regex.Pattern.compile(
                "<serviceTask\\b[^>]*flowable:delegateExpression\\s*=\\s*\"\\s*[$#]\\{\\s*toolCallDelegate\\s*\\}\\s*\"[\\s\\S]*?</serviceTask>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern fieldRe = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"(toolName|argTemplate)\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL);

        java.util.List<String> problems = new java.util.ArrayList<>();
        java.util.regex.Matcher tm = taskRe.matcher(bpmnXml);
        while (tm.find()) {
            String taskBlock = tm.group();
            String toolName = null;
            String argTemplate = null;
            java.util.regex.Matcher fm = fieldRe.matcher(taskBlock);
            while (fm.find()) {
                String which = fm.group(1);
                String value = fm.group(2).trim();
                if ("toolName".equals(which)) toolName = value;
                else if ("argTemplate".equals(which)) argTemplate = value;
            }
            if (toolName == null || argTemplate == null) continue;
            java.util.Set<String> traceKeys = traceKeysByTool.get(toolName);
            if (traceKeys == null) continue; // unknown tool — handled by validateRuleToolScope

            java.util.Set<String> bpmnKeys = extractJsonTopLevelKeys(argTemplate);
            if (bpmnKeys.isEmpty()) continue;

            java.util.Set<String> bad = new java.util.LinkedHashSet<>();
            for (String k : bpmnKeys) {
                if (!traceKeys.contains(k)) bad.add(k);
            }
            if (!bad.isEmpty()) {
                problems.add(toolName + ": argTemplate keys " + bad
                        + " are not in the recorded trace input. "
                        + "Recorded keys for this tool: " + traceKeys);
            }
        }
        if (problems.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN uses argTemplate keys that don't match the tool's actual API. "
                + "Look at the recorded trace input for each tool and copy its keys exactly:");
        for (String p : problems) msg.append("\n  - ").append(p);
        msg.append("\nFix: change the BPMN's argTemplate to use the recorded key names (e.g. "
                + "`ORD_ID` not `orderId`).");
        return msg.toString();
    }

    /** Extract the top-level JSON object keys from a string that may
     *  contain a JSON object with FEEL-quoted values. We don't do a full
     *  JSON parse because the values aren't strict JSON (FEEL escapes,
     *  multiline, etc.) — a regex over the top-level `"key":` form is
     *  good enough and tolerates the BPMN-embedded layout. */
    private static java.util.Set<String> extractJsonTopLevelKeys(String body) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        // Match `"key" :` — quoted identifier followed by optional whitespace
        // and a colon. Stops on the next `}` or end of string. We only
        // capture keys at depth 1 by ignoring quoted-string contents and
        // tracking brace depth.
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        StringBuilder current = new StringBuilder();
        boolean readingKey = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escape) { escape = false; if (inString && readingKey) current.append(c); continue; }
            if (c == '\\') { escape = true; continue; }
            if (inString) {
                if (c == '"') {
                    inString = false;
                    if (readingKey && depth == 1) {
                        // The string we just closed is a key candidate;
                        // confirm by scanning ahead for `:`.
                        int j = i + 1;
                        while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
                        if (j < body.length() && body.charAt(j) == ':') {
                            out.add(current.toString());
                        }
                    }
                    current.setLength(0);
                    readingKey = false;
                } else if (readingKey) {
                    current.append(c);
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                readingKey = (depth == 1);
                continue;
            }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return out;
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    private static String agenticAddendum() {
        return """
                ## Agentic compile mode

                You are running as an agent with filesystem tools. The trace, skill, and
                manifest for ONE rule of a multi-rule skill are written to files; you
                inspect them at will and submit your BPMN via a tool call.

                ### *** RULE SCOPE — STRICTEST CONSTRAINT, ENFORCED ***

                You are compiling ONE rule. The skill describes a longer workflow with
                MULTIPLE rules; you implement ONLY this rule's slice. Concretely:

                  - `manifest.json` is your authoritative scope. The `tools` array lists
                    EVERY tool this rule may invoke. Do NOT call any tool not in that list.
                    The post-compile validator rejects rules that invoke out-of-scope tools.
                  - The `trace/` folder contains ONLY this rule's recorded tool steps. If
                    a tool isn't in `trace/`, it's not part of this rule, even if `skill.md`
                    mentions it.
                  - Your BPMN's <serviceTask> elements should mirror the trace steps. A rule
                    that has 2 tool steps in its trace should produce a BPMN with 2 tool
                    serviceTasks (plus feelExtract / decisionTable / assemble tasks as
                    needed). If your BPMN has 5 toolCallDelegate serviceTasks when the
                    trace shows 2, you're over-reaching — delete the extras.

                ### Required workflow (do these in order):

                  1. `compile_read_file` `index.md` — overview.
                  2. `compile_read_file` `instructions.md` — the canonical compile contract.
                  3. `compile_read_file` `manifest.json` — your rule scope (which tools, which
                     result key). MEMORIZE the `tools` list.
                  4. `compile_list_files` `trace/**` — see every recorded step for this rule.
                  5. `compile_read_file` `trace/01-<tool>.output.json` — the order shape /
                     first tool's response. Authoritative for field paths. Failing to read
                     this leads to wrong paths like `leg.Origination` instead of
                     `leg.Addresses[AddressType="Origination"][1]`.
                  6. `compile_read_file` every subsequent `trace/NN-<tool>.input.json` —
                     for each value passed, find the matching value in the previous
                     `.output.json` and use a FEEL path.
                  7. `compile_read_file` `skill.md` — for any business rule the trace alone
                     doesn't explain (ItemCode → ServiceCode mapping, leg filter set, etc.).
                     **Implement only THIS rule's portion of the workflow.**
                  8. `compile_write_bpmn` — the BPMN XML.

                ### Validation rules (precise feedback on each failure, up to 5 attempts):

                  - **Delegate field names.** `toolName`/`argTemplate`/`outputBinding`/
                    `postTransform` for toolCallDelegate; `tableName`/`inputsTemplate`/
                    `outputBinding` for decisionTableDelegate; `feelExpr`/`outputBinding`
                    for feelExtractDelegate. Aliases (`inputBindings`, `expression`, `inputs`)
                    are rejected.
                  - **Bare `${X}` allowlist.** Only `userMessage`, `orderId`, `customerId`,
                    `sessionId`. Anything else must be a FEEL path into a prior outputBinding
                    variable.
                  - **No hardcoded trace literals.** Any quoted literal in argTemplate /
                    inputsTemplate / feelExpr that also appears as a value in any recorded
                    tool response is rejected — derive via a FEEL path. (Allowed
                    protocol constants: `"US"`, `"OK"`, `"Salesforce"`, etc.)
                  - **Tool scope.** Every `<flowable:field name="toolName">` value must be
                    in `manifest.json`'s `tools` array.
                  - **No boundary / error end events.** The post-processor injects them.
                    Single plain `<endEvent>` per scope.

                ### multi-instance aggregation — important convention

                `<flowable:variableAggregation target="X"><variable source="y"/></flowable:variableAggregation>`
                aggregates AS LIST-OF-OBJECTS keyed by the source variable name. That is,
                if each iteration writes `y = {a: 1}`, then `X = [{y:{a:1}}, {y:{a:1}}, ...]`,
                NOT `X = [{a:1}, {a:1}]`. To get a flat list of values in the assemble step,
                unwrap with FEEL: `for r in X return r.y`. The validator can't catch a
                missing unwrap, so the obligation is on you.

                ### Style

                  - Read what you need; don't list-files then read every file blindly.
                  - The first trace output (typically `trace/01-Get_OrderID.output.json`)
                    IS your schema source. Read it CAREFULLY before designing FEEL filters.
                  - When revising after a validator error, change the structure — don't
                    re-submit the same XML.
                """;
    }

    /** The canonical compile contract, written into the workspace as
     *  `instructions.md` so the LLM reads it via the filesystem tool. It
     *  duplicates {@link #agenticAddendum} content intentionally — the
     *  addendum is in the system prompt for fast paths, and this file is
     *  the LLM's authoritative reference when revising. */
    private String buildInstructionsDoc() {
        return promptBuilder.buildSystem() + "\n\n" + agenticAddendum();
    }
}
