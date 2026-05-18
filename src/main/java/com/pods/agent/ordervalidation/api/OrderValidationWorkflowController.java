package com.pods.agent.ordervalidation.api;

import com.pods.agent.ordervalidation.model.RunSummary;
import com.pods.agent.ordervalidation.service.OrderValidationRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Submits a new order-validation run. Matches the contract used by
 * order-validation-ui/src/services/api.ts:workflowRunsApi.start —
 * {@code processDefId} is the workflow id (a skill id), and
 * {@code initialVariables.orderId} drives the validation.
 */
@RestController
@RequestMapping("/api/v1/workflow")
@Slf4j
public class OrderValidationWorkflowController {

    private final OrderValidationRunService runService;

    public OrderValidationWorkflowController(OrderValidationRunService runService) {
        this.runService = runService;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunSummary> start(@RequestBody StartRequest body,
                                            @RequestParam(defaultValue = "true") boolean async) {
        if (body == null) return ResponseEntity.badRequest().build();
        String workflowId = body.processDefId();
        String orderId = body.initialVariables() == null
                ? null
                : String.valueOf(body.initialVariables().get("orderId"));
        try {
            RunSummary summary = runService.start(workflowId, orderId, body.requesterId(), async);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(new RunSummary(
                    null, workflowId, "FAILED", System.currentTimeMillis(), System.currentTimeMillis(),
                    body.requesterId(), "InvalidInput", ex.getMessage(), null));
        }
    }

    public record StartRequest(String processDefId,
                               Map<String, Object> initialVariables,
                               String requesterId) {}
}
