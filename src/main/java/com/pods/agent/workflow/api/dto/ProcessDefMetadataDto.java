package com.pods.agent.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Read-only telemetry surface for a single {@code process_def}. Returned by
 * {@code /api/v1/workflow/processes/{id}/metadata} and as the row shape inside
 * {@code /api/v1/workflow/processes/metadata}. Intentionally separate from the
 * editor-side {@link ProcessDefDto} so that adding new metrics never breaks
 * the React Flow board's POST/PUT contract.
 *
 * <p>Two complementary views are exposed:
 * <ul>
 *   <li>{@code allTime} — counters never reset; cheap to read; used for trust /
 *       reuse decisions.</li>
 *   <li>{@code recentWindow} — last {@code N} runs only (default N = 50);
 *       useful for "is the workflow currently healthy?".</li>
 * </ul>
 *
 * <p>{@code aiNodes} is the parsed view of {@code ai_nodes_json} — a list of
 * {@code {activityDefId, count}} entries for every {@code ai_reasoning}
 * activity that has actually fired (skipped via {@code invokeWhen} are
 * deliberately excluded).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessDefMetadataDto(
        String defId,
        AllTimeMetrics allTime,
        RecentWindow recentWindow,
        List<Map<String, Object>> aiNodes,
        boolean hasEmbedding
) {
    public record AllTimeMetrics(
            int totalRuns,
            int totalSuccesses,
            long totalLatencyMs,
            Float successRate,
            Long avgLatencyMs,
            Long lastRunAt
    ) {}

    public record RecentWindow(
            long runs,
            long successes,
            Double successRate,
            long avgLatencyMs,
            Long lastRunAt
    ) {}
}
