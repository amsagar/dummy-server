package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.compiler.CompilerPromptBuilder;
import com.pods.agent.ruledomain.compiler.agentic.AgenticTraceCompiler;
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
    private final AgenticTraceCompiler agenticCompiler;
    private final RuleDomainRepository repo;
    private final SkillSourceHasher skillHasher;
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TraceBasedBpmnCompiler(ModelProviderRouter modelProviderRouter,
                                  RuleDomainProperties props,
                                  BpmnCompiler bpmnCompiler,
                                  CompilerPromptBuilder promptBuilder,
                                  AgenticTraceCompiler agenticCompiler,
                                  RuleDomainRepository repo,
                                  SkillSourceHasher skillHasher,
                                  RuleDomainEventBus bus) {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
        this.bpmnCompiler = bpmnCompiler;
        this.promptBuilder = promptBuilder;
        this.agenticCompiler = agenticCompiler;
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
    public RuleDomain compileFromTrace(String sessionId,
                                       String skillId,
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

        // Delegate the LLM + validation portion to the agentic compiler.
        // It writes the trace + skill into a VFS workspace, gives the
        // model filesystem tools, runs a multi-turn loop with
        // validate-and-revise feedback, and returns a passing BPMN or
        // exhausts its attempt budget. The persistence / embedding /
        // version concerns stay here.
        long llmStart = System.currentTimeMillis();
        AgenticTraceCompiler.CompileResult result = agenticCompiler.compile(
                sessionId, slice.turnId(), skillName, skillMarkdown, manifestRule, slice);
        long llmMs = System.currentTimeMillis() - llmStart;

        bus.emit("rule_domain.compile.validating", Map.of(
                "llmMs", llmMs,
                "ruleName", ruleName,
                "attempts", result.attempts(),
                "ok", result.ok()));

        String xml = safeBpmnXmlForPersistence(result.xml());
        String error = result.ok() ? null : (result.lastError() == null ? "agentic compile failed" : result.lastError());

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
    public static String validateInputDiscipline(String bpmnXml) {
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
    public static String validateDelegateFieldNames(String bpmnXml) {
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

    // Literals that may legitimately appear in either trace responses or
    // BPMN argTemplates without being suspicious. These are protocol-level
    // constants the SKILL contract is allowed to hardcode (e.g. country
    // codes, channel identifiers). Extend cautiously — every entry here
    // weakens the check.
    private static final java.util.Set<String> ALLOWED_TRACE_LITERALS = java.util.Set.of(
            "US", "USA", "CA", "MX",
            "true", "false", "null",
            "OK", "Salesforce", "SFDC", "Web", "WEB", "API",
            // PODS protocol / rule constants used in valid filters and
            // sequence normalization. These may legitimately be hardcoded
            // in FEEL despite appearing in trace data.
            "IDEL", "RETSC", "LDT", "REDEL", "FPU", "MOV", "SID",
            "SFP", "SCDEL", "CSRED", "CSFPU",
            "NEW", "WRT", "WTW", "RDL"
    );
    private static final int MIN_INTERESTING_LITERAL_LENGTH = 2;

    /**
     * Reject any quoted FEEL literal in an argTemplate / inputsTemplate /
     * feelExpr that also appears as a value somewhere in this rule's
     * recorded tool responses. Such literals are almost always compiler
     * mistakes — the BPMN happens to work for the training order because
     * the literal matches, but will fail for any other order.
     *
     * <p>Returns {@code null} when grounded, or a human-readable error
     * listing every offending literal + the trace path where the same
     * value was observed.
     */
    public static String validateLiteralGrounding(String bpmnXml, ExecutionTrace slice) {
        if (bpmnXml == null || slice == null) return null;

        // 1. Build the set of values present in any recorded response.
        //    Map value-string → first-observed JSON pointer for the
        //    diagnostic message.
        java.util.Map<String, String> traceValues = new java.util.LinkedHashMap<>();
        for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
            if (step.output() == null) continue;
            collectStringValues(step.output(), "$." + safeStep(step.name()), traceValues);
        }
        if (traceValues.isEmpty()) return null;

        // 2. Walk every argTemplate / inputsTemplate / postTransform field
        //    in the BPMN. Parse its content as a JSON-shaped object and
        //    inspect ONLY the value side of each `(key, value)` pair —
        //    keys are static parameter names, not derivable. Skip the
        //    `tableName` field entirely (it's a static decision-table
        //    identifier). For `feelExpr` walk every literal but apply the
        //    looks-like-identifier exemption so single-token uppercase
        //    codes (`"NEW"`, `"WRT"`, …) pass through.
        java.util.Map<String, String> offenders = new java.util.LinkedHashMap<>();

        // 2a. JSON-shaped fields: argTemplate / inputsTemplate / postTransform.
        java.util.regex.Matcher jsonFieldRe = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"(argTemplate|inputsTemplate|postTransform)\"[^>]*>"
                + "\\s*<flowable:string>([\\s\\S]*?)</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (jsonFieldRe.find()) {
            String body = jsonFieldRe.group(2).replaceAll("<!\\[CDATA\\[|\\]\\]>", "").trim();
            tools.jackson.databind.JsonNode tree;
            try {
                tree = STATIC_MAPPER.readTree(body);
            } catch (Exception ex) {
                // Body isn't strict JSON — common for FEEL-quoted values.
                // Fall through to a regex value-only scan as a fallback.
                collectValueOnlyLiterals(body, traceValues, offenders);
                continue;
            }
            if (tree != null && tree.isObject()) {
                for (String key : tree.propertyNames()) {
                    tools.jackson.databind.JsonNode v = tree.get(key);
                    if (v != null && v.isTextual()) {
                        checkLiteral(v.asString(), traceValues, offenders);
                    } else if (v != null && (v.isObject() || v.isArray())) {
                        // Nested — recurse, still only checking text values.
                        collectTextValuesFromJson(v, traceValues, offenders);
                    }
                }
            } else {
                collectValueOnlyLiterals(body, traceValues, offenders);
            }
        }

        // 2b. feelExpr — its content is FEEL, not key-value JSON. Apply a
        // looser check: extract every double-quoted literal but skip
        // identifier-shaped tokens (`"NEW"`, `"WRT"`) via the
        // looksLikeIdentifierConstant heuristic.
        java.util.regex.Matcher feelFieldRe = java.util.regex.Pattern.compile(
                "<flowable:field\\s+name\\s*=\\s*\"feelExpr\"[^>]*>"
                + "\\s*<flowable:string>([\\s\\S]*?)</flowable:string>",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (feelFieldRe.find()) {
            String body = feelFieldRe.group(1).replaceAll("<!\\[CDATA\\[|\\]\\]>", "");
            java.util.regex.Matcher litRe = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(body);
            while (litRe.find()) {
                String lit = litRe.group(1);
                if (looksLikeIdentifierConstant(lit)) continue;
                checkLiteral(lit, traceValues, offenders);
            }
        }

        if (offenders.isEmpty()) return null;

        StringBuilder msg = new StringBuilder(
                "Compiled BPMN hardcodes literal value(s) that the recorded trace "
                + "shows can be derived from a prior tool's response. The rule won't "
                + "generalize beyond the training order:");
        for (var e : offenders.entrySet()) {
            msg.append("\n  - \"").append(e.getKey()).append("\" (observed in trace at ")
                    .append(e.getValue()).append(")");
        }
        msg.append("\nFix: replace each literal with a FEEL path into the response variable ")
                .append("(e.g. `order.Lines[1].DeliveryAddress.PostalCode` instead of `\"64157\"`).");
        return msg.toString();
    }

    /** Recursively walk a JsonNode and collect every string value (with
     *  its JSON-pointer-like path) into the {@code into} map. Used by
     *  {@link #validateLiteralGrounding} as the set of values the BPMN
     *  must not hardcode. */
    private static void collectStringValues(
            tools.jackson.databind.JsonNode node,
            String path,
            java.util.Map<String, String> into) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            String s = node.asString();
            if (s != null && s.length() >= MIN_INTERESTING_LITERAL_LENGTH
                    && !ALLOWED_TRACE_LITERALS.contains(s.trim())) {
                into.putIfAbsent(s.trim(), path);
            }
            return;
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                collectStringValues(node.get(name), path + "." + name, into);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectStringValues(node.get(i), path + "[" + i + "]", into);
            }
        }
    }

    private static String safeStep(String s) {
        return s == null ? "?" : s.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Used to parse argTemplate / inputsTemplate / postTransform bodies
     *  as JSON so we can inspect only the value side of each pair. */
    private static final tools.jackson.databind.ObjectMapper STATIC_MAPPER =
            new tools.jackson.databind.ObjectMapper();

    /** Test a single string literal against the trace-values map and
     *  record it as an offender if it matches AND isn't an identifier-
     *  shaped constant (e.g. "NEW", "WRT"). Centralizes the check used
     *  by both the JSON-walk and the FEEL-walk. */
    private static void checkLiteral(String value,
                                     java.util.Map<String, String> traceValues,
                                     java.util.Map<String, String> offenders) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.length() < MIN_INTERESTING_LITERAL_LENGTH) return;
        if (ALLOWED_TRACE_LITERALS.contains(trimmed)) return;
        if (looksLikeIdentifierConstant(trimmed)) return;
        if (traceValues.containsKey(trimmed) && !offenders.containsKey(trimmed)) {
            offenders.put(trimmed, traceValues.get(trimmed));
        }
    }

    /** Recurse into nested JSON only collecting textual VALUES (never
     *  keys). Used when an argTemplate value is itself an object/array. */
    private static void collectTextValuesFromJson(tools.jackson.databind.JsonNode node,
                                                  java.util.Map<String, String> traceValues,
                                                  java.util.Map<String, String> offenders) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            checkLiteral(node.asString(), traceValues, offenders);
            return;
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                collectTextValuesFromJson(node.get(name), traceValues, offenders);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectTextValuesFromJson(node.get(i), traceValues, offenders);
            }
        }
    }

    /** Fallback for argTemplate bodies that aren't strict JSON (FEEL
     *  literals with escaped quotes, multiline strings, etc.). Walk the
     *  body looking for `"key" : "value"` patterns and only check the
     *  value side. */
    private static void collectValueOnlyLiterals(String body,
                                                  java.util.Map<String, String> traceValues,
                                                  java.util.Map<String, String> offenders) {
        // Match `"key" : "value"` non-greedy, allowing escape sequences in
        // value. Captures only the value.
        java.util.regex.Matcher kvRe = java.util.regex.Pattern.compile(
                "\"[^\"]+\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        while (kvRe.find()) {
            String value = kvRe.group(1).replace("\\\"", "\"");
            // The value may itself be a FEEL-quoted literal like `\"foo\"`
            // (which after JSON unescape becomes `"foo"`). Strip one layer.
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                value = value.substring(1, value.length() - 1);
            }
            checkLiteral(value, traceValues, offenders);
        }
    }

    /** True for short identifier-shaped strings that the LLM legitimately
     *  hardcodes as protocol or business constants. Examples this passes:
     *  "NEW", "WRT", "OK", "IDEL", "Salesforce". Examples this fails:
     *  "64157" (zip), "Initial Delivery" (has space), "Leg Sequences"
     *  (handled separately as tableName), "abc def" (multi-word).
     *
     *  Rule: a single alphanumeric token of ≤ 12 chars, must contain at
     *  least one letter, must start with a letter, no spaces. */
    private static boolean looksLikeIdentifierConstant(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.length() > 12) return false;
        if (!Character.isLetter(s.charAt(0))) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            else if (!Character.isDigit(c) && c != '_' && c != '-') return false;
        }
        return hasLetter;
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
                   don't use it. For FEEL filter predicates like `Lines[X = "Y"]`, you
                   may only use field names `X` that appear at the corresponding level
                   in the recorded response. If you need to filter by a field you can't
                   see in the sample, refuse — the BPMN won't generalize.
                1b. *** TRACE-LITERAL DISCIPLINE — STRICT, ENFORCED ***
                    For every quoted string literal you would put in an `argTemplate`,
                    `inputsTemplate`, or `feelExpr` value: if that exact literal
                    appears as a value ANYWHERE in the recorded response of an earlier
                    tool (or as a value in the user prompt), you MUST instead encode
                    a FEEL path that reads it back from the response variable. Never
                    hardcode `"64157"`, `"COMMERCIAL"`, `"IDEL"`, `"Long Distance"`,
                    or any other value the trace shows you can derive. The
                    post-compile validator inspects every literal and rejects rules
                    that hardcode trace-observed values.
                    A literal IS allowed only when (a) it's a true constant of the
                    SKILL contract (e.g. `"US"` in `countryCode`), or (b) it does
                    NOT appear anywhere in any earlier recorded response. When in
                    doubt: write a FEEL path.
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
                                        String skillMarkdown,
                                        SkillRuleManifest.Rule rule,
                                        ExecutionTrace slice) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill: ").append(skillName).append("\n");
        sb.append("## Rule: ").append(rule.name()).append("\n");
        if (rule.intentExamples() != null && !rule.intentExamples().isEmpty()) {
            sb.append("## Sample intents the user might phrase:\n");
            for (String ex : rule.intentExamples()) sb.append("  - ").append(ex).append("\n");
        }

        // The skill markdown is the canonical contract — it tells the LLM
        // which fields exist on the order, which ItemCodes count as legs,
        // how to map ItemCode → ServiceCode, etc. Without this, the trace
        // alone is too thin for the LLM to write generalized FEEL filters.
        // 60k chars is enough for any realistic skill spec; truncating any
        // tighter risks cutting the table that defines ItemCode mappings.
        if (skillMarkdown != null && !skillMarkdown.isBlank()) {
            sb.append("\n## Skill specification (the canonical contract — your BPMN must follow this)\n\n");
            sb.append(truncate(skillMarkdown, 60000)).append("\n\n");
        }

        sb.append("\n## Execution trace for this rule\n");
        sb.append("This is what happened in the LLM-loop run. Convert it into a BPMN that\n");
        sb.append("does the same thing for any future request matching the intent above.\n");
        sb.append("CRITICAL: every value in the recorded request payloads below that ALSO\n");
        sb.append("appears as a field value in the prior tool's response MUST be encoded\n");
        sb.append("as a FEEL path into that response variable — never copy the literal.\n\n");

        int i = 1;
        for (ExecutionTrace.TraceStep s : slice.steps()) {
            if (s.isReasoning()) {
                sb.append("### Reasoning before step ").append(i).append(":\n");
                sb.append(truncate(s.text(), 600)).append("\n\n");
                continue;
            }
            sb.append("### Step ").append(i++).append(": ").append(s.name())
                    .append(" (").append(s.elapsedMs()).append("ms)\n");
            // Tool output is the ground truth the LLM uses to derive FEEL
            // paths. Truncating it is catastrophic for trace-based
            // compilation — every field path beyond the truncation point
            // is invisible to the LLM, and the literal-grounding
            // validator will (correctly) reject any BPMN that hardcodes
            // values from there. Modern LLM context windows are ample;
            // we give outputs 50k chars and inputs 10k. Genuinely huge
            // responses get truncated with a clear marker.
            sb.append("Input:\n```json\n").append(safeJson(s.input(), 10_000)).append("\n```\n");
            sb.append("Output:\n```json\n").append(safeJson(s.output(), 50_000)).append("\n```\n\n");
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

    /**
     * Failed compile attempts still persist a rule-domain row so admin/debug
     * views can show the error. The DB schema requires {@code bpmn_xml NOT NULL},
     * so normalize absent XML to empty string instead of null.
     */
    static String safeBpmnXmlForPersistence(String xml) {
        return xml == null ? "" : xml;
    }
}
