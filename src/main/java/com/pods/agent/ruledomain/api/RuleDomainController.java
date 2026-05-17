package com.pods.agent.ruledomain.api;

import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.ruledomain.model.RuleActivityEvent;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.RuleExecution;
import com.pods.agent.ruledomain.repository.RuleActivityEventRepository;
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
    private final RuleActivityEventRepository activityEventRepo;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final BpmnRuntime bpmnRuntime;
    private final BpmnCompiler bpmnCompiler;

    public RuleDomainController(RuleDomainRepository domainRepo,
                                RuleExecutionRepository executionRepo,
                                RuleActivityEventRepository activityEventRepo,
                                HistoryService historyService,
                                RepositoryService repositoryService,
                                BpmnRuntime bpmnRuntime,
                                BpmnCompiler bpmnCompiler) {
        this.domainRepo = domainRepo;
        this.executionRepo = executionRepo;
        this.activityEventRepo = activityEventRepo;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.bpmnRuntime = bpmnRuntime;
        this.bpmnCompiler = bpmnCompiler;
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

    /**
     * Per-activity I/O log for one execution: input args, bound output,
     * and any error per service-task invocation. Multi-instance subprocesses
     * produce one row per iteration. Used by the UI to overlay actual
     * values on the BPMN diagram so the operator can see exactly what
     * each step processed.
     */
    @GetMapping("/{id}/executions/{execId}/activity-events")
    public List<RuleActivityEvent> activityEvents(@PathVariable String id,
                                                  @PathVariable String execId) {
        return activityEventRepo.listForExecution(execId);
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
        response.put("diagnostics", computeRunDiagnostics(domain.getBpmnXml(), outcome.outputs()));
        return ResponseEntity.ok(response);
    }

    /**
     * Inspect the BPMN + the run's outputs to surface conditions that
     * succeeded structurally but produced no items — typically a
     * multi-instance subprocess whose driving filter matched zero
     * elements. Distinguishing "filter matched nothing legitimately"
     * from "filter is wrong / data didn't flow" is otherwise impossible
     * for the test panel user.
     */
    private List<Map<String, Object>> computeRunDiagnostics(String bpmnXml, Map<String, Object> outputs) {
        List<Map<String, Object>> notes = new java.util.ArrayList<>();
        if (bpmnXml == null || bpmnXml.isBlank()) return notes;

        // Find every multi-instance subprocess: its id, its driving
        // collection variable name, and its aggregation target name.
        java.util.regex.Matcher spRe = java.util.regex.Pattern.compile(
                "<subProcess\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"[^>]*>[\\s\\S]*?<multiInstanceLoopCharacteristics\\b[^>]*"
                + "\\bflowable:collection\\s*=\\s*\"[$#]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\""
                + "[\\s\\S]*?<flowable:variableAggregation\\b[^>]*\\btarget\\s*=\\s*\"([^\"]+)\"",
                java.util.regex.Pattern.DOTALL).matcher(bpmnXml);
        while (spRe.find()) {
            String subprocessId = spRe.group(1);
            String collectionVar = spRe.group(2);
            String aggTarget = spRe.group(3);
            // Find an empty list in outputs that likely corresponds to
            // this aggregation. Search the outputs JSON recursively for
            // any empty array under any key (the assemble step typically
            // renames the aggregation target to a user-facing key, so we
            // can't always match by name).
            String emptyAt = findFirstEmptyArrayPath(outputs);
            if (emptyAt != null) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("type", "subprocess_zero_iterations");
                note.put("subprocessId", subprocessId);
                note.put("collectionVar", collectionVar);
                note.put("aggregationTarget", aggTarget);
                note.put("emptyOutputPath", emptyAt);
                note.put("message", "Subprocess `" + subprocessId + "` had a multi-instance loop over "
                        + "collection `" + collectionVar + "`. The collection resolved to an empty list, "
                        + "so the subprocess ran 0 iterations. Check whether the upstream FEEL filter "
                        + "binding `" + collectionVar + "` was supposed to match items for this order.");
                notes.add(note);
            }
        }
        return notes;
    }

    /** Recursively scan a map/list/scalar structure for the first empty
     *  array and return its dotted path. Used to associate an empty
     *  output array with a multi-instance subprocess diagnostic. */
    @SuppressWarnings("unchecked")
    private static String findFirstEmptyArrayPath(Object value) {
        return findFirstEmptyArrayPath(value, "");
    }

    @SuppressWarnings("unchecked")
    private static String findFirstEmptyArrayPath(Object value, String prefix) {
        if (value == null) return null;
        if (value instanceof java.util.List<?> l) {
            if (l.isEmpty()) return prefix.isEmpty() ? "(root)" : prefix;
            return null;
        }
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                String childPath = prefix.isEmpty() ? key : prefix + "." + key;
                String found = findFirstEmptyArrayPath(e.getValue(), childPath);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Replace the BPMN XML of an existing rule-domain row in place, then
     * undeploy the prior Flowable definition so the next execution picks
     * up the new XML. Used to surgically repair a compiled BPMN (e.g. fix
     * a wrong argTemplate key) without forcing a full chat-driven recompile.
     *
     * <p>Body shape: {@code {"bpmnXml": "<?xml ...?>...</definitions>"}}.
     *
     * <p>The supplied XML is run through the same sanitize + boundary-injection
     * + tryDeploy pipeline the compiler uses, so syntactic errors are
     * rejected with the Flowable parse diagnostic before anything is
     * persisted.
     */
    @PutMapping("/{id}/bpmn")
    public ResponseEntity<Map<String, Object>> updateBpmn(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        var maybe = domainRepo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        RuleDomain existing = maybe.get();

        Object rawXml = body == null ? null : body.get("bpmnXml");
        if (!(rawXml instanceof String xmlIn) || xmlIn.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "bpmnXml is required"));
        }

        String cleaned = BpmnCompiler.sanitize(xmlIn);
        cleaned = BpmnCompiler.injectErrorBoundaries(cleaned);

        String deployError = bpmnCompiler.tryDeploy(cleaned, existing.getSkillName(), existing.getIntentLabel());
        if (deployError != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BPMN failed validation",
                    "detail", deployError));
        }

        String newProcKey = BpmnCompiler.extractProcessId(cleaned);
        if (newProcKey == null || newProcKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BPMN has no <process id=...>"));
        }

        // Persist the new XML on the same row (same id + version, so the
        // upsert in RuleDomainRepository.save updates in place).
        existing.setBpmnXml(cleaned);
        existing.setFlowableProcKey(newProcKey);
        existing.setLastError(null);
        existing.setStatus(RuleDomain.STATUS_DRAFT);
        existing.setCompileAttempts(existing.getCompileAttempts() + 1);
        domainRepo.save(existing, null);

        // Undeploy the prior Flowable definition (if any) so the next
        // ensureDeployed() picks up the patched XML rather than the cached
        // one. Multiple definitions may exist if the proc key changed.
        java.util.Set<String> keysToUndeploy = new java.util.LinkedHashSet<>();
        if (existing.getFlowableProcKey() != null) keysToUndeploy.add(existing.getFlowableProcKey());
        keysToUndeploy.add(newProcKey);
        int undeployed = 0;
        for (String key : keysToUndeploy) {
            try {
                List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionKey(key)
                        .list();
                for (ProcessDefinition def : defs) {
                    repositoryService.deleteDeployment(def.getDeploymentId(), true);
                    undeployed++;
                }
            } catch (Exception ex) {
                log.warn("[RuleDomain] failed to undeploy Flowable proc {} on BPMN edit: {}",
                        key, ex.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", existing.getId());
        response.put("flowableProcKey", newProcKey);
        response.put("status", existing.getStatus());
        response.put("undeployedFlowableDefinitions", undeployed);
        response.put("compileAttempts", existing.getCompileAttempts());
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
        // Domain-group / rule fields — surface so the admin UI can group
        // rules by skill and act on the group as a whole.
        m.put("domainGroupId", d.getDomainGroupId());
        m.put("domainGroupName", d.getDomainGroupName());
        m.put("ruleName", d.getRuleName());
        m.put("matchScope", d.getMatchScope());
        m.put("coverageState", d.getCoverageState());
        m.put("traceSource", d.getTraceSource());
        m.put("resultKey", d.getResultKey());
        return m;
    }

    // ── Bulk operations on a domain group (all rules of one skill) ──

    /**
     * Activate every DRAFT/DEPRECATED rule belonging to the same skill as
     * the supplied row. Used by the admin UI's "Activate all rules" action
     * on the skill-level row.
     */
    @PostMapping("/skill/{skillId}/activate-all")
    public Map<String, Object> activateAllForSkill(@PathVariable String skillId) {
        List<RuleDomain> all = domainRepo.listBySkill(skillId);
        int activated = 0;
        int deactivated = 0;
        for (RuleDomain d : all) {
            if (RuleDomain.STATUS_DRAFT.equals(d.getStatus())
                    || RuleDomain.STATUS_DEPRECATED.equals(d.getStatus())) {
                deactivated += domainRepo.deactivateSiblings(d.getId());
                domainRepo.updateStatus(d.getId(), RuleDomain.STATUS_ACTIVE, null);
                activated++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", skillId);
        body.put("activated", activated);
        body.put("deactivatedSiblings", deactivated);
        return body;
    }

    /**
     * Delete every rule (every revision) belonging to the supplied skill.
     * Mirrors the per-row delete but operates on the whole skill group at
     * once. Also undeploys orphaned Flowable definitions.
     */
    @DeleteMapping("/skill/{skillId}")
    public Map<String, Object> deleteAllForSkill(@PathVariable String skillId) {
        List<RuleDomain> all = domainRepo.listBySkill(skillId);
        java.util.Set<String> procKeys = new java.util.LinkedHashSet<>();
        for (RuleDomain d : all) {
            if (d.getFlowableProcKey() != null && !d.getFlowableProcKey().isBlank()) {
                procKeys.add(d.getFlowableProcKey());
            }
        }
        int deletedRows = 0;
        java.util.Set<String> seenGroups = new java.util.HashSet<>();
        for (RuleDomain d : all) {
            // deleteGroupContaining wipes all revisions of one (skill,intent)
            // group at once; skip rows whose group we've already deleted.
            String groupKey = d.getSkillId() + "::" + d.getIntentLabel();
            if (!seenGroups.add(groupKey)) continue;
            deletedRows += domainRepo.deleteGroupContaining(d.getId());
        }
        int undeployed = 0;
        for (String procKey : procKeys) {
            if (domainRepo.countByFlowableProcKey(procKey) > 0) continue;
            try {
                List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionKey(procKey).list();
                for (ProcessDefinition def : defs) {
                    repositoryService.deleteDeployment(def.getDeploymentId(), true);
                    undeployed++;
                }
            } catch (Exception ex) {
                log.warn("[RuleDomain] failed to undeploy Flowable proc {} after skill delete: {}",
                        procKey, ex.getMessage());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", skillId);
        body.put("deletedRevisions", deletedRows);
        body.put("undeployedFlowableDefinitions", undeployed);
        return body;
    }

    /**
     * Run every rule of the supplied skill against the same input map. The
     * admin UI's "Test full skill" action — useful when the user wants to
     * verify every rule end-to-end without going through a chat session.
     */
    @PostMapping("/skill/{skillId}/test")
    public Map<String, Object> testAllForSkill(@PathVariable String skillId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = body != null && body.get("inputs") instanceof Map<?, ?>
                ? (Map<String, Object>) body.get("inputs")
                : Map.of();
        String sessionId = "test-" + UUID.randomUUID();
        String turnId = "test-turn";

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        // Include DEPRECATED rules — this endpoint is an admin tool, so a
        // deprecated rule should still be testable from the UI (it's not
        // deleted, just non-routable). The runtime path still uses status
        // for routing decisions, but admin test runs are status-blind.
        List<RuleDomain> rules = domainRepo.listBySkill(skillId).stream()
                .filter(d -> !RuleDomain.STATUS_FAILED.equals(d.getStatus()))
                .toList();
        for (RuleDomain d : rules) {
            try {
                ExecutionOutcome outcome = bpmnRuntime.execute(d, inputs, sessionId, turnId, false);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ruleId", d.getId());
                r.put("ruleName", d.getRuleName() != null ? d.getRuleName() : d.getIntentLabel());
                r.put("success", outcome.error() == null);
                r.put("error", outcome.error());
                r.put("outputs", outcome.outputs());
                r.put("latencyMs", outcome.latencyMs());
                r.put("flowableProcId", outcome.flowableProcId());
                r.put("diagnostics", computeRunDiagnostics(d.getBpmnXml(), outcome.outputs()));
                results.add(r);
            } catch (Exception ex) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ruleId", d.getId());
                r.put("ruleName", d.getRuleName() != null ? d.getRuleName() : d.getIntentLabel());
                r.put("success", false);
                r.put("error", ex.getMessage());
                results.add(r);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("skillId", skillId);
        resp.put("ruleCount", rules.size());
        resp.put("results", results);
        return resp;
    }
}
