package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainApproval {
    private String id;
    private String runId;
    private String stepId;
    private String nodeId;
    private String requestId;
    private String approvalGroup;
    private String prompt;
    private String status;
    private String decisionBy;
    private String decisionComment;
    private long createdAt;
    private Long decidedAt;
}
