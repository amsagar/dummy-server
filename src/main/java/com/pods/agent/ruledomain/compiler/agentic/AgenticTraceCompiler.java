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
import tools.jackson.databind.JsonNode;
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
     *  one attempt). Empirically attempts 4+ rarely recover after three
     *  failures on the same rule — they burn 6–10 min of compute for
     *  negligible additional success rate. Three is the sweet spot. */
    private static final int MAX_ATTEMPTS = 3;

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
        //
        // The initial user message inlines the small files (manifest +
        // trace step summary). Each compile_read_file round-trip costs
        // ~15s with Azure GPT-5.2; inlining what we already have saves
        // 2–3 tool calls per attempt. The LLM still reads big .output.json
        // files on demand via compile_read_file.
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(buildInitialPrompt(rule, skillName, compileRoot, slice)));

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

            // First — confirm the BPMN parses at all. Most common failure
            // is a FEEL expression containing `<`, `>`, or `&` inside a
            // <flowable:string> that wasn't CDATA-wrapped. Catching this
            // BEFORE injectErrorBoundaries means the parse-failure becomes
            // a revision message to the LLM (with row/col) instead of an
            // exception that crashes the whole attempt.
            String parseErr = BpmnCompiler.firstXmlParseError(cleaned);
            if (parseErr != null) {
                bus.emit("rule_domain.compile.agentic.validator_failed", Map.of(
                        "ruleName", rule.name(),
                        "attempt", attempt,
                        "error", parseErr));
                lastError = parseErr;
                history.add(new UserMessage(
                        "Your submitted BPMN doesn't parse as XML.\n\n"
                        + parseErr + "\n\n"
                        + "Most common cause: a FEEL expression in a `<flowable:string>` body "
                        + "contains a bare `<`, `>`, or `&` character without CDATA wrapping. "
                        + "EVERY `<flowable:string>` body MUST be wrapped in `<![CDATA[ … ]]>`. "
                        + "Revise the XML and call `compile_write_bpmn` again with a parsable version."));
                continue;
            }

            cleaned = BpmnCompiler.injectErrorBoundaries(cleaned);

            String err = TraceBasedBpmnCompiler.validateDelegateFieldNames(cleaned);
            if (err == null) err = TraceBasedBpmnCompiler.validateInputDiscipline(cleaned);
            if (err == null) err = TraceBasedBpmnCompiler.validateLiteralGrounding(cleaned, slice);
            if (err == null) err = validateRuleToolScope(cleaned, rule, slice);
            if (err == null) err = validateNoMetaToolsInToolCall(cleaned);
            if (err == null) err = validateArgTemplateKeys(cleaned, slice, objectMapper);
            if (err == null) err = validateBoundVariableReferences(cleaned);
            if (err == null) err = validateFeelLiteralSyntax(cleaned);
            if (err == null) err = validateMultiInstanceCollectionFilter(cleaned);
            if (err == null) err = validateAggregationUnwrap(cleaned);
            if (err == null) err = validateFeelPathsExistInTrace(cleaned, slice);
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

    /**
     * Build the initial user message. Inlines manifest (small) and a
     * compact trace summary (one line per step) so the LLM can start
     * writing the BPMN without a round-trip for each. Large
     * trace/*.output.json files stay in the VFS and are read on demand.
     */
    private String buildInitialPrompt(SkillRuleManifest.Rule rule,
                                      String skillName,
                                      Path compileRoot,
                                      ExecutionTrace slice) {
        StringBuilder sb = new StringBuilder();
        sb.append("Compile the BPMN for rule `").append(rule.name())
                .append("` (one rule of skill `").append(skillName)
                .append("`). Your workspace is at: ").append(compileRoot).append("\n\n");

        // 1. Inline manifest — it's tiny and the LLM needs every byte.
        sb.append("## Manifest (your rule's authoritative scope)\n```json\n");
        try {
            tools.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            root.put("skill", skillName);
            root.put("rule", rule.name());
            if (rule.intentExamples() != null) {
                tools.jackson.databind.node.ArrayNode ex = root.putArray("intent_examples");
                for (String s : rule.intentExamples()) ex.add(s);
            }
            root.put("result_key", rule.effectiveResultKey());
            if (rule.tools() != null) {
                tools.jackson.databind.node.ArrayNode t = root.putArray("tools");
                for (String s : rule.tools()) t.add(s);
            }
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception ex) {
            sb.append("{\"rule\":\"").append(rule.name()).append("\"}");
        }
        sb.append("\n```\n\n");

        // 2. Trace summary — index, tool name, payload sizes, latency.
        //    Lets the LLM see at a glance what each step is and decide
        //    which output.json files are worth reading in detail.
        sb.append("## Recorded trace summary (this rule's tool steps only)\n");
        int i = 1;
        for (ExecutionTrace.TraceStep s : slice.toolSteps()) {
            String idx = String.format(java.util.Locale.ROOT, "%02d", i++);
            int inSize = jsonSize(s.input());
            int outSize = jsonSize(s.output());
            sb.append("  ").append(idx).append(". ").append(s.name())
                    .append("  —  input ").append(inSize).append("B, output ").append(outSize)
                    .append("B, ").append(s.elapsedMs()).append("ms")
                    .append("  →  files: `trace/").append(idx).append("-").append(safeFile(s.name()))
                    .append(".input.json`, `.output.json`\n");
        }
        sb.append("\n");

        // 3. Suggested next reads. The LLM picks; we suggest the most
        //    useful starting point and explain why.
        sb.append("## What to do\n\n");
        sb.append("Start by calling `compile_read_file` on the **first tool's output** "
                + "(typically the order-fetch response). That's the authoritative schema you'll "
                + "ground every later FEEL path against.\n\n");
        sb.append("Then, for each subsequent step, read the `.input.json` and locate every value "
                + "in the prior `.output.json`. Encode each as a FEEL path — never hardcode a "
                + "literal that came from the trace.\n\n");
        sb.append("Optional: `compile_read_file` `skill.md` if you need business rules the trace "
                + "alone doesn't explain (e.g. ItemCode→ServiceCode mappings).\n\n");
        sb.append("Constraints:\n");
        sb.append("  - Implement ONLY this rule's slice. Other rules of the same skill have their "
                + "own BPMNs; do not include their tool calls here.\n");
        sb.append("  - Number of `${toolCallDelegate}` serviceTasks should match the number of "
                + "tool steps above.\n");
        sb.append("  - EVERY `<flowable:string>` body MUST be `<![CDATA[ … ]]>` wrapped.\n\n");
        sb.append("When ready, call `compile_write_bpmn` with the complete XML.");
        return sb.toString();
    }

    private static int jsonSize(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return 0;
        return node.toString().length();
    }

    private static String safeFile(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
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
    // Package-private so the validator unit test can pin its behavior directly
    // rather than driving the full multi-turn LLM loop.
    static String validateArgTemplateKeys(String bpmnXml, ExecutionTrace slice, ObjectMapper mapper) {
        if (bpmnXml == null || slice == null) return null;

        // For each toolName in the trace, snapshot the set of input keys.
        // If a tool is called multiple times in the trace, we union keys
        // across all calls — the LLM may legitimately reuse fewer keys.
        java.util.Map<String, java.util.Set<String>> traceKeysByTool = new java.util.LinkedHashMap<>();
        for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
            if (step.name() == null || step.input() == null) continue;
            JsonNode inputObj = coerceInputToObject(step.input(), mapper);
            if (inputObj == null) continue;
            java.util.Set<String> keys = traceKeysByTool.computeIfAbsent(step.name(), k -> new java.util.LinkedHashSet<>());
            for (String k : inputObj.propertyNames()) keys.add(k);
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

    /** Tool names that are agent meta-tools / framework primitives, NOT real
     *  domain tools the BPMN should invoke via toolCallDelegate. These exist
     *  to orchestrate or fan out work inside the LLM tool-loop; in compiled
     *  BPMN, fan-out belongs in a {@code <subProcess>} with
     *  {@code multiInstanceLoopCharacteristics} that calls the real domain
     *  tool. Rejecting them here turns a silent runtime no-op into an
     *  actionable repair instruction the agentic loop can act on. */
    private static final java.util.Set<String> RESERVED_META_TOOL_NAMES =
            java.util.Set.of(
                    "parallel_task", "batch", "pipeline", "task",
                    "agent_send", "agent_receive", "plan_exit", "todowrite");

    /**
     * Reject any {@code toolCallDelegate} serviceTask whose {@code toolName}
     * field references an agent meta-tool (parallel_task, batch, pipeline,
     * task, agent_send, agent_receive). These framework primitives don't
     * map to a real HTTP / integration tool and would silently produce
     * empty results at runtime.
     */
    static String validateNoMetaToolsInToolCall(String bpmnXml) {
        if (bpmnXml == null) return null;
        java.util.regex.Pattern taskRe = java.util.regex.Pattern.compile(
                "<serviceTask\\b[^>]*flowable:delegateExpression\\s*=\\s*\"\\s*[$#]\\{\\s*toolCallDelegate\\s*\\}\\s*\"[\\s\\S]*?</serviceTask>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern toolNameRe = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"toolName\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL);
        java.util.Set<String> offenders = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher tm = taskRe.matcher(bpmnXml);
        while (tm.find()) {
            java.util.regex.Matcher fm = toolNameRe.matcher(tm.group());
            if (!fm.find()) continue;
            String name = fm.group(1).trim();
            if (RESERVED_META_TOOL_NAMES.contains(name)) offenders.add(name);
        }
        if (offenders.isEmpty()) return null;
        return "Compiled BPMN uses agent meta-tool name(s) " + offenders
                + " as the toolName on a ${toolCallDelegate} serviceTask. These are framework "
                + "primitives (orchestration helpers from the LLM tool loop), not real domain "
                + "tools — calling them via toolCallDelegate would silently produce empty "
                + "results.\nFix: replace the toolName with the real domain tool you want to "
                + "invoke (e.g. `Serviceability`, `ContainerAvailability`). To run that tool "
                + "in parallel per item, wrap the toolCallDelegate serviceTask in a "
                + "<subProcess> with <multiInstanceLoopCharacteristics isSequential=\"false\" "
                + "flowable:collection=\"${listVar}\" flowable:elementVariable=\"item\"/>.";
    }

    // ── Bound-variable references ────────────────────────────────────────

    /** Orchestrator-provided seed variables. Always in scope in any rule
     *  domain BPMN — no upstream {@code outputBinding} is required. */
    private static final java.util.Set<String> ORCHESTRATOR_SEEDS = java.util.Set.of(
            "userMessage", "orderId", "customerId", "sessionId",
            "_turnId", "_sessionId");

    /** FEEL / JUEL keywords + built-ins that may legitimately appear as the
     *  head of a `X.` or `X[` form. Filtered out so they don't get flagged
     *  as missing variable bindings. Conservative on purpose — better to
     *  miss a few real issues than block valid BPMNs. */
    private static final java.util.Set<String> FEEL_RESERVED = java.util.Set.of(
            "if", "then", "else", "for", "in", "return", "function",
            "some", "every", "satisfies", "between", "instance", "of",
            "and", "or", "not", "true", "false", "null",
            "sort", "count", "list", "contains", "string", "today",
            "now", "date", "time", "duration", "number", "boolean",
            "min", "max", "sum", "mean", "abs", "floor", "ceiling",
            "concatenate", "append", "union", "distinct", "flatten");

    /**
     * Reject BPMN that references a process variable (via FEEL or JUEL)
     * that no upstream task binds via {@code outputBinding}, and that
     * isn't an orchestrator-provided seed or a multi-instance loop
     * variable.
     *
     * <p>This catches the most damaging LLM regression: dropping the
     * {@code Get_OrderID} step (because the trace cached the order from a
     * sibling rule's prior call) and then referencing {@code order.X}
     * everywhere — every reference silently resolves to {@code null} at
     * runtime and the rule returns
     * {@code {orderId: null, sequence: null, valid: true}}.
     */
    static String validateBoundVariableReferences(String bpmnXml) {
        if (bpmnXml == null) return null;

        // 1. Collect every variable bound by an outputBinding field.
        java.util.Set<String> bound = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher om = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"outputBinding\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (om.find()) {
            String v = om.group(1).trim();
            if (!v.isEmpty()) bound.add(v);
        }

        // 2. Include multi-instance loop element variables and aggregation
        //    targets. Loop-local: in scope inside the subprocess.
        //    Aggregation target: bound after the multi-instance completes
        //    and visible to downstream tasks.
        java.util.regex.Matcher em = java.util.regex.Pattern.compile(
                "<multiInstanceLoopCharacteristics\\b[^>]*\\bflowable:elementVariable\\s*=\\s*\"([^\"]+)\"",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (em.find()) bound.add(em.group(1).trim());

        java.util.regex.Matcher agg = java.util.regex.Pattern.compile(
                "<flowable:variableAggregation\\b[^>]*\\btarget\\s*=\\s*\"([^\"]+)\"",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (agg.find()) bound.add(agg.group(1).trim());

        // 3. Walk every FEEL-bearing field and find unbound roots.
        java.util.Map<String, java.util.Set<String>> offenders = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher xm = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"(feelExpr|argTemplate|inputsTemplate|postTransform)\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (xm.find()) {
            String which = xm.group(1);
            String body = xm.group(2);
            collectUnboundRoots(which, body, bound, offenders);
        }

        // 4. Also scan JUEL ${X} references in conditionExpression and
        //    flowable:collection attributes — these must point at a
        //    bound variable too.
        java.util.regex.Matcher juel = java.util.regex.Pattern.compile(
                "[$#]\\{\\s*!?\\s*([A-Za-z_][A-Za-z0-9_]*)\\b").matcher(bpmnXml);
        while (juel.find()) {
            String ident = juel.group(1);
            // Skip delegate beans (`toolCallDelegate`, `feelExtractDelegate`, ...)
            if (ident.endsWith("Delegate")) continue;
            if (FEEL_RESERVED.contains(ident)) continue;
            if (ORCHESTRATOR_SEEDS.contains(ident)) continue;
            if (bound.contains(ident)) continue;
            offenders.computeIfAbsent(ident, k -> new java.util.LinkedHashSet<>()).add("JUEL");
        }

        if (offenders.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN references variable(s) that no upstream task binds and that "
                + "aren't orchestrator-provided seeds. Fix by adding the missing step that "
                + "binds them BEFORE the first reference:");
        for (java.util.Map.Entry<String, java.util.Set<String>> e : offenders.entrySet()) {
            msg.append("\n  - '").append(e.getKey()).append("' referenced in ").append(e.getValue());
            if ("order".equals(e.getKey())) {
                msg.append(" — add `<serviceTask flowable:delegateExpression=\"${toolCallDelegate}\">` "
                        + "with toolName=`Get_OrderID`, argTemplate=`{\"ORD_ID\":\"orderId\"}`, "
                        + "outputBinding=`order` as the first step.");
            }
        }
        msg.append("\nOrchestrator-provided seeds you may reference without binding: ")
           .append(ORCHESTRATOR_SEEDS);
        msg.append("\nBound by upstream outputBinding in this BPMN: ").append(bound);
        return msg.toString();
    }

    /** Scan a single FEEL / JSON-with-FEEL body for navigation roots
     *  ({@code X.}, {@code X[}) and add any that aren't bound, seeded,
     *  FEEL-local, or reserved to {@code offenders}. */
    private static void collectUnboundRoots(String fieldName,
                                            String body,
                                            java.util.Set<String> bound,
                                            java.util.Map<String, java.util.Set<String>> offenders) {
        if (body == null || body.isBlank()) return;
        java.util.Set<String> feelLocals = extractFeelLocals(body);
        // The lookbehind ensures we don't pick up `foo` inside `order.foo`
        // (i.e., a property accessor) — only a true root identifier. We do
        // NOT exclude `"` here: FEEL expressions appear inside JSON-quoted
        // argTemplate values (`{"key":"order.X"}`), so an identifier
        // preceded by `"` is still a legitimate root.
        java.util.regex.Matcher rm = java.util.regex.Pattern.compile(
                "(?<![.A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)\\s*[.\\[]").matcher(body);
        while (rm.find()) {
            String ident = rm.group(1);
            if (FEEL_RESERVED.contains(ident)) continue;
            if (feelLocals.contains(ident)) continue;
            if (ORCHESTRATOR_SEEDS.contains(ident)) continue;
            if (bound.contains(ident)) continue;
            // _resp is the raw tool response, available only inside postTransform.
            if ("_resp".equals(ident) && "postTransform".equals(fieldName)) continue;
            offenders.computeIfAbsent(ident, k -> new java.util.LinkedHashSet<>()).add(fieldName);
        }
    }

    /** Identifiers introduced as locals inside a FEEL expression:
     *  {@code for X in ...}, {@code function(X, Y)}, {@code some X in ...},
     *  {@code every X in ...}. Scoped to the expression body — we don't
     *  try to be precise about lexical nesting; over-allowing here only
     *  costs a missed validator hit, never a false positive. */
    private static java.util.Set<String> extractFeelLocals(String body) {
        java.util.Set<String> locals = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher forM = java.util.regex.Pattern.compile(
                "\\bfor\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\b").matcher(body);
        while (forM.find()) locals.add(forM.group(1));
        java.util.regex.Matcher quantM = java.util.regex.Pattern.compile(
                "\\b(?:some|every)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\b").matcher(body);
        while (quantM.find()) locals.add(quantM.group(1));
        java.util.regex.Matcher fnM = java.util.regex.Pattern.compile(
                "\\bfunction\\s*\\(\\s*([A-Za-z0-9_,\\s]+)\\s*\\)").matcher(body);
        while (fnM.find()) {
            for (String p : fnM.group(1).split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) locals.add(t);
            }
        }
        return locals;
    }

    // ── FEEL literal syntax ──────────────────────────────────────────────

    /**
     * Reject FEEL expressions that contain single-quoted string literals
     * (FEEL only supports double quotes — single quotes never bind and
     * cause a runtime parse failure) or {@code if X then "A" else "A"}
     * patterns where both branches are the same literal (a clear LLM
     * hallucination).
     *
     * <p>Scans the body of every {@code argTemplate}, {@code inputsTemplate},
     * {@code feelExpr}, and {@code postTransform} field. JSON wrappers are
     * tolerated — only the FEEL-meaningful contents are inspected.
     */
    static String validateFeelLiteralSyntax(String bpmnXml) {
        if (bpmnXml == null) return null;

        java.util.List<String> problems = new java.util.ArrayList<>();
        java.util.regex.Matcher xm = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"(feelExpr|argTemplate|inputsTemplate|postTransform)\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (xm.find()) {
            String which = xm.group(1);
            String body = xm.group(2);
            checkFeelLiteralSyntax(which, body, problems);
        }
        if (problems.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN contains invalid FEEL syntax. FEEL string literals must use "
                + "double quotes, and constant-branch `if` expressions are almost always a "
                + "hallucination:");
        for (String p : problems) msg.append("\n  - ").append(p);
        msg.append("\nFix: replace `'Foo'` with `\"Foo\"` (escape as `\\\"Foo\\\"` inside the "
                + "JSON wrapper). Replace `if X then \"A\" else \"A\"` with the literal `\"A\"`, "
                + "or with a real branch that returns different values.");
        return msg.toString();
    }

    private static final java.util.regex.Pattern SINGLE_QUOTED_FEEL = java.util.regex.Pattern.compile(
            "'[^'\\n]*'");

    /** Matches `if <cond> then "branchA" else "branchB"`. Tolerates the
     *  JSON-escaped form (`\"X\"`) that appears verbatim inside the
     *  raw {@code <flowable:string>} body before JSON parsing. We only
     *  flag when the two literals are character-for-character identical
     *  — anything else is legitimate branching. */
    private static final java.util.regex.Pattern IF_SAME_BRANCH = java.util.regex.Pattern.compile(
            "\\bif\\s+[^\\n]+?\\s+then\\s+\\\\?\"([^\"\\\\\\n]+)\\\\?\"\\s+else\\s+\\\\?\"([^\"\\\\\\n]+)\\\\?\"",
            java.util.regex.Pattern.DOTALL);

    private static void checkFeelLiteralSyntax(String fieldName,
                                               String body,
                                               java.util.List<String> problems) {
        if (body == null || body.isBlank()) return;

        // 1. Single-quoted FEEL strings. These never appear in valid FEEL
        //    — FEEL strictly uses double quotes. JSON values inside an
        //    argTemplate are quoted with double quotes too, so any `'...'`
        //    we see is inside the FEEL expression itself.
        java.util.regex.Matcher sm = SINGLE_QUOTED_FEEL.matcher(body);
        java.util.Set<String> singles = new java.util.LinkedHashSet<>();
        while (sm.find()) singles.add(sm.group());
        if (!singles.isEmpty()) {
            problems.add(fieldName + " contains single-quoted FEEL string(s) "
                    + singles + " — FEEL uses double quotes.");
        }

        // 2. `if ... then "A" else "A"` — both branches identical literal.
        java.util.regex.Matcher im = IF_SAME_BRANCH.matcher(body);
        while (im.find()) {
            String a = im.group(1);
            String b = im.group(2);
            if (a.equals(b)) {
                problems.add(fieldName + " has an `if` expression whose branches both return "
                        + "the same literal \"" + a + "\" — drop the `if` and use the literal "
                        + "directly, or pick a real differentiating value for the else branch.");
            }
        }
    }

    // ── Multi-instance collection filter ─────────────────────────────────

    /**
     * Reject any multi-instance subprocess whose {@code flowable:collection}
     * variable was bound from a bare {@code <rootVar>.Lines} expression
     * with no {@code [filter]}. A multi-instance fan-out across the entire
     * order's lines is rarely what the skill spec asks for — most rules
     * restrict the iteration to a specific subset (e.g. leg ItemCodes).
     *
     * <p>Heuristic, not exact: we won't catch every wrong filter, but we
     * do catch the most common regression of "the LLM forgot the filter
     * entirely".
     */
    static String validateMultiInstanceCollectionFilter(String bpmnXml) {
        if (bpmnXml == null) return null;

        // 1. Find every multi-instance flowable:collection variable name.
        java.util.Set<String> collectionVars = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                "<multiInstanceLoopCharacteristics\\b[^>]*\\bflowable:collection\\s*=\\s*\"[$#]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\"",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (cm.find()) collectionVars.add(cm.group(1).trim());
        if (collectionVars.isEmpty()) return null;

        // 2. For each collection variable, find the upstream feelExtract
        //    serviceTask that binds it and inspect its feelExpr.
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (String var : collectionVars) {
            String feelExpr = extractFeelExprForBinding(bpmnXml, var);
            if (feelExpr == null) continue; // bound by a tool call or aggregator, not a FEEL extract
            String trimmed = feelExpr.trim();
            // Match `<rootVar>.Lines` (or `.lines` / `.LINES`) with no
            // square-bracket filter immediately after.
            java.util.regex.Matcher lm = java.util.regex.Pattern.compile(
                    "^([A-Za-z_][A-Za-z0-9_]*)\\.(?:Lines|lines|LINES)\\s*$").matcher(trimmed);
            if (lm.matches()) {
                problems.add("multi-instance collection '" + var + "' is bound from bare `"
                        + trimmed + "` — every line in the order will be processed.");
            }
        }
        if (problems.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN fan-outs over the raw `order.Lines` list without filtering. "
                + "If the skill restricts which lines count (e.g. by ItemCode), apply the "
                + "filter upstream in the feelExtractDelegate that binds the collection "
                + "variable:");
        for (String p : problems) msg.append("\n  - ").append(p);
        msg.append("\nFix: change the feelExpr to a filtered form such as "
                + "`order.Lines[list contains([\"IDEL\",\"RETSC\",\"LDT\",\"REDEL\",\"FPU\"], ItemCode)]` "
                + "and re-bind the same outputBinding variable.");
        return msg.toString();
    }

    /** Find the {@code feelExpr} field of the {@code feelExtractDelegate}
     *  serviceTask whose {@code outputBinding} is {@code var}. Returns
     *  {@code null} when no such task exists (the variable may be bound
     *  by a tool call or aggregator, which is fine — only FEEL extracts
     *  can have the bare-Lines bug). */
    private static String extractFeelExprForBinding(String bpmnXml, String var) {
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                "<serviceTask\\b[^>]*flowable:delegateExpression\\s*=\\s*\"\\s*[$#]\\{\\s*feelExtractDelegate\\s*\\}\\s*\"[\\s\\S]*?</serviceTask>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (tm.find()) {
            String taskBlock = tm.group();
            String binding = null;
            String feelExpr = null;
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                    "<flowable:field\\s+name\\s*=\\s*\"(outputBinding|feelExpr)\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?\\s*</flowable:string>",
                    java.util.regex.Pattern.DOTALL).matcher(taskBlock);
            while (fm.find()) {
                String which = fm.group(1);
                String value = fm.group(2);
                if ("outputBinding".equals(which)) binding = value.trim();
                else if ("feelExpr".equals(which)) feelExpr = value;
            }
            if (var.equals(binding) && feelExpr != null) return feelExpr;
        }
        return null;
    }

    /**
     * For every multi-instance subprocess with a {@code variableAggregation},
     * verify the aggregation target variable is properly unwrapped wherever
     * it's referenced in a feelExpr.
     *
     * <p>Flowable wraps each iteration's value under the {@code source}
     * variable name: a subprocess that writes {@code _legResult = {…}} per
     * iteration produces a target list shaped like
     * {@code [{_legResult: {…}}, {_legResult: {…}}, …]}, NOT a flat
     * {@code [{…}, {…}]}. Assemble steps that reference the target without
     * the {@code for r in X return r._sourceVar} unwrap produce arrays of
     * effectively-empty objects (Jackson hides the underscore-prefixed
     * field).
     *
     * <p>This validator rejects any feelExpr that references the target
     * without unwrapping it. Allowed reference patterns:
     * <ul>
     *   <li>{@code for r in X return r.Y …} (the canonical unwrap)</li>
     *   <li>{@code count(X)} / {@code size(X)} (length, no value access)</li>
     *   <li>{@code if X = null …} or {@code X = null} (null check)</li>
     *   <li>{@code if … = null then [] else for r in X return r.Y …}</li>
     * </ul>
     */
    static String validateAggregationUnwrap(String bpmnXml) {
        if (bpmnXml == null) return null;

        // 1. Find every (target, source) aggregation pair.
        java.util.Map<String, String> aggPairs = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher aggRe = java.util.regex.Pattern.compile(
                "<flowable:variableAggregation\\b[^>]*\\btarget\\s*=\\s*\"([^\"]+)\"[^>]*>"
                + "\\s*<variable\\b[^>]*\\bsource\\s*=\\s*\"([^\"]+)\"[^/>]*/?>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (aggRe.find()) {
            aggPairs.put(aggRe.group(1).trim(), aggRe.group(2).trim());
        }
        if (aggPairs.isEmpty()) return null;

        // 2. For every feelExpr body, check references to each target.
        java.util.List<String> problems = new java.util.ArrayList<>();
        java.util.regex.Matcher feelRe = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"feelExpr\"[^>]*>"
                + "\\s*<flowable:string>([\\s\\S]*?)</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (feelRe.find()) {
            String body = feelRe.group(1).replaceAll("<!\\[CDATA\\[|\\]\\]>", "");
            for (var entry : aggPairs.entrySet()) {
                String target = entry.getKey();
                String source = entry.getValue();
                String issue = checkAggregationRefs(body, target, source);
                if (issue != null) problems.add(issue);
            }
        }
        if (problems.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN references variableAggregation target(s) without unwrapping. "
                + "Flowable wraps each iteration's result under the source variable name, so a "
                + "target with source `Y` is shaped `[{Y:val}, {Y:val}, …]` — accessing it "
                + "directly serializes to empty objects.\n");
        for (String p : problems) msg.append("  - ").append(p).append("\n");
        msg.append("Fix: change every bare reference to `target` into "
                + "`for r in target return r.source` (or `r.source.<field>` to project).");
        return msg.toString();
    }

    /** True if the body legitimately handles the target via unwrap or a
     *  length/null-check only. Returns the issue string for the first
     *  bare-reference problem, else null. */
    private static String checkAggregationRefs(String body, String target, String source) {
        // Find every occurrence of `target` as a whole identifier in the body.
        java.util.regex.Matcher refRe = java.util.regex.Pattern.compile(
                "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(target) + "(?![A-Za-z0-9_])")
                .matcher(body);
        while (refRe.find()) {
            int idx = refRe.start();
            // Look at a generous window around this reference to classify it.
            int from = Math.max(0, idx - 80);
            int to = Math.min(body.length(), refRe.end() + 80);
            String ctx = body.substring(from, to);

            // Pattern 1: `for r in target return r.source …` — canonical unwrap.
            java.util.regex.Pattern unwrap = java.util.regex.Pattern.compile(
                    "for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+" + java.util.regex.Pattern.quote(target)
                            + "\\s+return\\s+\\1\\." + java.util.regex.Pattern.quote(source) + "\\b");
            if (unwrap.matcher(ctx).find()) continue;

            // Pattern 2: `count(target)` / `size(target)` / `length(target)`.
            java.util.regex.Pattern len = java.util.regex.Pattern.compile(
                    "(?:count|size|length)\\s*\\(\\s*" + java.util.regex.Pattern.quote(target) + "\\s*\\)");
            if (len.matcher(ctx).find()) continue;

            // Pattern 3: null check — `target = null` or `target != null`.
            java.util.regex.Pattern nullChk = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(target) + "\\s*(?:=|!=)\\s*null");
            if (nullChk.matcher(ctx).find()) continue;

            // Bare reference. Report.
            String snippet = body.substring(from, to).replaceAll("\\s+", " ").trim();
            return "target `" + target + "` (source `" + source + "`) referenced without unwrap "
                    + "near: ‹" + snippet + "›";
        }
        return null;
    }

    /**
     * Reject FEEL paths that reference fields not present in any recorded
     * trace response (or in any earlier outputBinding's value shape).
     *
     * <p>Catches the {@code order.OrderType} class of error — the LLM
     * picks a similar-named field from the wrong variable. By walking
     * the recorded JSON schema, we can tell whether a path actually
     * resolves to something the runtime will find.
     *
     * <p>Build a schema map: for each known output-binding variable name
     * (and for each tool step), record the set of field names that appear
     * at each depth. Then for every FEEL access path in the BPMN starting
     * with one of those variable names, walk the schema and reject when
     * the path leaves the schema.
     *
     * <p>Conservative: missing field at depth N flags only when the path
     * is unambiguous (no list-index, filter, or function call). Paths
     * containing `[…]` or `(…)` are skipped because the LLM may be doing
     * dynamic filtering whose result shape we can't statically determine.
     */
    static String validateFeelPathsExistInTrace(String bpmnXml, ExecutionTrace slice) {
        if (bpmnXml == null || slice == null) return null;

        // 1. Build a schema map: varName → set of dotted-path field names
        //    that appear under that variable.
        java.util.Map<String, java.util.Set<String>> schemaByVar = new java.util.LinkedHashMap<>();
        for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
            if (step.output() == null) continue;
            // The LLM binds the response to whatever outputBinding name the
            // BPMN declares. We don't always know that mapping from here,
            // so we register the schema under every plausible name: the
            // tool name itself, and the common conventional names. The
            // validator only fires when a path is unambiguously wrong.
            // To keep things simple, we register under a single key:
            // the BPMN's outputBinding for the matching tool name.
            // — But we can also walk the BPMN to find the outputBinding map.
        }

        java.util.Map<String, String> outputBindingByTool = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                "<serviceTask\\b[^>]*flowable:delegateExpression\\s*=\\s*\"\\s*[$#]\\{\\s*toolCallDelegate\\s*\\}\\s*\"[\\s\\S]*?</serviceTask>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (tm.find()) {
            String taskBlock = tm.group();
            String toolName = null;
            String outputBinding = null;
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                    "<flowable:field\\s+name\\s*=\\s*\"(toolName|outputBinding)\"[^>]*>"
                    + "\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</flowable:string>",
                    java.util.regex.Pattern.DOTALL).matcher(taskBlock);
            while (fm.find()) {
                String which = fm.group(1);
                String val = fm.group(2).trim();
                if ("toolName".equals(which)) toolName = val;
                else if ("outputBinding".equals(which)) outputBinding = val;
            }
            if (toolName != null && outputBinding != null) {
                outputBindingByTool.put(toolName, outputBinding);
            }
        }

        // Populate schemaByVar using outputBinding mapping.
        for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
            if (step.name() == null || step.output() == null) continue;
            String var = outputBindingByTool.get(step.name());
            if (var == null) continue;
            java.util.Set<String> fields = schemaByVar.computeIfAbsent(var, k -> new java.util.LinkedHashSet<>());
            collectFieldPaths(step.output(), "", fields, 4 /* maxDepth */);
        }
        if (schemaByVar.isEmpty()) return null;

        // 2. Walk every FEEL body in the BPMN. Extract access paths and
        //    check each against the schema.
        java.util.List<String> problems = new java.util.ArrayList<>();
        java.util.regex.Matcher feelRe = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"(?:feelExpr|argTemplate|inputsTemplate|postTransform)\"[^>]*>"
                + "\\s*<flowable:string>([\\s\\S]*?)</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (feelRe.find()) {
            String body = feelRe.group(1).replaceAll("<!\\[CDATA\\[|\\]\\]>", "");
            // Extract `varName.field1.field2` access paths (no [], no ()).
            java.util.regex.Matcher pathRe = java.util.regex.Pattern.compile(
                    "\\b([A-Za-z_][A-Za-z0-9_]*)((?:\\.[A-Za-z_][A-Za-z0-9_]*)+)").matcher(body);
            while (pathRe.find()) {
                String varName = pathRe.group(1);
                if (!schemaByVar.containsKey(varName)) continue; // not an output var we know about
                // Skip if path immediately follows a `for X in ` (loop var, not the output var).
                int matchStart = pathRe.start();
                String preceding = body.substring(Math.max(0, matchStart - 30), matchStart);
                if (preceding.matches(".*\\bfor\\s+[A-Za-z_][A-Za-z0-9_]*\\s+in\\s+\\b" + java.util.regex.Pattern.quote(varName) + "\\s*$")) continue;
                // Skip if next char is `[` or `(` (filter/index/call — dynamic).
                int matchEnd = pathRe.end();
                if (matchEnd < body.length()) {
                    char next = body.charAt(matchEnd);
                    if (next == '[' || next == '(') continue;
                }

                String fieldPath = pathRe.group(2).substring(1); // strip leading dot
                java.util.Set<String> known = schemaByVar.get(varName);
                if (known.contains(fieldPath)) continue;
                // Path doesn't exist. Build a suggestion if a sibling does.
                String suggestion = suggestSimilarPath(varName, fieldPath, schemaByVar);
                String problem = "`" + varName + "." + fieldPath + "` does not exist in the recorded `"
                        + varName + "` response.";
                if (suggestion != null) problem += " Did you mean `" + suggestion + "`?";
                if (!problems.contains(problem)) problems.add(problem);
            }
        }
        if (problems.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN references FEEL paths that don't exist in any recorded trace "
                + "response. The runtime will resolve them to null, producing wrong output:\n");
        for (String p : problems) msg.append("  - ").append(p).append("\n");
        msg.append("Fix: re-read the trace `*.output.json` files and use only paths that actually "
                + "appear there. If you need a value from a different earlier step, change the "
                + "variable name in the path (e.g. `legSequenceResult.outputs.X` instead of `order.X`).");
        return msg.toString();
    }

    /** Walk a JsonNode and collect every dotted field path (relative to
     *  the root) up to {@code maxDepth} levels. Arrays are walked but
     *  array indices are dropped from the path — we record only "Lines"
     *  rather than "Lines[0]" since FEEL queries use field names. */
    private static void collectFieldPaths(JsonNode node, String prefix,
                                          java.util.Set<String> into, int maxDepth) {
        if (node == null || node.isNull() || maxDepth < 0) return;
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                String path = prefix.isEmpty() ? name : prefix + "." + name;
                into.add(path);
                JsonNode child = node.get(name);
                if (child != null && (child.isObject() || child.isArray())) {
                    collectFieldPaths(child, path, into, maxDepth - 1);
                }
            }
        } else if (node.isArray()) {
            // For arrays of objects, descend into the FIRST element to
            // record the per-item field names under the same prefix
            // (FEEL filter syntax: `Lines[X = …].Field` not `Lines[0].Field`).
            if (node.size() > 0) {
                JsonNode first = node.get(0);
                if (first != null && (first.isObject() || first.isArray())) {
                    collectFieldPaths(first, prefix, into, maxDepth - 1);
                }
            }
        }
    }

    /** Best-effort suggestion: find any path in any schema map entry that
     *  ends with the same final-segment name as the missing field. */
    private static String suggestSimilarPath(String wrongVar, String wrongPath,
                                             java.util.Map<String, java.util.Set<String>> schemaByVar) {
        String wantedTail = wrongPath.contains(".") ? wrongPath.substring(wrongPath.lastIndexOf('.') + 1) : wrongPath;
        if (wantedTail.length() < 3) return null; // too short to disambiguate
        String lowered = wantedTail.toLowerCase(java.util.Locale.ROOT);
        for (var entry : schemaByVar.entrySet()) {
            for (String path : entry.getValue()) {
                String tail = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                if (tail.equalsIgnoreCase(wantedTail) || tail.toLowerCase(java.util.Locale.ROOT).contains(lowered)) {
                    return entry.getKey() + "." + path;
                }
            }
        }
        return null;
    }

    /**
     * Best-effort coerce a recorded trace step's {@code input} node into a JSON
     * object. Returns the node as-is when already an object; tries to parse the
     * text content when the node is a JSON-encoded string (the historical
     * shape produced by {@code AgentToolCallback.saveRuntimeEvent} before the
     * {@code ObjectNode} rewrite). Returns {@code null} when neither applies —
     * matching the prior silent-skip behavior for non-JSON inputs.
     */
    private static JsonNode coerceInputToObject(JsonNode input, ObjectMapper mapper) {
        if (input == null || input.isNull()) return null;
        if (input.isObject()) return input;
        if (input.isTextual() && mapper != null) {
            String text = input.textValue();
            if (text == null) return null;
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') return null;
            try {
                JsonNode parsed = mapper.readTree(trimmed);
                if (parsed != null && parsed.isObject()) return parsed;
            } catch (Exception ignored) {
                // not parseable — fall through to null
            }
        }
        return null;
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

                ### *** XML WELL-FORMEDNESS — ENFORCED BY PARSER ***

                EVERY `<flowable:string>` body MUST be wrapped in `<![CDATA[ … ]]>`.
                Unconditional, no exceptions. FEEL expressions routinely contain `<`, `>`,
                and `&` characters that break XML parsing if they appear raw. The parser
                will reject your BPMN with a row/column error and you'll have to revise.

                YES:  `<flowable:string><![CDATA[if a < b then x else y]]></flowable:string>`
                NO:   `<flowable:string>if a < b then x else y</flowable:string>`

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
