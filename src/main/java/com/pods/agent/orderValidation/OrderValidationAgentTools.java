package com.pods.agent.orderValidation;

import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.engine.WorkflowManager;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tool implementations exposed to the AI chat assistant when the user
 * picks an order-validation agent profile. Each method returns a JSON
 * string so the SSE {@code tool.result} event carries plain JSON the
 * front-end can render with {@code JsonTree}.
 *
 * <p>All four tools are stateless and idempotent except {@link
 * #startValidation(String)}, which triggers an async workflow run.
 */
@Slf4j
@Component
public class OrderValidationAgentTools {

    // Block the tool call for at most this long while waiting for the
    // freshly-started validation workflow to reach a closed state. The
    // validate-order pipeline typically completes in ~25-30s; we cap at
    // 90s so a stuck run doesn't pin the agent's SSE connection.
    private static final long START_VALIDATION_TIMEOUT_MS = 90_000L;
    private static final long START_VALIDATION_POLL_MS = 1_500L;

    private final OrderValidationAnalyticsService analytics;
    private final OrderValidationSettingsRepository settingsRepo;
    private final ProcessDefService processDefService;
    private final WorkflowManager workflowManager;
    private final ProcessInstRepository processInstRepo;
    private final ObjectMapper objectMapper;

    public OrderValidationAgentTools(OrderValidationAnalyticsService analytics,
                                     OrderValidationSettingsRepository settingsRepo,
                                     @Lazy ProcessDefService processDefService,
                                     @Lazy WorkflowManager workflowManager,
                                     ProcessInstRepository processInstRepo,
                                     ObjectMapper objectMapper) {
        this.analytics = analytics;
        this.settingsRepo = settingsRepo;
        this.processDefService = processDefService;
        this.workflowManager = workflowManager;
        this.processInstRepo = processInstRepo;
        this.objectMapper = objectMapper;
    }

    public String listRunsForOrder(String orderId) {
        String workflowId = activeWorkflowId();
        if (workflowId == null) return jsonError("no_workflow_configured",
                "Set the order-validation workflow id under Settings → Validation Workflow.");
        if (orderId == null || orderId.isBlank()) return jsonError("missing_orderId", "orderId is required");

        try {
            OrderValidationDtos.OrderQueueResponse resp = analytics.orderQueue(
                    workflowId, null, null, null, orderId, 25, 0);
            List<Map<String, Object>> runs = resp.rows().stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("instId", r.instId());
                        m.put("orderId", r.orderId());
                        m.put("startedAt", r.startedAt());
                        m.put("overallStatus", r.overallStatus());
                        m.put("legSequenceStatus", r.legSequenceStatus());
                        m.put("serviceabilityStatus", r.serviceabilityStatus());
                        m.put("containerStatus", r.containerStatus());
                        return m;
                    })
                    .toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", orderId);
            out.put("count", runs.size());
            out.put("runs", runs);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("ovListRunsForOrder failed: {}", e.getMessage());
            return jsonError("error", e.getMessage());
        }
    }

    public String getRunDetail(String instId) {
        if (instId == null || instId.isBlank()) return jsonError("missing_instId", "instId is required");
        try {
            Optional<OrderValidationDtos.RunDetail> detail = analytics.runDetail(instId);
            if (detail.isEmpty()) return jsonError("not_found", "No run found with instId " + instId);
            return objectMapper.writeValueAsString(detail.get());
        } catch (Exception e) {
            log.warn("ovGetRunDetail failed: {}", e.getMessage());
            return jsonError("error", e.getMessage());
        }
    }

    public String startValidation(String orderId) {
        if (orderId == null || orderId.isBlank()) return jsonError("missing_orderId", "orderId is required");
        String workflowId = activeWorkflowId();
        if (workflowId == null) return jsonError("no_workflow_configured",
                "Set the order-validation workflow id under Settings → Validation Workflow.");

        try {
            var defOpt = processDefService.loadDomainById(workflowId);
            if (defOpt.isEmpty()) {
                return jsonError("workflow_not_found",
                        "Configured workflow id " + workflowId + " was not found in the engine.");
            }
            long startedAt = Instant.now().toEpochMilli();
            String instanceId = workflowManager.startProcessAsync(
                    defOpt.get(), Map.of("orderId", orderId), "order-validation-agent");

            // Block the tool call until the workflow reaches a closed state
            // (or until the timeout fires). This means the agent gets the
            // full run detail back in the same turn and can produce a
            // summary without the user having to come back. The chat SSE
            // stream stays open while we poll.
            ProcessInstRow finalRow = waitForCompletion(instanceId);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instId", instanceId);
            out.put("orderId", orderId);
            out.put("workflowId", workflowId);
            out.put("startedAt", startedAt);

            if (finalRow == null) {
                // Timed out without closing — give the agent enough info to
                // tell the user it's still running and they should re-ask
                // for a summary later.
                out.put("state", "running");
                out.put("timedOut", true);
                out.put("message",
                        "Workflow has not finished within " + (START_VALIDATION_TIMEOUT_MS / 1000) +
                        "s. Tell the user the validation is still running and they can ask to summarize order " +
                        orderId + " in a moment.");
                return objectMapper.writeValueAsString(out);
            }

            out.put("state", finalRow.state());
            out.put("endedAt", finalRow.endedAt());
            out.put("durationMs",
                    finalRow.endedAt() != null && finalRow.startedAt() != null
                            ? Math.max(0L, finalRow.endedAt() - finalRow.startedAt())
                            : null);

            // Fold the full per-check breakdown into the tool result so the
            // agent has everything it needs to produce the summary right
            // here, no follow-up tool call required.
            Optional<OrderValidationDtos.RunDetail> detail = analytics.runDetail(instanceId);
            if (detail.isPresent()) {
                out.put("runDetail", detail.get());
                out.put("message",
                        "Workflow completed. Use runDetail to produce the summary now — DO NOT call ovGetRunDetail again.");
            } else {
                out.put("message",
                        "Workflow finished but no detail row found. Tell the user something went wrong.");
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("ovStartValidation failed: {}", e.getMessage());
            return jsonError("error", e.getMessage());
        }
    }

    /**
     * Polls {@code process_inst} for the given instance id every
     * {@link #START_VALIDATION_POLL_MS} ms, returning the row once {@code
     * state} is in a closed phase (anything starting with "closed.") or
     * {@code null} if {@link #START_VALIDATION_TIMEOUT_MS} elapses first.
     */
    private ProcessInstRow waitForCompletion(String instanceId) {
        long deadline = System.currentTimeMillis() + START_VALIDATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Optional<ProcessInstRow> rowOpt = processInstRepo.findById(instanceId);
            if (rowOpt.isPresent()) {
                ProcessInstRow row = rowOpt.get();
                String state = row.state();
                if (state != null && state.startsWith("closed.")) {
                    return row;
                }
            }
            try {
                Thread.sleep(START_VALIDATION_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public String dashboardStats(Long fromTs, Long toTs) {
        String workflowId = activeWorkflowId();
        if (workflowId == null) return jsonError("no_workflow_configured",
                "Set the order-validation workflow id under Settings → Validation Workflow.");
        try {
            return objectMapper.writeValueAsString(analytics.dashboard(workflowId, fromTs, toTs));
        } catch (Exception e) {
            log.warn("ovDashboardStats failed: {}", e.getMessage());
            return jsonError("error", e.getMessage());
        }
    }

    private String activeWorkflowId() {
        return settingsRepo.find().map(OrderValidationSettingsRepository.Settings::workflowId).orElse(null);
    }

    private String jsonError(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", true, "code", code, "message", message));
        } catch (Exception ignored) {
            return "{\"error\":true,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        }
    }
}
