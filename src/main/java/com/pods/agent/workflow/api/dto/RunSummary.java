package com.pods.agent.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunSummary(
        String instanceId,
        String defId,
        String state,
        Long startedAt,
        Long endedAt,
        String requesterId,
        String errorClass,
        String errorMessage,
        // JSON-parsed value of the closing activity's `properties.result`
        // expression. Null (and omitted from the JSON response thanks to
        // NON_NULL) when the workflow doesn't declare an end-result
        // expression, the eval failed, or the run hasn't completed yet.
        Object result
) {}
