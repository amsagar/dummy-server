package com.pods.agent.ruledomain.api;

import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.RuleExecution;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.repository.RuleDomainRepository.PageResult;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import com.pods.agent.ruledomain.runtime.BpmnRuntime;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final RepositoryService repositoryService;
    private final BpmnRuntime bpmnRuntime;

    public RuleDomainController(RuleDomainRepository domainRepo,
                                RuleExecutionRepository executionRepo,
                                HistoryService historyService,
                                RepositoryService repositoryService,
                                BpmnRuntime bpmnRuntime) {
        this.domainRepo = domainRepo;
        this.executionRepo = executionRepo;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.bpmnRuntime = bpmnRuntime;
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

    /**
     * Manually invoke a compiled rule domain with the supplied inputs. Used by
     * the editor's "Test" tab to verify a BPMN works without going through chat.
     * The run is persisted just like any other execution (session_id is
     * tagged so a test run is identifiable in history).
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        var maybeDomain = domainRepo.findById(id);
        if (maybeDomain.isEmpty()) return ResponseEntity.notFound().build();
        RuleDomain domain = maybeDomain.get();

        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = body != null && body.get("inputs") instanceof Map<?, ?>
                ? (Map<String, Object>) body.get("inputs")
                : Map.of();
        String sessionId = "test-" + UUID.randomUUID();
        String turnId = "test-turn";

        ExecutionOutcome outcome = bpmnRuntime.execute(domain, inputs, sessionId, turnId, false);

        // Look up the execution row we just created so the frontend can pull the trace.
        String executionId = executionRepo.findByFlowableProcId(outcome.flowableProcId())
                .map(RuleExecution::getId)
                .orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", outcome.error() == null);
        response.put("error", outcome.error());
        response.put("outputs", outcome.outputs());
        response.put("latencyMs", outcome.latencyMs());
        response.put("flowableProcId", outcome.flowableProcId());
        response.put("executionId", executionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Hard-delete every revision for the same (skill, intent) as the given row.
     * The list page collapses to one row per (skill, intent), so the user's
     * mental model is "delete this rule domain", which means every version.
     * The FK on rule_executions cascades, so all execution history is removed too.
     *
     * For every Flowable process-definition key referenced by the deleted rows,
     * if no surviving rule_domain row still references it, the corresponding
     * Flowable deployment is removed so the engine doesn't carry orphan
     * definitions.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        var maybe = domainRepo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();

        // Snapshot every revision's proc key BEFORE we delete so we know what
        // to consider for undeployment afterward.
        List<RuleDomain> versions = domainRepo.findVersionsOf(id);
        java.util.Set<String> procKeys = new java.util.LinkedHashSet<>();
        for (RuleDomain v : versions) {
            if (v.getFlowableProcKey() != null && !v.getFlowableProcKey().isBlank()) {
                procKeys.add(v.getFlowableProcKey());
            }
        }

        int deletedRows = domainRepo.deleteGroupContaining(id);

        int undeployed = 0;
        for (String procKey : procKeys) {
            // After the group delete, ask if any *other* group still uses this key.
            if (domainRepo.countByFlowableProcKey(procKey) > 0) continue;
            try {
                List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionKey(procKey)
                        .list();
                for (ProcessDefinition def : defs) {
                    repositoryService.deleteDeployment(def.getDeploymentId(), true);
                    undeployed++;
                }
            } catch (Exception ex) {
                // Non-fatal — rows are gone from our table; the Flowable deployment
                // becomes orphaned and is harmless until the next restart at worst.
                log.warn("[RuleDomain] failed to undeploy Flowable proc {} after delete: {}",
                        procKey, ex.getMessage());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deletedRevisions", deletedRows);
        body.put("undeployedFlowableDefinitions", undeployed);
        return ResponseEntity.ok(body);
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
