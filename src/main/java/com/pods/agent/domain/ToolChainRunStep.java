package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainRunStep {
    private String id;
    private String runId;
    private String nodeId;
    private String nodeType;
    private String toolRef;
    private String branchPath;
    private String status;
    private int retryCount;
    private String inputPayload;
    private String outputPayload;
    private String errorMessage;
    private long startedAt;
    private Long endedAt;
}
