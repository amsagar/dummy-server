package com.pods.agent.api;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.domain.ToolChainVersion;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainRunStepRepository;
import com.pods.agent.service.SecurityContextService;
import com.pods.agent.service.ToolChainConfigChatService;
import com.pods.agent.service.ToolChainRuntimeService;
import com.pods.agent.service.ToolChainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "ToolChains", description = "Compiled ToolChain workflow APIs")
public class ToolChainController {
    private final ToolChainService toolChainService;
    private final ToolChainRuntimeService toolChainRuntimeService;
    private final ToolChainConfigChatService toolChainConfigChatService;
    private final ToolChainRunRepository toolChainRunRepository;
    private final ToolChainRunStepRepository toolChainRunStepRepository;
    private final SecurityContextService securityContextService;

    public ToolChainController(ToolChainService toolChainService,
                               ToolChainRuntimeService toolChainRuntimeService,
                               ToolChainConfigChatService toolChainConfigChatService,
                               ToolChainRunRepository toolChainRunRepository,
                               ToolChainRunStepRepository toolChainRunStepRepository,
                               SecurityContextService securityContextService) {
        this.toolChainService = toolChainService;
        this.toolChainRuntimeService = toolChainRuntimeService;
        this.toolChainConfigChatService = toolChainConfigChatService;
        this.toolChainRunRepository = toolChainRunRepository;
        this.toolChainRunStepRepository = toolChainRunStepRepository;
        this.securityContextService = securityContextService;
    }

    @GetMapping("/toolchains")
    @Operation(summary = "List all ToolChains")
    public ResponseEntity<?> listToolChains() {
        return ResponseEntity.ok(toolChainService.listAll());
    }

    @PostMapping("/toolchains")
    @Operation(summary = "Create a ToolChain")
    public ResponseEntity<?> createToolChain(@RequestBody ToolChainDtos.ToolChainCreateRequest request) {
        ToolChain created = toolChainService.create(request, securityContextService.currentUserIdOrThrow());
        return ResponseEntity.ok(created);
    }

    @PatchMapping("/toolchains/{id}")
    @Operation(summary = "Update ToolChain metadata")
    public ResponseEntity<?> updateToolChain(@PathVariable String id,
                                             @RequestBody ToolChainDtos.ToolChainCreateRequest request) {
        try {
            return ResponseEntity.ok(toolChainService.update(id, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @DeleteMapping("/toolchains/{id}")
    @Operation(summary = "Delete ToolChain")
    public ResponseEntity<?> deleteToolChain(@PathVariable String id) {
        toolChainService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @GetMapping("/toolchains/{id}/versions")
    @Operation(summary = "List ToolChain versions")
    public ResponseEntity<?> versions(@PathVariable String id) {
        return ResponseEntity.ok(toolChainService.listVersions(id));
    }

    @PostMapping("/toolchains/{id}/versions")
    @Operation(summary = "Create ToolChain version")
    public ResponseEntity<?> createVersion(@PathVariable String id,
                                           @RequestBody ToolChainDtos.ToolChainVersionRequest request) {
        ToolChainVersion version = toolChainService.createVersion(id, request, securityContextService.currentUserIdOrThrow());
        return ResponseEntity.ok(version);
    }

    @PostMapping("/toolchains/{id}/versions/{version}/publish")
    @Operation(summary = "Publish ToolChain version")
    public ResponseEntity<?> publishVersion(@PathVariable String id, @PathVariable int version) {
        return ResponseEntity.ok(toolChainService.publishVersion(id, version));
    }

    @PostMapping("/toolchains/{id}/generate-draft")
    @Operation(summary = "Generate AI draft graph from natural language")
    public ResponseEntity<?> generateDraft(@PathVariable String id,
                                           @RequestBody ToolChainDtos.ToolChainGenerateDraftRequest request) {
        return ResponseEntity.ok(Map.of(
                "toolChainId", id,
                "graphJson", toolChainService.generateDraftGraph(request.getPrompt())
        ));
    }

    @GetMapping("/toolchains/{id}/config-sessions")
    @Operation(summary = "List ToolChain configuration chat sessions")
    public ResponseEntity<?> configSessions(@PathVariable String id) {
        return ResponseEntity.ok(toolChainConfigChatService.listSessions(id));
    }

    @GetMapping("/toolchains/{id}/config-sessions/{sessionId}")
    @Operation(summary = "Get ToolChain configuration chat session details")
    public ResponseEntity<?> configSessionDetail(@PathVariable String id, @PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.getSessionDetail(id, sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/config-chat")
    @Operation(summary = "Post message to ToolChain configuration chat")
    public ResponseEntity<?> configChat(@PathVariable String id,
                                        @RequestBody ToolChainDtos.ToolChainConfigChatRequest request) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.processMessage(id, request, securityContextService.currentUserIdOrThrow()));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PostMapping(value = "/toolchains/{id}/config-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream ToolChain configuration chat updates")
    public SseEmitter configChatStream(@PathVariable String id,
                                       @RequestBody ToolChainDtos.ToolChainConfigChatRequest request,
                                       jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(300_000L);
        toolChainConfigChatService.handleStreamMessage(id, request, emitter, securityContextService.currentUserIdOrThrow());
        return emitter;
    }

    @PostMapping("/toolchains/{id}/config-sessions/{sessionId}/reply")
    @Operation(summary = "Reply to pending ToolChain config stream question")
    public ResponseEntity<?> replyConfigStream(@PathVariable String id,
                                               @PathVariable String sessionId,
                                               @RequestBody(required = false) ToolChainDtos.ToolChainConfigChatRequest request) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.replyToPendingStream(id, sessionId, request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/toolchains/config-chat")
    @Operation(summary = "Post message to requirement-first ToolChain configuration chat")
    public ResponseEntity<?> configChatGlobal(@RequestBody ToolChainDtos.ToolChainConfigChatRequest request) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.processMessage(null, request, securityContextService.currentUserIdOrThrow()));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping(value = "/toolchains/config-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream requirement-first ToolChain configuration chat updates")
    public SseEmitter configChatGlobalStream(@RequestBody ToolChainDtos.ToolChainConfigChatRequest request,
                                             jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(300_000L);
        toolChainConfigChatService.handleStreamMessage(null, request, emitter, securityContextService.currentUserIdOrThrow());
        return emitter;
    }

    @PostMapping("/toolchains/config-sessions/{sessionId}/reply")
    @Operation(summary = "Reply to pending requirement-first ToolChain config stream question")
    public ResponseEntity<?> replyGlobalConfigStream(@PathVariable String sessionId,
                                                     @RequestBody(required = false) ToolChainDtos.ToolChainConfigChatRequest request) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.replyToPendingStream(null, sessionId, request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PatchMapping("/toolchains/{id}/config-sessions/{sessionId}")
    @Operation(summary = "Update ToolChain config session metadata")
    public ResponseEntity<?> updateConfigSession(@PathVariable String id,
                                                 @PathVariable String sessionId,
                                                 @RequestBody ToolChainDtos.ToolChainConfigSessionUpdateRequest request) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.updateSession(id, sessionId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @DeleteMapping("/toolchains/{id}/config-sessions/{sessionId}")
    @Operation(summary = "Delete ToolChain config session")
    public ResponseEntity<?> deleteConfigSession(@PathVariable String id, @PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.deleteSession(id, sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @GetMapping("/toolchains/{id}/config-sessions/{sessionId}/layout")
    @Operation(summary = "Get user-specific ToolChain config session layout")
    public ResponseEntity<?> configSessionLayout(@PathVariable String id, @PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(
                    toolChainConfigChatService.getSessionLayout(id, sessionId, securityContextService.currentUserIdOrThrow())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PatchMapping("/toolchains/{id}/config-sessions/{sessionId}/layout")
    @Operation(summary = "Save user-specific ToolChain config session layout")
    public ResponseEntity<?> updateConfigSessionLayout(@PathVariable String id,
                                                       @PathVariable String sessionId,
                                                       @RequestBody ToolChainDtos.ToolChainConfigSessionLayoutRequest request) {
        try {
            return ResponseEntity.ok(
                    toolChainConfigChatService.upsertSessionLayout(id, sessionId, request, securityContextService.currentUserIdOrThrow())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/config-sessions/{sessionId}/compile")
    @Operation(summary = "Validate and compile ToolChain draft artifacts")
    public ResponseEntity<?> compileFromConfigSession(@PathVariable String id, @PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.compileToVersion(id, sessionId, securityContextService.currentUserIdOrThrow()));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/config-sessions/{sessionId}/publish")
    @Operation(summary = "Publish ToolChain version from compiled draft artifacts")
    public ResponseEntity<?> publishFromConfigSession(@PathVariable String id, @PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(toolChainConfigChatService.publishFromSession(id, sessionId, securityContextService.currentUserIdOrThrow()));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/execute")
    @Operation(summary = "Execute ToolChain")
    public ResponseEntity<?> execute(@PathVariable String id,
                                     @RequestBody(required = false) ToolChainDtos.ToolChainExecuteRequest request) {
        ToolChainDtos.ToolChainExecuteRequest payload = request == null ? new ToolChainDtos.ToolChainExecuteRequest() : request;
        var run = toolChainRuntimeService.execute(
                id,
                payload.getVersion(),
                payload.getTriggerSource() == null ? "api" : payload.getTriggerSource(),
                securityContextService.currentUserIdOrThrow(),
                payload.getInput(),
                payload.getOptions(),
                null
        );
        return ResponseEntity.ok(Map.of(
                "runId", run.getId(),
                "toolChainId", run.getToolChainId(),
                "version", run.getVersion(),
                "status", run.getStatus(),
                "statusUrl", "/api/v1/runs/" + run.getId() + "/status",
                "runUrl", "/api/v1/runs/" + run.getId()
        ));
    }

    @GetMapping("/toolchains/{id}/runs")
    @Operation(summary = "List runs for a ToolChain")
    public ResponseEntity<?> listRuns(@PathVariable String id,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of(
                "toolChainId", id,
                "limit", limit,
                "offset", offset,
                "total", toolChainRunRepository.countByChain(id),
                "runs", toolChainRunRepository.findByChain(id, limit, offset)
        ));
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Get run details")
    public ResponseEntity<?> runDetail(@PathVariable String runId) {
        var run = toolChainRuntimeService.getRun(runId);
        if (run.isEmpty()) return ResponseEntityFactory.notFound("Run not found: " + runId);
        return ResponseEntity.ok(Map.of(
                "run", run.get(),
                "steps", toolChainRuntimeService.getRunSteps(runId),
                "approvals", toolChainRuntimeService.getRunApprovals(runId),
                "events", toolChainRuntimeService.getRunEvents(runId)
        ));
    }

    @GetMapping("/runs/{runId}/events")
    @Operation(summary = "Get persisted runtime events for a run")
    public ResponseEntity<?> runEvents(@PathVariable String runId) {
        var run = toolChainRuntimeService.getRun(runId);
        if (run.isEmpty()) return ResponseEntityFactory.notFound("Run not found: " + runId);
        return ResponseEntity.ok(Map.of(
                "runId", runId,
                "events", toolChainRuntimeService.getRunEvents(runId)
        ));
    }

    @GetMapping("/runs/{runId}/status")
    @Operation(summary = "Get run status")
    public ResponseEntity<?> runStatus(@PathVariable String runId) {
        var run = toolChainRuntimeService.getRun(runId);
        if (run.isEmpty()) return ResponseEntityFactory.notFound("Run not found: " + runId);
        return ResponseEntity.ok(Map.of(
                "runId", runId,
                "status", run.get().getStatus(),
                "startedAt", run.get().getStartedAt(),
                "endedAt", run.get().getEndedAt()
        ));
    }

    @PostMapping("/runs/{runId}/rerun")
    @Operation(summary = "Re-run a previous execution")
    public ResponseEntity<?> rerun(@PathVariable String runId) {
        var run = toolChainRuntimeService.rerun(runId, securityContextService.currentUserIdOrThrow());
        return ResponseEntity.ok(Map.of("runId", run.getId(), "status", run.getStatus()));
    }

    @GetMapping("/toolchains/approvals")
    @Operation(summary = "Global pending approvals queue")
    public ResponseEntity<?> approvals() {
        return ResponseEntity.ok(Map.of("pending", toolChainRuntimeService.getPendingApprovals()));
    }

    @GetMapping("/runs/{runId}/approvals")
    @Operation(summary = "Approvals for a run")
    public ResponseEntity<?> runApprovals(@PathVariable String runId) {
        return ResponseEntity.ok(Map.of("runId", runId, "approvals", toolChainRuntimeService.getRunApprovals(runId)));
    }

    @PostMapping("/runs/{runId}/steps/{nodeId}/approve")
    @Operation(summary = "Approve pending step")
    public ResponseEntity<?> approve(@PathVariable String runId,
                                     @PathVariable String nodeId,
                                     @RequestBody(required = false) ToolChainDtos.ToolChainApprovalDecisionRequest request) {
        String comment = request == null ? null : request.getComment();
        var approvals = toolChainRuntimeService.getRunApprovals(runId).stream()
                .filter(a -> nodeId.equals(a.getNodeId()) && "pending".equalsIgnoreCase(a.getStatus()))
                .findFirst();
        if (approvals.isEmpty()) return ResponseEntityFactory.notFound("Pending approval not found for node: " + nodeId);
        toolChainRuntimeService.approve(approvals.get().getRequestId(), securityContextService.currentUserIdOrThrow(), comment);
        return ResponseEntity.ok(Map.of("ok", true, "runId", runId, "nodeId", nodeId, "status", "approved"));
    }

    @PostMapping("/runs/{runId}/steps/{nodeId}/reject")
    @Operation(summary = "Reject pending step")
    public ResponseEntity<?> reject(@PathVariable String runId,
                                    @PathVariable String nodeId,
                                    @RequestBody(required = false) ToolChainDtos.ToolChainApprovalDecisionRequest request) {
        String comment = request == null ? null : request.getComment();
        var approvals = toolChainRuntimeService.getRunApprovals(runId).stream()
                .filter(a -> nodeId.equals(a.getNodeId()) && "pending".equalsIgnoreCase(a.getStatus()))
                .findFirst();
        if (approvals.isEmpty()) return ResponseEntityFactory.notFound("Pending approval not found for node: " + nodeId);
        toolChainRuntimeService.reject(approvals.get().getRequestId(), securityContextService.currentUserIdOrThrow(), comment);
        return ResponseEntity.ok(Map.of("ok", true, "runId", runId, "nodeId", nodeId, "status", "rejected"));
    }

    @GetMapping("/toolchains/analytics")
    @Operation(summary = "Run analytics summary")
    public ResponseEntity<?> analytics(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(Map.of(
                "totalRuns", toolChainRunRepository.countAll(),
                "statusBreakdown", toolChainRunRepository.statusCounts(),
                "failureHotspots", toolChainRunStepRepository.failureHotspots(),
                "stepPerformance", toolChainRunStepRepository.performanceMetrics(),
                "latestRuns", toolChainRunRepository.findAll(limit, offset)
        ));
    }
}
