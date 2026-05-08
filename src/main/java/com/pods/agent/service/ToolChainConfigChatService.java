package com.pods.agent.service;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ChatSession;
import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.Skill;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.domain.ToolChainApproval;
import com.pods.agent.domain.ToolChainConfigLayout;
import com.pods.agent.domain.ToolChainConfigMessage;
import com.pods.agent.domain.ToolChainConfigSession;
import com.pods.agent.domain.ToolChainRun;
import com.pods.agent.domain.ToolChainUserLayout;
import com.pods.agent.domain.ToolChainVersion;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.ChatSessionRepository;
import com.pods.agent.repository.McpRegistryRepository;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.repository.ToolChainApprovalRepository;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.repository.ToolChainConfigLayoutRepository;
import com.pods.agent.repository.ToolChainConfigMessageRepository;
import com.pods.agent.repository.ToolChainConfigSessionRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainUserLayoutRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import com.pods.agent.service.workspace.WorkspaceSkillSyncService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ToolChainConfigChatService {
    private static final Set<String> RESPONSE_MODES = Set.of("hybrid", "raw_graph_output", "synthesized_text");
    private static final String TOOLCHAIN_SKILL_NAME = "toolchain-architect";
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}/]+)}");
    private static final String SYSTEM_PROMPT_FALLBACK =
            "You are a ToolChain workflow architect. Return JSON {\"assistantMessage\":...,"
                    + "\"artifactPatch\":{\"graphJson\":{\"nodes\":[],\"edges\":[]},\"intents\":[],"
                    + "\"responseMode\":\"hybrid\"}}.";

    private final ToolChainConfigSessionRepository sessionRepository;
    private final ToolChainConfigMessageRepository messageRepository;
    private final ToolChainService toolChainService;
    private final ToolChainRunRepository toolChainRunRepository;
    private final ToolChainApprovalRepository toolChainApprovalRepository;
    private final ToolChainConfigLayoutRepository toolChainConfigLayoutRepository;
    private final ToolChainUserLayoutRepository toolChainUserLayoutRepository;
    private final AgentToolRepository agentToolRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final SkillRepository skillRepository;
    private final McpRegistryRepository mcpRegistryRepository;
    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final AgentRuntimeService agentRuntimeService;
    private final PendingInteractionService pendingInteractionService;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final WorkspaceSkillSyncService workspaceSkillSyncService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final ObjectMapper objectMapper;

    public ToolChainConfigChatService(ToolChainConfigSessionRepository sessionRepository,
                                      ToolChainConfigMessageRepository messageRepository,
                                      ToolChainService toolChainService,
                                      ToolChainRunRepository toolChainRunRepository,
                                      ToolChainApprovalRepository toolChainApprovalRepository,
                                      ToolChainConfigLayoutRepository toolChainConfigLayoutRepository,
                                      ToolChainUserLayoutRepository toolChainUserLayoutRepository,
                                      AgentToolRepository agentToolRepository,
                                      ChatSessionRepository chatSessionRepository,
                                      SkillRepository skillRepository,
                                      McpRegistryRepository mcpRegistryRepository,
                                      ModelProviderRouter modelProviderRouter,
                                      SkillRegistryService skillRegistryService,
                                      AgentRuntimeService agentRuntimeService,
                                      PendingInteractionService pendingInteractionService,
                                      SessionWorkspaceService sessionWorkspaceService,
                                      WorkspaceSkillSyncService workspaceSkillSyncService,
                                      RuntimeTuningProperties runtimeTuningProperties,
                                      ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.toolChainService = toolChainService;
        this.toolChainRunRepository = toolChainRunRepository;
        this.toolChainApprovalRepository = toolChainApprovalRepository;
        this.toolChainConfigLayoutRepository = toolChainConfigLayoutRepository;
        this.toolChainUserLayoutRepository = toolChainUserLayoutRepository;
        this.agentToolRepository = agentToolRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.skillRepository = skillRepository;
        this.mcpRegistryRepository = mcpRegistryRepository;
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.agentRuntimeService = agentRuntimeService;
        this.pendingInteractionService = pendingInteractionService;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.workspaceSkillSyncService = workspaceSkillSyncService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> listSessions(String toolChainId) {
        List<ToolChainConfigSession> sessions = sessionRepository.findByToolChainId(toolChainId);
        List<Map<String, Object>> rows = sessions.stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("title", Optional.ofNullable(s.getTitle()).orElse("Untitled session"));
            row.put("status", Optional.ofNullable(s.getStatus()).orElse("draft"));
            row.put("updatedAt", s.getUpdatedAt());
            row.put("archivedAt", s.getArchivedAt());
            return row;
        }).toList();
        return Map.of("toolChainId", toolChainId, "sessions", rows);
    }

    public Map<String, Object> getSessionDetail(String toolChainId, String sessionId) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        List<ToolChainConfigMessage> messages = messageRepository.findBySessionId(sessionId);
        Map<String, Object> artifact = readMap(session.getLatestArtifactJson());
        ensureLifecycleDefaults(artifact);
        refreshRequirementFlags(artifact);
        Map<String, Object> contextBundle = loadContextBundle(toolChainId, artifact);
        artifact.put("contextBundle", contextBundle);
        session.setLatestArtifactJson(toJson(artifact));
        sessionRepository.update(session);
        Map<String, Object> pendingQuestion = readMap(session.getPendingQuestionJson());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.getId());
        payload.put("toolChainId", toolChainId);
        payload.put("status", session.getStatus());
        payload.put("phase", stringValue(artifact.get("phase")));
        payload.put("requirementsComplete", Boolean.TRUE.equals(artifact.get("requirementsComplete")));
        payload.put("synthesisPrompt", stringValue(artifact.get("synthesisPrompt")));
        payload.put("artifactSummary", buildArtifactSummary(artifact));
        // For proposal preview (awaiting_approval), surface the graph from pendingArtifactPatch
        // so the flow board renders the proposed nodes/edges next to the Approve/Request Changes card.
        String exposedGraph = "";
        if (shouldExposeGraph(artifact, pendingQuestion)) {
            exposedGraph = stringValue(artifact.get("graphJson"));
        } else if ("awaiting_approval".equals(stringValue(artifact.get("phase")))
                || "proposalDecision".equals(stringValue(pendingQuestion.get("key")))) {
            exposedGraph = resolveExposedGraphJson(artifact);
        }
        payload.put("graphJson", exposedGraph);
        payload.put("contextBundle", contextBundle);
        payload.put("pendingQuestion", pendingQuestion);
        payload.put("messages", messages.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("role", m.getRole());
            // Frontend expects "type" with values user|assistant|system; map role→type.
            row.put("type", m.getRole());
            row.put("content", m.getContent());
            row.put("createdAt", m.getCreatedAt());
            row.put("metadata", readMap(m.getMetadataJson()));
            if (m.getEventType() != null) row.put("eventType", m.getEventType());
            if (m.getEventPayload() != null) row.put("eventPayload", readMap(m.getEventPayload()));
            if (m.getRequestId() != null) row.put("requestId", m.getRequestId());
            if (m.getHitlStatus() != null) row.put("hitlStatus", m.getHitlStatus());
            if (m.getHitlResponse() != null) row.put("hitlResponse", m.getHitlResponse());
            return row;
        }).toList());
        return payload;
    }

    public Map<String, Object> getSessionLayout(String toolChainId, String sessionId, String userId) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolChainId", toolChainId);
        payload.put("sessionId", session.getId());
        payload.put("positions", Map.of());
        payload.put("viewport", Map.of());
        toolChainConfigLayoutRepository.findByScope(toolChainId, session.getId(), userId).ifPresent(layout -> {
            payload.put("positions", normalizeLayoutPositions(readMap(layout.getPositionsJson())));
            payload.put("viewport", readMap(layout.getViewportJson()));
        });
        return payload;
    }

    public Map<String, Object> upsertSessionLayout(String toolChainId,
                                                   String sessionId,
                                                   ToolChainDtos.ToolChainConfigSessionLayoutRequest request,
                                                   String userId) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        ToolChainConfigLayout layout = toolChainConfigLayoutRepository.findByScope(toolChainId, session.getId(), userId)
                .orElse(ToolChainConfigLayout.builder()
                        .toolChainId(toolChainId)
                        .sessionId(session.getId())
                        .userId(userId)
                        .build());
        boolean hasPositions = request != null && request.getPositions() != null;
        boolean hasViewport = request != null && request.getViewport() != null;
        Map<String, Object> existingPositions = normalizeLayoutPositions(readMap(layout.getPositionsJson()));
        Map<String, Object> existingViewport = readMap(layout.getViewportJson());
        Map<String, Object> positions = hasPositions
                ? normalizeLayoutPositions(readMap(request.getPositions()))
                : existingPositions;
        Map<String, Object> viewport = hasViewport
                ? readMap(request.getViewport())
                : existingViewport;
        layout.setPositionsJson(toJson(positions));
        layout.setViewportJson(toJson(viewport));
        toolChainConfigLayoutRepository.upsert(layout);

        return Map.of(
                "toolChainId", toolChainId,
                "sessionId", session.getId(),
                "positions", positions,
                "viewport", viewport
        );
    }

    public Map<String, Object> getUserLayout(String toolChainId, String userId) {
        toolChainService.getRequired(toolChainId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolChainId", toolChainId);
        payload.put("positions", Map.of());
        payload.put("viewport", Map.of());
        toolChainUserLayoutRepository.findByScope(toolChainId, userId).ifPresent(layout -> {
            payload.put("positions", normalizeLayoutPositions(readMap(layout.getPositionsJson())));
            payload.put("viewport", readMap(layout.getViewportJson()));
        });
        return payload;
    }

    public Map<String, Object> upsertUserLayout(String toolChainId,
                                                ToolChainDtos.ToolChainConfigSessionLayoutRequest request,
                                                String userId) {
        toolChainService.getRequired(toolChainId);
        ToolChainUserLayout layout = toolChainUserLayoutRepository.findByScope(toolChainId, userId)
                .orElse(ToolChainUserLayout.builder()
                        .toolChainId(toolChainId)
                        .userId(userId)
                        .build());
        boolean hasPositions = request != null && request.getPositions() != null;
        boolean hasViewport = request != null && request.getViewport() != null;
        Map<String, Object> existingPositions = normalizeLayoutPositions(readMap(layout.getPositionsJson()));
        Map<String, Object> existingViewport = readMap(layout.getViewportJson());
        Map<String, Object> positions = hasPositions
                ? normalizeLayoutPositions(readMap(request.getPositions()))
                : existingPositions;
        Map<String, Object> viewport = hasViewport
                ? readMap(request.getViewport())
                : existingViewport;
        layout.setPositionsJson(toJson(positions));
        layout.setViewportJson(toJson(viewport));
        toolChainUserLayoutRepository.upsert(layout);

        return Map.of(
                "toolChainId", toolChainId,
                "positions", positions,
                "viewport", viewport
        );
    }

    public Map<String, Object> processMessage(String toolChainId, ToolChainDtos.ToolChainConfigChatRequest request, String userId) {
        String resolvedToolChainId = resolveToolChainId(toolChainId, request, userId);
        ToolChainConfigSession session = resolveSession(resolvedToolChainId, request, userId);
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            String messageContent = request.getMessage().trim();
            if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
                List<String> names = request.getAttachments().stream()
                        .map(a -> String.valueOf(a.getOrDefault("fileName", "")))
                        .filter(s -> !s.isBlank())
                        .toList();
                if (!names.isEmpty()) {
                    messageContent = messageContent + "\n\n[Attachments: " + String.join(", ", names) + "]";
                }
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("modelSelectionMode", request.getModelSelectionMode());
            metadata.put("modelRef", request.getModelRef());
            metadata.put("attachments", request.getAttachments() == null ? List.of() : request.getAttachments());
            messageRepository.save(ToolChainConfigMessage.builder()
                    .sessionId(session.getId())
                    .role("user")
                    .content(messageContent)
                    .metadataJson(toJson(metadata))
                    .build());
        }

        Map<String, Object> artifact = new HashMap<>(readMap(session.getLatestArtifactJson()));
        ensureLifecycleDefaults(artifact);
        Map<String, Object> contextBundle = loadContextBundle(resolvedToolChainId, artifact);
        artifact.put("contextBundle", contextBundle);
        Map<String, Object> pendingQuestion = readMap(session.getPendingQuestionJson());
        String normalizedInput = normalizeInput(request, pendingQuestion);

        if (!pendingQuestion.isEmpty() && normalizedInput == null) {
            return getSessionDetail(resolvedToolChainId, session.getId());
        }

        Optional<String> contextualAnswer = buildContextualAnswer(normalizedInput, contextBundle, pendingQuestion);
        if (contextualAnswer.isPresent()) {
            messageRepository.save(ToolChainConfigMessage.builder()
                    .sessionId(session.getId())
                    .role("assistant")
                    .content(contextualAnswer.get())
                    .metadataJson(toJson(Map.of("answerType", "toolchain_context")))
                    .build());
            session.setLatestArtifactJson(toJson(artifact));
            sessionRepository.update(session);
            return getSessionDetail(resolvedToolChainId, session.getId());
        }

        String pendingKey = String.valueOf(pendingQuestion.getOrDefault("key", ""));
        boolean hasSelectedOption = request.getSelectedOptionId() != null && !request.getSelectedOptionId().isBlank();
        boolean regenerateDraft = false;
        if ("proposalDecision".equals(pendingKey) && !hasSelectedOption && request.getMessage() != null && !request.getMessage().isBlank()) {
            // Free-text while proposal decision is pending means "modify this draft".
            markPendingQuestionAnswered(session, pendingQuestion, request, normalizedInput);
            session.setPendingQuestionJson("{}");
            session.setStatus("draft");
            artifact.put("phase", "clarifying");
            artifact.put("proposalAccepted", false);
            applyInstruction(artifact, normalizedInput);
            regenerateDraft = true;
        } else if (!pendingQuestion.isEmpty() && normalizedInput != null) {
            Map<String, Object> pendingWithSelection = new LinkedHashMap<>(pendingQuestion);
            if (request.getSelectedOptionId() != null && !request.getSelectedOptionId().isBlank()) {
                pendingWithSelection.put("selectedOptionId", request.getSelectedOptionId().trim());
            }
            // Mark the persisted system-event row as resolved BEFORE applyAnswer so a reload
            // hydrates the question card collapsed with the answer instead of as still-pending.
            // This is the safety net for answers that don't go through replyToPendingStream
            // (e.g., a click on a hydrated card after the SSE stream has closed).
            markPendingQuestionAnswered(session, pendingQuestion, request, normalizedInput);
            applyAnswer(artifact, pendingWithSelection, normalizedInput);
            session.setPendingQuestionJson("{}");
            session.setStatus("draft");
            if (!"proposalDecision".equals(String.valueOf(pendingQuestion.getOrDefault("key", "")))) {
                regenerateDraft = true;
            }
            if (Boolean.TRUE.equals(artifact.get("proposalAccepted"))) {
                Map<String, Object> publishResult = publishFromArtifact(resolvedToolChainId, session, artifact, userId);
                messageRepository.save(ToolChainConfigMessage.builder()
                        .sessionId(session.getId())
                        .role("assistant")
                        .content("Done. I created and published ToolChain v" + publishResult.get("version") + ".")
                        .metadataJson("{}")
                        .build());
                sessionRepository.update(session);
                return getSessionDetail(resolvedToolChainId, session.getId());
            }
            // If the user is re-describing the workflow (graph clarification), run the AI
            // again so we produce a real graph from the new description.
            if ("graph".equals(String.valueOf(pendingQuestion.getOrDefault("key", "")))) {
                artifact.remove("lastAiSummary");
                applyInstruction(artifact, normalizedInput);
                regenerateDraft = true;
            }
        } else if (normalizedInput != null) {
            applyInstruction(artifact, normalizedInput);
            if (pendingQuestion.isEmpty()) {
                regenerateDraft = true;
            }
        }

        refreshRequirementFlags(artifact);
        if (regenerateDraft && Boolean.TRUE.equals(artifact.get("requirementsComplete"))) {
            String draftInstruction = resolveDraftInstruction(artifact, normalizedInput, pendingKey, !pendingQuestion.isEmpty());
            if (!draftInstruction.isBlank()) {
                applyAiInstruction(artifact, contextBundle, draftInstruction, request);
                if (!stringValue(artifact.get("graphJson")).isBlank()) {
                    artifact.put("draftGeneratedAt", System.currentTimeMillis());
                }
            }
        }

        Map<String, Object> nextQuestion = maybeAskClarification(artifact);
        if (!nextQuestion.isEmpty()) {
            session.setPendingQuestionJson(toJson(nextQuestion));
            session.setStatus("clarification_required");
            artifact.put("phase", "clarifying");
        } else {
            String assistantText = stringValue(artifact.get("lastAiSummary"));
            if (assistantText.isBlank()) {
                assistantText = "I prepared a draft ToolChain plan based on your inputs, tools, and skills.";
            }
            artifact.put("proposalSummary", assistantText);
            artifact.put("phase", "awaiting_acceptance");
            Map<String, Object> proposalDecisionQuestion = buildProposalDecisionQuestion(assistantText);
            session.setPendingQuestionJson(toJson(proposalDecisionQuestion));
            // Keep DB-compatible session status; use artifact.phase for finer lifecycle state.
            session.setStatus("clarification_required");
            messageRepository.save(ToolChainConfigMessage.builder()
                    .sessionId(session.getId())
                    .role("assistant")
                    .content(assistantText)
                    .metadataJson("{}")
                    .build());
        }

        session.setLatestArtifactJson(toJson(artifact));
        if (shouldAutoUpdateTitle(session)) {
            session.setTitle(deriveTitle(session, artifact));
        }
        sessionRepository.update(session);
        return getSessionDetail(resolvedToolChainId, session.getId());
    }

    public void handleStreamMessage(String toolChainId,
                                    ToolChainDtos.ToolChainConfigChatRequest request,
                                    SseEmitter emitter,
                                    String userId) {
        CompletableFuture.runAsync(() -> {
            SseEventSender sender = new SseEventSender(emitter, objectMapper);
            String registeredSessionId = null;
            try {
                String resolvedToolChainId = resolveToolChainId(toolChainId, request, userId);
                ToolChainConfigSession session = resolveSession(resolvedToolChainId, request, userId);
                persistIncomingUserMessage(session, request);

                String sessionId = session.getId();
                // Register this stream so a later POST /stream/{sessionId}/stop can complete
                // the emitter and unblock any pending HITL futures. Removed in the finally
                // block below so a clean completion doesn't leak a map entry.
                StreamHandle handle = new StreamHandle(emitter, new java.util.concurrent.atomic.AtomicBoolean(false));
                activeStreams.put(sessionId, handle);
                registeredSessionId = sessionId;
                final String finalSessionId = sessionId;
                emitter.onCompletion(() -> activeStreams.remove(finalSessionId, handle));
                emitter.onTimeout(() -> activeStreams.remove(finalSessionId, handle));
                emitter.onError(t -> activeStreams.remove(finalSessionId, handle));
                sender.setCancelledPredicate(handle.cancelled()::get);
                String emittedToolChainId = resolvedToolChainId;
                sender.sendConnected(session.getId());
                sender.send(new LinkedHashMap<>(Map.of(
                        "type", "toolchain.bound",
                        "sessionId", sessionId,
                        "toolChainId", emittedToolChainId
                )));
                Map<String, Object> fastPathDetail = processProposalDecisionIfAny(
                        emittedToolChainId,
                        session,
                        request
                );
                if (fastPathDetail != null) {
                    sender.sendStateUpdated(sessionId, buildDesignerStatePayload(fastPathDetail, emittedToolChainId));
                    Map<String, Object> donePayload = new LinkedHashMap<>();
                    donePayload.put("type", "done");
                    donePayload.put("sessionId", sessionId);
                    donePayload.put("toolChainId", emittedToolChainId);
                    String doneContent = latestAssistantMessage(fastPathDetail);
                    if (!doneContent.isBlank()) {
                        donePayload.put("content", doneContent);
                    }
                    sender.send(donePayload);
                    sender.complete();
                    return;
                }

                DesignerRuntimeStreamBridge bridge = new DesignerRuntimeStreamBridge(
                        emitter,
                        objectMapper,
                        session,
                        emittedToolChainId
                );
                String runtimeSessionId = "toolchain-config-" + sessionId;
                ensureRuntimeInteractionSession(runtimeSessionId, userId);
                AgentSession runtimeSession = hydrateRuntimeSession(runtimeSessionId, sessionId);
                ChatState state = buildDesignerRuntimeState(request);
                runtimeSession.setActiveState(state);
                var workspace = sessionWorkspaceService.getOrCreate(runtimeSessionId);
                runtimeSession.setWorkspacePath(workspace);
                workspaceSkillSyncService.sync(workspace);
                runtimeSession.beginTurn();

                String turnId = UUID.randomUUID().toString();
                String runtimeInput = buildDesignerRuntimeInput(emittedToolChainId, session, request);
                final java.nio.file.Path workspacePath = workspace;
                final ChatState runtimeState = state;
                final AgentSession[] runtimeSessionRef = { runtimeSession };
                final ToolChainConfigSession[] sessionRef = { session };
                String response = WorkspaceContextHolder.withWorkspace(workspacePath, () ->
                        UserContextHolder.withUser(userId, () ->
                                agentRuntimeService.runTurn(runtimeSessionRef[0], runtimeInput, runtimeState, bridge, turnId))
                );
                Map<String, Object> detail = applyDesignerRuntimeResponse(
                        emittedToolChainId,
                        sessionRef[0],
                        response,
                        bridge.latestQuestion()
                );
                detail = enforceClarificationLadder(emittedToolChainId, sessionRef[0], detail);
                sessionRef[0] = sessionRepository.findById(sessionId).orElse(sessionRef[0]);

                // Multi-turn clarification loop: keep the SAME SseEmitter open across
                // question → answer → continuation cycles so the user perceives one
                // continuous turn. We block on PendingInteractionService.awaitReply
                // between LLM turns; the frontend's /reply endpoint signals the future
                // via pendingInteractionService.reply(...) and short-circuits because
                // noActiveStream:false (no second SSE connection is opened).
                while (true) {
                    Map<String, Object> pq = readMap(detail.get("pendingQuestion"));
                    if (pq.isEmpty()) break;
                    String pendingKey = stringValue(pq.get("key"));

                    sender.sendStateUpdated(sessionId, buildDesignerStatePayload(detail, emittedToolChainId));

                    String questionId = stringValue(pq.get("id"));
                    if (questionId.isBlank()) {
                        questionId = UUID.randomUUID().toString();
                    }

                    List<Map<String, Object>> rawOptions = readMapList(pq.get("options"));
                    List<PendingInteractionService.QuestionOption> hitlOptions = rawOptions.stream()
                            .map(opt -> {
                                String optId = stringValue(opt.get("id"));
                                String label = stringValue(opt.get("label"));
                                return (optId.isBlank() || label.isBlank())
                                        ? null
                                        : new PendingInteractionService.QuestionOption(optId, label);
                            })
                            .filter(Objects::nonNull)
                            .toList();
                    PendingInteractionService.QuestionMetadata hitlMetadata = new PendingInteractionService.QuestionMetadata(
                            hitlOptions.isEmpty() ? "text" : "single_select",
                            hitlOptions,
                            true,
                            hitlOptions.isEmpty() ? null : 1,
                            hitlOptions.isEmpty() ? null : 1
                    );

                    // Register against the runtimeSessionId (the row registered in
                    // chat_sessions by ensureRuntimeInteractionSession) — hitl_interactions
                    // has a foreign key to chat_sessions and the bare config session id is
                    // not present there.
                    String registeredId = pendingInteractionService.create(
                            runtimeSessionId,
                            turnId,
                            "question",
                            stringValue(pq.get("question")),
                            hitlMetadata,
                            questionId
                    );

                    PendingInteractionService.InteractionReply reply;
                    try {
                        reply = pendingInteractionService.awaitReply(
                                registeredId,
                                runtimeTuningProperties.getHitlReplyTimeoutMs()
                        );
                    } catch (TimeoutException te) {
                        sender.sendError("Clarification timed out — no answer received in time.");
                        sender.complete();
                        return;
                    }
                    if (reply == null || reply.message() == null) break;

                    String rawMessage = reply.message();
                    String optionsCsv = "";
                    String typedText = rawMessage;
                    if (rawMessage.startsWith("options=")) {
                        int semi = rawMessage.indexOf(';');
                        optionsCsv = (semi >= 0
                                ? rawMessage.substring("options=".length(), semi)
                                : rawMessage.substring("options=".length())).trim();
                        if (semi >= 0) {
                            String tail = rawMessage.substring(semi + 1).trim();
                            typedText = tail.startsWith("message=") ? tail.substring("message=".length()).trim() : "";
                        } else {
                            typedText = "";
                        }
                    }
                    String selectedOptionId = optionsCsv.isBlank()
                            ? null
                            : (optionsCsv.contains(",") ? optionsCsv.split(",")[0].trim() : optionsCsv);
                    String selectedOptionLabel = null;
                    if (selectedOptionId != null) {
                        for (Map<String, Object> opt : rawOptions) {
                            if (selectedOptionId.equals(stringValue(opt.get("id")))) {
                                selectedOptionLabel = stringValue(opt.get("label"));
                                break;
                            }
                        }
                    }

                    sessionRef[0] = sessionRepository.findById(sessionId).orElseThrow(() ->
                            new IllegalStateException("Config session disappeared during clarification: " + sessionId));

                    ToolChainDtos.ToolChainConfigChatRequest replyRequest = new ToolChainDtos.ToolChainConfigChatRequest();
                    replyRequest.setSessionId(sessionId);
                    replyRequest.setToolChainId(emittedToolChainId);
                    replyRequest.setRequestId(registeredId);
                    replyRequest.setSelectedOptionId(selectedOptionId);
                    replyRequest.setSelectedOptionLabel(selectedOptionLabel);
                    replyRequest.setAnswerText(typedText.isBlank() ? null : typedText);
                    replyRequest.setModelSelectionMode(request.getModelSelectionMode());
                    replyRequest.setModelRef(request.getModelRef());

                    bridge.clearLatestQuestion();

                    if ("proposalDecision".equalsIgnoreCase(pendingKey)) {
                        // User clicked Approve / Request Changes. Run the proposal handler;
                        // approval applies the artifact patch and clears pendingQuestionJson,
                        // change-request resets to draft so the next LLM turn solicits new
                        // requirements.
                        Map<String, Object> approvalDetail = processProposalDecisionIfAny(
                                emittedToolChainId, sessionRef[0], replyRequest);
                        if (approvalDetail != null) {
                            detail = approvalDetail;
                            continue;
                        }
                        sessionRef[0] = sessionRepository.findById(sessionId).orElseThrow(() ->
                                new IllegalStateException("Config session disappeared during change request: " + sessionId));
                    } else {
                        // Clarification reply — defensively mirror what /reply does. Also apply
                        // the answer to the artifact (responseMode / approvalPolicy / etc.) so
                        // the dimension is deterministically captured before the next LLM turn,
                        // not relying on the LLM to infer it from the answer text alone.
                        Map<String, Object> answeredQuestion = new LinkedHashMap<>(pq);
                        if (selectedOptionId != null && !selectedOptionId.isBlank()) {
                            answeredQuestion.put("selectedOptionId", selectedOptionId);
                        }
                        markPendingQuestionAnswered(sessionRef[0], answeredQuestion, replyRequest, rawMessage);
                        Map<String, Object> currentArtifact = new LinkedHashMap<>(readMap(sessionRef[0].getLatestArtifactJson()));
                        applyAnswer(currentArtifact, answeredQuestion, replyRequest.getAnswerText() != null
                                ? replyRequest.getAnswerText()
                                : (selectedOptionLabel != null ? selectedOptionLabel : (selectedOptionId != null ? selectedOptionId : rawMessage)));
                        refreshRequirementFlags(currentArtifact);
                        sessionRef[0].setLatestArtifactJson(toJson(currentArtifact));
                        sessionRef[0].setPendingQuestionJson("{}");
                        if ("clarification_required".equalsIgnoreCase(sessionRef[0].getStatus())) {
                            sessionRef[0].setStatus("draft");
                        }
                        sessionRepository.update(sessionRef[0]);
                    }

                    String nextRuntimeInput = buildDesignerRuntimeInput(emittedToolChainId, sessionRef[0], replyRequest);
                    runtimeSessionRef[0] = hydrateRuntimeSession(runtimeSessionId, sessionId);
                    runtimeSessionRef[0].setActiveState(runtimeState);
                    runtimeSessionRef[0].setWorkspacePath(workspacePath);
                    runtimeSessionRef[0].beginTurn();

                    String nextResponse = WorkspaceContextHolder.withWorkspace(workspacePath, () ->
                            UserContextHolder.withUser(userId, () ->
                                    agentRuntimeService.runTurn(runtimeSessionRef[0], nextRuntimeInput, runtimeState, bridge, turnId))
                    );
                    detail = applyDesignerRuntimeResponse(
                            emittedToolChainId,
                            sessionRef[0],
                            nextResponse,
                            bridge.latestQuestion()
                    );
                    detail = enforceClarificationLadder(emittedToolChainId, sessionRef[0], detail);
                    sessionRef[0] = sessionRepository.findById(sessionId).orElse(sessionRef[0]);
                }

                sender.sendStateUpdated(sessionId, buildDesignerStatePayload(detail, emittedToolChainId));
                Map<String, Object> donePayload = new LinkedHashMap<>();
                donePayload.put("type", "done");
                donePayload.put("sessionId", sessionId);
                donePayload.put("toolChainId", emittedToolChainId);
                String doneContent = latestAssistantMessage(detail);
                if (!doneContent.isBlank()) {
                    donePayload.put("content", doneContent);
                }
                sender.send(donePayload);
                sender.complete();
            } catch (Exception e) {
                sender.sendError(e.getMessage() == null ? "ToolChain config stream failed" : e.getMessage());
                sender.complete();
            } finally {
                if (registeredSessionId != null) {
                    activeStreams.remove(registeredSessionId);
                }
            }
        });
    }

    /**
     * Cancel an in-flight stream for the given config session. Sets the cancellation
     * flag so SseEventSender stops emitting, completes the emitter to drop the HTTP
     * connection, and resolves any blocking HITL futures so the worker can unwind.
     * Returns true if a stream was found and cancelled.
     */
    public boolean cancelStream(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        StreamHandle handle = activeStreams.remove(sessionId);
        if (handle == null) return false;
        handle.cancelled().set(true);
        // Wake any awaitReply() futures so the worker thread unwinds.
        try {
            pendingInteractionService.cancelBySession("toolchain-config-" + sessionId);
        } catch (Exception ignored) {
        }
        try {
            handle.emitter().complete();
        } catch (Exception ignored) {
        }
        log.info("[ToolChainConfigChatService] Cancelled stream for session {}", sessionId);
        return true;
    }

    private record StreamHandle(SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean cancelled) {}

    private final java.util.concurrent.ConcurrentMap<String, StreamHandle> activeStreams =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, Object> replyToPendingStream(String toolChainId,
                                                    String sessionId,
                                                    ToolChainDtos.ToolChainConfigChatRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        ToolChainConfigSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Config session not found: " + sessionId));
        if (toolChainId != null && !toolChainId.isBlank() && !toolChainId.equals(session.getToolChainId())) {
            throw new IllegalArgumentException("Config session does not belong to toolchain: " + toolChainId);
        }
        ToolChainDtos.ToolChainConfigChatRequest payload =
                request == null ? new ToolChainDtos.ToolChainConfigChatRequest() : request;
        payload.setSessionId(sessionId);
        payload.setToolChainId(session.getToolChainId());

        Map<String, Object> pendingQuestion = readMap(session.getPendingQuestionJson());
        String requestId = firstNonBlank(payload.getRequestId(), stringValue(pendingQuestion.get("id")));
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        List<String> selected = payload.getSelectedOptionId() == null || payload.getSelectedOptionId().isBlank()
                ? List.of()
                : List.of(payload.getSelectedOptionId().trim());
        String responseText = firstNonBlank(
                payload.getSelectedOptionLabel(),
                payload.getAnswerText(),
                payload.getSelectedOptionId()
        );
        boolean noActiveStream = false;
        try {
            pendingInteractionService.reply(requestId, "reply", responseText, selected);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("no pending future") || message.contains("pending interaction not found")) {
                String pendingId = stringValue(pendingQuestion.get("id"));
                if (pendingId.isBlank() || !requestId.equals(pendingId)) {
                    throw new IllegalStateException("No active stream is waiting for this session reply");
                }
                noActiveStream = true;
            } else {
                throw e;
            }
        } catch (IllegalStateException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("no pending future") || message.contains("pending interaction not found")) {
                String pendingId = stringValue(pendingQuestion.get("id"));
                if (pendingId.isBlank() || !requestId.equals(pendingId)) {
                    throw new IllegalStateException("No active stream is waiting for this session reply");
                }
                noActiveStream = true;
            } else {
                throw new IllegalStateException("No active stream is waiting for this session reply");
            }
        }
        payload.setRequestId(requestId);
        markPendingQuestionAnswered(session, pendingQuestion, payload, responseText);
        session.setPendingQuestionJson("{}");
        if ("clarification_required".equalsIgnoreCase(session.getStatus())) {
            session.setStatus("draft");
        }
        sessionRepository.update(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", sessionId);
        out.put("noActiveStream", noActiveStream);
        return out;
    }

    /**
     * Resolve the persisted system-event row for the pending question to
     * {@code hitl_status="answered"} with the user's response text. Path-agnostic:
     * works whether the answer arrives via {@code /reply} (active stream) or via
     * a fresh {@code /config-chat/stream} POST after a reload.
     *
     * Prefers {@code request.requestId} when present; otherwise falls back to the
     * id from {@code session.pendingQuestionJson}, which is always set when there
     * is a pending question.
     */
    private void markPendingQuestionAnswered(ToolChainConfigSession session,
                                             Map<String, Object> pendingQuestion,
                                             ToolChainDtos.ToolChainConfigChatRequest request,
                                             String responseText) {
        if (session == null || session.getId() == null) return;
        String requestId = request != null ? request.getRequestId() : null;
        if (requestId == null || requestId.isBlank()) {
            requestId = pendingQuestion == null ? null : stringValue(pendingQuestion.get("id"));
        }
        if (requestId == null || requestId.isBlank()) return;
        String resolved = responseText;
        if (resolved == null || resolved.isBlank()) {
            if (request != null) {
                resolved = firstNonBlank(request.getSelectedOptionLabel(), request.getAnswerText(), request.getSelectedOptionId());
            }
        }
        try {
            messageRepository.updateHitlByRequestId(session.getId(), requestId, "answered", resolved);
        } catch (Exception ex) {
            log.warn("Failed to update HITL status for session={} requestId={}: {}", session.getId(), requestId, ex.getMessage());
        }
    }

    private void persistSystemMessage(String sessionId,
                                      String eventType,
                                      String requestId,
                                      String content,
                                      Map<String, Object> eventPayload,
                                      String hitlStatus,
                                      String hitlResponse) {
        try {
            messageRepository.save(ToolChainConfigMessage.builder()
                    .sessionId(sessionId)
                    .role("system")
                    .content(content == null ? "" : content)
                    .metadataJson("{}")
                    .eventType(eventType)
                    .eventPayload(eventPayload == null ? null : toJson(eventPayload))
                    .requestId(requestId)
                    .hitlStatus(hitlStatus)
                    .hitlResponse(hitlResponse)
                    .build());
        } catch (Exception ex) {
            // Bumped to error+stack so a missed schema migration (e.g. role CHECK
            // constraint not yet allowing 'system') is loud instead of silent.
            log.error("Failed to persist toolchain config system event sessionId={} eventType={} requestId={}",
                    sessionId, eventType, requestId, ex);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private AgentSession hydrateRuntimeSession(String runtimeSessionId, String configSessionId) {
        AgentSession runtimeSession = new AgentSession(runtimeSessionId);
        List<Message> history = runtimeSession.getMessages();
        for (ToolChainConfigMessage message : messageRepository.findBySessionId(configSessionId)) {
            String role = message.getRole() == null ? "" : message.getRole().toLowerCase(Locale.ROOT);
            String content = message.getContent() == null ? "" : message.getContent();
            if ("user".equals(role)) {
                if (!content.isBlank()) history.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                if (!content.isBlank()) history.add(new AssistantMessage(content));
            } else if ("system".equals(role) && "question".equalsIgnoreCase(message.getEventType())) {
                // Surface clarification questions and their answers to the LLM as an
                // assistant/user pair so the next turn knows what was asked + what the
                // user picked, and doesn't re-ask the same dimension.
                if (!content.isBlank()) {
                    history.add(new AssistantMessage(content));
                }
                if ("answered".equalsIgnoreCase(message.getHitlStatus())) {
                    String response = message.getHitlResponse();
                    if (response != null && !response.isBlank()) {
                        history.add(new UserMessage(response));
                    }
                }
            }
        }
        // Anthropic disallows two consecutive user messages; the runtime is about to
        // append a fresh UserMessage with the new turn's contract-wrapped input. Strip
        // any trailing UserMessage so the wire-level history alternates correctly.
        while (!history.isEmpty() && history.get(history.size() - 1) instanceof UserMessage) {
            history.remove(history.size() - 1);
        }
        return runtimeSession;
    }

    private void ensureRuntimeInteractionSession(String runtimeSessionId, String userId) {
        if (runtimeSessionId == null || runtimeSessionId.isBlank()) return;
        if (chatSessionRepository.findById(runtimeSessionId).isPresent()) return;
        long now = System.currentTimeMillis();
        try {
            chatSessionRepository.save(ChatSession.builder()
                    .sessionId(runtimeSessionId)
                    .userId(userId == null || userId.isBlank() ? null : userId)
                    .createdAt(now)
                    .lastActive(now)
                    .title("ToolChain Designer Runtime")
                    .build());
        } catch (Exception ignored) {
            // Concurrent stream starts may race on first insert. We'll verify existence below.
        }
        if (chatSessionRepository.findById(runtimeSessionId).isEmpty()) {
            throw new IllegalStateException("Failed to initialize runtime chat session for toolchain designer");
        }
    }

    private ChatState buildDesignerRuntimeState(ToolChainDtos.ToolChainConfigChatRequest request) {
        ChatState state = new ChatState();
        state.setRuntimeMode("toolchain_designer");
        state.setModelSelectionMode(request == null ? null : request.getModelSelectionMode());
        if (request != null && request.getModelRef() != null) {
            String providerId = request.getModelRef().get("providerID");
            String modelId = request.getModelRef().get("modelID");
            if (providerId != null && !providerId.isBlank() && modelId != null && !modelId.isBlank()) {
                state.setModel(new ModelRef(providerId, modelId));
            }
        }
        return state;
    }

    private void persistIncomingUserMessage(ToolChainConfigSession session, ToolChainDtos.ToolChainConfigChatRequest request) {
        if (request == null) return;
        // Reply payloads (answerText / selectedOptionId / requestId) are recorded on the
        // HITL system message row by markPendingQuestionAnswered; persisting them again as
        // a "user" row would duplicate the answer as a separate user bubble in the chat.
        // Match processMessage(): only persist when the user typed a freeform message.
        if (request.getMessage() == null || request.getMessage().isBlank()) return;
        String messageContent = request.getMessage().trim();
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            List<String> names = request.getAttachments().stream()
                    .map(a -> String.valueOf(a.getOrDefault("fileName", "")))
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!names.isEmpty()) {
                messageContent = messageContent + "\n\n[Attachments: " + String.join(", ", names) + "]";
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("modelSelectionMode", request.getModelSelectionMode());
        metadata.put("modelRef", request.getModelRef());
        metadata.put("attachments", request.getAttachments() == null ? List.of() : request.getAttachments());
        messageRepository.save(ToolChainConfigMessage.builder()
                .sessionId(session.getId())
                .role("user")
                .content(messageContent)
                .metadataJson(toJson(metadata))
                .build());
    }

    /**
     * Read the architect-edited graph file back from the per-session workspace. Returns the
     * raw JSON text (validated as parseable) or null if the file is absent / unreadable /
     * contains invalid JSON. The caller treats null as "no VFS edit happened, fall back to
     * the model's JSON response".
     */
    private String readGraphJsonFromWorkspace(ToolChainConfigSession session) {
        if (session == null) return null;
        try {
            String runtimeSessionId = "toolchain-config-" + session.getId();
            Path workspace = sessionWorkspaceService.get(runtimeSessionId);
            if (workspace == null) return null;
            Path file = workspace.resolve(".pods-agent/toolchain.json").normalize();
            if (!file.startsWith(workspace) || !Files.exists(file)) return null;
            String raw = Files.readString(file).trim();
            if (raw.isBlank()) return null;
            // Guard against half-applied / malformed edits — only accept parseable JSON.
            Map<String, Object> parsed = readMap(raw);
            if (parsed.isEmpty()) return null;
            return raw;
        } catch (Exception e) {
            log.warn("[ToolChainConfigChatService] Failed reading edited graph file: {}", e.getMessage());
            return null;
        }
    }

    private String buildDesignerRuntimeInput(String toolChainId,
                                             ToolChainConfigSession session,
                                             ToolChainDtos.ToolChainConfigChatRequest request) {
        Map<String, Object> artifact = readMap(session.getLatestArtifactJson());
        ensureLifecycleDefaults(artifact);
        Map<String, Object> contextBundle = loadContextBundle(toolChainId, artifact);
        String userInput = firstNonBlank(
                request == null ? null : request.getMessage(),
                request == null ? null : request.getAnswerText(),
                request == null ? null : request.getSelectedOptionLabel(),
                request == null ? null : request.getSelectedOptionId()
        );
        if (userInput == null || userInput.isBlank()) {
            userInput = "Continue designing the ToolChain draft.";
        }

        // VFS-edit mode: when an existing graph is present, seed the workspace with the graph
        // file and tell the architect to mutate it via read/edit/apply_patch instead of
        // re-emitting the entire JSON. Cuts output tokens ~95% on small edits and preserves
        // node ids (which the flow board uses to keep manual layout positions).
        // A *new* session on a published chain has an empty session.latestArtifactJson, so we
        // also fall back to the chain's currently-published version's graphJson — otherwise
        // every fresh session on an existing chain would re-create the graph from scratch.
        String currentGraphJson = stringValue(artifact.get("graphJson"));
        if (currentGraphJson.isBlank() && toolChainId != null && !toolChainId.isBlank()) {
            try {
                ToolChain chain = toolChainService.getRequired(toolChainId);
                Integer activeVersion = chain.getCurrentVersion();
                if (activeVersion != null) {
                    String publishedGraph = toolChainService
                            .resolveVersion(toolChainId, activeVersion)
                            .map(ToolChainVersion::getGraphJson)
                            .orElse(null);
                    if (publishedGraph != null && !publishedGraph.isBlank()) {
                        currentGraphJson = publishedGraph;
                        // Seed the session's artifact too so subsequent turns and refetches
                        // see the working graph; otherwise the runtime would think the session
                        // is empty and miss the graph in the response payload.
                        artifact.put("graphJson", publishedGraph);
                    }
                }
            } catch (Exception e) {
                log.warn("[ToolChainConfigChatService] Could not load published graph for edit-mode seeding: {}",
                        e.getMessage());
            }
        }
        boolean editMode = !currentGraphJson.isBlank();
        if (editMode) {
            String runtimeSessionId = "toolchain-config-" + session.getId();
            Path workspace = sessionWorkspaceService.get(runtimeSessionId);
            if (workspace == null) {
                workspace = sessionWorkspaceService.getOrCreate(runtimeSessionId);
            }
            try {
                Map<String, Object> graphObj = readMap(currentGraphJson);
                String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(graphObj);
                sessionWorkspaceService.writeText(workspace, ".pods-agent/toolchain.json", pretty);
            } catch (Exception e) {
                log.warn("[ToolChainConfigChatService] Failed seeding edit-mode workspace file: {}", e.getMessage());
            }
            return """
ToolChain designer EDIT request.
The current ToolChain graph is on disk at `.pods-agent/toolchain.json` (pretty-printed JSON).

Use the `read`, `edit`, and `apply_patch` tools to modify the graph SURGICALLY.
- ALWAYS `read` the file first to see the current state — every turn, even if you
  read it last turn. The file may have been changed since.
- For a single string replacement (rename one label, change one argMappings entry),
  call `edit` with `path`, `old_text`, `new_text`. `old_text` must match the file
  byte-for-byte (including indentation) and must be unique.
- For multi-spot edits (add a node + its edges, change several labels in one shot),
  call `apply_patch` with one or more hunks in this format inside `content`:

      <<<<<<< ORIGINAL
      <exact existing text>
      =======
      <replacement text>
      >>>>>>> UPDATED

  ORIGINAL must match the file exactly. Mismatched hunks reject the whole patch.
- PRESERVE every existing node id — only rename if the user explicitly asks. Node ids
  are how the flow board keeps user-dragged positions; a renamed id loses that layout.
- PRESERVE labels, descriptions, and config of nodes the user did not ask to change.
- When ADDING a node, append to the `nodes` array AND add the matching edges
  (start → newNode and newNode → synthesis are typical for parallel-fetch chains).
- When REMOVING a node, remove from `nodes` AND delete every edge that references it.
- Do NOT `write` the entire file unless the user explicitly asked for a ground-up rewrite.

After your file edits, return ONLY this JSON in your assistant text — do NOT include
`artifactPatch.graphJson`. The runtime reads the graph back from the file you edited:

{
  "assistantMessage": "1-2 sentences naming what changed (e.g. 'Renamed Get POET Timestamps label to POET Timestamps').",
  "nextQuestion": null,
  "responseMode": "<keep current unless the user asked otherwise>",
  "synthesisPrompt": "<keep current unless the user asked otherwise>",
  "intents": ["..."],
  "inputSchema": {"type":"object"},
  "outputSchema": {"type":"object"},
  "ragConfig": {}
}

If the user's request is ambiguous, do NOT edit the file — emit `nextQuestion` only and
the runtime will ask the user. Your file edits commit immediately, so be sure first.

ToolChain context:
%s

Designer instruction:
%s
""".formatted(toJson(contextBundle), userInput.trim());
        }

        // Creation mode (initial draft, no existing graph) — full-JSON contract is fine here.
        return """
ToolChain designer request payload.
You must either return a reviewable English plan or a clarification question.

Return ONLY JSON:
{
  "assistantMessage": "string",
  "plan": "english plan summary in plain language",
  "artifactPatch": {
    "graphJson": {"nodes":[],"edges":[],"description":""},
    "inputSchema": {"type":"object"},
    "outputSchema": {"type":"object"},
    "intents": [],
    "responseMode": "hybrid|raw_graph_output|synthesized_text",
    "synthesisPrompt": "",
    "ragConfig": {}
  },
  "nextQuestion": {
    "id": "optional-request-id",
    "key": "short_machine_key",
    "question": "question text",
    "options": [{"id":"option_id","label":"Option label"}]
  }
}

Rules:
- If enough information exists, produce `plan` and `artifactPatch`; do not ask proposal approval yourself.
- If information is missing, produce nextQuestion only; the question text/options must be generated by you.
- Never fabricate tool names.
- Keep artifactPatch fields minimal but valid.

ToolChain context:
%s

Current draft artifact:
%s

Designer instruction:
%s
""".formatted(toJson(contextBundle), toJson(artifact), userInput.trim());
    }

    private Map<String, Object> applyDesignerRuntimeResponse(String toolChainId,
                                                             ToolChainConfigSession session,
                                                             String rawResponse,
                                                             Map<String, Object> streamedQuestion) {
        Map<String, Object> artifact = new LinkedHashMap<>(readMap(session.getLatestArtifactJson()));
        ensureLifecycleDefaults(artifact);
        Map<String, Object> contextBundle = loadContextBundle(toolChainId, artifact);
        artifact.put("contextBundle", contextBundle);

        String parsedJson = extractJsonObject(rawResponse == null ? "" : rawResponse);
        Map<String, Object> parsed = readMap(parsedJson);
        Map<String, Object> patch = readMap(parsed.get("artifactPatch"));

        // VFS-edit round-trip: if the architect just edited the on-disk graph file via the
        // read/edit/apply_patch tools, prefer that over any artifactPatch.graphJson in the
        // response. In edit mode the response also carries side fields at the top level
        // (responseMode, synthesisPrompt, schemas, intents, ragConfig) — promote them into
        // the patch so applyArtifactPatch handles them uniformly.
        String graphFromFile = readGraphJsonFromWorkspace(session);
        boolean appliedFromFile = false;
        if (graphFromFile != null && !graphFromFile.isBlank()) {
            if (patch == null || patch.isEmpty()) {
                patch = new LinkedHashMap<>();
            } else {
                patch = new LinkedHashMap<>(patch);
            }
            patch.put("graphJson", graphFromFile);
            for (String key : List.of("responseMode", "synthesisPrompt", "intents",
                                       "inputSchema", "outputSchema", "ragConfig")) {
                if (parsed.containsKey(key) && !patch.containsKey(key)) {
                    patch.put(key, parsed.get(key));
                }
            }
            appliedFromFile = true;
        }

        // Edit-mode direct commit: when this is an edit on an already-published chain
        // (currentVersion != null), the user typed a clear edit instruction ("rename X
        // to Y") and we round-tripped through the file — there is nothing to "approve"
        // a second time. Apply graphJson straight to the artifact and skip the
        // proposalDecision gate so the flow board updates the moment the stream ends.
        boolean editModeDirectCommit = false;
        if (appliedFromFile && toolChainId != null && !toolChainId.isBlank()) {
            try {
                ToolChain chain = toolChainService.getRequired(toolChainId);
                editModeDirectCommit = chain.getCurrentVersion() != null;
            } catch (Exception ignored) {
                editModeDirectCommit = false;
            }
        }

        String assistantText = firstNonBlank(
                stringValue(parsed.get("assistantMessage")),
                stringValue(parsed.get("plan")),
                appliedFromFile ? "ToolChain updated." : rawResponse
        );
        if (assistantText == null) assistantText = "";
        boolean persistAssistantBubble = true;

        Map<String, Object> nextQuestion = !streamedQuestion.isEmpty()
                ? normalizeDesignerQuestion(streamedQuestion)
                : normalizeDesignerQuestion(readMap(parsed.get("nextQuestion")));
        if (!nextQuestion.isEmpty()) {
            // Clarification question path: the question card carries the ask, so the
            // separate "lead-in" assistant bubble is redundant restatement of the same
            // ask. Drop it so the transcript is a single container per turn.
            persistAssistantBubble = false;
            session.setPendingQuestionJson(toJson(nextQuestion));
            session.setStatus("clarification_required");
            artifact.put("phase", "clarifying");
            if (streamedQuestion.isEmpty()) {
                persistSystemMessage(
                        session.getId(),
                        "question",
                        stringValue(nextQuestion.get("id")),
                        stringValue(nextQuestion.get("question")),
                        Map.of("metadata", Map.of("options", nextQuestion.getOrDefault("options", List.of())), "key", nextQuestion.getOrDefault("key", "designer")),
                        "pending",
                        null
                );
            }
            artifact.remove("pendingArtifactPatch");
        } else if (editModeDirectCommit && !patch.isEmpty()) {
            // Edits to an existing published chain: commit immediately. No approval card.
            applyArtifactPatch(artifact, patch);
            artifact.remove("pendingArtifactPatch");
            artifact.put("phase", "clarifying");
            artifact.put("requirementsComplete", true);
            // Marker for refreshRequirementFlags so the flag survives recomputation in a
            // fresh edit session that lacks the creation-ladder dimensions.
            artifact.put("editCommitted", true);
            session.setPendingQuestionJson("{}");
            session.setStatus("draft");
            // Materialise the edit as a versioned draft row so the Versions panel
            // shows the in-flight changes and the user has an audit trail to publish
            // or roll back to. Reuses any existing unpublished draft head — only
            // creates a new version row on the FIRST edit after a publish.
            try {
                String userId = UserContextHolder.currentUserId();
                ToolChainDtos.ToolChainVersionRequest req = buildVersionRequestFromArtifact(artifact);
                toolChainService.upsertDraftVersion(toolChainId, req, userId);
            } catch (Exception e) {
                log.warn("[ToolChainConfigChatService] Failed to upsert draft version after edit: {}", e.getMessage());
            }
        } else {
            if (!patch.isEmpty()) {
                Map<String, Object> clarificationGateQuestion = buildClarificationGateQuestion(session);
                if (!clarificationGateQuestion.isEmpty()) {
                    session.setPendingQuestionJson(toJson(clarificationGateQuestion));
                    session.setStatus("clarification_required");
                    artifact.put("phase", "clarifying");
                    artifact.remove("pendingArtifactPatch");
                    persistSystemMessage(
                            session.getId(),
                            "question",
                            stringValue(clarificationGateQuestion.get("id")),
                            stringValue(clarificationGateQuestion.get("question")),
                            Map.of("metadata", Map.of("options", clarificationGateQuestion.getOrDefault("options", List.of())),
                                    "key", clarificationGateQuestion.getOrDefault("key", "designer_requirements_gate")),
                            "pending",
                            null
                    );
                } else {
                    // Proposal-decision path: the plan is embedded into the question card
                    // text via buildProposalDecisionQuestion(assistantText), so the standalone
                    // assistant bubble would only duplicate it. Persist the card itself so
                    // it survives the post-done refetch and renders in the transcript as an
                    // answered card after the user clicks Approve / Request Changes.
                    persistAssistantBubble = false;
                    Map<String, Object> proposalQuestion = buildProposalDecisionQuestion(assistantText);
                    artifact.put("pendingArtifactPatch", patch);
                    artifact.put("proposalSummary", assistantText);
                    artifact.put("phase", "awaiting_approval");
                    artifact.put("requirementsComplete", true);
                    session.setPendingQuestionJson(toJson(proposalQuestion));
                    session.setStatus("clarification_required");
                    persistSystemMessage(
                            session.getId(),
                            "question",
                            stringValue(proposalQuestion.get("id")),
                            stringValue(proposalQuestion.get("question")),
                            Map.of("metadata", Map.of("options", proposalQuestion.getOrDefault("options", List.of())),
                                    "key", proposalQuestion.getOrDefault("key", "proposalDecision")),
                            "pending",
                            null
                    );
                }
            } else {
                session.setStatus("draft");
                artifact.put("phase", "clarifying");
                session.setPendingQuestionJson("{}");
            }
        }

        if (persistAssistantBubble) {
            messageRepository.save(ToolChainConfigMessage.builder()
                    .sessionId(session.getId())
                    .role("assistant")
                    .content(assistantText)
                    .metadataJson("{}")
                    .build());
        }
        refreshRequirementFlags(artifact);

        artifact.remove("contextBundle");
        session.setLatestArtifactJson(toJson(artifact));
        if (shouldAutoUpdateTitle(session)) {
            session.setTitle(deriveTitle(session, artifact));
        }
        sessionRepository.update(session);
        return getSessionDetail(toolChainId, session.getId());
    }

    private Map<String, Object> buildDesignerStatePayload(Map<String, Object> detail, String toolChainId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", String.valueOf(detail.getOrDefault("status", "draft")));
        payload.put("phase", String.valueOf(detail.getOrDefault("phase", "clarifying")));
        payload.put("requirementsComplete", Boolean.TRUE.equals(detail.get("requirementsComplete")));
        payload.put("toolChainId", toolChainId);
        payload.put("pendingQuestion", readMap(detail.get("pendingQuestion")));
        payload.put("artifactSummary", String.valueOf(detail.getOrDefault("artifactSummary", "")));
        payload.put("graphJson", detail.get("graphJson"));
        payload.put("graphAvailable", detail.get("graphJson") != null && !String.valueOf(detail.get("graphJson")).isBlank());
        return payload;
    }

    private Map<String, Object> normalizeDesignerQuestion(Map<String, Object> rawQuestion) {
        if (rawQuestion == null || rawQuestion.isEmpty()) return Map.of();
        String question = stringValue(rawQuestion.get("question"));
        if (question.isBlank()) return Map.of();
        String id = stringValue(rawQuestion.get("id"));
        String key = stringValue(rawQuestion.get("key"));
        if (id.isBlank()) id = UUID.randomUUID().toString();
        if (key.isBlank()) key = "designer_question";
        List<Map<String, Object>> options = readMapList(rawQuestion.get("options")).stream()
                .map(opt -> {
                    String optId = stringValue(opt.get("id"));
                    String label = stringValue(opt.get("label"));
                    if (optId.isBlank() || label.isBlank()) return null;
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("id", optId);
                    option.put("label", label);
                    return option;
                })
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("key", key);
        normalized.put("question", question);
        normalized.put("options", options);
        return normalized;
    }

    private Map<String, Object> buildClarificationGateQuestion(ToolChainConfigSession session) {
        List<ToolChainConfigMessage> history = messageRepository.findBySessionId(session.getId());
        if (history.isEmpty()) return Map.of();
        boolean alreadyAskedDesignerQuestion = history.stream()
                .filter(m -> "system".equalsIgnoreCase(m.getRole()))
                .filter(m -> "question".equalsIgnoreCase(m.getEventType()))
                .map(ToolChainConfigMessage::getEventPayload)
                .map(this::readMap)
                .map(payload -> stringValue(payload.get("key")))
                .anyMatch(key -> !"proposalDecision".equalsIgnoreCase(key));
        if (alreadyAskedDesignerQuestion) {
            return Map.of();
        }
        String combinedUserText = history.stream()
                .filter(m -> "user".equalsIgnoreCase(m.getRole()))
                .map(ToolChainConfigMessage::getContent)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        if (combinedUserText.isBlank()) {
            return Map.of();
        }
        int wordCount = combinedUserText.split("\\s+").length;
        boolean hasScope = wordCount >= 6 || containsAny(combinedUserText,
                "workflow", "flow", "process", "pipeline", "validate", "approve", "route", "sync");
        boolean hasIdentifiers = containsAny(combinedUserText,
                "id", "ids", "input", "inputs", "field", "fields", "payload", "order", "customer",
                "email", "sku", "amount", "api", "endpoint", "table", "dataset");
        boolean hasOutputExpectation = containsAny(combinedUserText,
                "output", "outputs", "response", "result", "return", "status", "summary",
                "json", "schema", "notify", "notification", "webhook");
        if (hasScope && hasIdentifiers && hasOutputExpectation) {
            return Map.of();
        }
        List<String> gaps = new ArrayList<>();
        if (!hasScope) gaps.add("what workflow scope should be covered");
        if (!hasIdentifiers) gaps.add("which required identifiers/inputs the workflow needs");
        if (!hasOutputExpectation) gaps.add("what output/response should be produced");
        String question = "Before I generate the proposal, please clarify "
                + String.join(", and ", gaps)
                + ".";
        return normalizeDesignerQuestion(Map.of(
                "id", UUID.randomUUID().toString(),
                "key", "designer_requirements_gate",
                "question", question,
                "options", List.of()
        ));
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank() || tokens == null || tokens.length == 0) return false;
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private final class DesignerRuntimeStreamBridge extends SseEventSender {
        private final ToolChainConfigSession session;
        private final String toolChainId;
        private Map<String, Object> latestQuestion = Map.of();
        private final StringBuilder reasoningBuffer = new StringBuilder();
        private boolean reasoningPersisted = false;
        // For live graph streaming: buffer the LLM's structured-JSON text deltas
        // and progressively emit state.updated events as new nodes/edges complete.
        private final StringBuilder rawTextBuffer = new StringBuilder();
        private int lastEmittedNodeCount = 0;
        private int lastEmittedEdgeCount = 0;
        private long lastPartialEmitMs = 0;

        private DesignerRuntimeStreamBridge(SseEmitter emitter,
                                            ObjectMapper mapper,
                                            ToolChainConfigSession session,
                                            String toolChainId) {
            super(emitter, mapper);
            this.session = session;
            this.toolChainId = toolChainId;
        }

        @Override
        public void sendStateUpdated(String sessionId, Object state) {
            // Suppress generic planner state updates; the ToolChain designer
            // consumes ToolChain-specific state payloads emitted after artifact sync.
        }

        @Override
        public void sendQuestion(String sessionId, String requestId, String question, Object metadata) {
            String configSessionId = session.getId();
            super.sendQuestion(configSessionId, requestId, question, metadata);
            Map<String, Object> meta = metadataToMap(metadata);
            Map<String, Object> rawQuestion = new LinkedHashMap<>();
            rawQuestion.put("id", requestId == null ? UUID.randomUUID().toString() : requestId);
            rawQuestion.put("key", "designer_question");
            rawQuestion.put("question", question == null ? "" : question);
            rawQuestion.put("options", meta.getOrDefault("options", List.of()));
            Map<String, Object> questionPayload = normalizeDesignerQuestion(rawQuestion);
            latestQuestion = questionPayload;
            session.setPendingQuestionJson(toJson(questionPayload));
            session.setStatus("clarification_required");
            sessionRepository.update(session);
            persistSystemMessage(
                    configSessionId,
                    "question",
                    requestId,
                    question,
                    Map.of("metadata", meta, "key", "designer_question", "toolChainId", toolChainId),
                    "pending",
                    null
            );
        }

        @Override
        public void sendQuestion(String sessionId, String requestId, String question) {
            sendQuestion(sessionId, requestId, question, Map.of("options", List.of()));
        }

        @Override
        public void sendReasoningDelta(String content) {
            String safeContent = content == null ? "" : content;
            super.sendReasoningDelta(safeContent);
            if (!safeContent.isBlank()) {
                reasoningBuffer.append(safeContent);
            }
        }

        @Override
        public void sendTextDelta(String content) {
            // Designer mode asks the model to emit structured JSON; streaming that
            // raw JSON into assistant bubbles causes split/duplicate same-turn UI.
            // We swallow the raw deltas (no super call) but DO buffer them so we can
            // progressively extract finished nodes/edges from the partial JSON and
            // push them to the flow board as a live preview, rather than dumping the
            // entire graph at task.done.
            if (content == null || content.isEmpty()) return;
            rawTextBuffer.append(content);

            long now = System.currentTimeMillis();
            if (now - lastPartialEmitMs < 200) return;

            Map<String, Object> partialGraph = extractPartialGraph(rawTextBuffer.toString());
            if (partialGraph == null) return;

            Object nodesObj = partialGraph.get("nodes");
            Object edgesObj = partialGraph.get("edges");
            int nodeCount = (nodesObj instanceof List<?> nl) ? nl.size() : 0;
            int edgeCount = (edgesObj instanceof List<?> el) ? el.size() : 0;
            if (nodeCount <= lastEmittedNodeCount && edgeCount <= lastEmittedEdgeCount) return;

            lastEmittedNodeCount = nodeCount;
            lastEmittedEdgeCount = edgeCount;
            lastPartialEmitMs = now;

            Map<String, Object> previewState = new LinkedHashMap<>();
            previewState.put("status", "drafting");
            previewState.put("phase", "drafting");
            previewState.put("requirementsComplete", false);
            previewState.put("toolChainId", toolChainId);
            previewState.put("pendingQuestion", Map.of());
            previewState.put("artifactSummary", "graph=streaming");
            previewState.put("graphJson", toJson(partialGraph));
            previewState.put("graphAvailable", nodeCount > 0);
            super.sendStateUpdated(session.getId(), previewState);
        }

        private Map<String, Object> extractPartialGraph(String raw) {
            int graphMarker = raw.indexOf("\"graphJson\"");
            if (graphMarker < 0) return null;
            int objStart = raw.indexOf('{', graphMarker);
            if (objStart < 0) return null;
            int nodesMarker = raw.indexOf("\"nodes\"", objStart);
            if (nodesMarker < 0) return null;
            int nodesArr = raw.indexOf('[', nodesMarker);
            if (nodesArr < 0) return null;
            List<Map<String, Object>> nodes = extractArrayObjects(raw, nodesArr);
            int edgesMarker = raw.indexOf("\"edges\"", nodesArr);
            List<Map<String, Object>> edges = new ArrayList<>();
            if (edgesMarker > 0) {
                int edgesArr = raw.indexOf('[', edgesMarker);
                if (edgesArr > 0) edges = extractArrayObjects(raw, edgesArr);
            }
            if (nodes.isEmpty() && edges.isEmpty()) return null;
            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("nodes", nodes);
            graph.put("edges", edges);
            graph.put("description", "");
            return graph;
        }

        private List<Map<String, Object>> extractArrayObjects(String raw, int arrStart) {
            List<Map<String, Object>> out = new ArrayList<>();
            int i = arrStart + 1;
            while (i < raw.length()) {
                while (i < raw.length() && (Character.isWhitespace(raw.charAt(i)) || raw.charAt(i) == ',')) i++;
                if (i >= raw.length() || raw.charAt(i) == ']') break;
                if (raw.charAt(i) != '{') break;
                int depth = 0;
                int objEnd = -1;
                boolean inString = false;
                boolean escape = false;
                for (int j = i; j < raw.length(); j++) {
                    char c = raw.charAt(j);
                    if (escape) { escape = false; continue; }
                    if (c == '\\') { escape = true; continue; }
                    if (c == '"') { inString = !inString; continue; }
                    if (inString) continue;
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) { objEnd = j; break; }
                    }
                }
                if (objEnd < 0) break;
                try {
                    Map<String, Object> obj = readMap(raw.substring(i, objEnd + 1));
                    if (!obj.isEmpty()) out.add(obj);
                } catch (Exception ignored) {
                    break;
                }
                i = objEnd + 1;
            }
            return out;
        }

        @Override
        public void sendToolCall(String sessionId, String toolName, Object input) {
            // Suppressed for ToolChain designer transcript UX:
            // do not emit low-level tool call events and do not persist them as system cards.
        }

        @Override
        public void sendToolCall(String sessionId, String callId, String toolName, Object input) {
            // Suppressed for ToolChain designer transcript UX:
            // do not emit low-level tool call events and do not persist them as system cards.
        }

        @Override
        public void sendToolDone(String sessionId, String toolName, Object output) {
            // Suppressed for ToolChain designer transcript UX:
            // do not emit low-level tool result events and do not persist them as system cards.
        }

        @Override
        public void sendToolResult(String sessionId, String callId, String toolName, Object output, String status) {
            // Suppressed for ToolChain designer transcript UX:
            // do not emit low-level tool result events and do not persist them as system cards.
        }

        @Override
        public void sendToolMatch(String sessionId,
                                  String selectedTool,
                                  int score,
                                  boolean needsClarification,
                                  String reason,
                                  List<String> candidates) {
            // Suppressed for ToolChain designer transcript UX:
            // do not emit low-level tool-match routing events and do not persist them.
        }

        @Override
        public void sendTaskStarted(String sessionId, String taskId, String taskName) {
            // Suppressed for ToolChain designer transcript UX:
            // no task.started event emission and no persistence.
        }

        @Override
        public void sendTaskDone(String sessionId, String taskId, String result) {
            // Suppressed for ToolChain designer transcript UX:
            // no task.done event emission and no persistence.
        }

        @Override
        public void sendStepStarted(String sessionId, int step, Integer maxSteps) {
            // Suppressed for ToolChain designer transcript UX:
            // no step.started event emission and no persistence.
        }

        @Override
        public void sendStepFinished(String sessionId, int step, String mode, boolean executedAny) {
            // Suppressed for ToolChain designer transcript UX:
            // no step.finished event emission and no persistence.
        }

        @Override
        public void sendDone(String sessionId, String content) {
            persistReasoningSummaryIfPresent();
            super.sendDone(session.getId(), content);
        }

        @Override
        public void sendDone(String sessionId) {
            persistReasoningSummaryIfPresent();
            super.sendDone(session.getId());
        }

        private Map<String, Object> metadataToMap(Object metadata) {
            if (metadata == null) return Map.of();
            if (metadata instanceof Map<?, ?> map) {
                return readMap(map);
            }
            try {
                return objectMapper.convertValue(metadata, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                return Map.of();
            }
        }

        private Map<String, Object> latestQuestion() {
            return latestQuestion == null ? Map.of() : latestQuestion;
        }

        private void clearLatestQuestion() {
            this.latestQuestion = Map.of();
            // Reset live-stream graph extraction state so the next LLM turn doesn't
            // try to extract from the prior turn's accumulated buffer.
            this.rawTextBuffer.setLength(0);
            this.lastEmittedNodeCount = 0;
            this.lastEmittedEdgeCount = 0;
            this.lastPartialEmitMs = 0;
        }

        private void persistReasoningSummaryIfPresent() {
            if (reasoningPersisted) return;
            String summary = reasoningBuffer.toString().trim();
            if (!summary.isBlank()) {
                persistSystemMessage(
                        session.getId(),
                        "reasoning.summary",
                        null,
                        summary,
                        Map.of("source", "reasoning.delta.aggregate", "toolChainId", toolChainId),
                        null,
                        null
                );
            }
            reasoningPersisted = true;
        }
    }

    private Map<String, Object> processProposalDecisionIfAny(String toolChainId,
                                                             ToolChainConfigSession session,
                                                             ToolChainDtos.ToolChainConfigChatRequest request) {
        Map<String, Object> pending = readMap(session.getPendingQuestionJson());
        if (!"proposalDecision".equalsIgnoreCase(stringValue(pending.get("key")))) return null;
        if (request == null) return null;
        String selected = firstNonBlank(request.getSelectedOptionId(), request.getSelectedOptionLabel(), request.getAnswerText());
        if (selected == null || selected.isBlank()) return null;
        String normalized = selected.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("approve")) {
            return approvePendingProposal(toolChainId, session, request, pending);
        }
        if (normalized.contains("change") || normalized.contains("modify") || normalized.contains("revise")) {
            Map<String, Object> artifact = new LinkedHashMap<>(readMap(session.getLatestArtifactJson()));
            artifact.remove("pendingArtifactPatch");
            artifact.put("phase", "clarifying");
            session.setLatestArtifactJson(toJson(artifact));
            session.setPendingQuestionJson("{}");
            session.setStatus("draft");
            sessionRepository.update(session);
            return null;
        }
        return null;
    }

    private Map<String, Object> approvePendingProposal(String toolChainId,
                                                       ToolChainConfigSession session,
                                                       ToolChainDtos.ToolChainConfigChatRequest request,
                                                       Map<String, Object> pendingQuestion) {
        Map<String, Object> artifact = new LinkedHashMap<>(readMap(session.getLatestArtifactJson()));
        Map<String, Object> patch = readMap(artifact.get("pendingArtifactPatch"));
        if (patch.isEmpty()) {
            throw new IllegalStateException("No pending plan patch is available for approval");
        }
        applyArtifactPatch(artifact, patch);
        String stripped = stripSkillNodes(stringValue(artifact.get("graphJson")), new ArrayList<>());
        if (!stripped.isBlank()) artifact.put("graphJson", stripped);
        Map<String, Object> contextBundle = loadContextBundle(toolChainId, artifact);
        ensureSchemaFallback(artifact, contextBundle);
        artifact.remove("pendingArtifactPatch");
        artifact.put("phase", "proposal_ready");
        artifact.put("proposalAccepted", true);
        artifact.put("acceptedAt", System.currentTimeMillis());
        artifact.put("requirementsComplete", true);
        session.setPendingQuestionJson("{}");
        session.setStatus("ready_to_compile");
        markPendingQuestionAnswered(
                session,
                pendingQuestion,
                request,
                firstNonBlank(request.getSelectedOptionLabel(), request.getAnswerText(), request.getSelectedOptionId())
        );
        messageRepository.save(ToolChainConfigMessage.builder()
                .sessionId(session.getId())
                .role("assistant")
                .content("Plan approved. I generated the ToolChain draft graph. Review it, then compile when ready.")
                .metadataJson("{}")
                .build());
        session.setLatestArtifactJson(toJson(artifact));
        if (shouldAutoUpdateTitle(session)) {
            session.setTitle(deriveTitle(session, artifact));
        }
        sessionRepository.update(session);
        return getSessionDetail(toolChainId, session.getId());
    }

    /**
     * Truncate-and-resend support: delete the target message AND every message in the session
     * with a createdAt &gt;= the target's. Used by the frontend's "edit & resend" UI to rewind
     * the conversation back to the user's edit point.
     */
    public Map<String, Object> truncateFromMessage(String toolChainId, String sessionId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        ToolChainConfigMessage target = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        if (!sessionId.equals(target.getSessionId())) {
            throw new IllegalArgumentException("Message does not belong to this session");
        }
        int removed = messageRepository.deleteFromTime(sessionId, target.getCreatedAt());
        // Truncating the conversation invalidates any in-flight clarification — clear it.
        session.setPendingQuestionJson("{}");
        if ("clarification_required".equalsIgnoreCase(session.getStatus())) {
            session.setStatus("draft");
        }
        sessionRepository.update(session);
        return Map.of(
                "sessionId", sessionId,
                "toolChainId", toolChainId,
                "removed", removed,
                "fromCreatedAt", target.getCreatedAt()
        );
    }

    public Map<String, Object> compileToVersion(String toolChainId, String sessionId, String userId) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        Map<String, Object> artifact = readMap(session.getLatestArtifactJson());
        artifact = validateAndNormalizeDraftArtifact(artifact);
        artifact.put("phase", "proposal_ready");
        session.setLatestArtifactJson(toJson(artifact));
        session.setStatus("compiled");
        session.setPendingQuestionJson("{}");
        sessionRepository.update(session);

        return Map.of(
                "sessionId", sessionId,
                "toolChainId", toolChainId,
                "status", "compiled"
        );
    }

    public Map<String, Object> publishFromSession(String toolChainId, String sessionId, String userId) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        Map<String, Object> artifact = validateAndNormalizeDraftArtifact(readMap(session.getLatestArtifactJson()));
        ToolChainDtos.ToolChainVersionRequest req = buildVersionRequestFromArtifact(artifact);
        var version = toolChainService.createVersion(toolChainId, req, userId);
        toolChainService.publishVersion(toolChainId, version.getVersion());
        try {
            String chainName = deriveChainName(toolChainId, session, artifact);
            String chainDescription = deriveChainDescription(artifact);
            toolChainService.updateMetadata(toolChainId, chainName, chainDescription);
        } catch (Exception e) {
            log.warn("[ToolChainConfigChatService] Failed to update chain metadata after publishFromSession: {}", e.getMessage());
        }
        artifact.put("phase", "completed");
        artifact.put("proposalAccepted", true);
        artifact.put("acceptedAt", System.currentTimeMillis());
        session.setLatestArtifactJson(toJson(artifact));
        session.setStatus("compiled");
        session.setPendingQuestionJson("{}");
        sessionRepository.update(session);
        return Map.of(
                "sessionId", sessionId,
                "toolChainId", toolChainId,
                "version", version.getVersion(),
                "status", "published"
        );
    }

    private Map<String, Object> validateAndNormalizeDraftArtifact(Map<String, Object> artifact) {
        validateArtifact(artifact);
        List<Map<String, Object>> toolsCatalog = buildToolsCatalogForValidation();
        Set<String> validToolNames = collectNames(toolsCatalog);
        Set<String> validMcpNames = collectNames(buildMcpCatalog());
        String canonicalGraphJson = canonicalizeGraphToolNodeTypes(
                stringValue(artifact.get("graphJson")),
                validToolNames,
                validMcpNames
        );
        if (!canonicalGraphJson.isBlank()) {
            artifact.put("graphJson", canonicalGraphJson);
        }
        Map<String, ToolInputContract> toolContracts = buildToolContracts(toolsCatalog);
        GraphRequirementsResult requirementsResult = normalizeAndValidateGraphRequirements(
                stringValue(artifact.get("graphJson")),
                toolContracts,
                readMap(artifact.get("inputSchema"))
        );
        if (requirementsResult.hasErrors()) {
            throw new IllegalArgumentException("Compile blocked: " + String.join("; ", requirementsResult.errors()));
        }
        if (!requirementsResult.normalizedGraphJson().isBlank()) {
            artifact.put("graphJson", requirementsResult.normalizedGraphJson());
        }
        return artifact;
    }

    private ToolChainDtos.ToolChainVersionRequest buildVersionRequestFromArtifact(Map<String, Object> artifact) {
        ToolChainDtos.ToolChainVersionRequest req = new ToolChainDtos.ToolChainVersionRequest();
        req.setGraphJson(String.valueOf(artifact.get("graphJson")));
        req.setInputSchema(asJsonString(artifact.getOrDefault("inputSchema", Map.of("type", "object"))));
        req.setOutputSchema(asJsonString(artifact.getOrDefault("outputSchema", Map.of("type", "object"))));
        String responseMode = String.valueOf(artifact.getOrDefault("responseMode", "hybrid"));
        req.setResponseMode(RESPONSE_MODES.contains(responseMode) ? responseMode : "hybrid");
        req.setSynthesisPrompt((String) artifact.getOrDefault("synthesisPrompt", ""));
        req.setIntents(readStringList(artifact.get("intents")));
        req.setRagConfig(readMap(artifact.get("ragConfig")));
        return req;
    }

    private Map<String, Object> publishFromArtifact(String toolChainId,
                                                    ToolChainConfigSession session,
                                                    Map<String, Object> artifact,
                                                    String userId) {
        Map<String, Object> normalized = validateAndNormalizeDraftArtifact(artifact);
        ToolChainDtos.ToolChainVersionRequest req = buildVersionRequestFromArtifact(normalized);
        var version = toolChainService.createVersion(toolChainId, req, userId);
        toolChainService.publishVersion(toolChainId, version.getVersion());
        // After publish, refresh the chain row's display Name + Description from the
        // artifact the architect just compiled — otherwise the listing keeps echoing
        // the user's first prompt twice.
        try {
            String chainName = deriveChainName(toolChainId, session, normalized);
            String chainDescription = deriveChainDescription(normalized);
            toolChainService.updateMetadata(toolChainId, chainName, chainDescription);
        } catch (Exception e) {
            log.warn("[ToolChainConfigChatService] Failed to update chain metadata after publish: {}", e.getMessage());
        }
        normalized.put("phase", "completed");
        normalized.put("proposalAccepted", true);
        normalized.put("acceptedAt", System.currentTimeMillis());
        session.setLatestArtifactJson(toJson(normalized));
        session.setStatus("compiled");
        session.setPendingQuestionJson("{}");
        return Map.of(
                "version", version.getVersion(),
                "status", "published"
        );
    }

    /**
     * If the LLM produced an artifactPatch (or skipped straight to a proposalDecision)
     * while a required clarification dimension (responseMode, approvalPolicy,
     * outputJsonMode) is still missing, override the current pendingQuestion with that
     * dimension so the streaming loop blocks on it instead of letting the user approve
     * an incomplete design. Idempotent: if no dimension is missing, returns the input
     * detail untouched. Returns the freshly-fetched detail when an override happens.
     *
     * Skipped dimensions: `workflowIntent` (LLM owns it; the architect skill knows when
     * to ask), `graph` (the LLM produces the graph, not a question), and the explicit
     * `proposalDecision` ladder leaf (we don't want to ask for approval inside the
     * clarification phase).
     */
    private Map<String, Object> enforceClarificationLadder(String toolChainId,
                                                           ToolChainConfigSession session,
                                                           Map<String, Object> detail) {
        Map<String, Object> currentPq = readMap(detail.get("pendingQuestion"));
        String currentKey = stringValue(currentPq.get("key"));
        // Only intercept when the current state is "ready to approve" or "no question",
        // i.e. exactly when the LLM is about to skip a required dimension.
        boolean shouldIntercept = currentPq.isEmpty() || "proposalDecision".equalsIgnoreCase(currentKey);
        if (!shouldIntercept) return detail;

        Map<String, Object> artifact = readMap(session.getLatestArtifactJson());
        Map<String, Object> nextDimension = maybeAskClarification(artifact);
        if (nextDimension.isEmpty()) return detail;
        String nextKey = stringValue(nextDimension.get("key"));
        if (nextKey.isBlank() || "graph".equalsIgnoreCase(nextKey)
                || "proposalDecision".equalsIgnoreCase(nextKey)
                || "workflowIntent".equalsIgnoreCase(nextKey)) {
            return detail;
        }

        // If the dimension is genuinely required but the LLM produced a patch first,
        // strip the pendingArtifactPatch so the user isn't approving a half-baked draft.
        Map<String, Object> mutableArtifact = new LinkedHashMap<>(artifact);
        mutableArtifact.remove("pendingArtifactPatch");
        mutableArtifact.put("phase", "clarifying");
        session.setLatestArtifactJson(toJson(mutableArtifact));
        session.setPendingQuestionJson(toJson(nextDimension));
        session.setStatus("clarification_required");
        sessionRepository.update(session);

        // Persist the override as a system message so it appears in the transcript and
        // survives a refetch (frontend hydration relies on this for the answer card).
        persistSystemMessage(
                session.getId(),
                "question",
                stringValue(nextDimension.get("id")),
                stringValue(nextDimension.get("question")),
                Map.of("metadata", Map.of("options", nextDimension.getOrDefault("options", List.of())),
                        "key", nextDimension.getOrDefault("key", "designer")),
                "pending",
                null
        );
        return getSessionDetail(toolChainId, session.getId());
    }

    private Map<String, Object> buildProposalDecisionQuestion() {
        return buildProposalDecisionQuestion(null);
    }

    private Map<String, Object> buildProposalDecisionQuestion(String planSummary) {
        // Embedding the plan in the question text keeps the plan + approve/reject
        // affordance in a single visible card so the user reads what they're approving.
        String question = (planSummary == null || planSummary.isBlank())
                ? "Please review the plan. Approve to generate the draft graph, or request changes."
                : "Please review the plan and approve to generate the draft graph, or request changes:\n\n"
                        + planSummary.trim();
        return Map.of(
                "id", UUID.randomUUID().toString(),
                "key", "proposalDecision",
                "question", question,
                "options", List.of(
                        Map.of("id", "approve", "label", "Approve Plan"),
                        Map.of("id", "change", "label", "Request Changes")
                )
        );
    }

    public Map<String, Object> updateSession(String toolChainId,
                                             String sessionId,
                                             ToolChainDtos.ToolChainConfigSessionUpdateRequest request) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        if (request.getTitle() != null) {
            session.setTitle(request.getTitle().isBlank() ? "Untitled session" : request.getTitle().trim());
        }
        if (request.getArchived() != null) {
            session.setArchivedAt(Boolean.TRUE.equals(request.getArchived()) ? System.currentTimeMillis() : null);
        }
        sessionRepository.update(session);
        return Map.of(
                "id", session.getId(),
                "title", session.getTitle(),
                "status", session.getStatus(),
                "updatedAt", session.getUpdatedAt(),
                "archivedAt", session.getArchivedAt()
        );
    }

    public Map<String, Object> deleteSession(String toolChainId, String sessionId) {
        ToolChainConfigSession session = requireSession(toolChainId, sessionId);
        sessionRepository.delete(session.getId());
        return Map.of("deleted", true, "id", sessionId);
    }

    private String resolveToolChainId(String routeToolChainId, ToolChainDtos.ToolChainConfigChatRequest request, String userId) {
        String requestToolChainId = request.getToolChainId();
        if (routeToolChainId != null && !routeToolChainId.isBlank()) {
            if (requestToolChainId == null || requestToolChainId.isBlank() || routeToolChainId.equals(requestToolChainId)) {
                return routeToolChainId;
            }
            throw new IllegalArgumentException("toolChainId in payload does not match route id");
        }
        if (requestToolChainId != null && !requestToolChainId.isBlank()) {
            toolChainService.getRequired(requestToolChainId);
            return requestToolChainId;
        }
        if (!Boolean.TRUE.equals(request.getCreateIfMissing())) {
            throw new IllegalArgumentException("toolChainId is required when createIfMissing is false");
        }
        ToolChainDtos.ToolChainCreateRequest create = new ToolChainDtos.ToolChainCreateRequest();
        String source = request.getToolChainName();
        if ((source == null || source.isBlank()) && request.getMessage() != null && !request.getMessage().isBlank()) {
            source = request.getMessage();
        }
        String baseName = deriveToolChainName(source);
        create.setName(nextAvailableToolChainName(baseName));
        create.setDescription(request.getToolChainDescription());
        create.setEnabled(true);
        create.setMetadata(Map.of(
                "createdFrom", "config_chat",
                "createMode", "requirement_first"
        ));
        return toolChainService.create(create, userId).getId();
    }

    private String deriveToolChainName(String source) {
        if (source == null || source.isBlank()) return "AI Generated ToolChain";
        String normalized = cleanNameCandidate(source);
        if (normalized.isBlank()) normalized = source.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        normalized = toTitleCase(truncateTitle(normalized));
        if (normalized.isBlank()) return "AI Generated ToolChain";
        return normalized;
    }

    private String nextAvailableToolChainName(String baseName) {
        Set<String> existing = toolChainService.listAll().stream()
                .map(ToolChain::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!existing.contains(baseName)) return baseName;
        for (int i = 2; i <= 9999; i++) {
            String candidate = baseName + " (" + i + ")";
            if (!existing.contains(candidate)) return candidate;
        }
        return baseName + " (" + UUID.randomUUID().toString().substring(0, 8) + ")";
    }

    private ToolChainConfigSession resolveSession(String toolChainId, ToolChainDtos.ToolChainConfigChatRequest request, String userId) {
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            return requireSession(toolChainId, request.getSessionId());
        }
        Map<String, Object> bootstrapArtifact = defaultArtifact();
        bootstrapArtifact.put("contextBundle", loadContextBundle(toolChainId, bootstrapArtifact));
        // Title intentionally left null — deriveTitle fills it from the first user
        // message on the next turn. The frontend renders an "Untitled draft"
        // placeholder for the brief window before that first message arrives.
        ToolChainConfigSession created = ToolChainConfigSession.builder()
                .toolChainId(toolChainId)
                .status("draft")
                .latestArtifactJson(toJson(bootstrapArtifact))
                .pendingQuestionJson("{}")
                .createdBy(userId)
                .build();
        ToolChainConfigSession session = sessionRepository.save(created);
        return session;
    }

    private ToolChainConfigSession requireSession(String toolChainId, String sessionId) {
        ToolChainConfigSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Config session not found: " + sessionId));
        if (!toolChainId.equals(session.getToolChainId())) {
            throw new IllegalArgumentException("Config session does not belong to toolchain: " + toolChainId);
        }
        return session;
    }

    private String normalizeInput(ToolChainDtos.ToolChainConfigChatRequest request, Map<String, Object> pendingQuestion) {
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            return request.getMessage().trim();
        }
        if (request.getAnswerText() != null && !request.getAnswerText().isBlank()) {
            return request.getAnswerText().trim();
        }
        if (request.getSelectedOptionId() != null && !request.getSelectedOptionId().isBlank()) {
            if (pendingQuestion.isEmpty()) return request.getSelectedOptionId().trim();
            Object optionsObj = pendingQuestion.get("options");
            if (optionsObj instanceof List<?> opts) {
                for (Object row : opts) {
                    if (!(row instanceof Map<?, ?> map)) continue;
                    String id = String.valueOf(map.get("id"));
                    if (request.getSelectedOptionId().equals(id)) {
                        Object label = map.containsKey("label") ? map.get("label") : id;
                        return String.valueOf(label);
                    }
                }
            }
            return request.getSelectedOptionId().trim();
        }
        return null;
    }

    private void applyInstruction(Map<String, Object> artifact, String instruction) {
        String lower = instruction.toLowerCase(Locale.ROOT);
        if (lower.contains("create a new toolchain configuration session")
                || lower.contains("new toolchain configuration session")) {
            // UI/session bootstrap helper text should not mutate artifact or trigger auto-compile.
            return;
        }
        if (instruction.length() > 10) {
            artifact.put("titleHint", instruction.length() > 70 ? instruction.substring(0, 70) + "..." : instruction);
        }
        if (stringValue(artifact.get("workflowIntent")).isBlank() && instruction.length() > 2) {
            artifact.put("workflowIntent", instruction);
        }

        if (lower.contains("json only") || lower.contains("deterministic")) artifact.put("responseMode", "raw_graph_output");
        if (lower.contains("summary text") || lower.contains("narrative")) artifact.put("responseMode", "synthesized_text");
        if (lower.contains("hybrid")) artifact.put("responseMode", "hybrid");
        if (lower.contains("rag")) {
            artifact.put("ragConfig", Map.of("enabled", true, "strategy", "semantic", "topK", 5));
        }
        if (lower.contains("approval")) {
            artifact.put("approvalPolicy", Map.of("enabled", true, "mode", "per_step"));
        }
        // Intents and graph are populated by the AI step (applyAiInstruction).
        // We do NOT generate a stub graph here — that produces unusable "task" placeholder nodes.
    }

    private void applyAiInstruction(Map<String, Object> artifact,
                                    Map<String, Object> contextBundle,
                                    String instruction,
                                    ToolChainDtos.ToolChainConfigChatRequest request) {
        List<Map<String, Object>> toolsCatalog = buildToolsCatalog();
        List<Map<String, Object>> skillsCatalog = buildSkillsCatalog();
        List<Map<String, Object>> mcpCatalog = buildMcpCatalog();
        Set<String> validToolNames = collectNames(toolsCatalog);
        Set<String> validSkillNames = collectNames(skillsCatalog);
        Set<String> validMcpNames = collectNames(mcpCatalog);

        if (toolsCatalog.isEmpty() && skillsCatalog.isEmpty() && mcpCatalog.isEmpty()) {
            artifact.put("lastAiSummary",
                    "Cannot build a ToolChain: no tools, skills, or MCP servers are enabled in this workspace. "
                            + "Add at least one tool/skill/MCP entry, then describe your workflow again.");
            return;
        }

        String systemPrompt = loadSystemPrompt();

        Map<String, Object> aiInput = new LinkedHashMap<>();
        aiInput.put("instruction", instruction);
        aiInput.put("toolChainContext", readMap(contextBundle.get("toolChain")));
        aiInput.put("currentResponseMode", artifact.getOrDefault("responseMode", "hybrid"));
        aiInput.put("currentIntents", readStringList(artifact.get("intents")));
        aiInput.put("currentInputSchema", readMap(artifact.get("inputSchema")));
        aiInput.put("currentOutputSchema", readMap(artifact.get("outputSchema")));
        aiInput.put("toolsCatalog", toolsCatalog);
        aiInput.put("skillsCatalog", skillsCatalog);
        aiInput.put("mcpCatalog", mcpCatalog);

        String raw;
        try {
            // disableThinking=true: this is a structured-JSON output task; extended thinking can
            // consume the entire maxTokens budget and cause the model to never emit the JSON answer.
            raw = modelProviderRouter.resolve(resolveModelRef(request), true)
                    .client()
                    .prompt()
                    .system(systemPrompt)
                    .user(toJson(aiInput))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[ToolChainConfigChatService] AI config generation failed: {}", e.getMessage(), e);
            artifact.put("lastAiSummary",
                    "I could not reach the configured AI model to design the graph (" + e.getMessage()
                            + "). Check the model selection and try again.");
            return;
        }

        if (raw == null || raw.isBlank()) {
            artifact.put("lastAiSummary", "The AI returned an empty response. Try rephrasing the requirement.");
            return;
        }

        Map<String, Object> parsed = readMap(extractJsonObject(raw));
        Map<String, Object> patch = readMap(parsed.get("artifactPatch"));
        if (patch.isEmpty()) {
            log.warn("[ToolChainConfigChatService] AI response did not contain artifactPatch. Raw={}", raw);
            artifact.put("lastAiSummary",
                    "The AI response could not be parsed into a ToolChain patch. Try a more specific requirement.");
            return;
        }

        applyArtifactPatch(artifact, patch);
        List<String> stripWarnings = new ArrayList<>();
        String strippedGraphJson = stripSkillNodes(stringValue(artifact.get("graphJson")), stripWarnings);
        if (!strippedGraphJson.isBlank()) {
            artifact.put("graphJson", strippedGraphJson);
        }
        String canonicalGraphJson = canonicalizeGraphToolNodeTypes(
                stringValue(artifact.get("graphJson")),
                validToolNames,
                validMcpNames
        );
        if (!canonicalGraphJson.isBlank()) {
            artifact.put("graphJson", canonicalGraphJson);
        }
        Map<String, ToolInputContract> toolContracts = buildToolContracts(toolsCatalog);
        GraphRequirementsResult requirementsResult = normalizeAndValidateGraphRequirements(
                stringValue(artifact.get("graphJson")),
                toolContracts,
                readMap(artifact.get("inputSchema"))
        );
        if (!requirementsResult.normalizedGraphJson().isBlank()) {
            artifact.put("graphJson", requirementsResult.normalizedGraphJson());
        }
        ensureSchemaFallback(artifact, contextBundle);

        String assistantMessage = stringValue(parsed.get("assistantMessage"));
        List<String> validationErrors = validateGraphAgainstCatalog(
                stringValue(artifact.get("graphJson")), validToolNames, validSkillNames, validMcpNames);

        if (!validationErrors.isEmpty()) {
            String joined = String.join("; ", validationErrors);
            log.warn("[ToolChainConfigChatService] AI graph validation issues: {}", joined);
            // Strip the bad graph so the clarification flow asks again.
            artifact.put("graphJson", "");
            // Prefer the AI's own explanation if it provided one (e.g. "no suitable tool exists for X").
            String summary = !assistantMessage.isBlank()
                    ? assistantMessage
                    : "I drafted a graph but it referenced unknown tools (" + joined
                            + "). Tell me which of the available tools/skills should be used, or add them to the catalog.";
            artifact.put("lastAiSummary", summary);
            return;
        }

        if (!assistantMessage.isBlank()) {
            StringBuilder summary = new StringBuilder(assistantMessage);
            if (!stripWarnings.isEmpty()) {
                summary.append(" ").append(String.join(" ", stripWarnings));
            }
            if (requirementsResult.hasWarnings()) {
                summary.append(" Auto-mapped inputs: ").append(String.join("; ", requirementsResult.warnings()));
            }
            artifact.put("lastAiSummary", summary.toString());
        }

        // Merge any AI-suggested intents in addition to keyword-extracted ones from instruction.
        List<String> mergedIntents = new ArrayList<>(readStringList(artifact.get("intents")));
        if (mergedIntents.isEmpty()) {
            mergedIntents.addAll(extractIntentCandidates(instruction));
            artifact.put("intents", mergedIntents.stream().distinct().limit(20).toList());
        }
    }

    private static final int CATALOG_LIMIT = 40;
    private static final int DESCRIPTION_LIMIT = 120;

    private volatile String cachedSystemPrompt;

    private String loadSystemPrompt() {
        String cached = cachedSystemPrompt;
        if (cached != null) return cached;
        SkillRegistryService.SkillSnapshot snapshot =
                skillRegistryService.getEnabledSkillByName(TOOLCHAIN_SKILL_NAME);
        if (snapshot != null) {
            String body = snapshot.files().get("SKILL.md");
            if (body != null) {
                String stripped = stripYamlFrontmatter(body).trim();
                if (!stripped.isBlank()) {
                    cachedSystemPrompt = stripped;
                    return stripped;
                }
            }
        }
        log.warn("[ToolChainConfigChatService] Skill '{}' not found; using fallback prompt", TOOLCHAIN_SKILL_NAME);
        cachedSystemPrompt = SYSTEM_PROMPT_FALLBACK;
        return SYSTEM_PROMPT_FALLBACK;
    }

    private static String stripYamlFrontmatter(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return content;
        int after = end + "\n---".length();
        if (after < content.length() && content.charAt(after) == '\n') after++;
        return content.substring(after);
    }

    private List<Map<String, Object>> buildToolsCatalog() {
        return agentToolRepository.findAll().stream()
                .filter(AgentTool::isEnabled)
                .map(this::toToolCatalogRow)
                .limit(CATALOG_LIMIT)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildToolsCatalogForValidation() {
        return agentToolRepository.findAll().stream()
                .filter(AgentTool::isEnabled)
                .map(this::toToolCatalogRow)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toToolCatalogRow(AgentTool tool) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", tool.getName());
        row.put("description", trimDescription(tool.getDescription()));
        row.put("method", stringValue(tool.getMethod()).toUpperCase(Locale.ROOT));
        row.put("endpoint", stringValue(tool.getEndpoint()));
        row.put("requiredInputKeys", extractRequiredInputKeys(tool.getRequestSchema()));
        row.put("pathParams", extractPathParams(tool.getEndpoint()));
        row.put("sampleInput", buildSampleInput(tool));
        return row;
    }

    private List<Map<String, Object>> buildSkillsCatalog() {
        return skillRepository.findAll().stream()
                .filter(Skill::isEnabled)
                .map(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", s.getName());
                    row.put("description", trimDescription(s.getDescription()));
                    return row;
                })
                .limit(CATALOG_LIMIT)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildMcpCatalog() {
        return mcpRegistryRepository.findEnabled().stream()
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", m.getName());
                    return row;
                })
                .limit(CATALOG_LIMIT)
                .collect(Collectors.toList());
    }

    private String trimDescription(String value) {
        if (value == null) return "";
        String trimmed = value.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= DESCRIPTION_LIMIT) return trimmed;
        return trimmed.substring(0, DESCRIPTION_LIMIT) + "…";
    }

    private List<String> extractRequiredInputKeys(String requestSchema) {
        Map<String, Object> parsed = readMap(requestSchema);
        Map<String, Object> inputSchema = readMap(parsed.get("inputSchema"));
        List<String> required = readStringList(inputSchema.get("required"));
        if (!required.isEmpty()) return required;
        return readStringList(parsed.get("required"));
    }

    private List<String> extractPathParams(String endpoint) {
        List<String> params = new ArrayList<>();
        if (endpoint == null || endpoint.isBlank()) return params;
        Matcher matcher = PATH_PARAM_PATTERN.matcher(endpoint);
        while (matcher.find()) {
            String key = stringValue(matcher.group(1)).trim();
            if (!key.isBlank()) params.add(key);
        }
        return params.stream().distinct().toList();
    }

    private Map<String, Object> buildSampleInput(AgentTool tool) {
        Map<String, Object> sample = new LinkedHashMap<>();
        List<String> required = extractRequiredInputKeys(tool.getRequestSchema());
        for (String key : required) {
            if (!key.isBlank()) sample.put(key, "<" + key + ">");
        }
        for (String key : extractPathParams(tool.getEndpoint())) {
            if (!key.isBlank()) sample.putIfAbsent(key, "<" + key + ">");
        }
        if (!sample.isEmpty()) return sample;
        return readMap(tool.getSampleRequest());
    }

    private Set<String> collectNames(List<Map<String, Object>> rows) {
        Set<String> names = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String name = stringValue(row.get("name"));
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    private Map<String, ToolInputContract> buildToolContracts(List<Map<String, Object>> toolsCatalog) {
        Map<String, ToolInputContract> out = new LinkedHashMap<>();
        for (Map<String, Object> row : toolsCatalog) {
            String name = stringValue(row.get("name"));
            if (name.isBlank()) continue;
            List<String> required = readStringList(row.get("requiredInputKeys")).stream().distinct().toList();
            List<String> pathParams = readStringList(row.get("pathParams")).stream().distinct().toList();
            out.put(name, new ToolInputContract(required, pathParams));
        }
        return out;
    }

    GraphRequirementsResult normalizeAndValidateGraphRequirements(String graphJson,
                                                                  Map<String, ToolInputContract> toolContracts,
                                                                  Map<String, Object> inputSchema) {
        if (graphJson == null || graphJson.isBlank()) {
            return new GraphRequirementsResult(graphJson == null ? "" : graphJson, List.of(), List.of());
        }
        Map<String, Object> graph = readMap(graphJson);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        List<Map<String, Object>> edges = readMapList(graph.get("edges"));
        if (nodes.isEmpty()) {
            return new GraphRequirementsResult(graphJson, List.of(), List.of("graph has no nodes"));
        }

        Set<String> workflowInputKeys = extractWorkflowInputKeys(inputSchema);
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String nodeId = stringValue(node.get("id"));
            if (!nodeId.isBlank()) nodesById.put(nodeId, node);
        }
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        for (String nodeId : nodesById.keySet()) incoming.put(nodeId, new ArrayList<>());
        for (Map<String, Object> edge : edges) {
            String from = stringValue(edge.getOrDefault("from", edge.get("source")));
            String to = stringValue(edge.getOrDefault("to", edge.get("target")));
            if (incoming.containsKey(to) && nodesById.containsKey(from)) {
                incoming.get(to).add(from);
            }
        }

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Set<String>> availableCache = new LinkedHashMap<>();

        for (Map<String, Object> node : nodes) {
            String nodeId = stringValue(node.get("id"));
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if (!"tool".equals(type)) continue;
            Map<String, Object> config = new LinkedHashMap<>(readMap(node.get("config")));
            String toolName = stringValue(config.getOrDefault("toolName", config.get("name")));
            ToolInputContract contract = toolContracts.get(toolName);
            if (contract == null) continue;
            Set<String> required = new LinkedHashSet<>();
            required.addAll(contract.requiredInputKeys());
            required.addAll(contract.pathParams());
            if (required.isEmpty()) continue;

            Set<String> availableKeys = resolveAvailableKeys(nodeId, incoming, workflowInputKeys, availableCache);
            List<String> directIncoming = incoming.getOrDefault(nodeId, List.of());
            String currentInputKey = stringValue(config.get("inputKey")).trim();
            Map<String, Object> argMappings = new LinkedHashMap<>(readMap(config.get("argMappings")));
            List<String> missing = new ArrayList<>();

            if (currentInputKey.isBlank() && directIncoming.size() == 1) {
                String onlyParent = directIncoming.get(0);
                Map<String, Object> parentNode = nodesById.get(onlyParent);
                String parentType = parentNode == null ? "" : stringValue(parentNode.get("type")).toLowerCase(Locale.ROOT);
                if (!"start".equals(parentType) && !"end".equals(parentType)) {
                    config.put("inputKey", onlyParent);
                    currentInputKey = onlyParent;
                }
            }

            for (String key : required) {
                if (key == null || key.isBlank()) continue;
                if (containsIgnoreCase(argMappings.keySet(), key) || containsIgnoreCase(availableKeys, key)) {
                    continue;
                }
                if (!currentInputKey.isBlank() && containsIgnoreCase(availableKeys, currentInputKey)) {
                    continue;
                }
                String inferred = inferMappingCandidate(key, availableKeys);
                if (inferred != null) {
                    argMappings.put(key, inferred);
                    warnings.add("node " + nodeId + " mapped required '" + key + "' <- '" + inferred + "'");
                } else {
                    missing.add(key);
                }
            }

            if (!argMappings.isEmpty()) {
                config.put("argMappings", argMappings);
            }
            node.put("config", config);

            if (!missing.isEmpty()) {
                List<String> suggestions = suggestKeys(missing, availableKeys);
                String suggestionText = suggestions.isEmpty() ? "no upstream candidates" : String.join(", ", suggestions);
                errors.add("node " + nodeId + " (" + toolName + ") missing required params: "
                        + String.join(", ", missing) + " | upstream keys: " + suggestionText);
            }
        }

        graph.put("nodes", nodes);
        return new GraphRequirementsResult(toJson(graph), warnings, errors);
    }

    private Set<String> extractWorkflowInputKeys(Map<String, Object> inputSchema) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Object> properties = readMap(inputSchema.get("properties"));
        out.addAll(properties.keySet());
        out.addAll(readStringList(inputSchema.get("required")));
        return out;
    }

    private Set<String> resolveAvailableKeys(String nodeId,
                                             Map<String, List<String>> incoming,
                                             Set<String> workflowInputKeys,
                                             Map<String, Set<String>> cache) {
        if (cache.containsKey(nodeId)) return cache.get(nodeId);
        Set<String> out = new LinkedHashSet<>(workflowInputKeys);
        for (String parent : incoming.getOrDefault(nodeId, List.of())) {
            out.add(parent);
            out.addAll(resolveAvailableKeys(parent, incoming, workflowInputKeys, cache));
        }
        cache.put(nodeId, out);
        return out;
    }

    private boolean containsIgnoreCase(Set<String> keys, String target) {
        if (target == null || target.isBlank()) return false;
        for (String key : keys) {
            if (key != null && key.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private String inferMappingCandidate(String requiredKey, Set<String> availableKeys) {
        if (requiredKey == null || requiredKey.isBlank() || availableKeys.isEmpty()) return null;
        String requiredNorm = normalizeKey(requiredKey);
        int bestScore = 0;
        String best = null;
        boolean tie = false;
        for (String candidate : availableKeys) {
            if (candidate == null || candidate.isBlank()) continue;
            int score = mappingScore(requiredNorm, normalizeKey(candidate), requiredKey, candidate);
            if (score <= 0) continue;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        if (tie || bestScore < 70) return null;
        return best;
    }

    private int mappingScore(String requiredNorm, String candidateNorm, String requiredRaw, String candidateRaw) {
        if (candidateNorm.equals(requiredNorm)) return 100;
        if (candidateRaw.equalsIgnoreCase(requiredRaw)) return 95;
        if ((requiredNorm + "id").equals(candidateNorm) || (candidateNorm + "id").equals(requiredNorm)) return 84;
        if ("id".equals(requiredNorm) && candidateNorm.endsWith("id")) return 78;
        if (candidateNorm.endsWith(requiredNorm) || requiredNorm.endsWith(candidateNorm)) return 72;
        return 0;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private List<String> suggestKeys(List<String> missing, Set<String> availableKeys) {
        List<String> suggestions = new ArrayList<>();
        for (String key : missing) {
            String candidate = inferMappingCandidate(key, availableKeys);
            if (candidate != null) {
                suggestions.add(key + " <- " + candidate);
            }
        }
        if (!suggestions.isEmpty()) return suggestions;
        return availableKeys.stream().limit(8).toList();
    }

    /**
     * Removes any skill-type nodes the AI emitted (skills are now reasoning-layer
     * tools used inside the synthesis LLM, not graph nodes) and re-stitches edges
     * so a path from {@code A -> skill -> B} becomes {@code A -> B}.
     */
    private String stripSkillNodes(String graphJson, List<String> warnings) {
        if (graphJson == null || graphJson.isBlank()) return graphJson == null ? "" : graphJson;
        Map<String, Object> graph = readMap(graphJson);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        if (nodes.isEmpty()) return graphJson;
        Set<String> skillIds = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            if ("skill".equalsIgnoreCase(stringValue(node.get("type")))) {
                String id = stringValue(node.get("id"));
                if (!id.isBlank()) skillIds.add(id);
            }
        }
        if (skillIds.isEmpty()) return graphJson;

        List<Map<String, Object>> edges = readMapList(graph.get("edges"));
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String from = stringValue(edge.getOrDefault("from", edge.get("source")));
            String to = stringValue(edge.getOrDefault("to", edge.get("target")));
            if (from.isBlank() || to.isBlank()) continue;
            outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }

        Map<String, Set<String>> stitched = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = stringValue(node.get("id"));
            if (id.isBlank() || skillIds.contains(id)) continue;
            Set<String> seen = new java.util.HashSet<>();
            java.util.Deque<String> queue = new java.util.ArrayDeque<>(outgoing.getOrDefault(id, List.of()));
            while (!queue.isEmpty()) {
                String v = queue.pop();
                if (!seen.add(v)) continue;
                if (skillIds.contains(v)) {
                    queue.addAll(outgoing.getOrDefault(v, List.of()));
                } else if (!v.equals(id)) {
                    stitched.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(v);
                }
            }
        }

        List<Map<String, Object>> newEdges = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String from = stringValue(edge.getOrDefault("from", edge.get("source")));
            String to = stringValue(edge.getOrDefault("to", edge.get("target")));
            if (skillIds.contains(from) || skillIds.contains(to)) continue;
            newEdges.add(edge);
            Set<String> outs = stitched.get(from);
            if (outs != null) outs.remove(to);
        }
        for (Map.Entry<String, Set<String>> entry : stitched.entrySet()) {
            for (String to : entry.getValue()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("from", entry.getKey());
                e.put("to", to);
                newEdges.add(e);
            }
        }

        List<Map<String, Object>> newNodes = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (!skillIds.contains(stringValue(node.get("id")))) newNodes.add(node);
        }

        log.info("[ToolChainConfigChatService] Stripped {} skill node(s) from generated graph: {}",
                skillIds.size(), skillIds);
        warnings.add("Removed " + skillIds.size() + " skill node(s); skills now run inside the synthesis LLM.");

        Map<String, Object> stripped = new LinkedHashMap<>(graph);
        stripped.put("nodes", newNodes);
        stripped.put("edges", newEdges);
        return toJson(stripped);
    }

    String canonicalizeGraphToolNodeTypes(String graphJson,
                                          Set<String> validToolNames,
                                          Set<String> validMcpNames) {
        if (graphJson == null || graphJson.isBlank()) return graphJson == null ? "" : graphJson;
        Map<String, Object> graph = readMap(graphJson);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        if (nodes.isEmpty()) return graphJson;
        boolean changed = false;
        List<Map<String, Object>> canonicalNodes = new ArrayList<>(nodes.size());
        for (Map<String, Object> node : nodes) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if (!Set.of("tool", "mcp_tool").contains(type)) {
                canonicalNodes.add(node);
                continue;
            }
            Map<String, Object> config = readMap(node.get("config"));
            String toolName = stringValue(config.getOrDefault("toolName", config.get("name")));
            String canonicalType = canonicalizeToolNodeType(type, toolName, validToolNames, validMcpNames);
            if (!canonicalType.equals(type)) {
                Map<String, Object> updatedNode = new LinkedHashMap<>(node);
                updatedNode.put("type", canonicalType);
                canonicalNodes.add(updatedNode);
                changed = true;
            } else {
                canonicalNodes.add(node);
            }
        }
        if (!changed) return graphJson;
        Map<String, Object> normalized = new LinkedHashMap<>(graph);
        normalized.put("nodes", canonicalNodes);
        return toJson(normalized);
    }

    private String canonicalizeToolNodeType(String type,
                                            String toolName,
                                            Set<String> validToolNames,
                                            Set<String> validMcpNames) {
        String normalizedType = stringValue(type).toLowerCase(Locale.ROOT);
        if (!Set.of("tool", "mcp_tool").contains(normalizedType)) return normalizedType;
        String normalizedToolName = stringValue(toolName).trim();
        if (normalizedToolName.isBlank()) return normalizedType;
        boolean inTools = validToolNames.contains(normalizedToolName);
        boolean inMcp = validMcpNames.contains(normalizedToolName);
        if (inTools && !inMcp) return "tool";
        if (inMcp && !inTools) return "mcp_tool";
        return normalizedType;
    }

    private List<String> validateGraphAgainstCatalog(String graphJson,
                                                     Set<String> validToolNames,
                                                     Set<String> validSkillNames,
                                                     Set<String> validMcpNames) {
        List<String> issues = new ArrayList<>();
        if (graphJson == null || graphJson.isBlank()) {
            issues.add("graph is empty");
            return issues;
        }
        Map<String, Object> graph = readMap(graphJson);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        if (nodes.isEmpty()) {
            issues.add("graph has no nodes");
            return issues;
        }
        for (Map<String, Object> node : nodes) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if (!Set.of("start", "end", "tool", "mcp_tool", "decision", "synthesis", "code_execute").contains(type)) {
                issues.add("unknown node type '" + type + "' on " + node.get("id"));
                continue;
            }
            if (Set.of("tool", "mcp_tool").contains(type)) {
                Map<String, Object> config = readMap(node.get("config"));
                String toolName = stringValue(config.getOrDefault("toolName", config.get("name")));
                if (toolName.isBlank() || "task".equalsIgnoreCase(toolName)) {
                    issues.add("node " + node.get("id") + " is missing a real toolName");
                    continue;
                }
                Set<String> validForType = switch (type) {
                    case "tool" -> validToolNames;
                    case "mcp_tool" -> validMcpNames;
                    default -> Set.of();
                };
                if (!validForType.contains(toolName)) {
                    issues.add(type + " '" + toolName + "' (node " + node.get("id") + ") not in catalog");
                }
            }
            if ("code_execute".equals(type)) {
                Map<String, Object> config = readMap(node.get("config"));
                String language = stringValue(config.get("language")).toLowerCase(Locale.ROOT);
                String code = stringValue(config.get("code"));
                if (!Set.of("javascript", "typescript", "python", "java").contains(language)) {
                    issues.add("code_execute node " + node.get("id") + " has unsupported language '" + language + "'");
                }
                if (code.isBlank()) {
                    issues.add("code_execute node " + node.get("id") + " has empty code");
                }
                Object timeoutObj = config.get("timeoutMs");
                if (timeoutObj instanceof Number n && (n.longValue() < 250L || n.longValue() > 60_000L)) {
                    issues.add("code_execute node " + node.get("id") + " timeoutMs must be between 250 and 60000");
                }
                Object memoryObj = config.get("memoryLimitMb");
                if (memoryObj instanceof Number n && (n.intValue() < 16 || n.intValue() > 1024)) {
                    issues.add("code_execute node " + node.get("id") + " memoryLimitMb must be between 16 and 1024");
                }
                Object inputsObj = config.get("inputs");
                if (inputsObj != null && !(inputsObj instanceof List<?>)) {
                    issues.add("code_execute node " + node.get("id") + " inputs must be a list");
                }
                if (inputsObj instanceof List<?> rows) {
                    for (Object row : rows) {
                        if (!(row instanceof Map<?, ?> mapping)) {
                            issues.add("code_execute node " + node.get("id") + " inputs entries must be objects");
                            continue;
                        }
                        String name = stringValue(mapping.get("name"));
                        if (name.isBlank()) {
                            issues.add("code_execute node " + node.get("id") + " has input entry without name");
                        }
                    }
                }
                if ("java".equals(language)) {
                    String lowerCode = code.toLowerCase(Locale.ROOT);
                    if (lowerCode.contains("java.io")
                            || lowerCode.contains("java.net")
                            || lowerCode.contains("java.nio.file")
                            || lowerCode.contains("runtime.getruntime")
                            || lowerCode.contains("processbuilder")) {
                        issues.add("code_execute node " + node.get("id") + " uses blocked Java API");
                    }
                }
            }
        }
        return issues;
    }

    private void applyArtifactPatch(Map<String, Object> artifact, Map<String, Object> patch) {
        Object graphValue = patch.get("graphJson");
        if (graphValue instanceof Map<?, ?> graphMap) {
            artifact.put("graphJson", toJson(graphMap));
        } else if (graphValue != null && !stringValue(graphValue).isBlank()) {
            String raw = stringValue(graphValue).trim();
            // If the AI accidentally returned an object as Map.toString(), readMap will produce {} —
            // we accept only well-formed JSON strings.
            if (raw.startsWith("{") && raw.endsWith("}")) {
                artifact.put("graphJson", raw);
            }
        }
        List<String> intents = readStringList(patch.get("intents"));
        if (!intents.isEmpty()) {
            artifact.put("intents", intents.stream().distinct().limit(20).toList());
        }
        String responseMode = stringValue(patch.get("responseMode"));
        if (RESPONSE_MODES.contains(responseMode)) {
            artifact.put("responseMode", responseMode);
        }
        if (patch.get("synthesisPrompt") != null) {
            artifact.put("synthesisPrompt", stringValue(patch.get("synthesisPrompt")));
        }
        Map<String, Object> ragConfig = readMap(patch.get("ragConfig"));
        if (!ragConfig.isEmpty()) {
            artifact.put("ragConfig", ragConfig);
        }
        Map<String, Object> inputSchema = readMap(patch.get("inputSchema"));
        if (!inputSchema.isEmpty() && isMeaningfulSchema(inputSchema)) {
            artifact.put("inputSchema", inputSchema);
        }
        Map<String, Object> outputSchema = readMap(patch.get("outputSchema"));
        if (!outputSchema.isEmpty() && isMeaningfulSchema(outputSchema)) {
            artifact.put("outputSchema", outputSchema);
        }
    }

    private ModelRef resolveModelRef(ToolChainDtos.ToolChainConfigChatRequest request) {
        if (request == null || request.getModelRef() == null) return null;
        String providerId = request.getModelRef().get("providerID");
        String modelId = request.getModelRef().get("modelID");
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) return null;
        return new ModelRef(providerId, modelId);
    }

    private void applyAnswer(Map<String, Object> artifact, Map<String, Object> pendingQuestion, String answer) {
        String key = String.valueOf(pendingQuestion.getOrDefault("key", ""));
        if ("workflowIntent".equals(key)) {
            artifact.put("workflowIntent", answer.trim());
            return;
        }
        if ("responseMode".equals(key)) {
            String selected = String.valueOf(pendingQuestion.getOrDefault("selectedOptionId", ""));
            String normalized = (selected.isBlank() ? answer : selected).toLowerCase(Locale.ROOT).replace(" ", "_");
            if (!RESPONSE_MODES.contains(normalized)) normalized = "hybrid";
            artifact.put("responseMode", normalized);
            artifact.put("responseModeAsked", true);
            return;
        }
        if ("approvalScope".equals(key)) {
            String selected = String.valueOf(pendingQuestion.getOrDefault("selectedOptionId", answer)).trim().toLowerCase(Locale.ROOT);
            if (!Set.of("all_steps", "sensitive_only", "none").contains(selected)) {
                if (selected.contains("every")) selected = "all_steps";
                else if (selected.contains("sensitive")) selected = "sensitive_only";
                else selected = "none";
            }
            Map<String, Object> policy = switch (selected) {
                case "all_steps" -> Map.of("enabled", true, "mode", "required_all");
                case "sensitive_only" -> Map.of("enabled", true, "mode", "required_if_sensitive");
                default -> Map.of("enabled", false, "mode", "none");
            };
            artifact.put("approvalPolicy", policy);
            applyApprovalPolicyToGraph(artifact, String.valueOf(policy.get("mode")));
            return;
        }
        if ("outputJsonMode".equals(key)) {
            String selected = String.valueOf(pendingQuestion.getOrDefault("selectedOptionId", answer)).trim().toLowerCase(Locale.ROOT);
            if (!Set.of("strict_schema_json", "summary_plus_json", "raw_json_only").contains(selected)) {
                if (selected.contains("strict")) selected = "strict_schema_json";
                else if (selected.contains("summary")) selected = "summary_plus_json";
                else selected = "raw_json_only";
            }
            if ("strict_schema_json".equals(selected)) {
                artifact.put("outputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "status", Map.of("type", "string"),
                                "result", Map.of("type", "object"),
                                "summary", Map.of("type", "string")
                        ),
                        "required", List.of("status", "result")
                ));
            } else if ("summary_plus_json".equals(selected)) {
                artifact.put("outputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of("type", "string"),
                                "keyFindings", Map.of("type", "array"),
                                "data", Map.of("type", "object")
                        ),
                        "required", List.of("summary", "data")
                ));
            } else if ("raw_json_only".equals(selected)) {
                artifact.put("responseMode", "raw_graph_output");
                artifact.put("outputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("data", Map.of("type", "object")),
                        "required", List.of("data")
                ));
            }
            return;
        }
        if ("proposalDecision".equals(key)) {
            String selected = String.valueOf(pendingQuestion.getOrDefault("selectedOptionId", "")).trim().toLowerCase(Locale.ROOT);
            if ("accept".equals(selected)) {
                artifact.put("proposalAccepted", true);
                artifact.put("phase", "completed");
            } else {
                artifact.put("proposalAccepted", false);
                artifact.put("phase", "clarifying");
            }
            return;
        }
        if ("intents".equals(key)) {
            List<String> intents = new ArrayList<>();
            for (String p : answer.split(",")) {
                if (!p.isBlank()) intents.add(p.trim());
            }
            artifact.put("intents", intents);
            return;
        }
        if ("graph".equals(key)) {
            // Re-describe handler — applyAiInstruction will be invoked right after with the new
            // description, so we just record the answer as titleHint for naming purposes.
            if (artifact.get("titleHint") == null) {
                String hint = answer.length() > 70 ? answer.substring(0, 70) + "..." : answer;
                artifact.put("titleHint", hint);
            }
        }
    }

    private Map<String, Object> maybeAskClarification(Map<String, Object> artifact) {
        String workflowIntent = stringValue(artifact.get("workflowIntent")).trim();
        if (workflowIntent.isBlank()) {
            return Map.of(
                    "id", UUID.randomUUID().toString(),
                    "key", "workflowIntent",
                    "question", "What workflow should this ToolChain automate?",
                    "options", List.of()
            );
        }
        String mode = String.valueOf(artifact.getOrDefault("responseMode", ""));
        boolean modeAsked = Boolean.TRUE.equals(artifact.get("responseModeAsked"));
        if (!modeAsked || !RESPONSE_MODES.contains(mode)) {
            return Map.of(
                    "id", UUID.randomUUID().toString(),
                    "key", "responseMode",
                    "question", "Which response mode should this ToolChain use?",
                    "options", List.of(
                            Map.of("id", "hybrid", "label", "Hybrid (JSON + AI summary)"),
                            Map.of("id", "synthesized_text", "label", "AI Text"),
                            Map.of("id", "raw_graph_output", "label", "Raw Graph Output")
                    )
            );
        }
        Map<String, Object> approvalPolicy = readMap(artifact.get("approvalPolicy"));
        if (approvalPolicy.isEmpty()) {
            return Map.of(
                    "id", UUID.randomUUID().toString(),
                    "key", "approvalScope",
                    "question", "What approval policy should apply at runtime?",
                    "options", List.of(
                            Map.of("id", "none", "label", "No approvals"),
                            Map.of("id", "sensitive_only", "label", "Approvals for sensitive steps"),
                            Map.of("id", "all_steps", "label", "Approval for every step")
                    )
            );
        }
        if (!isMeaningfulSchema(readMap(artifact.get("outputSchema")))) {
            return Map.of(
                    "id", UUID.randomUUID().toString(),
                    "key", "outputJsonMode",
                    "question", "How should output JSON be shaped?",
                    "options", List.of(
                            Map.of("id", "strict_schema_json", "label", "Strict schema JSON"),
                            Map.of("id", "summary_plus_json", "label", "Summary + JSON payload"),
                            Map.of("id", "raw_json_only", "label", "Raw JSON only")
                    )
            );
        }
        if (!Boolean.TRUE.equals(artifact.get("requirementsComplete"))) {
            return Map.of();
        }
        if (artifact.get("graphJson") == null || String.valueOf(artifact.get("graphJson")).isBlank()) {
            String aiSummary = stringValue(artifact.get("lastAiSummary"));
            String question = aiSummary.isBlank()
                    ? "I have enough details to draft the graph. Do you want to refine the workflow goal before I generate it?"
                    : aiSummary;
            return Map.of(
                    "id", UUID.randomUUID().toString(),
                    "key", "graph",
                    "question", question,
                    "options", List.of()
            );
        }
        List<String> intents = readStringList(artifact.get("intents"));
        if (intents.isEmpty()) {
            return Map.of(
                    "id", UUID.randomUUID().toString(),
                    "key", "intents",
                    "question", "Which user intents should trigger this ToolChain? Provide one or more.",
                    "options", List.of()
            );
        }
        return Map.of();
    }

    private void validateArtifact(Map<String, Object> artifact) {
        String graphJson = String.valueOf(artifact.getOrDefault("graphJson", ""));
        if (graphJson.isBlank()) throw new IllegalArgumentException("Graph is required before compile.");
        List<String> intents = readStringList(artifact.get("intents"));
        if (intents.isEmpty()) throw new IllegalArgumentException("At least one intent is required before compile.");

        Map<String, Object> graph = readMap(graphJson);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        List<Map<String, Object>> edges = readMapList(graph.get("edges"));
        if (nodes.isEmpty()) throw new IllegalArgumentException("Graph must include at least one node.");

        Set<String> nodeIds = nodes.stream().map(n -> String.valueOf(n.get("id"))).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (nodeIds.size() != nodes.size()) throw new IllegalArgumentException("Graph node ids must be unique.");
        for (Map<String, Object> edge : edges) {
            String from = String.valueOf(edge.getOrDefault("from", edge.get("source")));
            String to = String.valueOf(edge.getOrDefault("to", edge.get("target")));
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) {
                throw new IllegalArgumentException("Graph edge references unknown node.");
            }
        }
        String responseMode = stringValue(artifact.getOrDefault("responseMode", "hybrid"));
        if (Set.of("hybrid", "synthesized_text").contains(responseMode)) {
            ensureSynthesisBeforeEnd(nodes, edges);
        }
        detectCycle(nodeIds, edges);
    }

    private void ensureSynthesisBeforeEnd(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Set<String> synthesisIds = nodes.stream()
                .filter(node -> "synthesis".equalsIgnoreCase(stringValue(node.get("type"))))
                .map(node -> stringValue(node.get("id")))
                .filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
        if (synthesisIds.isEmpty()) {
            throw new IllegalArgumentException("Hybrid/AI text mode requires a synthesis node before end.");
        }
        Set<String> endIds = nodes.stream()
                .filter(node -> "end".equalsIgnoreCase(stringValue(node.get("type"))))
                .map(node -> stringValue(node.get("id")))
                .filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
        if (endIds.isEmpty()) return;
        Map<String, Set<String>> incoming = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String from = stringValue(edge.getOrDefault("from", edge.get("source")));
            String to = stringValue(edge.getOrDefault("to", edge.get("target")));
            if (from.isBlank() || to.isBlank()) continue;
            incoming.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
        }
        for (String endId : endIds) {
            Set<String> parents = incoming.getOrDefault(endId, Set.of());
            boolean hasSynthesisParent = parents.stream().anyMatch(synthesisIds::contains);
            if (!hasSynthesisParent) {
                throw new IllegalArgumentException("Every end node must be reached from a synthesis node in hybrid/AI text mode.");
            }
        }
    }

    private void applyApprovalPolicyToGraph(Map<String, Object> artifact, String policyMode) {
        String rawGraph = stringValue(artifact.get("graphJson"));
        if (rawGraph.isBlank()) return;
        Map<String, Object> graph = readMap(rawGraph);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        if (nodes.isEmpty()) return;
        for (Map<String, Object> node : nodes) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if (!Set.of("tool", "mcp_tool").contains(type)) continue;
            Map<String, Object> config = new LinkedHashMap<>(readMap(node.get("config")));
            if ("required_all".equalsIgnoreCase(policyMode)) {
                config.put("approvalMode", "required");
            } else if ("required_if_sensitive".equalsIgnoreCase(policyMode)) {
                config.put("approvalMode", "required_if_sensitive");
            } else {
                config.remove("approvalMode");
            }
            node.put("config", config);
        }
        graph.put("nodes", nodes);
        artifact.put("graphJson", toJson(graph));
    }

    private void detectCycle(Set<String> nodeIds, List<Map<String, Object>> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (String id : nodeIds) {
            inDegree.put(id, 0);
            adj.put(id, new ArrayList<>());
        }
        for (Map<String, Object> edge : edges) {
            String from = String.valueOf(edge.getOrDefault("from", edge.get("source")));
            String to = String.valueOf(edge.getOrDefault("to", edge.get("target")));
            if (!adj.containsKey(from) || !adj.containsKey(to)) continue;
            adj.get(from).add(to);
            inDegree.put(to, inDegree.get(to) + 1);
        }
        Deque<String> q = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) q.add(entry.getKey());
        }
        int visited = 0;
        while (!q.isEmpty()) {
            String curr = q.poll();
            visited++;
            for (String next : adj.getOrDefault(curr, List.of())) {
                int nextDegree = inDegree.get(next) - 1;
                inDegree.put(next, nextDegree);
                if (nextDegree == 0) q.add(next);
            }
        }
        if (visited != nodeIds.size()) {
            throw new IllegalArgumentException("Graph must be acyclic (DAG validation failed).");
        }
    }

    private String deriveTitle(ToolChainConfigSession session, Map<String, Object> artifact) {
        Object titleHint = artifact.get("titleHint");
        if (titleHint != null && !String.valueOf(titleHint).isBlank()) {
            return truncateTitle(String.valueOf(titleHint));
        }
        // First user message — strongest signal that fires on turn 1, before the
        // architect has produced any intents.
        if (session != null && session.getId() != null) {
            try {
                List<ToolChainConfigMessage> rows = messageRepository.findBySessionId(session.getId());
                for (ToolChainConfigMessage m : rows) {
                    if (!"user".equalsIgnoreCase(m.getRole())) continue;
                    String c = m.getContent();
                    if (c == null || c.isBlank()) continue;
                    return toTitleCase(truncateTitle(c.trim()));
                }
            } catch (Exception ignored) {
            }
        }
        List<String> intents = readStringList(artifact.get("intents"));
        if (!intents.isEmpty()) return toTitleCase(truncateTitle(intents.get(0)));
        return "Untitled draft";
    }

    private static String truncateTitle(String raw) {
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 60) return collapsed;
        return collapsed.substring(0, 60).trim() + "…";
    }

    private static String toTitleCase(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String[] words = raw.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * Whether deriveTitle should overwrite this session's title. We respect
     * any user-set title (rename API), so we only refresh when the field is
     * empty or still carries a known auto-generated placeholder.
     */
    private boolean shouldAutoUpdateTitle(ToolChainConfigSession session) {
        if (session == null) return false;
        String t = session.getTitle();
        if (t == null || t.isBlank()) return true;
        String trimmed = t.trim();
        return "New ToolChain Session".equalsIgnoreCase(trimmed)
                || "Untitled session".equalsIgnoreCase(trimmed)
                || "Untitled draft".equalsIgnoreCase(trimmed)
                || "ToolChain config session".equalsIgnoreCase(trimmed);
    }

    /**
     * Pick a human-readable Name for a published ToolChain row from the artifact
     * the architect just produced. Used by publishFromArtifact so the row in the
     * ToolChains list reads like "Order Validation Pipeline" instead of echoing
     * the user's first prompt twice.
     */
    private String deriveChainName(String toolChainId, ToolChainConfigSession session, Map<String, Object> artifact) {
        String aiName = generateAiToolChainName(toolChainId, session, artifact);
        if (!aiName.isBlank()) {
            return appendStructuralSuffixIfMissing(toTitleCase(truncateTitle(aiName)), artifact);
        }
        Object titleHint = artifact.get("titleHint");
        if (titleHint != null && !String.valueOf(titleHint).isBlank()) {
            String cleaned = cleanNameCandidate(String.valueOf(titleHint));
            if (!cleaned.isBlank()) {
                return appendStructuralSuffixIfMissing(toTitleCase(truncateTitle(cleaned)), artifact);
            }
        }
        List<String> intents = readStringList(artifact.get("intents"));
        if (!intents.isEmpty()) {
            String cleaned = cleanNameCandidate(intents.get(0));
            if (!cleaned.isBlank()) {
                return appendStructuralSuffixIfMissing(toTitleCase(truncateTitle(cleaned)), artifact);
            }
        }
        String fromGraph = deriveNameFromGraph(artifact);
        if (!fromGraph.isBlank()) {
            return appendStructuralSuffixIfMissing(toTitleCase(truncateTitle(fromGraph)), artifact);
        }
        if (toolChainId != null && !toolChainId.isBlank()) {
            try {
                String existing = toolChainService.getRequired(toolChainId).getName();
                if (existing != null && !existing.isBlank()) {
                    String cleaned = cleanNameCandidate(existing);
                    if (!cleaned.isBlank() && !looksPromptLike(cleaned)) {
                        return appendStructuralSuffixIfMissing(toTitleCase(truncateTitle(cleaned)), artifact);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "Untitled " + structuralSuffix(artifact);
    }

    private String generateAiToolChainName(String toolChainId, ToolChainConfigSession session, Map<String, Object> artifact) {
        ModelRef modelRef = resolveNameGenerationModelRef(toolChainId, session);
        if (modelRef == null) return "";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowIntent", stringValue(artifact.get("workflowIntent")));
        payload.put("intents", readStringList(artifact.get("intents")));
        payload.put("responseMode", stringValue(artifact.getOrDefault("responseMode", "hybrid")));
        payload.put("proposalSummary", stringValue(artifact.get("proposalSummary")));
        payload.put("graphJson", readMap(artifact.get("graphJson")));
        String systemPrompt = """
                You name AI workflow toolchains.
                Return ONLY JSON: {"name":"..."}.
                Rules:
                - 2 to 6 words, concise and professional.
                - Must describe business purpose, not implementation details.
                - Do not start with verbs like Build/Create/Make/Generate/Design.
                - Do not include quotes, punctuation suffixes, or emojis.
                - Prefer title case.
                """;
        try {
            String raw = modelProviderRouter.resolve(modelRef, true)
                    .client()
                    .prompt()
                    .system(systemPrompt)
                    .user(toJson(payload))
                    .call()
                    .content();
            Map<String, Object> parsed = readMap(extractJsonObject(raw));
            String candidate = cleanNameCandidate(stringValue(parsed.get("name")));
            if (candidate.isBlank()) return "";
            if (looksPromptLike(candidate)) return "";
            return candidate;
        } catch (Exception e) {
            log.warn("[ToolChainConfigChatService] AI name generation failed for {}: {}", toolChainId, e.getMessage());
            return "";
        }
    }

    private ModelRef resolveNameGenerationModelRef(String toolChainId, ToolChainConfigSession session) {
        if (session != null && session.getId() != null) {
            try {
                List<ToolChainConfigMessage> rows = messageRepository.findBySessionId(session.getId());
                for (int i = rows.size() - 1; i >= 0; i--) {
                    ToolChainConfigMessage row = rows.get(i);
                    Map<String, Object> metadata = readMap(row.getMetadataJson());
                    Map<String, Object> modelRef = readMap(metadata.get("modelRef"));
                    String providerId = stringValue(modelRef.get("providerID"));
                    String modelId = stringValue(modelRef.get("modelID"));
                    if (!providerId.isBlank() && !modelId.isBlank()) {
                        return new ModelRef(providerId, modelId);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (toolChainId != null && !toolChainId.isBlank()) {
            try {
                ToolChain chain = toolChainService.getRequired(toolChainId);
                Map<String, Object> metadata = readMap(chain.getMetadataJson());
                Map<String, Object> modelRef = readMap(metadata.get("defaultModelRef"));
                String providerId = stringValue(modelRef.get("providerID"));
                String modelId = stringValue(modelRef.get("modelID"));
                if (!providerId.isBlank() && !modelId.isBlank()) {
                    return new ModelRef(providerId, modelId);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Pick a one-line Description for a published ToolChain row. Prefers the
     * architect's own graphJson.description; falls back to proposalSummary
     * (the architect's assistantMessage on the approval card) and finally to
     * a structural sentence derived from the graph + responseMode.
     */
    private String deriveChainDescription(Map<String, Object> artifact) {
        Map<String, Object> graph = readMap(artifact.get("graphJson"));
        Object graphDesc = graph.get("description");
        if (graphDesc != null && !String.valueOf(graphDesc).isBlank()) {
            return truncateDescription(String.valueOf(graphDesc));
        }
        Object proposalSummary = artifact.get("proposalSummary");
        if (proposalSummary != null && !String.valueOf(proposalSummary).isBlank()) {
            return truncateDescription(String.valueOf(proposalSummary));
        }
        int toolNodes = 0;
        boolean hasSynthesis = false;
        for (Map<String, Object> node : readMapList(graph.get("nodes"))) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if ("tool".equals(type) || "mcp_tool".equals(type)) toolNodes++;
            if ("synthesis".equals(type)) hasSynthesis = true;
        }
        String mode = stringValue(artifact.getOrDefault("responseMode", "hybrid"));
        if (toolNodes == 0) return "ToolChain (responseMode=" + mode + ").";
        return (toolNodes > 1 ? "Parallel" : "Single-tool")
                + " fetch of " + toolNodes + " tool" + (toolNodes == 1 ? "" : "s")
                + (hasSynthesis ? " followed by synthesis" : "")
                + " (responseMode=" + mode + ").";
    }

    private String structuralSuffix(Map<String, Object> artifact) {
        Map<String, Object> graph = readMap(artifact.get("graphJson"));
        int toolNodes = 0;
        boolean hasSynthesis = false;
        for (Map<String, Object> node : readMapList(graph.get("nodes"))) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if ("tool".equals(type) || "mcp_tool".equals(type)) toolNodes++;
            if ("synthesis".equals(type)) hasSynthesis = true;
        }
        if (toolNodes >= 2 && hasSynthesis) return "Pipeline";
        if (toolNodes >= 2) return "Workflow";
        if (toolNodes == 1) return "Lookup";
        return "ToolChain";
    }

    private String appendStructuralSuffixIfMissing(String base, Map<String, Object> artifact) {
        String normalizedBase = truncateTitle(base == null ? "" : base);
        if (normalizedBase.isBlank()) return "Untitled " + structuralSuffix(artifact);
        String suffix = structuralSuffix(artifact);
        String lower = normalizedBase.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" pipeline")
                || lower.endsWith(" workflow")
                || lower.endsWith(" lookup")
                || lower.endsWith(" toolchain")) {
            return normalizedBase;
        }
        return normalizedBase + " " + suffix;
    }

    private String deriveNameFromGraph(Map<String, Object> artifact) {
        Map<String, Object> graph = readMap(artifact.get("graphJson"));
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        if (nodes.isEmpty()) return "";
        Map<String, Integer> tokenCounts = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if ("start".equals(type) || "end".equals(type) || "synthesis".equals(type)) continue;
            String label = cleanNameCandidate(stringValue(node.get("label")));
            if (label.isBlank()) continue;
            for (String token : label.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() < 3) continue;
                if (Set.of("get", "set", "run", "call", "tool", "step", "check", "active", "node").contains(token)) continue;
                tokenCounts.put(token, tokenCounts.getOrDefault(token, 0) + 1);
            }
        }
        if (tokenCounts.isEmpty()) return "";
        List<String> top = tokenCounts.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    if (byCount != 0) return byCount;
                    return a.getKey().compareTo(b.getKey());
                })
                .map(Map.Entry::getKey)
                .limit(3)
                .toList();
        return String.join(" ", top);
    }

    private static boolean looksPromptLike(String text) {
        if (text == null || text.isBlank()) return true;
        String value = text.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("build ")
                || value.startsWith("create ")
                || value.startsWith("make ")
                || value.startsWith("design ")
                || value.startsWith("generate ")
                || value.contains("toolchain that")
                || value.contains("workflow that")
                || value.contains("pipeline that");
    }

    private static String cleanNameCandidate(String raw) {
        if (raw == null) return "";
        String value = raw.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        if (value.isBlank()) return "";
        value = value.replaceAll("(?i)^(please\\s+)?(build|create|make|design|generate)\\s+(me\\s+)?(a|an|the)?\\s*(ai\\s+)?(tool\\s*chain|toolchain|workflow|pipeline)\\s*(that|to)?\\s*", "");
        value = value.replaceAll("(?i)^for\\s+", "");
        value = value.replaceAll("^[\\p{Punct}\\s]+", "").replaceAll("[\\p{Punct}\\s]+$", "");
        return value;
    }

    private static String truncateDescription(String raw) {
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 280) return collapsed;
        return collapsed.substring(0, 280).trim() + "…";
    }

    private String latestAssistantMessage(Map<String, Object> detail) {
        List<Map<String, Object>> messages = readMapList(detail.get("messages"));
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if ("assistant".equals(String.valueOf(message.get("role")))) {
                return String.valueOf(message.getOrDefault("content", ""));
            }
        }
        return "";
    }

    private void streamTextDelta(SseEventSender sender, String content) {
        // The toolchain config flow doesn't yet stream the LLM in real time
        // (processMessage runs synchronously and we chunk the result here).
        // Pace the chunks so the browser actually paints them progressively
        // — without this delay, all chunks land in the same network frame
        // and the user only sees "Thinking..." then a sudden full reply.
        int chunkSize = 8;
        long perChunkDelayMillis = 15L;
        for (int i = 0; i < content.length(); i += chunkSize) {
            sender.sendTextDelta(content.substring(i, Math.min(content.length(), i + chunkSize)));
            if (perChunkDelayMillis > 0 && i + chunkSize < content.length()) {
                try {
                    Thread.sleep(perChunkDelayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Map<String, Object> normalizeLayoutPositions(Map<String, Object> rawPositions) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawPositions.entrySet()) {
            String nodeId = stringValue(entry.getKey()).trim();
            if (nodeId.isBlank()) continue;
            Map<String, Object> coords = readMap(entry.getValue());
            Double x = asDouble(coords.get("x"));
            Double y = asDouble(coords.get("y"));
            if (x == null || y == null) continue;
            normalized.put(nodeId, Map.of("x", x, "y", y));
        }
        return normalized;
    }

    private Map<String, Object> defaultArtifact() {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("graphJson", "");
        artifact.put("inputSchema", Map.of("type", "object"));
        artifact.put("outputSchema", Map.of("type", "object"));
        artifact.put("responseMode", "hybrid");
        artifact.put("responseModeAsked", false);
        artifact.put("synthesisPrompt", "");
        artifact.put("intents", List.of());
        artifact.put("ragConfig", Map.of());
        artifact.put("phase", "clarifying");
        artifact.put("workflowIntent", "");
        artifact.put("requirementsComplete", false);
        artifact.put("draftGeneratedAt", null);
        artifact.put("proposalAccepted", false);
        artifact.put("proposalSummary", "");
        return artifact;
    }

    private List<String> extractIntentCandidates(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("[\\n,;]")) {
            String normalized = part.trim();
            if (!normalized.isBlank() && normalized.length() >= 4 && normalized.length() <= 80) {
                out.add(normalized);
            }
        }
        return out;
    }

    private String buildArtifactSummary(String artifactJson) {
        return buildArtifactSummary(readMap(artifactJson));
    }

    private String buildArtifactSummary(Map<String, Object> artifact) {
        List<String> parts = new ArrayList<>();
        if (artifact.get("responseMode") != null && !String.valueOf(artifact.get("responseMode")).isBlank()) {
            parts.add("responseMode=" + artifact.get("responseMode"));
        }
        List<String> intents = readStringList(artifact.get("intents"));
        if (!intents.isEmpty()) {
            parts.add("intents=" + intents.stream().limit(5).reduce((a, b) -> a + ", " + b).orElse(""));
        }
        if (artifact.get("ragConfig") != null && !readMap(artifact.get("ragConfig")).isEmpty()) {
            parts.add("rag=enabled");
        }
        if (artifact.get("graphJson") != null && !String.valueOf(artifact.get("graphJson")).isBlank()) {
            parts.add("graph=drafted");
        }
        Map<String, Object> context = readMap(artifact.get("contextBundle"));
        if (!context.isEmpty()) {
            parts.add("versions=" + context.getOrDefault("versionCount", 0));
            parts.add("recentRuns=" + context.getOrDefault("recentRunsCount", 0));
            parts.add("pendingApprovals=" + context.getOrDefault("pendingApprovals", 0));
        }
        return parts.isEmpty() ? "No configuration artifacts yet." : String.join(" | ", parts);
    }

    private Map<String, Object> loadContextBundle(String toolChainId, Map<String, Object> artifact) {
        ToolChain chain = toolChainService.getRequired(toolChainId);
        List<ToolChainVersion> versions = toolChainService.listVersions(toolChainId);
        ToolChainVersion latest = versions.isEmpty() ? null : versions.get(0);
        ToolChainVersion published = versions.stream().filter(ToolChainVersion::isPublished).findFirst().orElse(null);

        String graphJson = stringValue(artifact.get("graphJson"));
        Map<String, Object> graph = readMap(graphJson);
        List<Map<String, Object>> nodes = readMapList(graph.get("nodes"));
        List<Map<String, Object>> edges = readMapList(graph.get("edges"));

        List<ToolChainRun> recentRuns = toolChainRunRepository.findByChain(toolChainId, 15, 0);
        Set<String> runIds = recentRuns.stream().map(ToolChainRun::getId).collect(Collectors.toSet());
        long pendingApprovals = toolChainApprovalRepository.findPending().stream()
                .filter(a -> runIds.contains(a.getRunId()))
                .count();

        List<AgentTool> availableTools = agentToolRepository.findAll();
        List<Skill> availableSkills = skillRepository.findAll();
        List<McpRegistryEntry> availableMcpEntries = mcpRegistryRepository.findEnabled();

        Set<String> referencedToolNames = findReferencedNames(nodes, "tool", "toolName", "name");
        Set<String> referencedSkillNames = findReferencedNames(nodes, "skill", "skillName", "name");
        Set<String> referencedMcpNames = findReferencedNames(nodes, "mcp", "mcpName", "name");

        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> toolChainMeta = new LinkedHashMap<>();
        toolChainMeta.put("id", chain.getId());
        toolChainMeta.put("name", chain.getName());
        toolChainMeta.put("description", Optional.ofNullable(chain.getDescription()).orElse(""));
        toolChainMeta.put("status", Optional.ofNullable(chain.getStatus()).orElse("draft"));
        toolChainMeta.put("currentVersion", chain.getCurrentVersion());
        context.put("toolChain", toolChainMeta);
        context.put("versionCount", versions.size());
        context.put("latestVersion", latest == null ? null : latest.getVersion());
        context.put("publishedVersion", published == null ? null : published.getVersion());
        context.put("nodeCount", nodes.size());
        context.put("edgeCount", edges.size());
        context.put("recentRunsCount", recentRuns.size());
        context.put("pendingApprovals", pendingApprovals);
        context.put("recentRunStatuses", recentRuns.stream()
                .collect(Collectors.groupingBy(ToolChainRun::getStatus, Collectors.counting())));
        Map<String, Object> references = new LinkedHashMap<>();
        references.put("tools", referencedToolNames);
        references.put("skills", referencedSkillNames);
        references.put("mcp", referencedMcpNames);
        context.put("references", references);

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("toolsAvailable", availableTools.size());
        catalog.put("skillsAvailable", availableSkills.size());
        catalog.put("mcpAvailable", availableMcpEntries.size());
        catalog.put("topTools", availableTools.stream().limit(8).map(AgentTool::getName).toList());
        catalog.put("topSkills", availableSkills.stream().limit(8).map(Skill::getName).toList());
        catalog.put("topMcp", availableMcpEntries.stream().limit(8).map(McpRegistryEntry::getName).toList());
        context.put("catalog", catalog);

        context.put("recentRuns", recentRuns.stream().limit(10).map(r -> {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("runId", r.getId());
            run.put("version", r.getVersion());
            run.put("status", r.getStatus());
            run.put("startedAt", r.getStartedAt());
            run.put("endedAt", r.getEndedAt());
            return run;
        }).toList());
        if (latest != null) {
            Map<String, Object> latestConfig = new LinkedHashMap<>();
            latestConfig.put("responseMode", latest.getResponseMode());
            latestConfig.put("hasInputSchema", latest.getInputSchema() != null && !latest.getInputSchema().isBlank());
            latestConfig.put("hasOutputSchema", latest.getOutputSchema() != null && !latest.getOutputSchema().isBlank());
            latestConfig.put("intents", readStringList(latest.getIntentsJson()));
            latestConfig.put("ragEnabled", !readMap(latest.getRagConfigJson()).isEmpty());
            context.put("latestConfig", latestConfig);
            context.put("latestInputSchema", readMap(latest.getInputSchema()));
            context.put("latestOutputSchema", readMap(latest.getOutputSchema()));
            context.put("referenceGraphJson", stringValue(latest.getGraphJson()));
        }
        return context;
    }

    private void ensureLifecycleDefaults(Map<String, Object> artifact) {
        if (!artifact.containsKey("phase") || stringValue(artifact.get("phase")).isBlank()) {
            artifact.put("phase", "clarifying");
        }
        if (!artifact.containsKey("workflowIntent")) {
            artifact.put("workflowIntent", "");
        }
        if (!artifact.containsKey("requirementsComplete")) {
            artifact.put("requirementsComplete", false);
        }
    }

    private void refreshRequirementFlags(Map<String, Object> artifact) {
        String workflowIntent = stringValue(artifact.get("workflowIntent")).trim();
        String mode = String.valueOf(artifact.getOrDefault("responseMode", ""));
        boolean modeAsked = Boolean.TRUE.equals(artifact.get("responseModeAsked"));
        boolean hasApprovalPolicy = !readMap(artifact.get("approvalPolicy")).isEmpty();
        boolean hasOutputMode = isMeaningfulSchema(readMap(artifact.get("outputSchema")));
        boolean requirementsComplete = !workflowIntent.isBlank()
                && modeAsked
                && RESPONSE_MODES.contains(mode)
                && hasApprovalPolicy
                && hasOutputMode;
        if (!requirementsComplete) {
            String phase = stringValue(artifact.get("phase"));
            boolean hasGraph = !stringValue(artifact.get("graphJson")).isBlank();
            if (hasGraph && Set.of("awaiting_approval", "proposal_ready", "completed").contains(phase)) {
                requirementsComplete = true;
            }
            // Edit-mode on a published chain: a fresh config session won't have any of the
            // creation-ladder fields (workflowIntent / responseModeAsked / approvalPolicy)
            // because the user didn't go through the creation flow — they're just editing.
            // The graph itself is the proof of completeness, so keep the flag true once a
            // direct edit-commit has set it.
            if (!requirementsComplete && hasGraph && Boolean.TRUE.equals(artifact.get("editCommitted"))) {
                requirementsComplete = true;
            }
        }
        artifact.put("requirementsComplete", requirementsComplete);
    }

    private String resolveDraftInstruction(Map<String, Object> artifact,
                                           String normalizedInput,
                                           String pendingKey,
                                           boolean fromPendingQuestion) {
        String workflowIntent = stringValue(artifact.get("workflowIntent")).trim();
        String latestInput = normalizedInput == null ? "" : normalizedInput.trim();
        if ("workflowIntent".equals(pendingKey) || "graph".equals(pendingKey) || "proposalDecision".equals(pendingKey)) {
            return latestInput.isBlank() ? workflowIntent : latestInput;
        }
        if (fromPendingQuestion) {
            return workflowIntent;
        }
        if (!latestInput.isBlank()) {
            return latestInput;
        }
        return workflowIntent;
    }

    private boolean shouldExposeGraph(Map<String, Object> artifact, Map<String, Object> pendingQuestion) {
        if (!Boolean.TRUE.equals(artifact.get("requirementsComplete"))) {
            return false;
        }
        String graphJson = stringValue(artifact.get("graphJson"));
        if (graphJson.isBlank()) {
            return false;
        }
        String pendingKey = stringValue(pendingQuestion.get("key"));
        if (pendingQuestion.isEmpty()) {
            return true;
        }
        return "proposalDecision".equals(pendingKey);
    }

    /**
     * Resolve the graph to expose to the frontend. During awaiting_approval the live
     * artifact.graphJson is empty (the patch hasn't been applied yet), so fall back to
     * pendingArtifactPatch.graphJson so the flow board can preview the proposed graph
     * alongside the approval card.
     */
    private String resolveExposedGraphJson(Map<String, Object> artifact) {
        String graphJson = stringValue(artifact.get("graphJson"));
        if (!graphJson.isBlank()) {
            return graphJson;
        }
        Map<String, Object> patch = readMap(artifact.get("pendingArtifactPatch"));
        if (patch.isEmpty()) {
            return "";
        }
        Object pendingGraph = patch.get("graphJson");
        if (pendingGraph instanceof String s) {
            return s;
        }
        if (pendingGraph instanceof Map<?, ?> || pendingGraph instanceof List<?>) {
            return toJson(pendingGraph);
        }
        return "";
    }

    private void ensureSchemaFallback(Map<String, Object> artifact, Map<String, Object> contextBundle) {
        Map<String, Object> currentInputSchema = readMap(artifact.get("inputSchema"));
        if (!isMeaningfulSchema(currentInputSchema)) {
            Map<String, Object> latestInputSchema = readMap(contextBundle.get("latestInputSchema"));
            if (isMeaningfulSchema(latestInputSchema)) {
                artifact.put("inputSchema", latestInputSchema);
            }
        }
        Map<String, Object> currentOutputSchema = readMap(artifact.get("outputSchema"));
        if (!isMeaningfulSchema(currentOutputSchema)) {
            Map<String, Object> latestOutputSchema = readMap(contextBundle.get("latestOutputSchema"));
            if (isMeaningfulSchema(latestOutputSchema)) {
                artifact.put("outputSchema", latestOutputSchema);
            }
        }
    }

    private boolean isMeaningfulSchema(Map<String, Object> schema) {
        if (schema.isEmpty()) return false;
        String type = stringValue(schema.get("type"));
        if (!type.isBlank() && !"object".equalsIgnoreCase(type)) return true;
        Map<String, Object> properties = readMap(schema.get("properties"));
        if (!properties.isEmpty()) return true;
        List<String> required = readStringList(schema.get("required"));
        return !required.isEmpty();
    }

    private Optional<String> buildContextualAnswer(String input, Map<String, Object> contextBundle, Map<String, Object> pendingQuestion) {
        if (input == null || input.isBlank()) return Optional.empty();
        String lower = input.toLowerCase(Locale.ROOT);
        boolean isQuestion = input.contains("?") || lower.startsWith("what") || lower.startsWith("how")
                || lower.startsWith("show") || lower.startsWith("which") || lower.startsWith("list")
                || lower.startsWith("tell") || lower.startsWith("do we") || lower.startsWith("is ");
        if (!isQuestion) return Optional.empty();

        Map<String, Object> toolChain = readMap(contextBundle.get("toolChain"));
        Map<String, Object> references = readMap(contextBundle.get("references"));
        Map<String, Object> latestConfig = readMap(contextBundle.get("latestConfig"));
        List<Map<String, Object>> recentRuns = readMapList(contextBundle.get("recentRuns"));
        Object statusesObj = contextBundle.get("recentRunStatuses");

        StringBuilder answer = new StringBuilder();
        answer.append("Here is the current ToolChain context: ");
        answer.append("name=").append(toolChain.getOrDefault("name", "n/a"));
        answer.append(", versions=").append(contextBundle.getOrDefault("versionCount", 0));
        answer.append(", latestVersion=").append(contextBundle.getOrDefault("latestVersion", "n/a"));
        answer.append(", publishedVersion=").append(contextBundle.getOrDefault("publishedVersion", "n/a"));
        answer.append(", nodes=").append(contextBundle.getOrDefault("nodeCount", 0));
        answer.append(", edges=").append(contextBundle.getOrDefault("edgeCount", 0));
        answer.append(", recentRuns=").append(contextBundle.getOrDefault("recentRunsCount", 0));
        answer.append(", pendingApprovals=").append(contextBundle.getOrDefault("pendingApprovals", 0)).append(". ");

        if (lower.contains("intent")) {
            answer.append("Intents: ").append(readStringList(latestConfig.get("intents"))).append(". ");
        }
        if (lower.contains("response") || lower.contains("mode")) {
            answer.append("Response mode: ").append(latestConfig.getOrDefault("responseMode", "not set")).append(". ");
        }
        if (lower.contains("rag")) {
            answer.append("RAG enabled: ").append(Boolean.TRUE.equals(latestConfig.get("ragEnabled"))).append(". ");
        }
        if (lower.contains("tool") || lower.contains("skill") || lower.contains("mcp")) {
            answer.append("Referenced tools=").append(references.getOrDefault("tools", List.of())).append(", ");
            answer.append("skills=").append(references.getOrDefault("skills", List.of())).append(", ");
            answer.append("mcp=").append(references.getOrDefault("mcp", List.of())).append(". ");
        }
        if (lower.contains("run") || lower.contains("status") || lower.contains("approval")) {
            answer.append("Recent run statuses=").append(String.valueOf(statusesObj)).append(". ");
            if (!recentRuns.isEmpty()) {
                answer.append("Latest run=").append(recentRuns.get(0).get("runId"))
                        .append(" (status=").append(recentRuns.get(0).get("status")).append("). ");
            }
        }
        if (!pendingQuestion.isEmpty()) {
            answer.append("There is a pending clarification: ")
                    .append(pendingQuestion.getOrDefault("question", "Please answer the pending question to proceed."));
        }
        return Optional.of(answer.toString().trim());
    }

    private Set<String> findReferencedNames(List<Map<String, Object>> nodes, String nodeType, String... configKeys) {
        Set<String> names = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            String type = stringValue(node.get("type")).toLowerCase(Locale.ROOT);
            if (!nodeType.equals(type)) continue;
            Map<String, Object> config = readMap(node.get("config"));
            for (String key : configKeys) {
                String value = stringValue(config.get(key));
                if (!value.isBlank()) names.add(value);
            }
        }
        return names;
    }

    record ToolInputContract(List<String> requiredInputKeys, List<String> pathParams) {}

    record GraphRequirementsResult(String normalizedGraphJson, List<String> warnings, List<String> errors) {
        boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }

        boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }

    private Map<String, Object> readMap(Object jsonOrMap) {
        if (jsonOrMap == null) return Map.of();
        if (jsonOrMap instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        String raw = String.valueOf(jsonOrMap);
        if (raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object row : list) out.add(readMap(row));
            return out;
        }
        return List.of();
    }

    private List<String> readStringList(Object rawValue) {
        if (rawValue == null) return new ArrayList<>();
        if (rawValue instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String raw = String.valueOf(rawValue).trim();
        if (raw.isBlank()) return new ArrayList<>();
        try {
            List<Object> parsed = objectMapper.readValue(raw, new TypeReference<List<Object>>() {});
            return parsed.stream()
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            return new ArrayList<>(List.of(raw));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String asJsonString(Object value) {
        if (value instanceof String raw) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        }
        return toJson(value);
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return "{}";
        String text = raw.trim();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            double out = number.doubleValue();
            return Double.isFinite(out) ? out : null;
        }
        if (value == null) return null;
        try {
            double out = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(out) ? out : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
