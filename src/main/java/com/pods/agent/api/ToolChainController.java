package com.pods.agent.api;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.SystemToolChainProposal;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.domain.ToolChainVersion;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.SystemToolChainProposalRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainRunStepRepository;
import com.pods.agent.service.SecurityContextService;
import com.pods.agent.service.expression.ExpressionValidator;
import com.pods.agent.service.ToolChainConfigChatService;
import com.pods.agent.service.ToolChainMappingEditorService;
import com.pods.agent.service.ToolChainRuntimeService;
import com.pods.agent.service.SystemToolChainAsyncService;
import com.pods.agent.service.ToolChainService;
import com.pods.agent.service.codeexec.CodeExecutionService;
import com.pods.agent.service.codeexec.CodeExecutionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "ToolChains", description = "Compiled ToolChain workflow APIs")
public class ToolChainController {
    private final ToolChainService toolChainService;
    private final ToolChainRuntimeService toolChainRuntimeService;
    private final ToolChainConfigChatService toolChainConfigChatService;
    private final ToolChainMappingEditorService toolChainMappingEditorService;
    private final ToolChainRunRepository toolChainRunRepository;
    private final ToolChainRunStepRepository toolChainRunStepRepository;
    private final SystemToolChainProposalRepository systemToolChainProposalRepository;
    private final SystemToolChainAsyncService systemToolChainAsyncService;
    private final SecurityContextService securityContextService;
    private final ExpressionValidator expressionValidator;
    private final CodeExecutionService codeExecutionService;
    private final ObjectMapper objectMapper;

    public ToolChainController(ToolChainService toolChainService,
                               ToolChainRuntimeService toolChainRuntimeService,
                               ToolChainConfigChatService toolChainConfigChatService,
                               ToolChainMappingEditorService toolChainMappingEditorService,
                               ToolChainRunRepository toolChainRunRepository,
                               ToolChainRunStepRepository toolChainRunStepRepository,
                               SystemToolChainProposalRepository systemToolChainProposalRepository,
                               SystemToolChainAsyncService systemToolChainAsyncService,
                               SecurityContextService securityContextService,
                               ExpressionValidator expressionValidator,
                               CodeExecutionService codeExecutionService,
                               ObjectMapper objectMapper) {
        this.toolChainService = toolChainService;
        this.toolChainRuntimeService = toolChainRuntimeService;
        this.toolChainConfigChatService = toolChainConfigChatService;
        this.toolChainMappingEditorService = toolChainMappingEditorService;
        this.toolChainRunRepository = toolChainRunRepository;
        this.toolChainRunStepRepository = toolChainRunStepRepository;
        this.systemToolChainProposalRepository = systemToolChainProposalRepository;
        this.systemToolChainAsyncService = systemToolChainAsyncService;
        this.securityContextService = securityContextService;
        this.expressionValidator = expressionValidator;
        this.codeExecutionService = codeExecutionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/toolchains")
    @Operation(summary = "List all ToolChains")
    public ResponseEntity<?> listToolChains(@RequestParam(required = false) String origin) {
        var rows = toolChainService.listAll(origin).stream().map(chain -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", chain.getId());
            row.put("name", chain.getName());
            row.put("description", chain.getDescription());
            row.put("status", chain.getStatus());
            row.put("enabled", chain.isEnabled());
            row.put("currentVersion", chain.getCurrentVersion());
            row.put("origin", chain.getOrigin());
            row.put("approvalStatus", chain.getApprovalStatus());
            row.put("approvedBy", chain.getApprovedBy());
            row.put("approvedAt", chain.getApprovedAt());
            row.put("intentSignature", chain.getIntentSignature());
            row.put("structureSignature", chain.getStructureSignature());
            row.put("metadataJson", chain.getMetadataJson());
            row.put("createdBy", chain.getCreatedBy());
            row.put("createdAt", chain.getCreatedAt());
            row.put("updatedAt", chain.getUpdatedAt());
            // publishedVersion is computed separately so the listing can show
            // "Published v1" even when currentVersion has advanced to a draft v2.
            row.put("publishedVersion", toolChainService.resolveVersion(chain.getId(), null)
                    .filter(ToolChainVersion::isPublished)
                    .map(ToolChainVersion::getVersion)
                    .orElse(null));
            return row;
        }).toList();
        return ResponseEntity.ok(rows);
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

    @PostMapping("/toolchains/{id}/config-chat/stream/{sessionId}/stop")
    @Operation(summary = "Cancel an in-flight ToolChain config chat stream")
    public ResponseEntity<?> cancelConfigStream(@PathVariable String id, @PathVariable String sessionId) {
        boolean cancelled = toolChainConfigChatService.cancelStream(sessionId);
        return ResponseEntity.ok(java.util.Map.of("cancelled", cancelled, "sessionId", sessionId));
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

    @GetMapping("/toolchains/{id}/layout")
    @Operation(summary = "Get user-specific ToolChain layout (per-toolchain, per-user)")
    public ResponseEntity<?> userLayout(@PathVariable String id) {
        try {
            return ResponseEntity.ok(
                    toolChainConfigChatService.getUserLayout(id, securityContextService.currentUserIdOrThrow())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PatchMapping("/toolchains/{id}/layout")
    @Operation(summary = "Save user-specific ToolChain layout (per-toolchain, per-user)")
    public ResponseEntity<?> updateUserLayout(@PathVariable String id,
                                              @RequestBody ToolChainDtos.ToolChainConfigSessionLayoutRequest request) {
        try {
            return ResponseEntity.ok(
                    toolChainConfigChatService.upsertUserLayout(id, request, securityContextService.currentUserIdOrThrow())
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/config-sessions/{sessionId}/truncate")
    @Operation(summary = "Truncate config-session messages from the given message id onward (used by edit & resend)")
    public ResponseEntity<?> truncateConfigSession(@PathVariable String id,
                                                    @PathVariable String sessionId,
                                                    @RequestBody Map<String, Object> body) {
        try {
            String messageId = body == null ? null : String.valueOf(body.get("messageId"));
            return ResponseEntity.ok(toolChainConfigChatService.truncateFromMessage(id, sessionId, messageId));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
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
        try {
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
        } catch (IllegalStateException e) {
            return ResponseEntityFactory.resourceConflict(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/approve")
    @Operation(summary = "Approve a system-suggested ToolChain")
    public ResponseEntity<?> approveToolChain(@PathVariable String id,
                                              @RequestBody(required = false) ToolChainDtos.ToolChainApprovalDecisionRequest request) {
        String comment = request == null ? null : request.getComment();
        try {
            return ResponseEntity.ok(toolChainService.approveSystemToolChain(
                    id,
                    securityContextService.currentUserIdOrThrow(),
                    comment
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/reject")
    @Operation(summary = "Reject a system-suggested ToolChain")
    public ResponseEntity<?> rejectToolChain(@PathVariable String id,
                                             @RequestBody(required = false) ToolChainDtos.ToolChainApprovalDecisionRequest request) {
        String comment = request == null ? null : request.getComment();
        try {
            return ResponseEntity.ok(toolChainService.rejectSystemToolChain(
                    id,
                    securityContextService.currentUserIdOrThrow(),
                    comment
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/toolchains/{id}/mappings/test")
    @Operation(summary = "Evaluate a JSONata expression against this chain's recorded turn")
    public ResponseEntity<?> testMapping(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("expr")) {
            return ResponseEntityFactory.badRequest("Body must include 'expr'");
        }
        return ResponseEntity.ok(toolChainMappingEditorService.testMapping(id, body.get("expr")));
    }

    @PostMapping("/toolchains/expressions/validate")
    @Operation(summary = "Validate a boolean expression for toolchain routing and mappings")
    public ResponseEntity<?> validateExpression(@RequestBody Map<String, Object> body) {
        String expression = body == null ? null : String.valueOf(body.getOrDefault("expression", ""));
        var result = expressionValidator.validate(expression);
        return ResponseEntity.ok(Map.of(
                "valid", result.valid(),
                "error", result.error()
        ));
    }

    @PostMapping("/toolchains/code/preview")
    @Operation(summary = "Execute inline code snippet preview")
    public ResponseEntity<?> previewCode(@RequestBody ToolChainDtos.ToolChainCodePreviewRequest request) {
        if (request == null) return ResponseEntityFactory.badRequest("Request body is required.");
        CodeExecutionResult result = codeExecutionService.execute(
                request.getLanguage(),
                request.getCode(),
                request.getInput(),
                request.getTimeoutMs(),
                request.getMemoryLimitMb()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        payload.put("output", result.output());
        payload.put("stdout", result.stdout());
        payload.put("stderr", result.stderr());
        payload.put("error", result.error());
        payload.put("timedOut", result.timedOut());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/toolchains/templates")
    @Operation(summary = "List built-in toolchain templates")
    public ResponseEntity<?> listTemplates() {
        return ResponseEntity.ok(Map.of("templates", loadTemplates(false)));
    }

    @PostMapping("/toolchains/from-template")
    @Operation(summary = "Create a toolchain from a built-in template")
    public ResponseEntity<?> createFromTemplate(@RequestBody Map<String, Object> body) {
        String templateId = body == null ? null : String.valueOf(body.getOrDefault("templateId", ""));
        if (templateId == null || templateId.isBlank()) {
            return ResponseEntityFactory.badRequest("templateId is required");
        }
        Map<String, Object> selected = loadTemplates(true).stream()
                .filter(row -> templateId.equals(String.valueOf(row.get("id"))))
                .findFirst()
                .orElse(null);
        if (selected == null) return ResponseEntityFactory.notFound("Template not found: " + templateId);
        String name = body != null && body.get("name") != null
                ? String.valueOf(body.get("name"))
                : String.valueOf(selected.get("name"));
        String description = body != null && body.get("description") != null
                ? String.valueOf(body.get("description"))
                : String.valueOf(selected.get("description"));
        ToolChainDtos.ToolChainCreateRequest createReq = new ToolChainDtos.ToolChainCreateRequest();
        createReq.setName(name);
        createReq.setDescription(description);
        createReq.setEnabled(true);
        ToolChain chain = toolChainService.create(createReq, securityContextService.currentUserIdOrThrow());

        ToolChainDtos.ToolChainVersionRequest versionReq = new ToolChainDtos.ToolChainVersionRequest();
        versionReq.setGraphJson(String.valueOf(selected.get("graphJson")));
        versionReq.setResponseMode("hybrid");
        versionReq.setInputSchema("{}");
        versionReq.setOutputSchema("{}");
        toolChainService.createVersion(chain.getId(), versionReq, securityContextService.currentUserIdOrThrow());
        return ResponseEntity.ok(Map.of("toolChainId", chain.getId(), "name", chain.getName()));
    }

    private List<Map<String, Object>> loadTemplates(boolean includeGraphJson) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:toolchain-templates/*.json");
            java.util.Arrays.sort(resources, java.util.Comparator.comparing(Resource::getFilename));
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Resource resource : resources) {
                String fileName = resource.getFilename() == null ? "template.json" : resource.getFilename();
                String id = fileName.replace(".json", "");
                String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("name", parsed.getOrDefault("name", id));
                row.put("description", parsed.getOrDefault("description", ""));
                if (includeGraphJson) {
                    row.put("graphJson", objectMapper.writeValueAsString(parsed.getOrDefault("graph", Map.of())));
                }
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @PatchMapping("/toolchains/{id}/mappings/{nodeId}/{argName}")
    @Operation(summary = "Update a single argMapping on a node within the chain's current version")
    public ResponseEntity<?> updateMapping(@PathVariable String id,
                                           @PathVariable String nodeId,
                                           @PathVariable String argName,
                                           @RequestBody Map<String, Object> mapping) {
        boolean ok = toolChainMappingEditorService.updateMapping(id, nodeId, argName, mapping);
        if (!ok) return ResponseEntityFactory.badRequest("Mapping not updated. Verify nodeId and argName exist on the chain's current version.");
        return ResponseEntity.ok(Map.of("ok", true));
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

    @GetMapping("/toolchains/system-proposals")
    @Operation(summary = "List pending system toolchain proposals for current user")
    public ResponseEntity<?> listSystemToolChainProposals() {
        String userId = securityContextService.currentUserIdOrThrow();
        var proposals = systemToolChainProposalRepository.findPendingByUser(userId).stream()
                .map(this::toSystemProposalRow)
                .toList();
        return ResponseEntity.ok(Map.of("proposals", proposals));
    }

    @PostMapping("/toolchains/system-proposals/{proposalId}/approve")
    @Operation(summary = "Approve a pending system toolchain proposal")
    public ResponseEntity<?> approveSystemToolChainProposal(@PathVariable String proposalId,
                                                            @RequestBody(required = false) ToolChainDtos.ToolChainApprovalDecisionRequest request) {
        String userId = securityContextService.currentUserIdOrThrow();
        String comment = request == null ? null : request.getComment();
        var updated = systemToolChainAsyncService.approveProposal(proposalId, userId, comment);
        if (updated.isEmpty()) return ResponseEntityFactory.notFound("Proposal not found: " + proposalId);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "proposal", toSystemProposalRow(updated.get())
        ));
    }

    @PostMapping("/toolchains/system-proposals/{proposalId}/reject")
    @Operation(summary = "Reject a pending system toolchain proposal")
    public ResponseEntity<?> rejectSystemToolChainProposal(@PathVariable String proposalId,
                                                           @RequestBody(required = false) ToolChainDtos.ToolChainApprovalDecisionRequest request) {
        String userId = securityContextService.currentUserIdOrThrow();
        String comment = request == null ? null : request.getComment();
        var updated = systemToolChainAsyncService.rejectProposal(proposalId, userId, comment);
        if (updated.isEmpty()) return ResponseEntityFactory.notFound("Proposal not found: " + proposalId);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "proposal", toSystemProposalRow(updated.get())
        ));
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

    private Map<String, Object> toSystemProposalRow(SystemToolChainProposal proposal) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", proposal.getId());
        row.put("sessionId", proposal.getSessionId());
        row.put("turnId", proposal.getTurnId());
        row.put("status", proposal.getStatus());
        row.put("reason", proposal.getReason() == null ? "" : proposal.getReason());
        row.put("confidence", proposal.getConfidence() == null ? "" : proposal.getConfidence());
        row.put("createdAt", proposal.getCreatedAt());
        return row;
    }
}
