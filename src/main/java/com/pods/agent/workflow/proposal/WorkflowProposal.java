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
    /**
     * Nullable. Phase-1 lightweight proposals (created by the classifier) carry
     * only the suggested name + session metadata; the workflow JSON is
     * produced by the Phase-2 builder agent on approval and persisted to the
     * VFS draft file before this column is finally populated by the builder
     * loop on success.
     */
    private String proposedWorkflowJson;
    private String matchedToolNamesJson;
    /** Phase-1 classifier output: short, human-friendly workflow name. */
    private String suggestedName;
    /**
     * JSON array of skill names the chat AI loaded during the source turn
     * (sourced from runtime_events tool.call entries with toolName="skill").
     * Phase-2 builder uses this as its skill allowlist.
     */
    private String skillNamesJson;
    /** Counter incremented on every Phase-2 build attempt (initial + retries). */
    private int buildAttempts;
    private String decisionComment;
    private String decidedBy;
    private Long decidedAt;
    private String materializedDefId;
    private String errorMessage;
    private long createdAt;
    private long updatedAt;
}
