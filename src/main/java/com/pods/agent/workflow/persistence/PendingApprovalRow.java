package com.pods.agent.workflow.persistence;

/**
 * Row in {@code agent.pending_approval}. The list endpoint joins to
 * {@code process_def} for {@code workflowName}, which is null on lookups by id.
 */
public record PendingApprovalRow(
        String id,
        String instId,
        String activityInstId,
        String activityDefId,
        String requestedBy,
        long requestedAt,
        String reason,
        String decidedBy,
        Long decidedAt,
        String decision,
        String comment,
        // join columns (nullable on direct lookups)
        String defId,
        String workflowName
) {}
