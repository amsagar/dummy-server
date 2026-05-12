package com.pods.agent.orderValidation;

import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.engine.WorkflowManager;
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

    private final OrderValidationAnalyticsService analytics;
    private final OrderValidationSettingsRepository settingsRepo;
    private final ProcessDefService processDefService;
    private final WorkflowManager workflowManager;
    private final ObjectMapper objectMapper;

    public OrderValidationAgentTools(OrderValidationAnalyticsService analytics,
                                     OrderValidationSettingsRepository settingsRepo,
                                     @Lazy ProcessDefService processDefService,
                                     @Lazy WorkflowManager workflowManager,
                                     ObjectMapper objectMapper) {
        this.analytics = analytics;
        this.settingsRepo = settingsRepo;
        this.processDefService = processDefService;
        this.workflowManager = workflowManager;
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
            String instanceId = workflowManager.startProcessAsync(
                    defOpt.get(), Map.of("orderId", orderId), "order-validation-agent");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instId", instanceId);
            out.put("orderId", orderId);
            out.put("workflowId", workflowId);
            out.put("state", "running");
            out.put("startedAt", Instant.now().toEpochMilli());
            out.put("message", "Validation started; poll ovGetRunDetail with the returned instId for status.");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("ovStartValidation failed: {}", e.getMessage());
            return jsonError("error", e.getMessage());
        }
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
