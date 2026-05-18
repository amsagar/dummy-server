package com.pods.agent.ordervalidation.model;

import java.util.List;

/**
 * Paginated table data for the OrderQueuePage. Mirrors UI types
 * OrderQueueRow + OrderQueueResponse.
 */
public final class OrderQueue {
    private OrderQueue() {}

    public record Row(
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
            String errorMessage,
            /** How the run was triggered: AGENT / QUICK_TEST / INGESTED. Nullable for legacy rows. */
            String source
    ) {}

    public record Response(long total, long passed, long review, long failed, List<Row> rows) {}
}
