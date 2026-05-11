package com.pods.agent.workflow.proposal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowProposal {
    private String id;
    private String sessionId;
    private String turnId;
    private String userId;
    private String status;
    private String reason;
    private Double confidence;
    private String intentSignature;
    private String traceRef;
    private String userPrompt;
    private String modelProviderId;
    private String modelId;
    private String proposedWorkflowJson;
    private String matchedToolNamesJson;
    private String decisionComment;
    private String decidedBy;
    private Long decidedAt;
    private String materializedDefId;
    private String errorMessage;
    private long createdAt;
    private long updatedAt;
}
