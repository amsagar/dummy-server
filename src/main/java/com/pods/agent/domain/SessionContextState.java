package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContextState {
    private String sessionId;
    private String runtimeMode;
    private String modelSelectionMode;
    private String modelRef;
    private String stateJson;
    private String rollingSummary;
    private long summaryTokens;
    private long updatedAt;
}
