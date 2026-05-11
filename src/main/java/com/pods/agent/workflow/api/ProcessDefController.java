package com.pods.agent.workflow.api;

import com.pods.agent.workflow.api.dto.ProcessDefDto;
import com.pods.agent.workflow.api.dto.ProcessDefMetadataDto;
import com.pods.agent.workflow.engine.WorkflowSchemaValidator;
import com.pods.agent.workflow.metadata.WorkflowMetadataService;
import com.pods.agent.workflow.proposal.WorkflowProposal;
import com.pods.agent.workflow.proposal.WorkflowProposalService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.pods.agent.service.SecurityContextService;

/**
 * CRUD-ish endpoints for process definitions. The React Flow board POSTs the
 * full process JSON here; the engine reads it back at run time.
 */
@RestController
@RequestMapping("/api/v1/workflow/processes")
public class ProcessDefController {

    private final ProcessDefService service;
    private final WorkflowProposalService workflowProposalService;
    private final SecurityContextService securityContextService;
    private final WorkflowSchemaValidator schemaValidator;
    private final WorkflowMetadataService metadataService;
    private final ObjectMapper objectMapper;

    public ProcessDefController(ProcessDefService service,
                                WorkflowProposalService workflowProposalService,
                                SecurityContextService securityContextService,
                                WorkflowSchemaValidator schemaValidator,
                                WorkflowMetadataService metadataService,
                                ObjectMapper objectMapper) {
        this.service = service;
        this.workflowProposalService = workflowProposalService;
        this.securityContextService = securityContextService;
        this.schemaValidator = schemaValidator;
        this.metadataService = metadataService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ProcessDefDto> create(@RequestBody ProcessDefDto dto) {
        return ResponseEntity.ok(service.save(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProcessDefDto> update(@PathVariable("id") String id,
                                                @RequestBody ProcessDefDto dto) {
        // Force the path id into the body to prevent silent renames.
        ProcessDefDto withId = new ProcessDefDto(
                id, dto.name(), dto.version(), dto.packageId(), dto.description(),
                dto.variables(), dto.activities(), dto.transitions());
        return ResponseEntity.ok(service.save(withId));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody ProcessDefDto dto) {
        List<String> errors = new ArrayList<>();
        try {
            var domain = ProcessDefinitionMapper.toDomain(dto);
            domain.activities().forEach(activity -> {
                List<String> inErr = schemaValidator.validate(activity.inputSchema(), activity.properties());
                inErr.forEach(e -> errors.add("activity " + activity.id() + " inputSchema: " + e));
                List<String> outErr = schemaValidator.validate(activity.outputSchema(), Map.of());
                outErr.forEach(e -> errors.add("activity " + activity.id() + " outputSchema: " + e));
            });
        } catch (Exception ex) {
            errors.add(ex.getMessage());
        }
        return ResponseEntity.ok(Map.of(
                "valid", errors.isEmpty(),
                "errors", errors));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProcessDefDto> getOne(@PathVariable("id") String id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<ProcessDefDto>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    /**
     * Phase C: per-definition rolling aggregates. Combines the all-time
     * counters on {@code process_def} with the rolling-window stats from
     * {@code process_def_runs_recent}, plus the AI-node fingerprint and an
     * embedding-presence flag (Phase D readiness signal).
     *
     * <p>Returns 200 with empty/zero values when the def exists but has never
     * run — so the UI can render "no runs yet" without an extra round-trip.
     * Returns 404 only when the def itself isn't in {@code process_def}.
     */
    @GetMapping("/{id}/metadata")
    public ResponseEntity<ProcessDefMetadataDto> metadata(@PathVariable("id") String id) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(buildMetadata(id));
    }

    /**
     * Bulk metadata fetch for the workflows index page. Accepts either a
     * comma-separated {@code ids} query param ({@code ?ids=a,b,c}) or, when
     * {@code ids} is omitted, returns metadata for every def. Comma-form keeps
     * the response payload tight when the index is filtered client-side.
     */
    @GetMapping("/metadata")
    public ResponseEntity<Map<String, Object>> metadataBatch(
            @RequestParam(name = "ids", required = false) String idsCsv) {
        List<String> ids;
        if (idsCsv == null || idsCsv.isBlank()) {
            ids = service.findAll().stream().map(ProcessDefDto::id).filter(s -> s != null && !s.isBlank()).toList();
        } else {
            ids = Arrays.stream(idsCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        Map<String, ProcessDefMetadataDto> rows = new LinkedHashMap<>();
        for (String id : ids) {
            rows.put(id, buildMetadata(id));
        }
        return ResponseEntity.ok(Map.of("metadata", rows));
    }

    /**
     * Single-def composition shared by the singleton + batch endpoints. Pulls
     * Snapshot + WindowStats + embedding presence and packages them into the
     * wire DTO.
     */
    private ProcessDefMetadataDto buildMetadata(String id) {
        WorkflowMetadataService.Snapshot snap = metadataService.getSnapshot(id);
        WorkflowMetadataService.WindowStats win = metadataService.getRecentWindow(id);
        return new ProcessDefMetadataDto(
                id,
                new ProcessDefMetadataDto.AllTimeMetrics(
                        snap.totalRuns(),
                        snap.totalSuccesses(),
                        snap.totalLatencyMs(),
                        snap.successRate(),
                        snap.avgLatencyMs(),
                        snap.lastRunAt()),
                new ProcessDefMetadataDto.RecentWindow(
                        win.runs(),
                        win.successes(),
                        win.successRate(),
                        win.avgLatencyMs(),
                        win.lastRunAt()),
                snap.aiNodes(objectMapper),
                metadataService.hasEmbedding(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") String id,
                                                      @RequestParam(name = "force", defaultValue = "false") boolean force) {
        try {
            boolean deleted = force ? service.forceDeleteById(id) : service.deleteById(id);
            if (!deleted) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("deleted", true, "id", id, "force", force));
        } catch (IllegalStateException | DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of(
                    "deleted", false,
                    "id", id,
                    "force", force,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<ProcessDefDto>> listVersions(@PathVariable("id") String id) {
        var base = service.findById(id).orElse(null);
        if (base == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(service.findByName(base.name()));
    }

    @GetMapping("/templates")
    public ResponseEntity<Map<String, Object>> templates() {
        Map<String, Object> starter = new LinkedHashMap<>();
        starter.put("id", "starter-route");
        starter.put("name", "Starter Route");
        starter.put("description", "Simple start-to-end route workflow.");
        starter.put("definition", new ProcessDefDto(
                null,
                "Starter Route",
                "1",
                null,
                "Starter route workflow template",
                List.of(),
                List.of(
                        new ProcessDefDto.ActivityDto("start", "Start", "route", null, Map.of(), Map.of(), Map.of(), null, true, false, null, Map.of(), Map.of(), List.of(), false, null),
                        new ProcessDefDto.ActivityDto("endNode", "End", "route", null, Map.of(), Map.of(), Map.of(), null, false, true, null, Map.of(), Map.of(), List.of(), false, null)
                ),
                List.of(
                        new ProcessDefDto.TransitionDto("t1", "start", "endNode", null, false, null, "ON_SUCCESS", null, false)
                )
        ));
        return ResponseEntity.ok(Map.of("templates", List.of(starter)));
    }

    @PostMapping("/from-template")
    public ResponseEntity<ProcessDefDto> createFromTemplate(@RequestBody Map<String, Object> body,
                                                            @RequestParam(name = "name", required = false) String nameOverride) {
        String templateId = body == null ? "" : String.valueOf(body.getOrDefault("templateId", ""));
        if (!"starter-route".equals(templateId)) return ResponseEntity.badRequest().build();
        String name = nameOverride != null && !nameOverride.isBlank() ? nameOverride : "Starter Route";
        ProcessDefDto dto = new ProcessDefDto(
                null,
                name,
                "1",
                null,
                "Starter route workflow template",
                List.of(),
                List.of(
                        new ProcessDefDto.ActivityDto("start", "Start", "route", null, Map.of(), Map.of(), Map.of(), null, true, false, null, Map.of(), Map.of(), List.of(), false, null),
                        new ProcessDefDto.ActivityDto("endNode", "End", "route", null, Map.of(), Map.of(), Map.of(), null, false, true, null, Map.of(), Map.of(), List.of(), false, null)
                ),
                List.of(
                        new ProcessDefDto.TransitionDto("t1", "start", "endNode", null, false, null, "ON_SUCCESS", null, false)
                )
        );
        return ResponseEntity.ok(service.save(dto));
    }

    @GetMapping("/proposals")
    public ResponseEntity<Map<String, Object>> listPendingProposals() {
        String userId = securityContextService.currentUserIdOrThrow();
        List<Map<String, Object>> rows = workflowProposalService.listPendingByUser(userId).stream()
                .map(this::toProposalPayload)
                .toList();
        return ResponseEntity.ok(Map.of("proposals", rows));
    }

    /**
     * Polling endpoint for the UI: returns the current state of a single
     * proposal so a reviewer can watch the Phase-2 builder progress through
     * {@code approved → building → materialized | failed} after they hit
     * approve.
     */
    @GetMapping("/proposals/{id}")
    public ResponseEntity<Map<String, Object>> getProposal(@PathVariable("id") String id) {
        return workflowProposalService.getById(id)
                .map(p -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("proposal", toProposalPayload(p));
                    return ResponseEntity.ok(payload);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveProposal(@PathVariable("id") String id,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        String userId = securityContextService.currentUserIdOrThrow();
        String comment = body == null ? null : String.valueOf(body.getOrDefault("comment", ""));
        // approve() flips status to 'approved' synchronously and enqueues the
        // Phase-2 build asynchronously. The builder pushes the row through
        // 'building' and onward; clients should poll GET /proposals/{id}.
        return workflowProposalService.approve(id, userId, comment)
                .map(p -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("proposal", toProposalPayload(p));
                    payload.put("buildState", "queued");
                    return ResponseEntity.ok(payload);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectProposal(@PathVariable("id") String id,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        String userId = securityContextService.currentUserIdOrThrow();
        String comment = body == null ? null : String.valueOf(body.getOrDefault("comment", ""));
        return workflowProposalService.reject(id, userId, comment)
                .map(p -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("proposal", toProposalPayload(p));
                    return ResponseEntity.ok(payload);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toProposalPayload(WorkflowProposal proposal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", proposal.getId());
        row.put("sessionId", proposal.getSessionId());
        row.put("turnId", proposal.getTurnId());
        row.put("status", proposal.getStatus());
        row.put("reason", proposal.getReason());
        row.put("confidence", proposal.getConfidence());
        row.put("intentSignature", proposal.getIntentSignature());
        row.put("traceRef", proposal.getTraceRef());
        row.put("userPrompt", proposal.getUserPrompt());
        row.put("suggestedName", proposal.getSuggestedName());
        row.put("skillNamesJson", proposal.getSkillNamesJson());
        row.put("matchedToolNamesJson", proposal.getMatchedToolNamesJson());
        row.put("buildAttempts", proposal.getBuildAttempts());
        row.put("decisionComment", proposal.getDecisionComment());
        row.put("decidedBy", proposal.getDecidedBy());
        row.put("decidedAt", proposal.getDecidedAt());
        row.put("materializedDefId", proposal.getMaterializedDefId());
        row.put("errorMessage", proposal.getErrorMessage());
        row.put("createdAt", proposal.getCreatedAt());
        row.put("updatedAt", proposal.getUpdatedAt());
        return row;
    }
}
