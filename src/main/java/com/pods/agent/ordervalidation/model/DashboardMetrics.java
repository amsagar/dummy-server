package com.pods.agent.ordervalidation.model;

import java.util.List;

/**
 * Aggregate metrics for the dashboard page, derived live from
 * {@code agent.rule_executions} grouped by (session_id, turn_id).
 * Mirrors UI:DashboardMetrics.
 */
public record DashboardMetrics(
        long ordersValidated,
        double passRate,
        long failedValidations,
        Long avgValidationMs,
        PassFailByCheck passFailByCheck,
        List<VolumeBucket> volumeBuckets,
        List<RecentResult> recentResults
) {
    public record PassFailByCheck(
            long legSequencePass,
            long legSequenceFail,
            long serviceabilityPass,
            long serviceabilityFail,
            long containerPass,
            long containerFail,
            long containerSkipped
    ) {}

    public record VolumeBucket(long dayStartTs, long total, long failures) {}

    public record RecentResult(
            String instId,
            String orderId,
            String journeyType,
            String legSequenceStatus,
            String serviceabilityStatus,
            String containerStatus,
            String overallStatus,
            long startedAt,
            /** AGENT / QUICK_TEST / INGESTED — nullable on legacy rows. */
            String source
    ) {}
}
