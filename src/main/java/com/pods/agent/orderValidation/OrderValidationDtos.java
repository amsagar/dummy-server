package com.pods.agent.orderValidation;

import java.util.List;

/**
 * Wire shapes for the read-only order-validation analytics endpoints. Names
 * mirror the TypeScript types in
 * {@code order-validation-ui/src/types/orderValidation.ts} — when changing
 * one, change both.
 */
public final class OrderValidationDtos {

    private OrderValidationDtos() {}

    public record WorkflowSummary(String id, String name, String version, String description) {}

    public record DashboardMetrics(
            long ordersValidated,
            double passRate,
            long failedValidations,
            Long avgValidationMs,
            PassFailByCheck passFailByCheck,
            List<VolumeBucket> volumeBuckets,
            List<RecentResult> recentResults
    ) {}

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
            long startedAt
    ) {}

    public record OrderQueueResponse(
            long total,
            long passed,
            long review,
            long failed,
            List<OrderQueueRow> rows
    ) {}

    public record OrderQueueRow(
            String instId,
            String orderId,
            String journeyType,
            Integer legLines,
            String legSequenceStatus,
            String serviceabilityStatus,
            String containerStatus,
            String overallStatus,
            long startedAt,
            Long endedAt,
            String state,
            String errorMessage
    ) {}

    public record LegSequenceSummary(
            long totalChecks,
            double passRate,
            long failed,
            String mostCommonFailure,
            List<FailuresByJourney> failuresByJourney,
            List<LegSequenceResult> recent,
            long recentTotal
    ) {}

    public record FailuresByJourney(String journeyType, long count) {}

    public record LegSequenceResult(
            String instId,
            String orderId,
            String journeyType,
            List<String> actualSequence,
            String matchedRule,
            boolean valid,
            String message
    ) {}

    public record ServiceabilitySummary(
            long totalChecks,
            double serviceableRate,
            long exceptions,
            long skipped,
            List<ExceptionBreakdown> exceptionBreakdown,
            List<ServiceabilityResult> recent,
            long recentTotal
    ) {}

    public record ExceptionBreakdown(String exceptionType, long count) {}

    public record ServiceabilityResult(
            String instId,
            String orderId,
            String lineId,
            String itemCode,
            String originZip,
            String destinationZip,
            Boolean isServiceable,
            String exceptionType,
            String status
    ) {}

    public record ContainerAvailabilitySummary(
            long idelLinesChecked,
            double datesAvailableRate,
            long skipped,
            long noAvailability,
            List<SkipReason> skipReasons,
            List<ContainerAvailabilityResult> recent,
            long recentTotal
    ) {}

    public record SkipReason(String reason, long count) {}

    public record ContainerAvailabilityResult(
            String instId,
            String orderId,
            String lineId,
            String itemCode,
            boolean checked,
            List<String> availableDates,
            String skipReason
    ) {}

    public record RunDetail(
            String instId,
            String orderId,
            String journeyType,
            String state,
            String overallStatus,
            long startedAt,
            Long endedAt,
            Long durationMs,
            String errorClass,
            String errorMessage,
            LegSequenceResult legSequence,
            List<ServiceabilityResult> serviceability,
            List<ContainerAvailabilityResult> containerAvailability,
            List<ActivityTimeline> timeline
    ) {}

    public record ActivityTimeline(
            String activityDefId,
            String type,
            String state,
            Long startedAt,
            Long endedAt,
            Integer attempt,
            String errorMessage
    ) {}

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

    public record UiSettings(
            String chatModelRef,
            String responseMode,
            String workflowId
    ) {}
}
