package com.pods.agent.workflow.proposal;

import com.pods.agent.workflow.api.ProcessDefinitionMapper;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared parser + validator for {@link ProcessDefDto}-shaped workflow JSON.
 *
 * <p>This is a pure utility extracted from the original {@code
 * WorkflowProposalService} so the Phase-1 classifier (intent signature
 * generation), the Phase-2 builder loop (per-attempt validation), and any
 * future tooling can share one source of truth for what a "valid" workflow
 * draft looks like.
 *
 * <p>The {@link #validate(String, String)} entry point returns a typed
 * {@link ValidationReport} so the builder agent can be sent precise edit
 * targets — codes like {@code transition_trigger_invalid} are far more
 * actionable for the model than a stack trace.
 */
@Component
@Slf4j
public class WorkflowJsonValidator {

    public static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b");
    public static final Pattern LONG_NUMBER_PATTERN = Pattern.compile("\\b\\d{4,}\\b");

    private static final Set<String> CANONICAL_ACTIVITY_TYPES = Set.of(
            "normal", "tool", "route", "subflow", "foreach", "while", "batch", "ai_reasoning");
    private static final Set<String> CANONICAL_TRIGGERS = Set.of(
            "ON_SUCCESS", "ON_NO_MATCH", "ON_ERROR", "ON_TIMEOUT", "ON_VALIDATION_ERROR");

    /**
     * Minimum number of {@code tool} activities sharing the same {@code
     * toolName} + same input key set + varying values that triggers the
     * {@code enumeration_antipattern} error. Two-step manual fan-outs are
     * still allowed; three or more is what the {@code workflow-architect}
     * skill's "AgentToolPlugin activities = distinct call-sites" rule forbids
     * (it should be a single {@code foreach}/{@code while}/{@code batch}
     * activity body instead).
     */
    static final int ENUMERATION_THRESHOLD = 3;

    private final ObjectMapper objectMapper;
    private final WorkflowContractCatalog contractCatalog;

    /**
     * SpEL variable reference inside an expression string: matches
     * {@code #varName.firstField} so we can ask the catalog whether the
     * envelope path the author chose for {@code firstField} matches the
     * producing activity's plugin contract.
     */
    private static final Pattern SPEL_VAR_DEREF = Pattern.compile(
            "#([A-Za-z_][A-Za-z0-9_]*)\\s*\\??\\.([A-Za-z_][A-Za-z0-9_]*)");

    private static final Set<String> CODE_EXEC_ENVELOPE_FIELDS = Set.of(
            "output", "success", "stdout", "stderr");

    public WorkflowJsonValidator(ObjectMapper objectMapper, WorkflowContractCatalog contractCatalog) {
        this.objectMapper = objectMapper;
        this.contractCatalog = contractCatalog;
    }

    /**
     * One-shot parse + structural validation. Both passes run unconditionally
     * so the builder agent always sees the full set of errors per attempt
     * (rather than fix-one, find-next).
     */
    public ValidationReport validate(String rawJson, String sourcePrompt) {
        return validate(rawJson, sourcePrompt, List.of());
    }

    /**
     * Variant that additionally enforces template-congruence: for every
     * non-null {@code template} passed in (typically a workflow skeleton
     * shipped by a skill under {@code templates/*.json}), the draft must
     * contain at least every {@code (toolName, pluginName)} pair the
     * template declares and at least as many {@code foreach} activities.
     * Drafts that drift earn a {@code template_structure_drift} error per
     * missing piece. This is the structural counterpart to the alignment
     * judge's "did you actually start from the skeleton?" critique —
     * generic across every skill that ships a templates/ directory.
     */
    public ValidationReport validate(String rawJson,
                                     String sourcePrompt,
                                     List<ProcessDefDto> templates) {
        List<ValidationError> errors = new ArrayList<>();
        ProcessDefDto dto;
        try {
            String json = extractJson(rawJson);
            dto = parseProcessDefDtoFlexible(json);
        } catch (Exception e) {
            errors.add(new ValidationError("parse_failed", e.getMessage(), null));
            return new ValidationReport(null, errors);
        }
        if (!validateGenericWorkflow(dto, sourcePrompt)) {
            errors.add(new ValidationError(
                    "proposal_not_generic",
                    "workflow JSON contains run-specific literals (UUIDs / long numbers) extracted from the source prompt; replace them with variables",
                    null));
        }
        validateWorkflowStructureCollect(dto, errors);
        if (templates != null) {
            for (ProcessDefDto template : templates) {
                validateTemplateCongruence(dto, template, errors);
            }
        }
        return new ValidationReport(dto, errors);
    }

    public String extractJson(String raw) {
        if (raw == null) throw new IllegalStateException("empty_llm_response");
        String text = raw.trim();
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) return text.substring(first, last + 1);
        throw new IllegalStateException("invalid_json_response");
    }

    public ProcessDefDto parseProcessDefDtoFlexible(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!(root instanceof ObjectNode objectRoot)) {
            throw new IllegalStateException("workflow_json_must_be_object");
        }
        JsonNode activitiesNode = objectRoot.get("activities");
        if (activitiesNode instanceof ArrayNode activities) {
            for (JsonNode activityNode : activities) {
                if (!(activityNode instanceof ObjectNode activityObj)) continue;
                JsonNode properties = activityObj.get("properties");
                if (!(properties == null || properties.isNull() || properties.isObject())) {
                    ObjectNode normalized = objectMapper.createObjectNode();
                    if (properties.isArray()) {
                        ArrayNode arr = (ArrayNode) properties;
                        int idx = 0;
                        for (JsonNode item : arr) {
                            if (item != null && item.isObject()
                                    && item.has("key")
                                    && item.has("value")
                                    && item.get("key").isTextual()) {
                                normalized.set(item.get("key").asText(), item.get("value"));
                            } else if (item != null && item.isObject() && item.size() == 1) {
                                item.properties().forEach(entry -> normalized.set(entry.getKey(), entry.getValue()));
                            } else {
                                normalized.set("item" + idx++, item);
                            }
                        }
                    } else {
                        normalized.set("value", properties);
                    }
                    activityObj.set("properties", normalized);
                }
            }
            normalizeActivityTypes(activities);
        }
        JsonNode transitionsNode = objectRoot.get("transitions");
        if (transitionsNode instanceof ArrayNode transitions) {
            normalizeTransitionFields(transitions);
        }
        return objectMapper.treeToValue(objectRoot, ProcessDefDto.class);
    }

    public boolean validateGenericWorkflow(ProcessDefDto dto, String sourcePrompt) {
        if (dto == null) return false;
        try {
            String json = objectMapper.writeValueAsString(dto).toLowerCase(Locale.ROOT);
            for (String literal : extractRunSpecificLiterals(sourcePrompt)) {
                if (json.contains(literal.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Collect-mode structural validation. Walks every check the original
     * fail-fast {@code validateWorkflowStructure} ran but accumulates errors
     * into {@code errors} instead of throwing on the first one.
     */
    public void validateWorkflowStructureCollect(ProcessDefDto dto, List<ValidationError> errors) {
        if (dto == null) {
            errors.add(new ValidationError("workflow_json_null", "draft is null after parse", null));
            return;
        }
        // Engine-side mapping does its own structural assertions — run it first
        // so any graph/connectivity errors surface.
        try {
            ProcessDefDto candidate = dto;
            if (dto.id() == null || dto.id().isBlank()) {
                candidate = new ProcessDefDto(
                        UUID.randomUUID().toString(),
                        dto.name(),
                        dto.version(),
                        dto.packageId(),
                        dto.description(),
                        dto.variables(),
                        dto.activities(),
                        dto.transitions());
            }
            ProcessDefinitionMapper.toDomain(candidate);
        } catch (Exception e) {
            errors.add(new ValidationError("engine_mapping_failed", e.getMessage(), null));
        }
        if (dto.activities() != null) {
            for (ProcessDefDto.ActivityDto a : dto.activities()) {
                String type = a.type() == null ? "" : a.type().toLowerCase(Locale.ROOT);
                if (!CANONICAL_ACTIVITY_TYPES.contains(type)) {
                    errors.add(new ValidationError(
                            "activity_type_invalid",
                            "activity " + a.id() + " has non-canonical type '" + a.type()
                                    + "'; allowed: " + CANONICAL_ACTIVITY_TYPES,
                            "activities[" + a.id() + "].type"));
                }
                if ("route".equals(type) && a.pluginName() != null && !a.pluginName().isBlank()) {
                    errors.add(new ValidationError(
                            "route_activity_plugin_not_allowed",
                            "route activity " + a.id() + " must not declare a pluginName",
                            "activities[" + a.id() + "].pluginName"));
                }
                if (isLoopType(type)) {
                    Object maxIterations = a.properties() == null ? null : a.properties().get("maxIterations");
                    if (!(maxIterations instanceof Number num) || num.intValue() <= 0) {
                        errors.add(new ValidationError(
                                "loop_activity_missing_max_iterations",
                                "loop activity " + a.id() + " must declare a positive properties.maxIterations",
                                "activities[" + a.id() + "].properties.maxIterations"));
                    }
                }
                if ("ai_reasoning".equals(type)) {
                    Map<String, Object> props = a.properties();
                    Object prompt = props == null ? null : props.get("prompt");
                    if (!(prompt instanceof String s) || s.isBlank()) {
                        errors.add(new ValidationError(
                                "ai_reasoning_missing_prompt",
                                "ai_reasoning activity " + a.id() + " must declare properties.prompt",
                                "activities[" + a.id() + "].properties.prompt"));
                    }
                    if (a.outputVariables() == null || a.outputVariables().isEmpty()) {
                        errors.add(new ValidationError(
                                "ai_reasoning_missing_output_variable",
                                "ai_reasoning activity " + a.id() + " must declare at least one outputVariables entry",
                                "activities[" + a.id() + "].outputVariables"));
                    }
                    if (a.pluginName() != null && !a.pluginName().isBlank()) {
                        errors.add(new ValidationError(
                                "ai_reasoning_plugin_not_allowed",
                                "ai_reasoning activity " + a.id() + " must have pluginName=null",
                                "activities[" + a.id() + "].pluginName"));
                    }
                }
            }
        }
        if (dto.transitions() != null) {
            for (ProcessDefDto.TransitionDto t : dto.transitions()) {
                if (t.trigger() == null || t.trigger().isBlank()) {
                    errors.add(new ValidationError(
                            "transition_trigger_required",
                            "transition " + t.id() + " must declare a trigger",
                            "transitions[" + t.id() + "].trigger"));
                    continue;
                }
                String normalized = t.trigger().trim().toUpperCase(Locale.ROOT);
                if (!CANONICAL_TRIGGERS.contains(normalized)) {
                    errors.add(new ValidationError(
                            "transition_trigger_invalid",
                            "transition " + t.id() + " has trigger '" + t.trigger()
                                    + "'; allowed: " + CANONICAL_TRIGGERS,
                            "transitions[" + t.id() + "].trigger"));
                }
            }
        }
        validateNoEnumerationAntipattern(dto, errors);
        validateExpressionEnvelopes(dto, errors);
        validateForbiddenSpelTokens(dto, errors);
        validateForeachWiring(dto, errors);
        validateUndeclaredVariableReferences(dto, errors);
    }

    /**
     * Scans every expression string against
     * {@code default-skills/workflow-architect/doc/spel-rules.json} and emits
     * {@code forbidden_spel_token} for each match. The rules mirror what
     * {@code SecureSpelEvaluator} rejects at parse/runtime (T(...), new,
     * @bean, .getClass, .class). Catching them in the validator means the
     * builder LLM sees a precise corrective sentence on the same attempt
     * rather than burning a retry on a runtime VALIDATION error.
     */
    private void validateForbiddenSpelTokens(ProcessDefDto dto, List<ValidationError> errors) {
        if (dto == null || contractCatalog == null) return;
        var rules = contractCatalog.spelRules();
        if (rules.isEmpty()) return;
        for (ExpressionLocation loc : collectExpressionLocations(dto)) {
            for (var rule : rules) {
                if (rule.pattern().matcher(loc.expression).find()) {
                    errors.add(new ValidationError(
                            "forbidden_spel_token",
                            "expression at " + loc.where + " contains a SecureSpel-forbidden token ("
                                    + rule.code() + "): " + rule.description()
                                    + " Replacement: " + rule.replacement(),
                            loc.where));
                }
            }
        }
    }

    /**
     * Loop activities (foreach / while / batch) need THREE transitions to
     * iterate correctly: a body-forward {@code ON_SUCCESS} edge, a body-back
     * {@code ON_SUCCESS} edge from the last body activity to the loop
     * activity, and a loop-exit {@code ON_NO_MATCH} edge (typically priority
     * >= 100 with isDefault=true). Drafts missing any of these get a
     * {@code foreach_wiring_incomplete} error. We compute the body-back edge
     * by checking whether any transition's target is this loop activity
     * (any inbound edge counts — the LLM may have wired through several
     * body steps; only the back-edge into the foreach matters).
     */
    private void validateForeachWiring(ProcessDefDto dto, List<ValidationError> errors) {
        if (dto == null || dto.activities() == null || dto.transitions() == null) return;
        Set<String> loopIds = new LinkedHashSet<>();
        for (ProcessDefDto.ActivityDto a : dto.activities()) {
            if (a == null || a.type() == null) continue;
            if (isLoopType(a.type().toLowerCase(Locale.ROOT))) loopIds.add(a.id());
        }
        if (loopIds.isEmpty()) return;
        Map<String, Boolean> hasBodyForward = new LinkedHashMap<>();
        Map<String, Boolean> hasLoopExit = new LinkedHashMap<>();
        Map<String, Boolean> hasBodyBack = new LinkedHashMap<>();
        for (String id : loopIds) {
            hasBodyForward.put(id, false);
            hasLoopExit.put(id, false);
            hasBodyBack.put(id, false);
        }
        for (ProcessDefDto.TransitionDto t : dto.transitions()) {
            if (t == null) continue;
            String trigger = t.trigger() == null ? "" : t.trigger().trim().toUpperCase(Locale.ROOT);
            String from = t.fromActivityId();
            String to = t.toActivityId();
            if (loopIds.contains(from)) {
                if ("ON_SUCCESS".equals(trigger)) hasBodyForward.put(from, true);
                else if ("ON_NO_MATCH".equals(trigger)) hasLoopExit.put(from, true);
            }
            if (loopIds.contains(to) && "ON_SUCCESS".equals(trigger) && !loopIds.contains(from)) {
                // Back-edge from a body activity into the loop activity.
                hasBodyBack.put(to, true);
            }
        }
        for (String id : loopIds) {
            List<String> missing = new java.util.ArrayList<>();
            if (Boolean.FALSE.equals(hasBodyForward.get(id))) missing.add("body-forward (ON_SUCCESS from " + id + " to its body)");
            if (Boolean.FALSE.equals(hasBodyBack.get(id))) missing.add("body-back (ON_SUCCESS from last body activity back to " + id + ")");
            if (Boolean.FALSE.equals(hasLoopExit.get(id))) missing.add("loop-exit (ON_NO_MATCH default priority>=100 from " + id + " to the next activity)");
            if (!missing.isEmpty()) {
                errors.add(new ValidationError(
                        "foreach_wiring_incomplete",
                        "loop activity '" + id + "' is missing required transition(s): "
                                + String.join("; ", missing)
                                + ". See workflow-architect/references/foreach-wiring.md.",
                        "activities[" + id + "]"));
            }
        }
    }

    /**
     * Every {@code #identifier} referenced in any expression string must
     * resolve to either a declared top-level workflow variable or the output
     * variable of some activity. Catches typos and stale renames before
     * runtime. Skips well-known synthetic identifiers (loop guards) and
     * convention identifiers like {@code item} / {@code index} that the
     * engine seeds when an itemVar/indexVar wasn't explicitly named.
     */
    private void validateUndeclaredVariableReferences(ProcessDefDto dto, List<ValidationError> errors) {
        if (dto == null) return;
        Set<String> declared = new LinkedHashSet<>();
        if (dto.variables() != null) {
            for (ProcessDefDto.VariableSpecDto v : dto.variables()) {
                if (v != null && v.name() != null) declared.add(v.name());
            }
        }
        if (dto.activities() != null) {
            for (ProcessDefDto.ActivityDto a : dto.activities()) {
                if (a == null) continue;
                if (a.outputVariables() != null) {
                    for (ProcessDefDto.VariableSpecDto v : a.outputVariables()) {
                        if (v != null && v.name() != null) declared.add(v.name());
                    }
                }
                // Loop activities seed itemVar/indexVar/batchVar/batchIndexVar
                // as accessible variables during body execution.
                if (a.properties() != null) {
                    for (String key : List.of("itemVar", "indexVar", "batchVar", "batchIndexVar")) {
                        Object v = a.properties().get(key);
                        if (v instanceof String s && !s.isBlank()) declared.add(s.trim());
                    }
                }
            }
        }
        // Engine-seeded defaults when itemVar/indexVar are not specified.
        declared.add("item");
        declared.add("index");
        declared.add("batchItems");
        declared.add("batchIndex");
        declared.add("input"); // CodeExec body-bind

        Pattern varRef = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)");
        for (ExpressionLocation loc : collectExpressionLocations(dto)) {
            var m = varRef.matcher(loc.expression);
            Set<String> reported = new LinkedHashSet<>();
            while (m.find()) {
                String name = m.group(1);
                if (declared.contains(name)) continue;
                if (name.startsWith("__loop_")) continue; // synthetic loop guards
                if (!reported.add(name)) continue;
                errors.add(new ValidationError(
                        "undeclared_variable_reference",
                        "expression at " + loc.where + " references #" + name
                                + " but no top-level variable and no activity outputVariables entry has that name."
                                + " Either declare it in variables[] (for workflow inputs) or add it to the producing activity's outputVariables.",
                        loc.where));
            }
        }
    }

    /**
     * Walks every SpEL expression in the workflow and flags two engine-trap
     * mistakes the LLM tends to make without a code-side check:
     *
     * <ol>
     *   <li>{@code result_expression_envelope_mismatch} —
     *       {@code CodeExecPlugin} wraps its return in
     *       {@code {success, output, stdout, stderr}}, so authors MUST access
     *       its result via {@code #var.output.<field>}. Every other plugin
     *       returns its body directly with NO envelope, so authors MUST NOT
     *       prefix with {@code .output}. Mixing these up is the root cause
     *       of the {@code journeyType} bug that landed against the Validate
     *       Pods Order workflow — the model wrote
     *       {@code #legSequenceResult.output.journeyType} where
     *       {@code legSequenceResult} was an {@code AgentToolPlugin}
     *       (decisionTableEvaluate) result with no envelope.</li>
     *   <li>{@code tool_output_shape_mismatch} — for
     *       {@code AgentToolPlugin} activities whose {@code toolName} is
     *       listed in
     *       {@code default-skills/workflow-architect/doc/tools.json}, flag
     *       any expression that accesses a top-level field not in the
     *       documented output shape. {@code decisionTableEvaluate} is the
     *       seed entry: its output fields live under {@code .outputs}
     *       (plural), not {@code .output} (singular) and not at the top
     *       level.</li>
     * </ol>
     *
     * <p>Unknown plugins/tools (not in the contract catalog) are silently
     * skipped so new plugins don't break validation before their contract
     * is documented. Variables not produced by any activity (top-level
     * workflow inputs declared in {@code variables[]} but never written by
     * an output-variable) are also skipped — those are user-provided
     * domain objects whose shape we don't know.
     */
    private void validateExpressionEnvelopes(ProcessDefDto dto, List<ValidationError> errors) {
        if (dto == null || contractCatalog == null) return;
        Map<String, VariableProducer> producerByVar = mapVariableProducers(dto);
        if (producerByVar.isEmpty()) return;

        for (ExpressionLocation loc : collectExpressionLocations(dto)) {
            var matcher = SPEL_VAR_DEREF.matcher(loc.expression);
            // Dedup repeated occurrences within the same expression — one
            // error per (location, var, firstField) is enough to act on.
            Set<String> reported = new LinkedHashSet<>();
            while (matcher.find()) {
                String varName = matcher.group(1);
                String firstField = matcher.group(2);
                VariableProducer producer = producerByVar.get(varName);
                if (producer == null) continue;
                String dedupKey = varName + "." + firstField;
                if (!reported.add(dedupKey)) continue;

                var pluginContract = contractCatalog.plugin(producer.pluginName);
                if (pluginContract.isPresent()) {
                    boolean wraps = pluginContract.get().wrapsInEnvelope();
                    if (wraps && !CODE_EXEC_ENVELOPE_FIELDS.contains(firstField)) {
                        errors.add(new ValidationError(
                                "result_expression_envelope_mismatch",
                                "expression at " + loc.where + " reads #" + varName + "." + firstField
                                        + " but #" + varName + " is produced by " + producer.pluginName
                                        + " which wraps its return in {success, output, stdout, stderr}."
                                        + " Use #" + varName + ".output." + firstField + " instead."
                                        + " See workflow-architect/references/output-envelopes.md.",
                                loc.where));
                    } else if (!wraps && "output".equals(firstField)) {
                        errors.add(new ValidationError(
                                "result_expression_envelope_mismatch",
                                "expression at " + loc.where + " reads #" + varName + ".output.* but #"
                                        + varName + " is produced by " + producer.pluginName
                                        + " which returns its body directly (no envelope)."
                                        + " Access fields directly: #" + varName + ".<field>."
                                        + " See workflow-architect/references/output-envelopes.md.",
                                loc.where));
                    }
                }

                if (producer.toolName != null && (pluginContract.isEmpty() || !pluginContract.get().wrapsInEnvelope())) {
                    var toolContract = contractCatalog.tool(producer.toolName);
                    if (toolContract.isPresent()
                            && !toolContract.get().outputTopLevelKeys().isEmpty()
                            && !toolContract.get().outputTopLevelKeys().contains(firstField)) {
                        errors.add(new ValidationError(
                                "tool_output_shape_mismatch",
                                "expression at " + loc.where + " reads #" + varName + "." + firstField
                                        + " but tool '" + producer.toolName + "' returns top-level keys "
                                        + toolContract.get().outputTopLevelKeys()
                                        + ". Hint: " + String.join("; ", toolContract.get().accessorHints())
                                        + ". See workflow-architect/references/decision-tables.md.",
                                loc.where));
                    }
                }
            }
        }
    }

    /** Maps a workflow variable name to the activity that produced it. */
    private Map<String, VariableProducer> mapVariableProducers(ProcessDefDto dto) {
        Map<String, VariableProducer> out = new LinkedHashMap<>();
        if (dto.activities() == null) return out;
        for (ProcessDefDto.ActivityDto a : dto.activities()) {
            if (a == null || a.outputVariables() == null || a.outputVariables().isEmpty()) continue;
            String plugin = a.pluginName();
            String toolName = null;
            if (a.properties() != null) {
                Object t = a.properties().get("toolName");
                if (t instanceof String s && !s.isBlank()) toolName = s.trim();
            }
            for (ProcessDefDto.VariableSpecDto v : a.outputVariables()) {
                if (v == null || v.name() == null || v.name().isBlank()) continue;
                out.put(v.name(), new VariableProducer(a.id(), plugin, toolName));
            }
        }
        return out;
    }

    /** Every place an expression string can live in a workflow. */
    private List<ExpressionLocation> collectExpressionLocations(ProcessDefDto dto) {
        List<ExpressionLocation> out = new ArrayList<>();
        if (dto.activities() != null) {
            for (ProcessDefDto.ActivityDto a : dto.activities()) {
                if (a == null) continue;
                if (a.properties() != null) {
                    for (Map.Entry<String, Object> e : a.properties().entrySet()) {
                        addExpressionStringsRecursively(e.getValue(),
                                "activities[" + a.id() + "].properties." + e.getKey(), out);
                    }
                }
                if (a.deadlineExpression() != null) {
                    out.add(new ExpressionLocation(a.deadlineExpression(),
                            "activities[" + a.id() + "].deadlineExpression"));
                }
            }
        }
        if (dto.transitions() != null) {
            for (ProcessDefDto.TransitionDto t : dto.transitions()) {
                if (t == null || t.condition() == null || t.condition().isBlank()) continue;
                out.add(new ExpressionLocation(t.condition(), "transitions[" + t.id() + "].condition"));
            }
        }
        if (dto.variables() != null) {
            for (ProcessDefDto.VariableSpecDto v : dto.variables()) {
                if (v == null || v.defaultExpression() == null) continue;
                out.add(new ExpressionLocation(v.defaultExpression(),
                        "variables[" + v.name() + "].defaultExpression"));
            }
        }
        return out;
    }

    private static void addExpressionStringsRecursively(Object value, String path,
                                                        List<ExpressionLocation> sink) {
        if (value == null) return;
        if (value instanceof String s) {
            if (!s.isBlank()) sink.add(new ExpressionLocation(s, path));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                addExpressionStringsRecursively(e.getValue(), path + "." + e.getKey(), sink);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                addExpressionStringsRecursively(list.get(i), path + "[" + i + "]", sink);
            }
        }
    }

    private record VariableProducer(String activityId, String pluginName, String toolName) {}

    private record ExpressionLocation(String expression, String where) {}

    /**
     * Detects the "N copies of the same tool with hardcoded inputs differing
     * only by one key" anti-pattern. This is what the failing builder run
     * produced (20 separate {@code call_getProductById_N} activities, each
     * with {@code {"id":N}}) instead of one {@code foreach} body. The
     * {@code workflow-architect} skill explicitly forbids it: "the count of
     * AgentToolPlugin activities you emit MUST equal the count of distinct
     * agent-tool call-sites in the turn (one per loop body, even if the turn
     * called it many times)".
     *
     * <p>Heuristic — for each group of {@code tool} activities sharing the
     * same {@code toolName}:
     * <ul>
     *   <li>Skip groups smaller than {@link #ENUMERATION_THRESHOLD} (manual
     *       fan-outs of 2 are not flagged).</li>
     *   <li>Parse each activity's {@code properties.input}. SpEL templates
     *       ({@code "#{...}"}) are skipped — those are already parameterized
     *       and not enumerations.</li>
     *   <li>Require every parsed input to be an object with the SAME set of
     *       top-level keys.</li>
     *   <li>Require at least one key to have values that differ across two
     *       or more activities (otherwise the activities are exact dupes —
     *       likely a JSON authoring mistake but not specifically this
     *       anti-pattern).</li>
     * </ul>
     * On match, emit one {@code enumeration_antipattern} error per offending
     * tool group with a message that names the activity ids and points at
     * the canonical {@code foreach-accumulate.json} replacement.
     */
    private void validateNoEnumerationAntipattern(ProcessDefDto dto, List<ValidationError> errors) {
        if (dto == null || dto.activities() == null) return;
        Map<String, List<ProcessDefDto.ActivityDto>> byTool = new LinkedHashMap<>();
        for (ProcessDefDto.ActivityDto a : dto.activities()) {
            if (a == null) continue;
            String type = a.type() == null ? "" : a.type().toLowerCase(Locale.ROOT);
            if (!"tool".equals(type)) continue;
            Map<String, Object> props = a.properties();
            Object toolNameValue = props == null ? null : props.get("toolName");
            if (!(toolNameValue instanceof String toolName) || toolName.isBlank()) continue;
            byTool.computeIfAbsent(toolName.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<ProcessDefDto.ActivityDto>> entry : byTool.entrySet()) {
            List<ProcessDefDto.ActivityDto> group = entry.getValue();
            if (group.size() < ENUMERATION_THRESHOLD) continue;

            List<Map<String, Object>> parsedInputs = new ArrayList<>();
            List<String> activityIds = new ArrayList<>();
            boolean abort = false;
            for (ProcessDefDto.ActivityDto a : group) {
                Map<String, Object> parsed = parseActivityInput(a);
                if (parsed == null) {
                    // Either a SpEL template or unparseable — not an enumeration.
                    abort = true;
                    break;
                }
                parsedInputs.add(parsed);
                activityIds.add(a.id());
            }
            if (abort || parsedInputs.size() < ENUMERATION_THRESHOLD) continue;

            Set<String> referenceKeys = new TreeSet<>(parsedInputs.get(0).keySet());
            boolean keysMatch = true;
            for (Map<String, Object> input : parsedInputs) {
                if (!new TreeSet<>(input.keySet()).equals(referenceKeys)) {
                    keysMatch = false;
                    break;
                }
            }
            if (!keysMatch) continue;

            // At least one key must have varying values across two or more
            // activities — otherwise the duplicates are exact clones, not an
            // enumeration over a varying parameter.
            boolean anyKeyVaries = false;
            for (String key : referenceKeys) {
                Set<Object> values = new LinkedHashSet<>();
                for (Map<String, Object> input : parsedInputs) {
                    values.add(normalizeValueForCompare(input.get(key)));
                    if (values.size() > 1) {
                        anyKeyVaries = true;
                        break;
                    }
                }
                if (anyKeyVaries) break;
            }
            if (!anyKeyVaries) continue;

            String toolNameOriginal = group.get(0).properties().get("toolName").toString();
            String idsList = String.join(", ", activityIds);
            errors.add(new ValidationError(
                    "enumeration_antipattern",
                    "found " + group.size() + " 'tool' activities (" + idsList
                            + ") all calling '" + toolNameOriginal
                            + "' with hardcoded inputs differing only by " + referenceKeys
                            + ". Collapse them into ONE 'foreach' activity whose body is a single"
                            + " 'tool' activity that reads the varying value from #{#currentItem}."
                            + " Reference: default-skills/workflow-architect/templates/foreach-accumulate.json"
                            + " (load via skill_load(\"workflow-architect\")).",
                    "activities[*].properties.toolName='" + toolNameOriginal + "'"));
        }
    }

    /**
     * Asserts the draft is structurally congruent with a skill-supplied
     * workflow skeleton. Generic — runs for any template the caller hands
     * in, not tied to any specific workflow. Checks two things, both
     * additive (draft may extend the template but not subtract from it):
     *
     * <ol>
     *   <li>Every {@code (toolName, pluginName)} pair in the template must
     *       appear in the draft. This catches the most common failure mode
     *       we observed: the model "skips" required tool calls by deleting
     *       activities the skeleton declared. Identity is case-insensitive
     *       on toolName and pluginName; missing pluginName is permitted
     *       (treated as null vs null match).</li>
     *   <li>The draft's {@code foreach} activity count must be ≥ the
     *       template's. Replacing a foreach with an enumeration of
     *       activities is the second-most-common drift mode and is also
     *       blocked separately by the enumeration anti-pattern check, but
     *       this rule catches the "model dropped the foreach entirely and
     *       used a single activity instead" case which the enumeration
     *       check doesn't flag.</li>
     * </ol>
     *
     * Each missing piece produces its own {@code template_structure_drift}
     * error with a message that names what's missing and points the model
     * at the skeleton it should have started from. Templates with a null
     * activities list are skipped silently (defensive — a broken template
     * shouldn't crash validation).
     */
    private void validateTemplateCongruence(ProcessDefDto draft,
                                            ProcessDefDto template,
                                            List<ValidationError> errors) {
        if (draft == null || template == null || template.activities() == null) return;
        List<ProcessDefDto.ActivityDto> draftActivities = draft.activities() == null
                ? List.of() : draft.activities();

        // 1. ACTIVITY-ID congruence (strongest check). Every activity id in
        //    the template must appear in the draft with the same type. This
        //    is the contract: the skeleton's activity ids ARE the schema.
        //    Models that "rebuild" the workflow by inventing new ids fail
        //    here immediately, not after the alignment judge has burned a
        //    retry on critique. Type match prevents the also-common
        //    failure where the model keeps an id like 'iterateServiceability'
        //    but changes its type from foreach to something else.
        Map<String, ProcessDefDto.ActivityDto> draftById = new LinkedHashMap<>();
        for (ProcessDefDto.ActivityDto a : draftActivities) {
            if (a != null && a.id() != null) draftById.put(a.id(), a);
        }
        for (ProcessDefDto.ActivityDto templateActivity : template.activities()) {
            if (templateActivity == null || templateActivity.id() == null) continue;
            String id = templateActivity.id();
            ProcessDefDto.ActivityDto draftMatch = draftById.get(id);
            if (draftMatch == null) {
                errors.add(new ValidationError(
                        "template_structure_drift",
                        "skill-supplied skeleton requires activity id '" + id
                                + "' (type=" + templateActivity.type()
                                + ") but the draft has no activity with that id."
                                + " Activity ids ARE part of the skeleton contract — do not rename,"
                                + " delete, or replace them. If your edits removed this activity,"
                                + " re-add it from the skeleton under templates/*.json verbatim."
                                + " Template: '" + safeName(template) + "'.",
                        "activities[" + id + "]"));
            } else if (templateActivity.type() != null && draftMatch.type() != null
                    && !templateActivity.type().equalsIgnoreCase(draftMatch.type())) {
                errors.add(new ValidationError(
                        "template_structure_drift",
                        "skill-supplied skeleton declares activity '" + id + "' as type='"
                                + templateActivity.type() + "' but the draft has it as type='"
                                + draftMatch.type() + "'. Activity type is part of the skeleton"
                                + " contract — changing foreach to normal or tool to ai_reasoning"
                                + " is forbidden. Template: '" + safeName(template) + "'.",
                        "activities[" + id + "].type"));
            }
        }

        // 2. (toolName, pluginName) pair coverage. Backstop in case the
        //    activity-id check passes (e.g. model kept ids but swapped
        //    properties.toolName from decisionTableEvaluate to something
        //    else, or changed pluginName from AgentToolPlugin to a custom
        //    plugin). Tool activities with blank toolName are tracked by
        //    pluginName alone so CodeExecPlugin activities stay in scope.
        Set<String> templatePairs = collectToolPluginPairs(template);
        Set<String> draftPairs = collectToolPluginPairs(draft);
        Set<String> missing = new LinkedHashSet<>(templatePairs);
        missing.removeAll(draftPairs);
        for (String pair : missing) {
            errors.add(new ValidationError(
                    "template_structure_drift",
                    "skill-supplied workflow skeleton declares tool activity "
                            + pair + " but the draft has no matching pair."
                            + " Start your draft from the skeleton under templates/*.json (surfaced"
                            + " by skill_load with a 'REQUIRED WORKFLOW SKELETON(S)' banner) and"
                            + " edit field values rather than synthesizing a different layout."
                            + " Template name: '" + safeName(template) + "'.",
                    null));
        }

        // 3. Count foreach activities in template vs draft. The draft must
        //    have at least the template's count so the model can't quietly
        //    replace a foreach with a single activity (caught by #1 if ids
        //    were renamed, by this if ids stayed but types changed).
        long templateForeachCount = template.activities().stream()
                .filter(a -> a != null && a.type() != null
                        && "foreach".equalsIgnoreCase(a.type().trim()))
                .count();
        long draftForeachCount = draftActivities.stream()
                .filter(a -> a != null && a.type() != null
                        && "foreach".equalsIgnoreCase(a.type().trim()))
                .count();
        if (templateForeachCount > draftForeachCount) {
            errors.add(new ValidationError(
                    "template_structure_drift",
                    "skill-supplied workflow skeleton declares " + templateForeachCount
                            + " foreach activity(ies) but the draft has only " + draftForeachCount
                            + ". A foreach in the skeleton is a hard requirement — replacing it"
                            + " with a single activity or enumerated calls violates Step coverage"
                            + " rules and the alignment judge will reject the draft."
                            + " Template name: '" + safeName(template) + "'.",
                    null));
        }
    }

    private static Set<String> collectToolPluginPairs(ProcessDefDto dto) {
        Set<String> out = new LinkedHashSet<>();
        if (dto == null || dto.activities() == null) return out;
        for (ProcessDefDto.ActivityDto a : dto.activities()) {
            if (a == null || a.type() == null || !"tool".equalsIgnoreCase(a.type().trim())) continue;
            Map<String, Object> props = a.properties();
            Object toolNameValue = props == null ? null : props.get("toolName");
            String plugin = a.pluginName() == null ? "" : a.pluginName().trim();
            if (toolNameValue instanceof String toolName && !toolName.isBlank()) {
                out.add("(toolName='" + toolName.trim().toLowerCase(Locale.ROOT)
                        + "', pluginName='" + plugin.toLowerCase(Locale.ROOT) + "')");
            } else if (!plugin.isBlank()) {
                // tool activities without a toolName (e.g. CodeExecPlugin
                // which is identified by its `code` rather than a tool
                // registry name) — key on activity id + plugin so they
                // still participate in the pair-coverage check. Without
                // this the model could delete every CodeExec activity
                // and still pass the (toolName, pluginName) check.
                String id = a.id() == null ? "" : a.id().trim();
                out.add("(activityId='" + id.toLowerCase(Locale.ROOT)
                        + "', pluginName='" + plugin.toLowerCase(Locale.ROOT) + "')");
            }
        }
        return out;
    }

    private static String safeName(ProcessDefDto dto) {
        if (dto == null) return "(null)";
        String n = dto.name();
        return n == null || n.isBlank() ? "(unnamed)" : n;
    }

    /**
     * Best-effort extraction of {@code activity.properties.input} as a
     * top-level JSON object. Returns null when the input is missing, is a
     * SpEL template (starts with {@code #{}), or isn't object-shaped.
     */
    private Map<String, Object> parseActivityInput(ProcessDefDto.ActivityDto activity) {
        if (activity == null || activity.properties() == null) return null;
        Object inputValue = activity.properties().get("input");
        if (inputValue == null) return null;
        if (inputValue instanceof Map<?, ?> map) {
            Map<String, Object> coerced = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                coerced.put(String.valueOf(e.getKey()), e.getValue());
            }
            return coerced;
        }
        if (!(inputValue instanceof String raw)) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // SpEL templates / variable refs are already parameterized.
        if (trimmed.startsWith("#{") || trimmed.startsWith("#")) return null;
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) return null;
        try {
            Object parsed = objectMapper.readValue(trimmed, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    coerced.put(String.valueOf(e.getKey()), e.getValue());
                }
                return coerced;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Normalize input-map values for cross-activity equality comparisons.
     * Numbers and booleans round-trip through {@link Object#toString()} so
     * that {@code 1} (int) and {@code 1L} (long) compare equal — Jackson can
     * reasonably hand us either depending on payload size.
     */
    private Object normalizeValueForCompare(Object value) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof String s) return s;
        return value;
    }

    /**
     * Maps BPMN-style activity types onto the canonical set accepted by
     * ActivityDef. Canonical values pass through untouched.
     */
    private void normalizeActivityTypes(ArrayNode activities) {
        Map<String, String> remapped = new LinkedHashMap<>();
        boolean anyStartFlagged = false;
        for (JsonNode activityNode : activities) {
            if (!(activityNode instanceof ObjectNode obj)) continue;
            if (obj.path("isStart").asBoolean(false)) anyStartFlagged = true;
        }
        for (JsonNode activityNode : activities) {
            if (!(activityNode instanceof ObjectNode obj)) continue;
            JsonNode typeNode = obj.get("type");
            if (typeNode == null || !typeNode.isTextual()) continue;
            String original = typeNode.asText();
            String key = original.trim().toLowerCase(Locale.ROOT);
            String mapped = switch (key) {
                case "normal", "tool", "route", "subflow", "foreach", "while", "batch", "ai_reasoning" -> null;
                case "startevent", "start", "event", "none" -> "route";
                case "endevent", "end", "terminateendevent" -> "route";
                case "task", "servicetask", "scripttask", "businessruletask" -> "tool";
                case "usertask", "manualtask", "humantask" -> "normal";
                case "exclusivegateway", "inclusivegateway", "eventbasedgateway" -> "route";
                case "parallelgateway" -> "route";
                case "subprocess", "callactivity" -> "subflow";
                default -> "route";
            };
            if (mapped == null) continue;
            obj.put("type", mapped);
            remapped.put(obj.path("id").asText(original), original + " -> " + mapped);
            if (("startevent".equals(key) || "start".equals(key))
                    && !obj.path("isStart").asBoolean(false) && !anyStartFlagged) {
                obj.put("isStart", true);
                anyStartFlagged = true;
            }
            if ("endevent".equals(key) || "end".equals(key) || "terminateendevent".equals(key)) {
                if (!obj.path("isEnd").asBoolean(false)) obj.put("isEnd", true);
            }
            if ("parallelgateway".equals(key) && !obj.path("andJoin").asBoolean(false)) {
                obj.put("andJoin", true);
            }
        }
        if (!remapped.isEmpty()) {
            log.warn("[WorkflowJsonValidator] normalized {} non-canonical activity type(s): {}",
                    remapped.size(), remapped);
        }
    }

    private void normalizeTransitionFields(ArrayNode transitions) {
        for (JsonNode transitionNode : transitions) {
            if (!(transitionNode instanceof ObjectNode obj)) continue;
            JsonNode triggerNode = obj.get("trigger");
            if (triggerNode == null || triggerNode.isNull() || !triggerNode.isTextual()) continue;
            String raw = triggerNode.asText().trim();
            if (raw.isEmpty()) continue;
            String normalized = raw
                    .replace("-", "_")
                    .replace(" ", "_")
                    .toUpperCase(Locale.ROOT);
            if (!normalized.startsWith("ON_")) {
                normalized = "ON_" + normalized;
            }
            obj.put("trigger", normalized);
        }
    }

    private boolean isLoopType(String type) {
        if (type == null) return false;
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return "foreach".equals(normalized) || "while".equals(normalized) || "batch".equals(normalized);
    }

    public List<String> extractRunSpecificLiterals(String sourcePrompt) {
        if (sourcePrompt == null || sourcePrompt.isBlank()) return List.of();
        List<String> literals = new ArrayList<>();
        var uuidMatcher = UUID_PATTERN.matcher(sourcePrompt);
        while (uuidMatcher.find()) {
            literals.add(uuidMatcher.group());
        }
        var numericMatcher = LONG_NUMBER_PATTERN.matcher(sourcePrompt);
        while (numericMatcher.find()) {
            literals.add(numericMatcher.group());
        }
        return literals;
    }

    /**
     * Test-friendly fail-fast variant: parses + validates, throws on first
     * structural issue. Wraps {@link #validate(String, String)} so behaviour
     * matches the pre-extraction {@code WorkflowProposalService} contract
     * the original test suite asserted against.
     */
    public void assertWorkflowStructure(ProcessDefDto dto) {
        if (dto == null) throw new IllegalStateException("workflow_json_null");
        List<ValidationError> errors = new ArrayList<>();
        validateWorkflowStructureCollect(dto, errors);
        if (!errors.isEmpty()) {
            ValidationError first = errors.get(0);
            throw new IllegalStateException(first.code()
                    + (first.path() == null ? "" : " at " + first.path())
                    + ": " + first.message());
        }
    }

    /** A single typed validation finding the builder agent can act on. */
    public record ValidationError(String code, String message, String path) {}

    /**
     * @param dto    parsed DTO when parsing succeeded; null otherwise.
     * @param errors empty when the draft passed validation; one or more
     *               typed errors otherwise.
     */
    public record ValidationReport(ProcessDefDto dto, List<ValidationError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }
}
