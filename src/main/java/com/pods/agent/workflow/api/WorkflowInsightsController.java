package com.pods.agent.workflow.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflow/insights")
public class WorkflowInsightsController {

    private final WorkflowInsightsService service;

    public WorkflowInsightsController(WorkflowInsightsService service) {
        this.service = service;
    }

    /**
     * @param period one of {@code 24h, 7d, 14d, 30d, 90d, 6mo, 1y}.
     * @param limit  max rows in {@code byWorkflow} and {@code hotspots} (default 20, clamped 1–200).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam(name = "period", defaultValue = "7d") String period,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return ResponseEntity.ok(service.insights(period, safeLimit));
    }
}
