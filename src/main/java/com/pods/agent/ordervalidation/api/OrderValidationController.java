package com.pods.agent.ordervalidation.api;

import com.pods.agent.ordervalidation.model.ActivityInvocation;
import com.pods.agent.ordervalidation.model.CheckResults;
import com.pods.agent.ordervalidation.model.DashboardMetrics;
import com.pods.agent.ordervalidation.model.OrderQueue;
import com.pods.agent.ordervalidation.model.RunDetail;
import com.pods.agent.ordervalidation.model.WorkflowSummary;
import com.pods.agent.ordervalidation.service.OrderValidationAnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoints for the order-validation-ui analytics surface.
 * All paths under {@code /api/v1/order-validation/*}. See
 * {@code order-validation-ui/src/services/api.ts} for the consumer.
 */
@RestController
@RequestMapping("/api/v1/order-validation")
@Slf4j
public class OrderValidationController {

    private final OrderValidationAnalyticsService analytics;

    public OrderValidationController(OrderValidationAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/workflows")
    public List<WorkflowSummary> listWorkflows() {
        return analytics.listWorkflows();
    }

    @GetMapping("/dashboard")
    public DashboardMetrics dashboard(@RequestParam String workflowId,
                                     @RequestParam(required = false) Long fromTs,
                                     @RequestParam(required = false) Long toTs) {
        return analytics.dashboard(workflowId, fromTs, toTs);
    }

    @GetMapping("/orders")
    public OrderQueue.Response orderQueue(@RequestParam String workflowId,
                                          @RequestParam(required = false) Long fromTs,
                                          @RequestParam(required = false) Long toTs,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String search,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        return analytics.orderQueue(workflowId, fromTs, toTs, status, search, limit, offset);
    }

    @GetMapping("/leg-sequence")
    public CheckResults.LegSequenceSummary legSequence(@RequestParam String workflowId,
                                                       @RequestParam(required = false) Long fromTs,
                                                       @RequestParam(required = false) Long toTs,
                                                       @RequestParam(required = false) String search,
                                                       @RequestParam(defaultValue = "50") int limit,
                                                       @RequestParam(defaultValue = "0") int offset) {
        return analytics.legSequenceSummary(workflowId, fromTs, toTs, search, limit, offset);
    }

    @GetMapping("/serviceability")
    public CheckResults.ServiceabilitySummary serviceability(@RequestParam String workflowId,
                                                             @RequestParam(required = false) Long fromTs,
                                                             @RequestParam(required = false) Long toTs,
                                                             @RequestParam(required = false) String search,
                                                             @RequestParam(defaultValue = "50") int limit,
                                                             @RequestParam(defaultValue = "0") int offset) {
        return analytics.serviceabilitySummary(workflowId, fromTs, toTs, search, limit, offset);
    }

    @GetMapping("/container-availability")
    public CheckResults.ContainerAvailabilitySummary containerAvailability(@RequestParam String workflowId,
                                                                           @RequestParam(required = false) Long fromTs,
                                                                           @RequestParam(required = false) Long toTs,
                                                                           @RequestParam(required = false) String search,
                                                                           @RequestParam(defaultValue = "50") int limit,
                                                                           @RequestParam(defaultValue = "0") int offset) {
        return analytics.containerAvailabilitySummary(workflowId, fromTs, toTs, search, limit, offset);
    }

    @GetMapping("/runs/{instId}")
    public ResponseEntity<RunDetail> runDetail(@PathVariable String instId) {
        RunDetail d = analytics.runDetail(instId);
        return d == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(d);
    }

    @GetMapping("/runs/{instId}/activities/{defId}")
    public List<ActivityInvocation> runActivities(@PathVariable String instId,
                                                  @PathVariable String defId) {
        return analytics.runActivities(instId, defId);
    }

    /**
     * Returns the order payload captured by the first non-looped tool
     * call of this run (typically the {@code Get_OrderID} fetch),
     * shaped as {@code { orderId, payload, activityId }}. Used by the
     * OV-UI to surface the raw order JSON without forcing the user to
     * drill into individual activity events.
     */
    @GetMapping("/runs/{instId}/order-payload")
    public ResponseEntity<java.util.Map<String, Object>> orderPayload(@PathVariable String instId) {
        java.util.Map<String, Object> body = analytics.orderPayload(instId);
        return body == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(body);
    }
}
