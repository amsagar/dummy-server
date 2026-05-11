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
        String errorMessage
) {}
