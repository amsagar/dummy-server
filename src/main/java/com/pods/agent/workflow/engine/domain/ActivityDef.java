package com.pods.agent.workflow.engine.domain;

import com.pods.agent.workflow.joget.model.WorkflowActivity;
import java.util.List;
import java.util.Map;

/**
 * One activity in a process definition. {@code type} is one of the
 * {@link WorkflowActivity#TYPE_NORMAL}/{@code TYPE_TOOL}/{@code TYPE_ROUTE}/
 * {@code TYPE_SUBFLOW} constants.
 *
 * Type-specific fields:
 * <ul>
 *   <li>{@code TYPE_TOOL}: {@code pluginName} selects the {@link
 *       com.pods.agent.workflow.joget.plugin.ApplicationPlugin} bean. {@code
 *       properties} is the plugin config (values may be SecureSpel expressions
 *       resolved at runtime).</li>
 *   <li>{@code TYPE_ROUTE}: {@code pluginName} selects a {@link
 *       com.pods.agent.workflow.joget.plugin.DecisionPlugin} bean, or {@code
 *       null} to use simple condition-on-transition routing.</li>
 *   <li>{@code TYPE_SUBFLOW}: {@code subflowDefId} is the child process_def
 *       id; {@code subflowInputs}/{@code subflowOutputs} map variable names
 *       across the boundary.</li>
 * </ul>
 *
 * {@code deadlineExpression} is an ISO-8601 duration (e.g. {@code PT60S}); the
 * engine sets {@code due_at} when the activity starts. Null means no deadline.
 */
public record ActivityDef(
        String id,
        String name,
        String type,
        String pluginName,
        Map<String, Object> properties,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String deadlineExpression,
        boolean isStart,
        boolean isEnd,
        String subflowDefId,
        Map<String, String> subflowInputs,
        Map<String, String> subflowOutputs,
        List<VariableSpec> outputVariables,
        /**
         * If true, this activity is an AND-join: it executes only after EVERY
         * incoming transition has fired. If false (default) the activity is an
         * OR-join: it executes on the first incoming arrival and ignores
         * subsequent arrivals. See JoinCoordinator for full semantics.
         */
        boolean andJoin,
        ActivityErrorPolicy errorPolicy
) {
    public ActivityDef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ActivityDef.id must be non-blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("ActivityDef.type must be set for " + id);
        }
        switch (type) {
            case WorkflowActivity.TYPE_NORMAL,
                 WorkflowActivity.TYPE_TOOL,
                 WorkflowActivity.TYPE_ROUTE,
                 WorkflowActivity.TYPE_SUBFLOW,
                 WorkflowActivity.TYPE_FOREACH,
                 WorkflowActivity.TYPE_WHILE,
                 WorkflowActivity.TYPE_BATCH,
                 WorkflowActivity.TYPE_AI_REASONING -> { /* ok */ }
            default -> throw new IllegalArgumentException(
                    "ActivityDef.type must be one of normal/tool/route/subflow/foreach/while/batch/ai_reasoning, got: "
                            + type);
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        subflowInputs = subflowInputs == null ? Map.of() : Map.copyOf(subflowInputs);
        subflowOutputs = subflowOutputs == null ? Map.of() : Map.copyOf(subflowOutputs);
        outputVariables = outputVariables == null ? List.of() : List.copyOf(outputVariables);
        errorPolicy = errorPolicy == null ? ActivityErrorPolicy.defaults() : errorPolicy;
    }
}
