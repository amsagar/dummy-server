package com.pods.agent.ruledomain.api;

import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.RuleExecution;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.repository.RuleDomainRepository.PageResult;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST for compiled rule domains.
 *
 * Endpoints:
 *   GET    /api/v1/rule-domains
 *          ?search=&skillId=&onlyLatest=true&page=0&pageSize=20
 *          Paginated list; onlyLatest collapses to one row per
 *          (skill, intent) keyed by max(version).
 *   GET    /api/v1/rule-domains/{id}             detail + BPMN XML
 *   GET    /api/v1/rule-domains/{id}/versions    all revisions for the same (skill, intent)
 *   GET    /api/v1/rule-domains/{id}/executions  last N runs
 *   GET    /api/v1/rule-domains/{id}/executions/{execId}/trace
 *          per-activity execution trace from Flowable history,
 *          used to overlay the BPMN diagram with success/failure colors.
 *   POST   /api/v1/rule-domains/{id}/deprecate
 *   POST   /api/v1/rule-domains/{id}/activate
 *          Marks ACTIVE *and* deprecates any other versions of the same
 *          (skill, intent) that were previously active.
 */
@RestController
@RequestMapping("/api/v1/rule-domains")
@Slf4j
public class RuleDomainController {

    private final RuleDomainRepository domainRepo;
    private final RuleExecutionRepository executionRepo;
    private final HistoryService historyService;

    public RuleDomainController(RuleDomainRepository domainRepo,
                                RuleExecutionRepository executionRepo,
                                HistoryService historyService) {
        this.domainRepo = domainRepo;
        this.executionRepo = executionRepo;
        this.historyService = historyService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String search,
                                    @RequestParam(required = false) String skillId,
                                    @RequestParam(defaultValue = "true") boolean onlyLatest,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        PageResult<RuleDomain> result = domainRepo.list(search, skillId, onlyLatest, page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(this::summary).toList());
        body.put("total", result.total());
        body.put("page", result.page());
        body.put("pageSize", result.pageSize());
        return body;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return domainRepo.findById(id)
                .map(d -> {
                    Map<String, Object> body = new LinkedHashMap<>(summary(d));
                    body.put("bpmnXml", d.getBpmnXml());
                    body.put("lastError", d.getLastError());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id) {
        return domainRepo.findVersionsOf(id).stream().map(this::summary).toList();
    }

    @GetMapping("/{id}/executions")
    public List<RuleExecution> executions(@PathVariable String id,
                                          @RequestParam(defaultValue = "50") int limit) {
        return executionRepo.listForDomain(id, Math.min(Math.max(limit, 1), 200));
    }

    /**
     * Per-activity execution trace. Reads from Flowable's history tables for the
     * process instance stored on the rule_executions row.
     * Front-end uses this to color the BPMN diagram (which step ran, where it errored).
     */
    @GetMapping("/{id}/executions/{execId}/trace")
    public ResponseEntity<List<Map<String, Object>>> trace(@PathVariable String id,
                                                           @PathVariable String execId) {
        RuleExecution exec = executionRepo.listForDomain(id, 200).stream()
                .filter(e -> e.getId().equals(execId))
                .findFirst()
                .orElse(null);
        if (exec == null) return ResponseEntity.notFound().build();
        String procInstanceId = exec.getFlowableProcId();
        if (procInstanceId == null || procInstanceId.isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        List<HistoricActivityInstance> activities;
        try {
            activities = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(procInstanceId)
                    .orderByHistoricActivityInstanceStartTime()
                    .asc()
                    .list();
        } catch (Exception ex) {
            log.warn("Failed to read Flowable history for procInstanceId={}: {}", procInstanceId, ex.getMessage());
            return ResponseEntity.ok(List.of());
        }

        List<Map<String, Object>> rows = activities.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activityId", a.getActivityId());
            m.put("activityName", a.getActivityName());
            m.put("activityType", a.getActivityType());
            m.put("startTime", a.getStartTime() == null ? null : a.getStartTime().getTime());
            m.put("endTime", a.getEndTime() == null ? null : a.getEndTime().getTime());
            m.put("durationMs", a.getDurationInMillis());
            m.put("deleteReason", a.getDeleteReason());
            // Mark whether this activity errored — Flowable sets deleteReason when
            // an activity is terminated due to a BpmnError or unhandled exception.
            m.put("errored", a.getDeleteReason() != null && !a.getDeleteReason().isBlank());
            // If this is the last activity *that didn't reach end*, it's where the run stopped.
            m.put("status", classify(a, exec.isSuccess()));
            return m;
        }).toList();

        return ResponseEntity.ok(rows);
    }

    private static String classify(HistoricActivityInstance a, boolean runSucceeded) {
        if (a.getDeleteReason() != null && !a.getDeleteReason().isBlank()) return "failed";
        if (a.getEndTime() == null) return "running";
        return "completed";
    }

    @PostMapping("/{id}/deprecate")
    public ResponseEntity<?> deprecate(@PathVariable String id) {
        if (domainRepo.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        domainRepo.updateStatus(id, RuleDomain.STATUS_DEPRECATED, "Manually deprecated via admin UI");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable String id) {
        if (domainRepo.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        // Single-active invariant: deprecate every other version of the same
        // (skill, intent) before flipping this row to ACTIVE.
        int deactivated = domainRepo.deactivateSiblings(id);
        domainRepo.updateStatus(id, RuleDomain.STATUS_ACTIVE, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activatedId", id);
        body.put("deactivatedCount", deactivated);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> summary(RuleDomain d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("skillId", d.getSkillId());
        m.put("skillName", d.getSkillName());
        m.put("intentLabel", d.getIntentLabel());
        m.put("status", d.getStatus());
        m.put("version", d.getVersion());
        m.put("flowableProcKey", d.getFlowableProcKey());
        m.put("compileAttempts", d.getCompileAttempts());
        m.put("createdAt", d.getCreatedAt());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }
}
