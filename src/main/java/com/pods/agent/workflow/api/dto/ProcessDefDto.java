package com.pods.agent.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pods.agent.workflow.engine.domain.ErrorClass;
import java.util.List;
import java.util.Map;

/**
 * Wire shape for a process definition. The React Flow board emits this; the
 * REST API persists it to {@code process_def.xpdl_json} and converts it to a
 * {@link com.pods.agent.workflow.engine.domain.ProcessDefinition} at run
 * time.
 *
 * <p>Field names are camelCase; types match the engine domain closely so the
 * conversion in {@link com.pods.agent.workflow.api.ProcessDefinitionMapper}
 * is straightforward.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessDefDto(
        String id,
        String name,
        String version,
        String packageId,
        String description,
        List<VariableSpecDto> variables,
        List<ActivityDto> activities,
        List<TransitionDto> transitions
) {
    public record VariableSpecDto(
            String name,
            String javaClass,
            String defaultExpression,
            Boolean required
    ) {}

    public record ActivityDto(
            String id,
            String name,
            String type,
            String pluginName,
            Map<String, Object> properties,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            String deadlineExpression,
            Boolean isStart,
            Boolean isEnd,
            String subflowDefId,
            Map<String, String> subflowInputs,
            Map<String, String> subflowOutputs,
            List<VariableSpecDto> outputVariables,
            Boolean andJoin,
            ErrorPolicyDto errorPolicy
    ) {}

    public record TransitionDto(
            String id,
            String fromActivityId,
            String toActivityId,
            String condition,
            Boolean isErrorEdge,
            ErrorClass matchesErrorClass,
            String trigger,
            Integer priority,
            Boolean isDefault
    ) {}

    public record ErrorPolicyDto(
            Integer retryCount,
            Long backoffMs,
            Long timeoutMs,
            Boolean failFast,
            Boolean continueOnError
    ) {}
}
