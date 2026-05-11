package com.pods.agent.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartRunRequest(
        String processDefId,
        Map<String, Object> initialVariables,
        String requesterId
) {}
