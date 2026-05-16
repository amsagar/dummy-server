package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.invalidation.SkillSourceHasher;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles a per-rule BPMN from a recorded execution trace.
 *
 * <p>Where {@link BpmnCompiler} guesses field paths from skill prose, this
 * compiler is grounded in the real tool inputs and responses observed during
 * the original LLM-loop run. The hallucination class that's been biting us
 * ("LLM invented {@code leg.Origination} because the skill said 'origination
 * address'") disappears because every field path appears literally in the
 * sample response embedded in the prompt.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Build a trace-grounded user prompt — actual tool I/O for this rule.</li>
 *   <li>Ask the LLM to convert the trace into a parameterized BPMN, replacing
 *       literals with process variables and folding repeated structurally-identical
 *       calls into multi-instance subprocesses.</li>
 *   <li>Sanitize + inject error boundaries + validate-deploy via the existing
 *       {@link BpmnCompiler} helpers.</li>
 *   <li>Save as DRAFT with {@code traceSource=LLM_TRACE} and a derived
 *       {@code coverageManifest} listing the observed inputs.</li>
 * </ol>
 */
@Component
@Slf4j
public class TraceBasedBpmnCompiler {

    private final ModelProviderRouter modelProviderRouter;
    private final RuleDomainProperties props;
    private final BpmnCompiler bpmnCompiler;
    private final RuleDomainRepository repo;
    private final SkillSourceHasher skillHasher;
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TraceBasedBpmnCompiler(ModelProviderRouter modelProviderRouter,
                                  RuleDomainProperties props,
                                  BpmnCompiler bpmnCompiler,
                                  RuleDomainRepository repo,
                                  SkillSourceHasher skillHasher,
                                  RuleDomainEventBus bus) {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
        this.bpmnCompiler = bpmnCompiler;
        this.repo = repo;
        this.skillHasher = skillHasher;
        this.bus = bus;
    }

    /**
     * Compile one rule's BPMN from its trace slice.
     *
     * @param skillId         owning skill id
     * @param skillName       owning skill name (for display + deploy resource name)
     * @param skillMarkdown   full skill markdown (used for source_hash + as
     *                        background context in the compiler prompt)
     * @param domainGroupId   uuid of the domain group; new groups should mint one
     * @param domainGroupName human-readable group name (e.g. "Pods-Order-Validation")
     * @param manifestRule    the rule entry from the skill's manifest
     * @param toolSignature   pre-computed tool signature hash (passed in to keep
     *                        this compiler decoupled from {@code ToolSignatureHasher})
     * @param slice           the per-rule trace slice from {@link RuleSlicer}
     * @return the persisted {@link RuleDomain} (DRAFT or FAILED status)
     */
    public RuleDomain compileFromTrace(String skillId,
                                       String skillName,
                                       String skillMarkdown,
                                       String domainGroupId,
                                       String domainGroupName,
                                       SkillRuleManifest.Rule manifestRule,
                                       String toolSignature,
                                       ExecutionTrace slice) {
        String ruleName = manifestRule.name();
        String intentLabel = ruleName;

        bus.emit("rule_domain.compile.start", Map.of(
                "turnId", slice.turnId() == null ? "" : slice.turnId(),
                "ruleName", ruleName,
                "traceSource", RuleDomain.TRACE_LLM_TRACE));

        ModelRef compilerRef = new ModelRef(
                props.getCompilerModel().getProviderId(),
                props.getCompilerModel().getModelId());
        ModelProviderRouter.Spec spec = modelProviderRouter.resolve(compilerRef, true);
        ChatClient client = spec.client();

        String system = traceSystemPrompt();
        String user = buildTraceUserPrompt(skillName, manifestRule, slice);

        bus.emit("rule_domain.compile.llm_call", Map.of(
                "model", compilerRef.modelID(),
                "provider", compilerRef.providerID(),
                "ruleName", ruleName));

        long llmStart = System.currentTimeMillis();
        String xml = invokeLlm(client, spec.options(), system, user);
        long llmMs = System.currentTimeMillis() - llmStart;
        xml = BpmnCompiler.sanitize(xml);
        xml = BpmnCompiler.injectErrorBoundaries(xml);

        bus.emit("rule_domain.compile.validating", Map.of("llmMs", llmMs, "ruleName", ruleName));

        // Structural pre-check: reject any bare ${X} reference where X
        // isn't a user-provided identifier, an output of an earlier step,
        // a delegate bean, or a FEEL function. This catches the
        // "compiler left a literal as a bare input" failure mode that
        // produces unusable rules like the old container-availability
        // BPMN where ${zip} and ${orderType} should have been derived
        // from ${order.*}.
        String structuralError = validateInputDiscipline(xml);
        String error = structuralError != null ? structuralError
                : bpmnCompiler.tryDeploy(xml, skillName, intentLabel);

        long now = System.currentTimeMillis();
        String resultKey = manifestRule.effectiveResultKey();
        String manifestJson = buildCoverageManifest(slice, manifestRule);

        RuleDomain.RuleDomainBuilder builder = RuleDomain.builder()
                .skillId(skillId)
                .skillName(skillName)
                .intentLabel(intentLabel)
                .sourceHash(skillHasher.hash(skillMarkdown))
                .toolSignature(toolSignature)
                .bpmnXml(xml)
                .compileAttempts(1)
                .version(repo.latestVersion(skillId, intentLabel) + 1)
                .createdAt(now)
                .updatedAt(now)
                .domainGroupId(domainGroupId)
                .domainGroupName(domainGroupName)
                .ruleName(ruleName)
                .matchScope(RuleDomain.SCOPE_RULE)
                .coverageState(RuleDomain.COVERAGE_PROVISIONAL)
                .coverageManifest(manifestJson)
                .traceSource(RuleDomain.TRACE_LLM_TRACE)
                .compiledFromTurn(slice.turnId())
                .resultKey(resultKey);

        if (error != null) {
            log.warn("[TraceBasedBpmnCompiler] BPMN deploy validation failed for rule={}: {}", ruleName, error);
            bus.emit("rule_domain.compile.failed", Map.of("ruleName", ruleName, "error", error));
            return repo.save(builder
                    .status(RuleDomain.STATUS_FAILED)
                    .flowableProcKey("")
                    .lastError(error)
                    .build(), null);
        }

        String procKey = BpmnCompiler.extractProcessId(xml);
        if (procKey == null) {
            String msg = "Compiled BPMN has no <process id=...>";
            bus.emit("rule_domain.compile.failed", Map.of("ruleName", ruleName, "error", msg));
            return repo.save(builder
                    .status(RuleDomain.STATUS_FAILED)
                    .flowableProcKey("")
                    .lastError(msg)
                    .build(), null);
        }

        bus.emit("rule_domain.compile.deployed", Map.of("ruleName", ruleName, "procKey", procKey));

        // Embed the rule's *narrow* intent examples so two-tier matching can
        // find it via rule-level pass. If the manifest lists none, fall back
        // to the rule name (still better than nothing).
        String embedText = manifestRule.intentExamples() == null || manifestRule.intentExamples().isEmpty()
                ? ruleName
                : String.join(" / ", manifestRule.intentExamples());
        float[] embedding = bpmnCompiler.embed(embedText);

        RuleDomain saved = builder
                .status(RuleDomain.STATUS_DRAFT)
                .flowableProcKey(procKey)
                .build();
        RuleDomain persisted = repo.save(saved, embedding);

        bus.emit("rule_domain.compile.saved", Map.of(
                "ruleName", ruleName,
                "domainId", persisted.getId() == null ? "" : persisted.getId(),
                "procKey", procKey));
        return persisted;
    }

    private String invokeLlm(ChatClient client,
                             org.springframework.ai.chat.prompt.ChatOptions options,
                             String system,
                             String user) {
        var req = client.prompt().system(system).user(user);
        if (options != null) req = req.options(options);
        return req.call().content();
    }

    // Bare ${X} references the compiler may legally introduce. Anything
    // else is treated as a bug: the compiler should have derived the
    // value from a prior tool's outputBinding, not invented a new input.
    private static final java.util.Set<String> ALLOWED_BARE_INPUTS = java.util.Set.of(
            "userMessage", "orderId", "customerId", "sessionId");

    private static final java.util.Set<String> KNOWN_DELEGATES = java.util.Set.of(
            "toolCallDelegate", "decisionTableDelegate", "feelExtractDelegate");

    private static final java.util.Set<String> FEEL_FUNCTIONS = java.util.Set.of(
            "now", "today", "date", "time", "duration", "string", "number", "boolean",
            "size", "count", "sum", "min", "max", "abs", "floor", "ceiling", "round",
            "concatenate", "contains", "starts_with", "ends_with", "matches",
            "upper_case", "lower_case", "substring", "split", "list_contains", "not");

    /**
     * Walk the produced BPMN and reject it if it references any bare
     * {@code ${X}} where {@code X} isn't in the allowlist, isn't a
     * delegate bean, isn't an output of an earlier step in the same
     * process, and isn't a FEEL function call. Returns {@code null} on
     * success, or a human-readable error string when the BPMN should be
     * rejected (the rule is then saved as FAILED with this string in
     * {@code lastError}).
     *
     * <p>This is the structural check that catches the
     * "${zip}, ${orderType}, ${actualSequence} are bare inputs but
     * should have been derived from ${order.*}" failure mode that
     * produced unusable container-availability / leg-sequence rules in
     * the first trace-compile attempt.
     */
    static String validateInputDiscipline(String bpmnXml) {
        if (bpmnXml == null) return null;
        java.util.Set<String> outputs = new java.util.HashSet<>();
        // outputBinding values — `<flowable:field name="outputBinding"><flowable:string>X</flowable:string></flowable:field>`
        java.util.regex.Matcher om = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"outputBinding\"[^>]*>\\s*<flowable:string>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (om.find()) {
            String name = om.group(1).trim();
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) outputs.add(name);
        }
        // multi-instance loop element variable
        java.util.regex.Matcher mi = java.util.regex.Pattern.compile(
                "flowable:elementVariable\\s*=\\s*\"([^\"]+)\"").matcher(bpmnXml);
        while (mi.find()) outputs.add(mi.group(1).trim());

        // Walk every ${...} body and look at the LEADING identifier of
        // each comma/paren/operator-separated fragment.
        java.util.Map<String, String> firstBadUsage = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher em = java.util.regex.Pattern.compile("[$#]\\{([^}]+)\\}").matcher(bpmnXml);
        while (em.find()) {
            String body = em.group(1).trim();
            for (String frag : body.split("[(),\\s+\\-*/&|=!<>?:]+")) {
                if (frag.isEmpty()) continue;
                java.util.regex.Matcher idm = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*").matcher(frag);
                if (!idm.find()) continue;
                String lead = idm.group();
                if (lead.startsWith("_")) continue;
                if (FEEL_FUNCTIONS.contains(lead)) continue;
                if (KNOWN_DELEGATES.contains(lead)) continue;
                if (ALLOWED_BARE_INPUTS.contains(lead)) continue;
                if (outputs.contains(lead)) continue;
                if ("true".equals(lead) || "false".equals(lead) || "null".equals(lead)) continue;
                // It's a bare reference to something we can't explain.
                // We only flag it as "bare" when the fragment itself is
                // just the identifier (no `.field` or `[idx]` access).
                if (frag.equals(lead)) {
                    firstBadUsage.putIfAbsent(lead, body);
                }
            }
        }
        if (firstBadUsage.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN references bare process variable(s) the compiler "
                + "should have derived from a prior tool's response:");
        for (var e : firstBadUsage.entrySet()) {
            msg.append("\n  - ").append(e.getKey())
                    .append(" (used as ${").append(e.getValue()).append("})");
        }
        msg.append("\nFix: derive these from an earlier outputBinding variable ")
                .append("(e.g. ${order.<path>}) or via a feelExtractDelegate step. ")
                .append("Allowed bare inputs: ").append(ALLOWED_BARE_INPUTS).append(".");
        return msg.toString();
    }

    private static String traceSystemPrompt() {
        return """
                You are converting a recorded execution trace into a parameterized BPMN 2.0
                process for the Flowable engine. The trace shows real tool calls made by a
                successful LLM-loop run, with real inputs and real responses. Your job is to
                produce a BPMN that re-executes the same flow with new parameters.

                Rules:
                1. Output raw BPMN XML only. No markdown fences, no commentary.
                2. Use ${toolCallDelegate} for tool calls, ${decisionTableDelegate} for
                   decision-table evaluations, ${feelExtractDelegate} for in-process
                   transformations.
                3. Service-task outputs MUST be written via a <flowable:field name="outputBinding">
                   extension element naming the target process variable.
                   NEVER use the resultVariableName="..." attribute — Flowable rejects it on
                   tasks using flowable:delegateExpression and the deploy will fail.
                4. Reference field paths EXACTLY as they appear in the response samples below.
                   Do not invent fields. If you can't find a path in the sample, don't use it.
                5. *** INPUT VARIABLE DISCIPLINE — STRICT ***
                   The ONLY process variables you may reference as bare ${X} (without a
                   `.field` or `[i]` access path) are these "user-provided identifiers":
                       userMessage, orderId, customerId, sessionId
                   Every other value you need MUST be expressed as `${name.path}` where
                   `name` is a variable already written by an earlier service task's
                   outputBinding in the SAME process. For example: prefer
                   `${order.Lines[0].DeliveryAddress.PostalCode}` over `${zip}`.
                   If a value cannot be derived from any prior tool's response, you must
                   either:
                     (a) introduce a `feelExtractDelegate` service task earlier that
                         computes it from existing variables, OR
                     (b) use a literal constant from the trace verbatim (do NOT invent a
                         new input variable for it).
                   Bare ${X} references to anything outside the allowlist above will be
                   rejected by the post-compile validator and the rule will fail.
                6. Replace observed literal values with process variables FROM EARLIER
                   STEPS — not new inputs:
                     - Long numeric ids that match the user message → ${orderId}.
                     - Date/time literals → today() or now() (FEEL functions).
                     - Any other value present in a prior tool's response → derive it
                       via a path expression on that response's outputBinding name.
                     - String literals that don't appear anywhere else → keep verbatim.
                7. When the trace shows N structurally-identical calls (same tool, varying only
                   in per-item fields), fold into a multi-instance subprocess over the
                   driving list. The driving list MUST be built from an earlier outputBinding
                   variable (e.g. via a feelExtractDelegate FEEL expression) — never as a
                   bare input.
                8. Do NOT emit <bpmn:boundaryEvent> for tool failures — they're auto-injected.
                9. Emit a final t_assemble step that builds a single "result" variable shaped
                   per the rule's purpose. The orchestrator merges per-rule results into the
                   composite outcome by the rule's result_key.

                Example service task with correct output binding:
                  <serviceTask id="t_get_order" name="Get Order"
                               flowable:delegateExpression="${toolCallDelegate}">
                    <extensionElements>
                      <flowable:field name="toolName">
                        <flowable:string>Get_OrderID</flowable:string>
                      </flowable:field>
                      <flowable:field name="inputBindings">
                        <flowable:string>{"ORD_ID":"${orderId}"}</flowable:string>
                      </flowable:field>
                      <flowable:field name="outputBinding">
                        <flowable:string>order</flowable:string>
                      </flowable:field>
                    </extensionElements>
                  </serviceTask>

                Required XML preamble:
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             targetNamespace="http://pods.com/rule-domains"
                             typeLanguage="http://www.w3.org/2001/XMLSchema">
                """;
    }

    private String buildTraceUserPrompt(String skillName,
                                        SkillRuleManifest.Rule rule,
                                        ExecutionTrace slice) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill: ").append(skillName).append("\n");
        sb.append("## Rule: ").append(rule.name()).append("\n");
        if (rule.intentExamples() != null && !rule.intentExamples().isEmpty()) {
            sb.append("## Sample intents the user might phrase:\n");
            for (String ex : rule.intentExamples()) sb.append("  - ").append(ex).append("\n");
        }
        sb.append("\n## Execution trace for this rule\n");
        sb.append("This is what happened in the LLM-loop run. Convert it into a BPMN that\n");
        sb.append("does the same thing for any future request matching the intent above.\n\n");

        int i = 1;
        for (ExecutionTrace.TraceStep s : slice.steps()) {
            if (s.isReasoning()) {
                sb.append("### Reasoning before step ").append(i).append(":\n");
                sb.append(truncate(s.text(), 600)).append("\n\n");
                continue;
            }
            sb.append("### Step ").append(i++).append(": ").append(s.name())
                    .append(" (").append(s.elapsedMs()).append("ms)\n");
            sb.append("Input:\n```json\n").append(safeJson(s.input(), 1500)).append("\n```\n");
            sb.append("Output:\n```json\n").append(safeJson(s.output(), 2500)).append("\n```\n\n");
        }

        sb.append("\n## Process variables available to your BPMN\n");
        sb.append("Allowed bare ${X} references (these are the user-provided identifiers):\n");
        sb.append("  - userMessage (raw user text)\n");
        sb.append("  - orderId (best-effort numeric extract from user message)\n");
        sb.append("  - customerId, sessionId (if applicable)\n");
        sb.append("\nAll OTHER values must be derived via path expressions like\n");
        sb.append("`${order.Lines[0].DeliveryAddress.PostalCode}` from variables written by\n");
        sb.append("an earlier step's outputBinding. Do NOT invent new bare inputs.\n\n");

        sb.append("## Result\n");
        sb.append("Emit a t_assemble step at the end that builds a `result` variable. ");
        sb.append("It will be merged into the composite outcome under key `")
                .append(rule.effectiveResultKey()).append("`.\n\n");

        sb.append("Produce the BPMN XML now. XML only — no commentary, no fences.\n");
        return sb.toString();
    }

    /**
     * Build the coverage manifest from the observed trace. v1 records the
     * tools that were exercised + the literal user-prompt-derived inputs so
     * Phase 3's runtime evaluator can detect when a future input falls
     * outside what we've seen.
     */
    private String buildCoverageManifest(ExecutionTrace slice, SkillRuleManifest.Rule rule) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", 1);
        root.put("rule_name", rule.name());
        root.put("compiled_from_turn", slice.turnId() == null ? "" : slice.turnId());

        ArrayNode exercisedTools = root.putArray("exercised_tools");
        List<String> uniqueTools = new ArrayList<>();
        for (ExecutionTrace.TraceStep s : slice.toolSteps()) {
            if (s.name() != null && !uniqueTools.contains(s.name())) uniqueTools.add(s.name());
        }
        for (String t : uniqueTools) {
            ObjectNode te = exercisedTools.addObject();
            te.put("name", t);
        }

        // Empty observed_inputs initially — CoverageEvaluator returns "covered"
        // for empty manifests (legacy back-compat). Phase 3 will populate this
        // from input-shape inference; v1 keeps coverage_state=PROVISIONAL and
        // relies on the orchestrator's circuit breaker for safety.
        root.putObject("observed_inputs");
        root.putArray("open_questions");

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String safeJson(tools.jackson.databind.JsonNode n, int maxLen) {
        if (n == null || n.isMissingNode() || n.isNull()) return "null";
        try {
            String s = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(n);
            return truncate(s, maxLen);
        } catch (Exception ex) {
            return n.toString();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }
}
