package com.pods.agent.workflow.api;

import com.pods.agent.workflow.api.dto.RunSummary;
import com.pods.agent.workflow.api.dto.StartRunRequest;
import com.pods.agent.workflow.engine.ResumeService;
import com.pods.agent.workflow.engine.WorkflowManager;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.persistence.ActivityInstRepository;
import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.AuditTrailRepository;
import com.pods.agent.workflow.persistence.AuditTrailRow;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Start a process and query its state / activities / audit trail.
 */
@RestController
@RequestMapping("/api/v1/workflow/runs")
public class ProcessRunController {

    private final ProcessDefService defService;
    private final WorkflowManager workflowManager;
    private final ResumeService resumeService;
    private final ProcessInstRepository processInstRepo;
    private final ActivityInstRepository activityInstRepo;
    private final AuditTrailRepository auditRepo;
    private final WorkflowRerunService rerunService;

    public ProcessRunController(ProcessDefService defService,
                                WorkflowManager workflowManager,
                                ResumeService resumeService,
                                ProcessInstRepository processInstRepo,
                                ActivityInstRepository activityInstRepo,
                                AuditTrailRepository auditRepo,
                                WorkflowRerunService rerunService) {
        this.defService = defService;
        this.workflowManager = workflowManager;
        this.resumeService = resumeService;
        this.processInstRepo = processInstRepo;
        this.activityInstRepo = activityInstRepo;
        this.auditRepo = auditRepo;
        this.rerunService = rerunService;
    }

    /**
     * Start a process. Default is synchronous (returns after the process
     * reaches a terminal state). Pass {@code ?async=true} to return
     * immediately with the new instance id; use {@code GET /runs/{id}} to
     * poll status.
     */
    @PostMapping
    public ResponseEntity<RunSummary> start(@RequestBody StartRunRequest req,
                                            @RequestParam(name = "async", defaultValue = "false") boolean async) {
        if (req.processDefId() == null || req.processDefId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ProcessDefinition def = defService.loadDomainById(req.processDefId()).orElse(null);
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        if (async) {
            String instanceId = workflowManager.startProcessAsync(
                    def, req.initialVariables(), req.requesterId());
            ProcessInstRow row = processInstRepo.findById(instanceId).orElse(null);
            return ResponseEntity.accepted().body(toSummary(instanceId, row));
        }
        WorkflowManager.StartResult result = workflowManager.startProcess(
                def, req.initialVariables(), req.requesterId());
        ProcessInstRow row = processInstRepo.findById(result.instanceId()).orElse(null);
        return ResponseEntity.ok(toSummary(result.instanceId(), row));
    }

    /**
     * Replay a previous run: copy its variable snapshot and start a brand-
     * new instance from the start activity. NOT a mid-flow checkpoint
     * resume — see {@link ResumeService} comments.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<RunSummary> replay(@PathVariable("id") String id,
                                             @RequestParam(name = "requesterId", required = false) String requesterId) {
        String newInstanceId = resumeService.replay(id, requesterId);
        if (newInstanceId == null) {
            return ResponseEntity.notFound().build();
        }
        ProcessInstRow row = processInstRepo.findById(newInstanceId).orElse(null);
        return ResponseEntity.ok(toSummary(newInstanceId, row));
    }

    /**
     * True mid-flow resume: continue the same instance from its persisted
     * checkpoint (worklist + join state + variable scope). The original
     * instance id is preserved.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<RunSummary> resume(@PathVariable("id") String id,
                                             @RequestParam(name = "requesterId", required = false) String requesterId) {
        ProcessInstRow row = processInstRepo.findById(id).orElse(null);
        if (row == null) {
            return ResponseEntity.notFound().build();
        }
        var def = defService.loadDomainById(row.defId()).orElse(null);
        if (def == null) {
            return ResponseEntity.status(409).build();
        }
        WorkflowManager.StartResult result = workflowManager.resumeProcess(id, def, requesterId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        ProcessInstRow latest = processInstRepo.findById(id).orElse(null);
        return ResponseEntity.ok(toSummary(id, latest));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunSummary> getOne(@PathVariable("id") String id) {
        return processInstRepo.findById(id)
                .map(row -> ResponseEntity.ok(toSummary(id, row)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/activities")
    public ResponseEntity<List<ActivityInstRow>> activities(@PathVariable("id") String id) {
        return ResponseEntity.ok(activityInstRepo.findByInstId(id));
    }

    @GetMapping("/{id}/audit")
    public ResponseEntity<List<AuditTrailRow>> audit(@PathVariable("id") String id) {
        return ResponseEntity.ok(auditRepo.findByInstId(id));
    }

    /**
     * Sub-flow drill-in: list child instances spawned by sub-flow activities
     * within this run, so the UI can switch the canvas to a child run when
     * the user clicks a sub-flow node.
     */
    @GetMapping("/{id}/subflows")
    public ResponseEntity<Map<String, Object>> subflows(@PathVariable("id") String id) {
        return ResponseEntity.ok(Map.of(
                "instanceId", id,
                "children", processInstRepo.findChildren(id)
        ));
    }

    /**
     * Re-run from a specific node. Pins every upstream activity's output
     * from the source run and starts a new instance — the dispatcher
     * fast-replays the pinned activities, so {@code fromActivityDefId} and
     * everything downstream execute against the source run's intermediate
     * state. Pins are cleared after the rerun terminates.
     */
    @PostMapping("/{id}/rerun-from/{activityDefId}")
    public ResponseEntity<RunSummary> rerunFrom(
            @PathVariable("id") String id,
            @PathVariable("activityDefId") String activityDefId,
            @RequestParam(name = "requesterId", required = false) String requesterId) {
        return rerunService.rerunFrom(id, activityDefId, requesterId)
                .map(r -> {
                    ProcessInstRow row = processInstRepo.findById(r.instanceId()).orElse(null);
                    return ResponseEntity.ok(toSummary(r.instanceId(), row));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cross-workflow filtered list — backs the global Executions page.
     *
     * Query params (all optional):
     * <ul>
     *   <li>{@code state} — exact state match (e.g. {@code closed.completed}).</li>
     *   <li>{@code stateGroup} — {@code open}, {@code closed}, or {@code suspended}.</li>
     *   <li>{@code defId} — restrict to a single workflow definition.</li>
     *   <li>{@code requester} — substring of requester id (case-insensitive).</li>
     *   <li>{@code from}, {@code to} — millis-since-epoch range on {@code started_at}.</li>
     *   <li>{@code limit} (default 50), {@code offset} (default 0).</li>
     * </ul>
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "stateGroup", required = false) String stateGroup,
            @RequestParam(name = "defId", required = false) String defId,
            @RequestParam(name = "requester", required = false) String requester,
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        String stateLike = stateLikePattern(stateGroup);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        return ResponseEntity.ok(Map.of(
                "limit", safeLimit,
                "offset", safeOffset,
                "total", processInstRepo.countFiltered(state, stateLike, defId, requester, from, to),
                "runs", processInstRepo.listFiltered(state, stateLike, defId, requester, from, to, safeLimit, safeOffset)
        ));
    }

    private static String stateLikePattern(String group) {
        if (group == null || group.isBlank()) return null;
        return switch (group.toLowerCase()) {
            case "open" -> "open.%";
            case "closed" -> "closed.%";
            case "suspended" -> "open.not_running.suspended";
            default -> null;
        };
    }

    @GetMapping("/by-process/{defId}")
    public ResponseEntity<Map<String, Object>> listByProcess(@PathVariable("defId") String defId,
                                                             @RequestParam(defaultValue = "50") int limit,
                                                             @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of(
                "processDefId", defId,
                "limit", limit,
                "offset", offset,
                "total", processInstRepo.countByDefId(defId),
                "runs", processInstRepo.findByDefId(defId, limit, offset)
        ));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("id") String id) {
        ProcessInstRow row = processInstRepo.findById(id).orElse(null);
        if (row == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "instanceId", row.id(),
                "state", row.state(),
                "startedAt", row.startedAt(),
                "endedAt", row.endedAt(),
                "errorClass", row.errorClass(),
                "errorMessage", row.errorMessage()
        ));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable("id") String id) {
        if (processInstRepo.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "instanceId", id,
                "events", auditRepo.findByInstId(id)
        ));
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> analytics(@RequestParam(defaultValue = "50") int limit,
                                                         @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of(
                "totalRuns", processInstRepo.countAll(),
                "statusBreakdown", processInstRepo.statusCounts(),
                "transitionOutcomeBreakdown", auditRepo.transitionOutcomeCounts(),
                "activityTypeBreakdown", activityInstRepo.countsByType(),
                "retryTimeoutStats", activityInstRepo.retryAndTimeoutStats(),
                "failureHotspots", activityInstRepo.failureHotspots(limit),
                "stepPerformance", activityInstRepo.performanceMetrics(limit),
                "latestRuns", processInstRepo.findAll(limit, offset)
        ));
    }

    @GetMapping("/approvals")
    public ResponseEntity<Map<String, Object>> approvalsQueue() {
        // Placeholder for approval parity: workflow engine currently auto-completes normal tasks.
        return ResponseEntity.ok(Map.of("pending", List.of()));
    }

    private static RunSummary toSummary(String instanceId, ProcessInstRow row) {
        if (row == null) {
            return new RunSummary(instanceId, null, null, null, null, null, null, null);
        }
        return new RunSummary(
                row.id(),
                row.defId(),
                row.state(),
                row.startedAt(),
                row.endedAt(),
                row.requesterId(),
                row.errorClass(),
                row.errorMessage());
    }
}
