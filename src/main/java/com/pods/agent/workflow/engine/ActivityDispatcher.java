package com.pods.agent.workflow.engine;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ActivityResult;
import com.pods.agent.workflow.engine.domain.ErrorClass;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;
import com.pods.agent.workflow.joget.model.WorkflowActivity;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Executes a single activity according to its type. The four types follow
 * Joget's {@link WorkflowActivity} taxonomy:
 *
 * <ul>
 *   <li>{@link WorkflowActivity#TYPE_TOOL}: invoke a {@link ApplicationPlugin}
 *       bean (looked up via {@link PluginRegistry}). The plugin output is
 *       returned as the activity output and may be stored under the activity's
 *       declared output variable.</li>
 *   <li>{@link WorkflowActivity#TYPE_ROUTE}: invoke a {@link DecisionPlugin}
 *       (or fall back to plain transition-condition routing). Returns a
 *       {@link DecisionResult} attached as the activity output for the
 *       {@link RouteResolver} to consume.</li>
 *   <li>{@link WorkflowActivity#TYPE_NORMAL}: a manual / human-task activity.
 *       For now we treat it as a no-op success since there's no UI for
 *       claim/complete in Phase 1; Phase 5 will gate it via the existing
 *       {@code SkillExecutionGate}.</li>
 *   <li>{@link WorkflowActivity#TYPE_SUBFLOW}: not implemented in Phase 1;
 *       returns a {@code SUBFLOW} error so the engine routes via error edges
 *       until Phase 5 wires up the sub-flow executor.</li>
 * </ul>
 *
 * <p>Plugin properties are resolved against the variable scope through
 * {@link SecureSpelEvaluator}. Resolution failures are surfaced as
 * {@link ErrorClass#EXPRESSION} (not silently nulled) — fixes audit #1.
 */
@Component
@Slf4j
public class ActivityDispatcher {

    /** Run-scope variable carrying the run's default LLM provider id, set by callers (chat / API). */
    public static final String VAR_RUN_PROVIDER_ID = "__providerID";
    /** Run-scope variable carrying the run's default LLM model id, set by callers (chat / API). */
    public static final String VAR_RUN_MODEL_ID = "__modelID";

    private final PluginRegistry plugins;
    private final AuditTrailManager audit;
    private final WorkflowSchemaValidator schemaValidator;
    private final com.pods.agent.workflow.persistence.ActivityPinRepository pinRepo;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final ModelProviderRouter modelProviderRouter;
    private SubFlowExecutor subFlowExecutor;

    @Autowired
    public ActivityDispatcher(PluginRegistry plugins,
                              AuditTrailManager audit,
                              WorkflowSchemaValidator schemaValidator,
                              com.pods.agent.workflow.persistence.ActivityPinRepository pinRepo,
                              tools.jackson.databind.ObjectMapper objectMapper,
                              ModelProviderRouter modelProviderRouter) {
        this.plugins = plugins;
        this.audit = audit;
        this.schemaValidator = schemaValidator;
        this.pinRepo = pinRepo;
        this.objectMapper = objectMapper;
        this.modelProviderRouter = modelProviderRouter;
    }

    ActivityDispatcher(PluginRegistry plugins, AuditTrailManager audit) {
        this(plugins, audit, new WorkflowSchemaValidator(), null, new tools.jackson.databind.ObjectMapper(), null);
    }

    @Autowired(required = false)
    void setSubFlowExecutor(@Lazy SubFlowExecutor subFlowExecutor) {
        this.subFlowExecutor = subFlowExecutor;
    }

    public ActivityResult dispatch(ExecutionContext ctx, ActivityDef activity) {
        try {
            return switch (activity.type()) {
                case WorkflowActivity.TYPE_TOOL -> dispatchTool(ctx, activity);
                case WorkflowActivity.TYPE_ROUTE -> dispatchRoute(ctx, activity);
                case WorkflowActivity.TYPE_NORMAL -> dispatchNormal(ctx, activity);
                case WorkflowActivity.TYPE_SUBFLOW -> dispatchSubflow(ctx, activity);
                case WorkflowActivity.TYPE_FOREACH -> dispatchForEach(ctx, activity);
                case WorkflowActivity.TYPE_WHILE -> dispatchWhile(ctx, activity);
                case WorkflowActivity.TYPE_BATCH -> dispatchBatch(ctx, activity);
                case WorkflowActivity.TYPE_AI_REASONING -> dispatchAiReasoning(ctx, activity);
                default -> ActivityResult.failure(
                        ErrorClass.UNCAUGHT, "unknown activity type: " + activity.type());
            };
        } catch (RuntimeException e) {
            log.warn("[ActivityDispatcher] activity {} threw: {}", activity.id(), e.toString());
            return ActivityResult.failure(ErrorClass.UNCAUGHT,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------- TOOL

    private ActivityResult dispatchTool(ExecutionContext ctx, ActivityDef activity) {
        // Pinned-output fast path: if a pin exists for this (def_id, activity_def_id),
        // return it as the activity's output without invoking the plugin. Used by
        // "re-run from this node" to fast-replay upstream steps deterministically.
        ActivityResult pinned = applyPinIfPresent(ctx, activity);
        if (pinned != null) return pinned;

        if (activity.pluginName() == null) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "tool activity " + activity.id() + " has no pluginName");
        }
        ApplicationPlugin plugin = plugins.applicationPlugin(activity.pluginName()).orElse(null);
        if (plugin == null) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "no ApplicationPlugin registered as " + activity.pluginName());
        }
        Map<String, Object> resolved = resolveProperties(ctx, activity);
        if (resolved == null) {
            // resolve failure already audited; surface as expression failure
            return ActivityResult.failure(ErrorClass.EXPRESSION,
                    "failed to resolve plugin properties");
        }
        // Input schema: the engine always passes the resolved properties map
        // to the plugin, so the schema describes that map. If the LLM wrote
        // a non-object schema (e.g. {"type":"string"}) we validate against the
        // primary input value instead so the natural "this tool takes a
        // string" shape works without forcing object wrapping.
        Object inputSchemaValue = pickInputSchemaTarget(activity, resolved);
        ActivityResult schemaFail = validateSchema(ctx, activity, activity.inputSchema(), inputSchemaValue, "input");
        if (schemaFail != null) return schemaFail;
        Object out;
        try {
            out = plugin.execute(resolved);
        } catch (RuntimeException e) {
            return ActivityResult.failure(ErrorClass.TOOL,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        Map<String, Object> updates = activity.outputVariables().isEmpty()
                ? Map.of()
                : Map.of(activity.outputVariables().get(0).name(), out);
        // Output schema: validate against the raw plugin return value by
        // default — that's what authors think of as "the tool's output".
        // Fall back to the wrapped variable map only when the schema is an
        // object schema that explicitly mentions the output variable name,
        // preserving compatibility with workflows that wrap.
        Object outputSchemaValue = pickOutputSchemaTarget(activity, out, updates);
        schemaFail = validateSchema(ctx, activity, activity.outputSchema(), outputSchemaValue, "output");
        if (schemaFail != null) return schemaFail;
        return ActivityResult.success(out, updates);
    }

    @SuppressWarnings("unchecked")
    private Object pickInputSchemaTarget(ActivityDef activity, Map<String, Object> resolved) {
        Map<String, Object> schema = activity.inputSchema();
        if (schema == null || schema.isEmpty()) return resolved;
        Object typeNode = schema.get("type");
        String type = typeNode == null ? null : String.valueOf(typeNode).trim().toLowerCase();
        if (type == null || type.isEmpty() || "object".equals(type)) {
            return resolved;
        }
        // Non-object schema → validate against the single canonical input
        // value instead of the resolved properties map. Most agent tools take
        // a single "input" property.
        if (resolved.containsKey("input")) return resolved.get("input");
        if (resolved.size() == 1) return resolved.values().iterator().next();
        return resolved;
    }

    @SuppressWarnings("unchecked")
    static Object pickOutputSchemaTarget(ActivityDef activity, Object rawOut, Map<String, Object> updates) {
        Map<String, Object> schema = activity.outputSchema();
        if (schema == null || schema.isEmpty()) return rawOut;
        if (updates.isEmpty()) return rawOut;
        Object typeNode = schema.get("type");
        String type = typeNode == null ? null : String.valueOf(typeNode).trim().toLowerCase();
        // If the schema is explicitly an object schema that names the output
        // variable as a property, the author intends to validate the wrapped
        // form. Otherwise (the common case) validate the raw plugin return.
        if (!"object".equals(type)) return rawOut;
        Object propsNode = schema.get("properties");
        if (!(propsNode instanceof Map<?, ?> props)) return rawOut;
        if (activity.outputVariables().isEmpty()) return rawOut;
        String outVarName = activity.outputVariables().get(0).name();
        return props.containsKey(outVarName) ? updates : rawOut;
    }

    // ------------------------------------------------------------------- ROUTE

    private ActivityResult dispatchRoute(ExecutionContext ctx, ActivityDef activity) {
        if (activity.pluginName() != null && !activity.pluginName().isBlank()) {
            audit.record(ctx.processInstanceId(), null,
                    AuditTrailManager.Action.EXPRESSION_FAILED, ctx.requesterId(),
                    Map.of("activityId", activity.id(),
                            "message", "route activity pluginName is deprecated; transition-only routing is used",
                            "pluginName", activity.pluginName()));
        }
        return ActivityResult.success(null);
    }

    // ------------------------------------------------------------------ NORMAL

    private ActivityResult dispatchSubflow(ExecutionContext ctx, ActivityDef activity) {
        if (subFlowExecutor == null) {
            return ActivityResult.failure(ErrorClass.SUBFLOW,
                    "SubFlowExecutor not available — Spring container not wired");
        }
        return subFlowExecutor.dispatch(ctx, activity);
    }

    private ActivityResult dispatchNormal(ExecutionContext ctx, ActivityDef activity) {
        // If the activity declares `properties.requireApproval == true`, the
        // engine suspends the run until a human approves or rejects via
        // /api/v1/workflow/approvals/{id}. The decision is exposed to
        // downstream activities as the variable `approvalDecision`
        // ("approve" | "reject"); a "reject" fails the activity so the
        // workflow can route via an error edge.
        Object req = activity.properties().get("requireApproval");
        boolean approvalRequired = req instanceof Boolean b ? b
                : req != null && "true".equalsIgnoreCase(String.valueOf(req).trim());

        if (!approvalRequired) {
            log.info("[ActivityDispatcher] manual activity {} auto-completing", activity.id());
            ActivityResult schemaFail = validateSchema(
                    ctx, activity, activity.inputSchema(), ctx.scope().effectiveSnapshot(), "input");
            if (schemaFail != null) return schemaFail;
            schemaFail = validateSchema(ctx, activity, activity.outputSchema(), null, "output");
            if (schemaFail != null) return schemaFail;
            return ActivityResult.success(null);
        }

        Object decision = ctx.scope().effectiveSnapshot().get("approvalDecision");
        if (decision == null) {
            String reason = String.valueOf(activity.properties()
                    .getOrDefault("approvalReason", "approval required"));
            log.info("[ActivityDispatcher] suspending {} for approval", activity.id());
            return ActivityResult.suspended(reason);
        }
        String decisionStr = String.valueOf(decision).trim().toLowerCase();
        if ("reject".equals(decisionStr) || "rejected".equals(decisionStr)) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "approval rejected: " + ctx.scope().effectiveSnapshot()
                            .getOrDefault("approvalComment", ""));
        }
        // approved (treat any other non-null value as approval)
        Map<String, Object> output = Map.of(
                "approved", true,
                "decision", decisionStr,
                "comment", ctx.scope().effectiveSnapshot().getOrDefault("approvalComment", ""));
        ActivityResult schemaFail = validateSchema(ctx, activity, activity.outputSchema(), output, "output");
        if (schemaFail != null) return schemaFail;
        return ActivityResult.success(output);
    }

    private ActivityResult dispatchForEach(ExecutionContext ctx, ActivityDef activity) {
        List<?> items = extractListInput(ctx, activity, "collection", "collectionVar");
        if (items == null) return ActivityResult.failure(ErrorClass.VALIDATION, "foreach collection must be a list");
        String itemVar = stringProperty(activity, "itemVar", "item");
        String indexVar = stringProperty(activity, "indexVar", "index");
        String continueVar = loopContinueVar(activity.id());
        String guardVar = loopGuardVar(activity.id());
        int maxIterations = intProperty(activity, "maxIterations", 1000);
        int guard = intFromScope(ctx, guardVar, 0) + 1;
        if (guard > maxIterations) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "foreach exceeded maxIterations=" + maxIterations);
        }
        int index = intFromScope(ctx, loopIndexVar(activity.id()), 0);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(guardVar, guard);
        updates.put(loopIndexVar(activity.id()), index + 1);
        if (index < items.size()) {
            updates.put(continueVar, true);
            updates.put(itemVar, items.get(index));
            updates.put(indexVar, index);
            return ActivityResult.success(Map.of("continue", true, "index", index, "size", items.size()), updates);
        }
        updates.put(continueVar, false);
        updates.put(loopIndexVar(activity.id()), 0);
        return ActivityResult.success(Map.of("continue", false, "size", items.size()), updates);
    }

    private ActivityResult dispatchWhile(ExecutionContext ctx, ActivityDef activity) {
        String condition = stringProperty(activity, "condition", null);
        if (condition == null || condition.isBlank()) {
            return ActivityResult.failure(ErrorClass.VALIDATION, "while activity requires properties.condition");
        }
        int maxIterations = intProperty(activity, "maxIterations", 1000);
        String guardVar = loopGuardVar(activity.id());
        int guard = intFromScope(ctx, guardVar, 0) + 1;
        if (guard > maxIterations) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "while exceeded maxIterations=" + maxIterations);
        }
        SecureSpelEvaluator.Result eval = SecureSpelEvaluator.evaluateBoolean(condition, ctx.scope().effectiveSnapshot());
        if (!eval.ok()) {
            return ActivityResult.failure(ErrorClass.EXPRESSION, "while condition eval failed: " + eval.error());
        }
        boolean keepRunning = Boolean.TRUE.equals(eval.value());
        return ActivityResult.success(
                Map.of("continue", keepRunning),
                Map.of(loopContinueVar(activity.id()), keepRunning, guardVar, guard));
    }

    private ActivityResult dispatchBatch(ExecutionContext ctx, ActivityDef activity) {
        List<?> items = extractListInput(ctx, activity, "collection", "collectionVar");
        if (items == null) return ActivityResult.failure(ErrorClass.VALIDATION, "batch collection must be a list");
        int size = Math.max(1, intProperty(activity, "batchSize", 10));
        String batchVar = stringProperty(activity, "batchVar", "batchItems");
        String batchIndexVar = stringProperty(activity, "batchIndexVar", "batchIndex");
        int cursor = intFromScope(ctx, loopIndexVar(activity.id()), 0);
        boolean hasMore = cursor < items.size();
        if (!hasMore) {
            return ActivityResult.success(
                    Map.of("continue", false, "batchSize", size),
                    Map.of(loopContinueVar(activity.id()), false, loopIndexVar(activity.id()), 0));
        }
        int end = Math.min(cursor + size, items.size());
        List<?> batch = items.subList(cursor, end);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(batchVar, batch);
        updates.put(batchIndexVar, cursor / size);
        updates.put(loopIndexVar(activity.id()), end);
        updates.put(loopContinueVar(activity.id()), end < items.size());
        return ActivityResult.success(
                Map.of("continue", true, "from", cursor, "to", end, "size", items.size()),
                updates);
    }

    // ------------------------------------------------------------ AI_REASONING

    /**
     * Explicit AI reasoning node. The deterministic runtime is normally barred
     * from invoking the LLM — the chat agent does that during the exploratory
     * phase. The Workflow Architect promotes a reasoning step to an
     * {@code ai_reasoning} activity when the run genuinely requires LLM
     * judgement (classification, summarization, fraud verdict, etc.). All
     * other LLM invocations from a materialized workflow are forbidden by
     * convention; the validator and prompts steer the architect toward this
     * type whenever the source step looked like reasoning.
     *
     * <p>Properties:
     * <ul>
     *   <li>{@code prompt} (required): user prompt. SecureSpel expressions are
     *       resolved by {@link #resolveProperties}.</li>
     *   <li>{@code system} (optional): system prompt.</li>
     *   <li>{@code invokeWhen} (optional): SecureSpel boolean. When present
     *       and {@code false}, the node short-circuits to success without
     *       calling the LLM and writes
     *       {@code {skipped: true, reason: "invokeWhen=false"}} into the
     *       output variable. When absent, the node always runs (per design).</li>
     *   <li>{@code providerID} / {@code modelID} (optional): per-node model
     *       override. Falls back to the run-scope {@code __providerID} /
     *       {@code __modelID} variables that the caller (chat / API) sets on
     *       run start.</li>
     * </ul>
     *
     * <p>Output (also stored in the first declared output variable):
     * {@code { text, finishReason, usage, model: { providerID, modelID }, skipped: false }}.
     * Downstream activities should reference {@code #yourVar.text} — never
     * {@code #yourVar} directly — when they want the assistant message.
     */
    private ActivityResult dispatchAiReasoning(ExecutionContext ctx, ActivityDef activity) {
        ActivityResult pinned = applyPinIfPresent(ctx, activity);
        if (pinned != null) return pinned;

        if (modelProviderRouter == null) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "ai_reasoning activity " + activity.id()
                            + " requires ModelProviderRouter (Spring container not wired)");
        }

        Map<String, Object> resolved = resolveProperties(ctx, activity);
        if (resolved == null) {
            return ActivityResult.failure(ErrorClass.EXPRESSION,
                    "failed to resolve ai_reasoning properties");
        }

        if (resolved.containsKey("invokeWhen")) {
            Boolean cond = coerceBoolean(resolved.get("invokeWhen"));
            if (cond != null && !cond) {
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("text", null);
                skipped.put("finishReason", null);
                skipped.put("usage", Map.of());
                skipped.put("model", Map.of());
                skipped.put("skipped", true);
                skipped.put("reason", "invokeWhen=false");
                Map<String, Object> updates = activity.outputVariables().isEmpty()
                        ? Map.of()
                        : Map.of(activity.outputVariables().get(0).name(), skipped);
                return ActivityResult.success(skipped, updates);
            }
        }

        String prompt = stringOrNull(resolved.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "ai_reasoning activity " + activity.id() + " requires properties.prompt");
        }
        String system = stringOrNull(resolved.get("system"));

        ActivityResult schemaFail = validateSchema(ctx, activity, activity.inputSchema(),
                pickInputSchemaTarget(activity, resolved), "input");
        if (schemaFail != null) return schemaFail;

        ModelRef modelRef = resolveModelRef(ctx, resolved);
        if (modelRef == null) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "ai_reasoning activity " + activity.id()
                            + " has no model: set properties.providerID/modelID, or pass __providerID/__modelID at run start");
        }

        ChatResponse response;
        try {
            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(modelRef);
            if (spec == null || spec.client() == null) {
                return ActivityResult.failure(ErrorClass.TOOL,
                        "ModelProviderRouter returned no ChatClient for " + modelRef.providerID()
                                + "/" + modelRef.modelID());
            }
            ChatClient.ChatClientRequestSpec req = spec.client().prompt();
            if (system != null && !system.isBlank()) req = req.system(system);
            req = req.user(prompt);
            if (spec.options() != null) req = req.options(spec.options());
            response = req.call().chatResponse();
        } catch (RuntimeException e) {
            return ActivityResult.failure(ErrorClass.TOOL,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        Map<String, Object> output = buildAiReasoningOutput(response, modelRef);
        Map<String, Object> updates = activity.outputVariables().isEmpty()
                ? Map.of()
                : Map.of(activity.outputVariables().get(0).name(), output);

        Object outputSchemaValue = pickOutputSchemaTarget(activity, output, updates);
        schemaFail = validateSchema(ctx, activity, activity.outputSchema(), outputSchemaValue, "output");
        if (schemaFail != null) return schemaFail;

        return ActivityResult.success(output, updates);
    }

    private ModelRef resolveModelRef(ExecutionContext ctx, Map<String, Object> resolved) {
        String provider = stringOrNull(resolved.get("providerID"));
        String model = stringOrNull(resolved.get("modelID"));
        if (provider == null || model == null) {
            // Accept "provider/model" shorthand under "model" too.
            String combined = stringOrNull(resolved.get("model"));
            if (combined != null && combined.contains("/")) {
                int slash = combined.indexOf('/');
                if (provider == null) provider = combined.substring(0, slash);
                if (model == null) model = combined.substring(slash + 1);
            }
        }
        Map<String, Object> bindings = ctx.scope().effectiveSnapshot();
        if (provider == null) provider = stringOrNull(bindings.get(VAR_RUN_PROVIDER_ID));
        if (model == null) model = stringOrNull(bindings.get(VAR_RUN_MODEL_ID));
        if (provider == null || model == null) return null;
        return new ModelRef(provider, model);
    }

    private Map<String, Object> buildAiReasoningOutput(ChatResponse response, ModelRef modelRef) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            out.put("text", null);
            out.put("finishReason", null);
            out.put("usage", Map.of());
        } else {
            var output = response.getResult().getOutput();
            out.put("text", output.getText());
            String finishReason = null;
            try {
                finishReason = response.getResult().getMetadata() == null
                        ? null
                        : response.getResult().getMetadata().getFinishReason();
            } catch (Exception ignored) { /* SDK shape varies across providers */ }
            out.put("finishReason", finishReason);
            Map<String, Object> usage = new LinkedHashMap<>();
            try {
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    var u = response.getMetadata().getUsage();
                    if (u.getPromptTokens() != null) usage.put("promptTokens", u.getPromptTokens());
                    if (u.getCompletionTokens() != null) usage.put("completionTokens", u.getCompletionTokens());
                    if (u.getTotalTokens() != null) usage.put("totalTokens", u.getTotalTokens());
                }
            } catch (Exception ignored) { /* token usage is provider-specific */ }
            out.put("usage", usage);
        }
        out.put("model", Map.of(
                "providerID", modelRef.providerID(),
                "modelID", modelRef.modelID()));
        out.put("skipped", false);
        return out;
    }

    private static Boolean coerceBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            String trimmed = s.trim().toLowerCase();
            if ("true".equals(trimmed)) return Boolean.TRUE;
            if ("false".equals(trimmed)) return Boolean.FALSE;
        }
        if (v instanceof Number n) return n.intValue() != 0;
        return null;
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Look up a pinned output for this (process_def, activity_def_id). When
     * present, return a synthesized success result so the engine's main loop
     * skips plugin invocation. The pinned output also flows into the activity's
     * first declared output variable, just like a real execution would.
     */
    private ActivityResult applyPinIfPresent(ExecutionContext ctx, ActivityDef activity) {
        if (pinRepo == null) return null;
        String defId = ctx.definition().id();
        if (defId == null) return null;
        var pin = pinRepo.findOne(defId, activity.id()).orElse(null);
        if (pin == null) return null;
        Object out;
        try {
            out = pin.pinnedOutput() == null
                    ? null
                    : objectMapper.readValue(pin.pinnedOutput(), Object.class);
        } catch (RuntimeException e) {
            log.warn("[ActivityDispatcher] failed to deserialize pin for {}: {}",
                    activity.id(), e.getMessage());
            out = pin.pinnedOutput();
        }
        Map<String, Object> updates = activity.outputVariables().isEmpty()
                ? Map.of()
                : Map.of(activity.outputVariables().get(0).name(), out);
        log.info("[ActivityDispatcher] using pinned output for {}", activity.id());
        return ActivityResult.success(out, updates);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Resolve each property value as a SecureSpel expression if it looks like
     * one (starts with {@code #} or {@code ${...}}). Plain values pass through.
     * Returns {@code null} if any expression fails — caller should treat as
     * an EXPRESSION failure.
     */
    Map<String, Object> resolveProperties(ExecutionContext ctx, ActivityDef activity) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, Object> bindings = ctx.scope().effectiveSnapshot();
        for (Map.Entry<String, Object> e : activity.properties().entrySet()) {
            Object value = e.getValue();
            if (value instanceof String s && looksLikeExpression(s)) {
                String unwrapped = unwrap(s);
                SecureSpelEvaluator.Result r = SecureSpelEvaluator.evaluate(unwrapped, bindings);
                if (!r.ok()) {
                    audit.record(ctx.processInstanceId(), null,
                            AuditTrailManager.Action.EXPRESSION_FAILED, null,
                            Map.of("activityId", activity.id(),
                                    "property", e.getKey(),
                                    "expression", s,
                                    "error", r.error()));
                    return null;
                }
                resolved.put(e.getKey(), r.value());
            } else {
                resolved.put(e.getKey(), value);
            }
        }
        return resolved;
    }

    private static boolean looksLikeExpression(String s) {
        return s.startsWith("#{") && s.endsWith("}");
    }

    private static String unwrap(String s) {
        return s.substring(2, s.length() - 1);
    }

    private ActivityResult validateSchema(ExecutionContext ctx,
                                          ActivityDef activity,
                                          Map<String, Object> schema,
                                          Object value,
                                          String phase) {
        List<String> errors = schemaValidator.validate(schema, value);
        if (errors.isEmpty()) return null;
        audit.record(ctx.processInstanceId(), null,
                AuditTrailManager.Action.EXPRESSION_FAILED, ctx.requesterId(),
                Map.of("activityId", activity.id(),
                        "phase", phase,
                        "schemaErrors", errors));
        return ActivityResult.failure(ErrorClass.VALIDATION,
                "schema validation failed (" + phase + "): " + String.join("; ", errors));
    }

    private static String loopContinueVar(String activityId) {
        return "__loop_continue_" + activityId;
    }

    private static String loopIndexVar(String activityId) {
        return "__loop_index_" + activityId;
    }

    private static String loopGuardVar(String activityId) {
        return "__loop_guard_" + activityId;
    }

    @SuppressWarnings("unchecked")
    private List<?> extractListInput(ExecutionContext ctx, ActivityDef activity, String exprKey, String varKey) {
        Object expression = activity.properties().get(exprKey);
        Object raw = expression;
        if (expression instanceof String s && looksLikeExpression(s)) {
            SecureSpelEvaluator.Result eval = SecureSpelEvaluator.evaluate(unwrap(s), ctx.scope().effectiveSnapshot());
            if (!eval.ok()) return null;
            raw = eval.value();
        } else {
            Object varName = activity.properties().get(varKey);
            if (varName != null) {
                raw = ctx.scope().effectiveSnapshot().get(String.valueOf(varName));
            }
        }
        if (raw instanceof List<?> list) return list;
        if (raw instanceof Object[] arr) return java.util.Arrays.asList(arr);
        return null;
    }

    private int intFromScope(ExecutionContext ctx, String key, int fallback) {
        Object v = ctx.scope().effectiveSnapshot().get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringProperty(ActivityDef activity, String key, String fallback) {
        Object v = activity.properties().get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private int intProperty(ActivityDef activity, String key, int fallback) {
        Object v = activity.properties().get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }
}
