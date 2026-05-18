package com.pods.agent.ordervalidation.model;

import java.util.List;

/**
 * Single-run detail view for RunDetailPage. Composes the validation
 * outcome across the three rule executions plus a unified timeline.
 */
public record RunDetail(
        String instId,
        String orderId,
        String journeyType,
        String state,
        String overallStatus,
        long startedAt,
        Long endedAt,
        Integer durationMs,
        String errorClass,
        String errorMessage,
        CheckResults.LegSequenceResult legSequence,
        List<CheckResults.ServiceabilityResult> serviceability,
        List<CheckResults.ContainerAvailabilityResult> containerAvailability,
        List<ActivityTimeline> timeline
) {
    public record ActivityTimeline(
            String activityDefId,
            String type,
            String state,
            Long startedAt,
            Long endedAt,
            Integer attempt,
            String errorMessage
    ) {}
}
