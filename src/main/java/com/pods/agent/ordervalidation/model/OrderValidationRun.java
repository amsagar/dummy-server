package com.pods.agent.ordervalidation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * In-memory DTO that groups the per-rule {@link com.pods.agent.ruledomain.model.RuleExecution}
 * rows produced by a single skill fan-out (one orderId → N validation
 * rules). NOT persisted — the OV-UI derives one of these per query from
 * {@code agent.rule_executions} grouped by {@code (session_id, turn_id)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderValidationRun {
    private String id;
    private String workflowId;
    private String orderId;
    private String journeyType;
    private Integer legLines;
    private String state;
    private String overallStatus;
    private String legSequenceStatus;
    private String serviceabilityStatus;
    private String containerStatus;
    private String requesterId;
    private String errorClass;
    private String errorMessage;
    private String resultJson;
    private long startedAt;
    private Long endedAt;
    private Integer durationMs;
    private long createdAt;
    /**
     * How the run was triggered:
     * <ul>
     *   <li>{@code AGENT} — POST /api/v1/workflow/runs (the OV-UI's "Submit order" / chat)</li>
     *   <li>{@code QUICK_TEST} — synthesized from a Quick Test session_id in the main UI</li>
     *   <li>{@code INGESTED} — synthesized from any other orphan rule_execution</li>
     * </ul>
     * Null on legacy rows; UI tolerates null and falls back to "—".
     */
    private String source;
}
