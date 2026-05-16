package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.compiler.CompilerPromptBuilder;
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
    private final CompilerPromptBuilder promptBuilder;
    private final RuleDomainRepository repo;
    private final SkillSourceHasher skillHasher;
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TraceBasedBpmnCompiler(ModelProviderRouter modelProviderRouter,
                                  RuleDomainProperties props,
                                  BpmnCompiler bpmnCompiler,
                                  CompilerPromptBuilder promptBuilder,
                                  RuleDomainRepository repo,
                                  SkillSourceHasher skillHasher,
                                  RuleDomainEventBus bus) {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
        this.bpmnCompiler = bpmnCompiler;
        this.promptBuilder = promptBuilder;
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

        // Reuse the canonical prose-compiler system prompt + few-shot
        // examples. That document is the source of truth for the
        // delegate contract (argTemplate vs inputBindings, feelExpr vs
        // expression, FEEL syntax vs ${} interpolation). A hand-rolled
        // trace-compiler prompt drifted from this contract once and
        // produced unusable rules; don't repeat that.
        String system = promptBuilder.buildSystem() + "\n\n" + traceAddendum();
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

        // 0. Catch the recurring "wrong field name" failure first. Each
        //    delegate has a strict set of required field names; if the LLM
        //    emits an alias (inputBindings, expression, inputs, ...) we
        //    must reject before deploy, because Flowable will just throw
        //    MISSING_VARIABLE at run time with no hint at what went wrong.
        String fieldNameError = validateDelegateFieldNames(bpmnXml);
        if (fieldNameError != null) return fieldNameError;

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

    // For every service task wired via flowable:delegateExpression="${beanName}",
    // these are the ONLY field names the runtime delegate will look up. The
    // map is keyed by bean name. If the LLM emits any other field name (a
    // common drift: `inputBindings`, `expression`, `inputs`), the deploy
    // succeeds but every run dies with MISSING_VARIABLE because the delegate
    // never finds the field it needs.
    private static final java.util.Map<String, java.util.Set<String>> DELEGATE_FIELDS = java.util.Map.of(
            "toolCallDelegate",
            java.util.Set.of("toolName", "argTemplate", "outputBinding", "postTransform"),
            "decisionTableDelegate",
            java.util.Set.of("tableName", "inputsTemplate", "outputBinding"),
            "feelExtractDelegate",
            java.util.Set.of("feelExpr", "outputBinding"));

    private static final java.util.Map<String, java.util.Set<String>> DELEGATE_REQUIRED_FIELDS = java.util.Map.of(
            "toolCallDelegate",
            java.util.Set.of("toolName", "argTemplate", "outputBinding"),
            "decisionTableDelegate",
            java.util.Set.of("tableName", "inputsTemplate", "outputBinding"),
            "feelExtractDelegate",
            java.util.Set.of("feelExpr", "outputBinding"));

    /**
     * For each {@code <serviceTask flowable:delegateExpression="${beanName}">}
     * verify the children {@code <flowable:field name="X">} satisfy the
     * bean's contract: every required field must be present and every
     * declared field must be in the allowed set. Returns {@code null} on
     * success, or a human-readable error otherwise.
     */
    static String validateDelegateFieldNames(String bpmnXml) {
        java.util.regex.Matcher taskRe = java.util.regex.Pattern.compile(
                "<serviceTask\\b([^>]*)>([\\s\\S]*?)</serviceTask>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);

        java.util.List<String> problems = new java.util.ArrayList<>();
        while (taskRe.find()) {
            String attrs = taskRe.group(1);
            String body = taskRe.group(2);

            java.util.regex.Matcher delegateRe = java.util.regex.Pattern.compile(
                    "flowable:delegateExpression\\s*=\\s*\"\\s*[$#]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\s*\"")
                    .matcher(attrs);
            if (!delegateRe.find()) continue;
            String bean = delegateRe.group(1);
            java.util.Set<String> allowed = DELEGATE_FIELDS.get(bean);
            java.util.Set<String> required = DELEGATE_REQUIRED_FIELDS.get(bean);
            if (allowed == null) continue; // user-defined delegate; trust it

            java.util.regex.Matcher idRe = java.util.regex.Pattern.compile(
                    "id\\s*=\\s*\"([^\"]+)\"").matcher(attrs);
            String taskId = idRe.find() ? idRe.group(1) : "?";

            java.util.Set<String> declaredFields = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher fieldRe = java.util.regex.Pattern.compile(
                    "<flowable:field\\s+name\\s*=\\s*\"([^\"]+)\"").matcher(body);
            while (fieldRe.find()) declaredFields.add(fieldRe.group(1));

            for (String f : declaredFields) {
                if (!allowed.contains(f)) {
                    problems.add(taskId + " (" + bean + "): unknown field `" + f
                            + "` — allowed: " + allowed + ". "
                            + suggestForBean(bean, f));
                }
            }
            for (String r : required) {
                if (!declaredFields.contains(r)) {
                    problems.add(taskId + " (" + bean + "): missing required field `" + r + "`.");
                }
            }
        }
        if (problems.isEmpty()) return null;
        StringBuilder msg = new StringBuilder(
                "Compiled BPMN uses wrong <flowable:field name=...> values that the "
                + "runtime delegates won't recognize (this is the most common cause of "
                + "MISSING_VARIABLE at run time):");
        for (String p : problems) msg.append("\n  - ").append(p);
        return msg.toString();
    }

    private static String suggestForBean(String bean, String wrongName) {
        if ("toolCallDelegate".equals(bean)) {
            if ("inputBindings".equals(wrongName) || "inputs".equals(wrongName)) {
                return "Did you mean `argTemplate`? (FEEL expressions as values, not ${} interpolation.)";
            }
        }
        if ("feelExtractDelegate".equals(bean)) {
            if ("expression".equals(wrongName) || "expressions".equals(wrongName)) {
                return "Did you mean `feelExpr`?";
            }
        }
        if ("decisionTableDelegate".equals(bean)) {
            if ("inputBindings".equals(wrongName) || "inputs".equals(wrongName)) {
                return "Did you mean `inputsTemplate`?";
            }
        }
        return "";
    }

    /**
     * Trace-mode addendum on top of the canonical compiler system prompt.
     * The base prompt already nails the delegate contract (argTemplate,
     * feelExpr, inputsTemplate, FEEL syntax, etc.); this addendum just
     * reorients the LLM for trace-based compilation, where the input is
     * a recorded execution rather than a skill spec.
     */
    private static String traceAddendum() {
        return """
                ## Trace-mode addendum

                You are converting a recorded execution trace into a parameterized BPMN.
                The trace shows real tool calls made by a successful LLM-loop run, with
                real inputs and real responses. Your job is to produce a BPMN that
                re-executes the same flow with new parameters.

                All the rules above (argTemplate / feelExpr / inputsTemplate / FEEL value
                syntax / outputBinding / final `result` variable / process-id convention)
                still apply. The trace just changes how you *discover* the right shape.

                Trace-specific guidance:

                1. Reference field paths EXACTLY as they appear in the response samples
                   below. Do not invent fields. If you can't find a path in a sample,
                   don't use it.
                2. *** INPUT VARIABLE DISCIPLINE — STRICT ***
                   The ONLY user-provided identifiers you may reference as bare names
                   in an `argTemplate` value are:
                       userMessage, orderId, customerId, sessionId
                   Every other value MUST be a FEEL path into a variable already written
                   by an earlier outputBinding in the same process. For example, prefer
                   `"order.Lines[1].DeliveryAddress.PostalCode"` over a bare `"zip"`.
                   If a value cannot be derived from any prior tool's response, you must
                   either (a) introduce an earlier `${feelExtractDelegate}` task that
                   computes it from existing variables, or (b) use a literal verbatim
                   from the trace — never invent a new input.
                3. Replace observed literal values with FEEL references from earlier
                   steps, not new inputs:
                     - Long numeric ids that match the user message → `orderId`.
                     - Date/time literals → `today()` or `now()`.
                     - Any other value present in a prior tool's response → derive it
                       via a path expression on that response's outputBinding name.
                     - String literals that don't appear anywhere → keep verbatim,
                       FEEL-quoted: `"\\"NEW\\""`.
                4. When the trace shows N structurally-identical calls (same tool,
                   varying per-item fields), fold into a multi-instance subprocess.
                   The driving collection MUST be built from an earlier outputBinding
                   variable, not from a bare input.
                5. Do NOT emit `<boundaryEvent>` elements for tool failures — they are
                   auto-injected by the compiler post-processor. Do NOT emit an
                   `endOnToolFailure` end event either, and especially do NOT make any
                   end event an *error* end event (with `<errorEventDefinition>`).
                   Error end events re-throw their error, and at the top of the process
                   nothing catches it — runs crash with "no matching parent execution
                   for error code TOOL_EXECUTION_FAILED". A single plain `<endEvent>`
                   per scope is all you need; the post-processor adds the failure path.

                Field-name reminder (from the contract above):
                  - toolCallDelegate         → toolName, argTemplate, outputBinding[, postTransform]
                  - decisionTableDelegate    → tableName, inputsTemplate, outputBinding
                  - feelExtractDelegate      → feelExpr, outputBinding
                Any other field-name (e.g. `inputBindings`, `expression`, `inputs`) will
                be rejected by the runtime with `MISSING_VARIABLE` and the rule will fail.
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
        sb.append("These are the only user-provided identifiers (use as bare FEEL refs in argTemplate values):\n");
        sb.append("  - userMessage (raw user text)\n");
        sb.append("  - orderId (best-effort numeric extract from user message)\n");
        sb.append("  - customerId, sessionId (if applicable)\n");
        sb.append("\nAll OTHER values must be FEEL paths into a variable written by an\n");
        sb.append("earlier step's outputBinding (e.g. `order.Lines[1].DeliveryAddress.PostalCode`).\n");
        sb.append("Do NOT introduce new bare-name inputs. Do NOT use ${...} interpolation\n");
        sb.append("inside argTemplate/inputsTemplate/feelExpr — those fields are FEEL, not JUEL.\n\n");

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
