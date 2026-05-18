package com.pods.agent.ordervalidation.model;

/**
 * Per-activity invocation row used by {@code GET /runs/{instId}/activities/{defId}}.
 * Backed by {@code agent.rule_activity_events}.
 */
public record ActivityInvocation(
        String activityInstId,
        int index,
        String state,
        Long startedAt,
        Long endedAt,
        Integer attempt,
        Object input,
        Object output,
        String errorMessage
) {}
