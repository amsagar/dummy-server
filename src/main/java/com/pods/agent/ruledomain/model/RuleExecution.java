package com.pods.agent.ruledomain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One execution of a compiled rule domain. Every BPMN run — whether the domain
 * was a cache hit or just-compiled — produces exactly one row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleExecution {
    private String id;
    private String domainId;
    private String sessionId;
    private String turnId;
    private String flowableProcId;
    private String inputsJson;
    private String outputsJson;
    private boolean success;
    private boolean fallbackTriggered;
    private String errorMessage;
    private Integer latencyMs;
    private long createdAt;
}
