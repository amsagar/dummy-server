package com.pods.agent.ruledomain.api;

import com.pods.agent.ruledomain.model.RuleActivityEvent;
import com.pods.agent.ruledomain.repository.RuleActivityEventRepository;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository.ExecutionFilters;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-rule execution browser + analytics. Sits at its own base path so
 * the {id} path variable on {@link RuleDomainController} doesn't shadow
 * the static "executions" / "analytics" segments.
 *
 * Endpoints:
 *   GET /api/v1/rule-executions?skillId=&domainId=&ruleName=&status=&since=&until=&page=&pageSize=
 *   GET /api/v1/rule-executions/{execId}/activity-events
 *   GET /api/v1/rule-executions/analytics/summary?days=30
 *   GET /api/v1/rule-executions/analytics/timeseries?days=30
 *   GET /api/v1/rule-executions/analytics/top-errors?days=7&limit=10
 *   GET /api/v1/rule-executions/analytics/slow-rules?days=7&limit=10
 *   GET /api/v1/rule-executions/analytics/per-skill?days=30
 */
@RestController
@RequestMapping("/api/v1/rule-executions")
@Slf4j
public class RuleExecutionsController {

    private final RuleExecutionRepository executionRepo;
    private final RuleActivityEventRepository activityEventRepo;

    public RuleExecutionsController(RuleExecutionRepository executionRepo,
                                    RuleActivityEventRepository activityEventRepo) {
        this.executionRepo = executionRepo;
        this.activityEventRepo = activityEventRepo;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String skillId,
                                    @RequestParam(required = false) String domainId,
                                    @RequestParam(required = false) String ruleName,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long since,
                                    @RequestParam(required = false) Long until,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int pageSize) {
        Boolean success = null;
        if ("success".equalsIgnoreCase(status)) success = Boolean.TRUE;
        else if ("failed".equalsIgnoreCase(status) || "failure".equalsIgnoreCase(status)) success = Boolean.FALSE;

        PageResult result = executionRepo.findPage(new ExecutionFilters(
                emptyToNull(skillId),
                emptyToNull(domainId),
                emptyToNull(ruleName),
                success,
                since,
                until,
                page,
                pageSize));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items());
        body.put("total", result.total());
        body.put("page", result.page());
        body.put("pageSize", result.pageSize());
        return body;
    }

    @GetMapping("/{execId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String execId) {
        return executionRepo.findEnrichedById(execId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{execId}/activity-events")
    public ResponseEntity<List<RuleActivityEvent>> activityEvents(@PathVariable String execId) {
        return ResponseEntity.ok(activityEventRepo.listForExecution(execId));
    }

    // ── Analytics ──────────────────────────────────────────────────────

    @GetMapping("/analytics/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        return executionRepo.analyticsSummary(sinceFor(days));
    }

    @GetMapping("/analytics/timeseries")
    public List<Map<String, Object>> timeseries(@RequestParam(defaultValue = "30") int days) {
        return executionRepo.analyticsTimeseries(sinceFor(days));
    }

    @GetMapping("/analytics/top-errors")
    public List<Map<String, Object>> topErrors(@RequestParam(defaultValue = "7") int days,
                                               @RequestParam(defaultValue = "10") int limit) {
        return executionRepo.analyticsTopErrors(sinceFor(days), Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/analytics/slow-rules")
    public List<Map<String, Object>> slowRules(@RequestParam(defaultValue = "7") int days,
                                               @RequestParam(defaultValue = "10") int limit) {
        return executionRepo.analyticsSlowRules(sinceFor(days), Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/analytics/per-skill")
    public List<Map<String, Object>> perSkill(@RequestParam(defaultValue = "30") int days) {
        return executionRepo.analyticsPerSkill(sinceFor(days));
    }

    private static long sinceFor(int days) {
        int clamped = Math.min(Math.max(days, 1), 365);
        return System.currentTimeMillis() - (long) clamped * 24 * 3600 * 1000;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
