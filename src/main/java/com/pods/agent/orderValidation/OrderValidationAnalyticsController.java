package com.pods.agent.orderValidation;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only analytics over completed runs of the order-validation workflow.
 *
 * <p>Each workflow run stores its Step 7 output as
 * {@code process_inst.result_json} — a JSON object of shape
 * {@code {orderId, legSequence, serviceability[], containerAvailability[]}}
 * (see {@code default-skills/pods-order-validation/SKILL.md}). The analytics
 * service parses that JSON, aggregates across the selected date range, and
 * surfaces dashboard / per-check / per-row views.
 *
 * <p>All endpoints are GET-only and exposed under {@code permitAll()} in
 * {@code SecurityConfig} — they perform no mutations and reveal only data
 * the operator could already see by querying {@code process_inst} directly.
 */
@RestController
@RequestMapping("/api/v1/order-validation")
public class OrderValidationAnalyticsController {

    private final OrderValidationAnalyticsService service;
    private final OrderValidationSettingsRepository settingsRepo;

    public OrderValidationAnalyticsController(OrderValidationAnalyticsService service,
                                              OrderValidationSettingsRepository settingsRepo) {
        this.service = service;
        this.settingsRepo = settingsRepo;
    }

    @GetMapping("/settings")
    public ResponseEntity<OrderValidationDtos.UiSettings> getSettings() {
        return ResponseEntity.ok(settingsRepo.find()
                .map(s -> new OrderValidationDtos.UiSettings(s.chatModelRef(), s.responseMode(), s.workflowId()))
                .orElseGet(() -> new OrderValidationDtos.UiSettings(null, "basic", null)));
    }

    @PutMapping("/settings")
    public ResponseEntity<OrderValidationDtos.UiSettings> updateSettings(
            @RequestBody OrderValidationDtos.UiSettings body) {
        var saved = settingsRepo.upsert(body.chatModelRef(), body.responseMode(), body.workflowId());
        return ResponseEntity.ok(new OrderValidationDtos.UiSettings(
                saved.chatModelRef(), saved.responseMode(), saved.workflowId()));
    }

    @GetMapping("/workflows")
    public ResponseEntity<List<OrderValidationDtos.WorkflowSummary>> listWorkflows() {
        return ResponseEntity.ok(service.listWorkflows());
    }

    @GetMapping("/dashboard")
    public ResponseEntity<OrderValidationDtos.DashboardMetrics> dashboard(
            @RequestParam("workflowId") String workflowId,
            @RequestParam(value = "fromTs", required = false) Long fromTs,
            @RequestParam(value = "toTs", required = false) Long toTs) {
        return ResponseEntity.ok(service.dashboard(workflowId, fromTs, toTs));
    }

    @GetMapping("/orders")
    public ResponseEntity<OrderValidationDtos.OrderQueueResponse> orderQueue(
            @RequestParam("workflowId") String workflowId,
            @RequestParam(value = "fromTs", required = false) Long fromTs,
            @RequestParam(value = "toTs", required = false) Long toTs,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.orderQueue(workflowId, fromTs, toTs, status, search, limit, offset));
    }

    @GetMapping("/leg-sequence")
    public ResponseEntity<OrderValidationDtos.LegSequenceSummary> legSequence(
            @RequestParam("workflowId") String workflowId,
            @RequestParam(value = "fromTs", required = false) Long fromTs,
            @RequestParam(value = "toTs", required = false) Long toTs,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", defaultValue = "25") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.legSequence(workflowId, fromTs, toTs, search, limit, offset));
    }

    @GetMapping("/serviceability")
    public ResponseEntity<OrderValidationDtos.ServiceabilitySummary> serviceability(
            @RequestParam("workflowId") String workflowId,
            @RequestParam(value = "fromTs", required = false) Long fromTs,
            @RequestParam(value = "toTs", required = false) Long toTs,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", defaultValue = "25") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.serviceability(workflowId, fromTs, toTs, search, limit, offset));
    }

    @GetMapping("/container-availability")
    public ResponseEntity<OrderValidationDtos.ContainerAvailabilitySummary> containerAvailability(
            @RequestParam("workflowId") String workflowId,
            @RequestParam(value = "fromTs", required = false) Long fromTs,
            @RequestParam(value = "toTs", required = false) Long toTs,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", defaultValue = "25") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.containerAvailability(workflowId, fromTs, toTs, search, limit, offset));
    }

    @GetMapping("/runs/{instId}")
    public ResponseEntity<OrderValidationDtos.RunDetail> runDetail(@PathVariable("instId") String instId) {
        return service.runDetail(instId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/runs/{instId}/activities/{defId}")
    public ResponseEntity<List<OrderValidationDtos.ActivityInvocation>> activityIo(
            @PathVariable("instId") String instId,
            @PathVariable("defId") String defId) {
        return ResponseEntity.ok(service.activityIo(instId, defId));
    }
}
