package com.pods.agent.workflow.persistence;

/**
 * Row in {@code agent.workflow_api_key}.
 *
 * <p>{@code keyHash} is sha-256 of the plaintext key — the plaintext is shown
 * to the user exactly once at creation and never persisted. {@code keyPrefix}
 * is the leading 12 chars of the plaintext, indexed for O(1) lookup at auth
 * time. {@code processDefIds} is a JSON array of process definition ids
 * (the scope: this key may only start runs of workflows on this list).
 */
public record WorkflowApiKeyRow(
        String id,
        String name,
        String keyPrefix,
        String keyHash,
        String ownerId,
        String processDefIds,
        long createdAt,
        Long lastUsedAt,
        Long revokedAt
) {}
