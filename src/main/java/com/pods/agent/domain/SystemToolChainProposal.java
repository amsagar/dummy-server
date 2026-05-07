package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemToolChainProposal {
    private String id;
    private String sessionId;
    private String turnId;
    private String userId;
    private String status;
    private String reason;
    private String confidence;
    private String tracePath;
    private String durableTraceJson;
    private String userPrompt;
    private String assistantResponse;
    private String modelProviderId;
    private String modelId;
    private String decisionComment;
    private String decidedBy;
    private Long decidedAt;
    private String toolChainId;
    private String errorMessage;
    private long createdAt;
    private long updatedAt;
}
