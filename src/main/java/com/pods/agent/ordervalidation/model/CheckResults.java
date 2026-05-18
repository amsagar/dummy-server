package com.pods.agent.ordervalidation.model;

import java.util.List;

/**
 * Per-check summary + result rows for the three validation pages
 * (LegSequence, Serviceability, ContainerAvailability). Mirrors the UI
 * types.
 */
public final class CheckResults {
    private CheckResults() {}

    public record LegSequenceSummary(
            long totalChecks,
            double passRate,
            long failed,
            String mostCommonFailure,
            List<JourneyCount> failuresByJourney,
            List<LegSequenceResult> recent,
            long recentTotal
    ) {}

    public record JourneyCount(String journeyType, long count) {}

    public record LegSequenceResult(
            String instId,
            String orderId,
            String journeyType,
            List<String> actualSequence,
            String matchedRule,
            Boolean valid,
            String message
    ) {}

    public record ServiceabilitySummary(
            long totalChecks,
            double serviceableRate,
            long exceptions,
            long skipped,
            List<ExceptionCount> exceptionBreakdown,
            List<ServiceabilityResult> recent,
            long recentTotal
    ) {}

    public record ExceptionCount(String exceptionType, long count) {}

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
            List<ReasonCount> skipReasons,
            List<ContainerAvailabilityResult> recent,
            long recentTotal
    ) {}

    public record ReasonCount(String reason, long count) {}

    public record ContainerAvailabilityResult(
            String instId,
            String orderId,
            String lineId,
            String itemCode,
            boolean checked,
            List<String> availableDates,
            String skipReason
    ) {}
}
