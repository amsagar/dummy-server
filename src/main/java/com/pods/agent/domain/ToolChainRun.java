package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainRun {
    private String id;
    private String toolChainId;
    private String toolChainVersionId;
    private int version;
    private String triggerSource;
    private String initiatedBy;
    private String status;
    private long startedAt;
    private Long endedAt;
    private Long durationMs;
    private String inputSnapshot;
    private String outputSnapshot;
    private String errorMessage;
}
