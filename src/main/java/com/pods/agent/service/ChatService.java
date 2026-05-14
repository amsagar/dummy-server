package com.pods.agent.service;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.AgentSessionManager;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.api.dto.ChatRequest;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.ChatMessage;
import com.pods.agent.domain.CostUsage;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.SessionContextState;
import com.pods.agent.repository.ChatMessageRepository;
import com.pods.agent.repository.ChatSessionRepository;
import com.pods.agent.repository.CostUsageRepository;
import com.pods.agent.repository.HitlInteractionRepository;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.RuntimeTraceRepository;
import com.pods.agent.repository.SessionContextStateRepository;
import com.pods.agent.service.workspace.ExecutionLogService;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import com.pods.agent.service.workspace.WorkspaceSkillSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Manages a single chat turn with full DB persistence:
 *
 *  1. Resolve / create session
 *  2. Restore message history from DB if session was just reconnected
 *  3. Persist user message
 *  4. Invoke AgentOrchestrator (streams text deltas)
 *  5. Persist assistant response
 *  6. Update session last_active
 *  7. If first message: generate LLM title, persist, emit session.updated
 *  8. Stream done event
 */
@Service
@Slf4j
public class ChatService {
    private static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "xml", "yaml", "yml", "log",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg", "webp"
    );
    private static final int MAX_ATTACHMENTS = 5;
    private static final long MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L;

    private final AgentSessionManager sessionManager;
    private final AgentRuntimeService agentRuntimeService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final CostUsageRepository costUsageRepository;
    private final RuntimeEventRepository runtimeEventRepository;
    private final RuntimeTraceRepository runtimeTraceRepository;
    private final HitlInteractionRepository hitlInteractionRepository;
    private final ContextSummarizationService summarizationService;
    private final SessionContextStateRepository sessionContextStateRepository;
    private final ModelRegistryService modelRegistryService;
    private final ModelProviderRouter modelProviderRouter;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final WorkspaceSkillSyncService workspaceSkillSyncService;
    private final SystemToolChainAsyncService systemToolChainAsyncService;
    private final ExecutionLogService executionLogService;
    private final ObjectMapper objectMapper;

    public ChatService(AgentSessionManager sessionManager,
                       ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       AgentRuntimeService agentRuntimeService,
                       CostUsageRepository costUsageRepository,
                       RuntimeEventRepository runtimeEventRepository,
                       RuntimeTraceRepository runtimeTraceRepository,
                       HitlInteractionRepository hitlInteractionRepository,
                       ContextSummarizationService summarizationService,
                       SessionContextStateRepository sessionContextStateRepository,
                       ModelRegistryService modelRegistryService,
                       ModelProviderRouter modelProviderRouter,
                       RuntimeTuningProperties runtimeTuningProperties,
                       SessionWorkspaceService sessionWorkspaceService,
                       WorkspaceSkillSyncService workspaceSkillSyncService,
                       SystemToolChainAsyncService systemToolChainAsyncService,
                       ExecutionLogService executionLogService,
                       ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.agentRuntimeService = agentRuntimeService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.costUsageRepository = costUsageRepository;
        this.runtimeEventRepository = runtimeEventRepository;
        this.runtimeTraceRepository = runtimeTraceRepository;
        this.hitlInteractionRepository = hitlInteractionRepository;
        this.summarizationService = summarizationService;
        this.sessionContextStateRepository = sessionContextStateRepository;
        this.modelRegistryService = modelRegistryService;
        this.modelProviderRouter = modelProviderRouter;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.workspaceSkillSyncService = workspaceSkillSyncService;
        this.systemToolChainAsyncService = systemToolChainAsyncService;
        this.executionLogService = executionLogService;
        this.objectMapper = objectMapper;
    }

    public void handleChatAsync(ChatRequest request, SseEmitter emitter, String userId) {
        CompletableFuture.runAsync(() -> {
            SseEventSender sender = new SseEventSender(emitter, objectMapper);

            try {
                validateAttachments(request);
                AgentSession session = sessionManager.getOrCreate(request.getSessionId(), userId);
                String sessionId = session.getSessionId();
                session.setActiveEmitter(emitter);
                emitter.onCompletion(() -> {
                    session.cancel();
                    session.setActiveEmitter(null);
                    log.debug("[ChatService] SSE completed for session={}", sessionId);
                });
                emitter.onTimeout(() -> {
                    session.cancel();
                    session.setActiveEmitter(null);
                    log.warn("[ChatService] SSE timed out for session={}", sessionId);
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                });
                emitter.onError(ex -> {
                    session.cancel();
                    session.setActiveEmitter(null);
                    log.warn("[ChatService] SSE error for session={}: {}", sessionId, ex != null ? ex.getMessage() : "unknown");
                });

                ChatState state = buildEffectiveState(session, request);
                session.setActiveState(state);

                boolean noHistoryYet = session.getMessages().isEmpty();

                if (noHistoryYet) {
                    restoreHistory(session, sessionId);
                }

                boolean isFirstMessage = noHistoryYet && session.getMessages().isEmpty();

                sender.sendConnected(sessionId);
                log.info("[ChatService] Chat turn: session={}, model={}, firstMessage={}",
                        sessionId, state.getModel(), isFirstMessage);
                Path workspace = sessionWorkspaceService.getOrCreate(sessionId);
                session.setWorkspacePath(workspace);
                workspaceSkillSyncService.sync(workspace);
                session.beginTurn();
                String turnId = UUID.randomUUID().toString();
                if (runtimeTuningProperties.isPersistInternalEvents()) {
                    runtimeEventRepository.save(RuntimeEvent.builder()
                            .sessionId(sessionId)
                            .turnId(turnId)
                            .eventType("workspace.ready")
                            .payload("{\"path\":\"" + workspace.toString().replace("\\", "/") + "\"}")
                            .build());
                    runtimeEventRepository.save(RuntimeEvent.builder()
                            .sessionId(sessionId)
                            .turnId(turnId)
                            .eventType("skills.synced")
                            .payload("{\"root\":\"" + workspace.resolve(".pods-agent/skills").toString().replace("\\", "/") + "\"}")
                            .build());
                }

                String providerHint = state.getModel() != null ? state.getModel().providerID() : null;
                ContextSummarizationService.CompactionResult compactionResult = summarizationService.maybeSummarize(
                        session,
                        state.getRollingSummary(),
                        runtimeTuningProperties.getSummaryTokenThreshold(),
                        providerHint,
                        runtimeTuningProperties.getSummaryRetainRecentMessages());
                if (compactionResult.compacted()) {
                    summarizationService.retainRecentMessages(session, runtimeTuningProperties.getSummaryRetainRecentMessages());
                }
                if (compactionResult.summary() != null && !compactionResult.summary().equals(state.getRollingSummary())) {
                    state.setRollingSummary(compactionResult.summary());
                    sender.sendSummaryUpdated(sessionId, compactionResult.summary());
                }

                String finalUserMessage = enrichMessageWithAttachments(request);

                if (isFirstMessage) {
                    String sourceMessage = request.getMessage() == null || request.getMessage().isBlank() ? finalUserMessage : request.getMessage();
                    scheduleAsyncTitleGeneration(sessionId, userId, sourceMessage, state, sender);
                }

                saveMessage(sessionId, "user", finalUserMessage, null);

                long turnStart = System.currentTimeMillis();
                String response = WorkspaceContextHolder.withWorkspace(workspace, () ->
                        UserContextHolder.withUser(userId, () ->
                                agentRuntimeService.runTurn(session, finalUserMessage, state, sender, turnId))
                );
                long elapsed = System.currentTimeMillis() - turnStart;

                saveMessage(sessionId, "assistant", response, turnId);

                // Fire `done` as soon as the assistant message is durable.
                // Everything below this point — session-last-active touch,
                // context state, cost rollup — is best-effort bookkeeping
                // and shouldn't gate the UI's "stream ended" indicator over
                // a slow DB link. The SSE stream stays open until
                // sender.complete() further down, so the cost.updated event
                // we still want to ship arrives normally after this.
                sender.sendDone(sessionId, response);

                sessionRepository.updateLastActive(sessionId, userId, System.currentTimeMillis());
                persistContextState(sessionId, state, compactionResult);

                CostUsage usage = estimateCost(sessionId, state, finalUserMessage, response, elapsed);
                costUsageRepository.save(usage);
                sender.sendCostUpdated(sessionId, costUsageRepository.summarizeBySession(sessionId));
                // Legacy ToolChain async generation intentionally disabled after workflow-only cutover.
                try {
                    executionLogService.finalizeTurnLog(
                            sessionId,
                            turnId,
                            userId,
                            request.getMessage(),
                            response,
                            state.getModel(),
                            turnStart,
                            System.currentTimeMillis());
                } catch (Exception e) {
                    log.warn("[ChatService] Failed to write execution log for turn={}: {}",
                            turnId, e.getMessage());
                }
                sender.complete();
                session.setActiveEmitter(null);

            } catch (Exception e) {
                log.error("[ChatService] Error during chat: {}", e.getMessage(), e);
                sender.sendError(e.getMessage());
                sender.complete();
                try {
                    AgentSession active = sessionManager.get(request.getSessionId());
                    if (active != null) {
                        active.setActiveEmitter(null);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Truncates a session's history from a given message (inclusive), then evicts in-memory cache
     * so next chat turn rebuilds context from DB.
     *
     * @return number of deleted messages
     */
    public int truncateSessionFromMessage(String userId, String sessionId, String messageId) {
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) return 0;
        List<ChatMessage> history = messageRepository.findBySessionId(sessionId);
        if (history.isEmpty()) return 0;

        int startIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (messageId.equals(history.get(i).getId())) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) return 0;
        long truncateFromCreatedAt = history.get(startIndex).getCreatedAt();

        int deleted = 0;
        for (int i = startIndex; i < history.size(); i++) {
            messageRepository.deleteById(history.get(i).getId());
            deleted++;
        }
        int deletedEvents = runtimeEventRepository.deleteFromTime(sessionId, truncateFromCreatedAt);
        int deletedTraces = runtimeTraceRepository.deleteFromTime(sessionId, truncateFromCreatedAt);
        int deletedHitl = hitlInteractionRepository.deleteFromTime(sessionId, truncateFromCreatedAt);
        int deletedCosts = costUsageRepository.deleteFromTime(sessionId, truncateFromCreatedAt);

        sessionManager.evict(sessionId);
        sessionWorkspaceService.evict(sessionId);
        log.info("[ChatService] Truncated session={} from message={}, deletedMessages={}, deletedEvents={}, deletedTraces={}, deletedHitl={}, deletedCosts={}",
                sessionId, messageId, deleted, deletedEvents, deletedTraces, deletedHitl, deletedCosts);
        return deleted;
    }

    private String generateTitle(String userMessage, ChatState state) {
        String aiTitle = generateTitleWithAi(userMessage, state);
        if (aiTitle != null && !aiTitle.isBlank()) {
            return aiTitle;
        }
        return generateTitleFallback(userMessage);
    }

    private void scheduleAsyncTitleGeneration(String sessionId,
                                              String userId,
                                              String sourceMessage,
                                              ChatState state,
                                              SseEventSender sender) {
        if (sourceMessage == null || sourceMessage.isBlank()) return;
        ChatState stateSnapshot = state == null ? null : state.copy();
        CompletableFuture.runAsync(() -> {
            try {
                String title = generateTitle(sourceMessage, stateSnapshot);
                sessionRepository.renameTitle(sessionId, userId, title);
                sender.sendSessionUpdated(sessionId, title);
                log.info("[ChatService] Generated title for session={}: '{}'", sessionId, title);
            } catch (Exception e) {
                log.debug("[ChatService] Async title generation failed for session={}: {}", sessionId, e.getMessage());
            }
        });
    }

    private String generateTitleWithAi(String userMessage, ChatState state) {
        if (state == null || state.getModel() == null || userMessage == null || userMessage.isBlank()) return null;
        long timeoutMs = Math.max(4_000L, runtimeTuningProperties.getTitleGenerationTimeoutMs());
        try {
            return CompletableFuture.supplyAsync(() -> {
                        try {
                            var spec = modelProviderRouter.resolve(state.getModel());
                            String prompt = "Create a concise chat title (max 6 words) for this user message. "
                                    + "Return only plain title text with no punctuation wrapping.\n\nMessage:\n" + userMessage;
                            String raw = spec.client()
                                    .prompt()
                                    .system("You generate short conversation titles.")
                                    .user(prompt)
                                    .call()
                                    .content();
                            if (raw == null) return null;
                            String clean = raw.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
                            clean = clean.replaceAll("^\"|\"$", "");
                            if (clean.isBlank()) return null;
                            if (clean.length() > 80) clean = clean.substring(0, 80).trim();
                            return clean;
                        } catch (Exception e) {
                            log.debug("[ChatService] AI title generation model call failed: {} - {}",
                                    e.getClass().getSimpleName(),
                                    e.getMessage());
                            return null;
                        }
                    })
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("[ChatService] AI title generation timed out after {} ms; using fallback title.", timeoutMs);
            return null;
        } catch (Exception e) {
            log.debug("[ChatService] AI title generation fallback: {} - {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private String generateTitleFallback(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "New Chat";
        }
        String compact = userMessage.trim().replaceAll("\\s+", " ");
        String[] words = compact.split(" ");
        int take = Math.min(words.length, 5);
        String title = String.join(" ", java.util.Arrays.copyOfRange(words, 0, take));
        return title.length() > 80 ? title.substring(0, 77) + "..." : title;
    }

    private void validateAttachments(ChatRequest request) {
        if (request.getAttachments() == null || request.getAttachments().isEmpty()) return;
        if (request.getAttachments().size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("Maximum " + MAX_ATTACHMENTS + " attachments are allowed");
        }
        for (ChatRequest.Attachment a : request.getAttachments()) {
            if (a == null || a.getFileName() == null || a.getFileName().isBlank()) {
                throw new IllegalArgumentException("Attachment fileName is required");
            }
            String ext = "";
            int idx = a.getFileName().lastIndexOf('.');
            if (idx >= 0 && idx < a.getFileName().length() - 1) {
                ext = a.getFileName().substring(idx + 1).toLowerCase();
            }
            if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("Unsupported attachment format: " + a.getFileName());
            }
            if (a.getSizeBytes() > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("Attachment too large: " + a.getFileName() + " (max 5MB)");
            }
        }
    }

    private String enrichMessageWithAttachments(ChatRequest request) {
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        if (request.getAttachments() == null || request.getAttachments().isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        if (!message.isBlank()) {
            sb.append(message).append("\n\n");
        }
        sb.append("Attached files:\n");
        for (ChatRequest.Attachment a : request.getAttachments()) {
            if (a == null || a.getFileName() == null || a.getFileName().isBlank()) continue;
            sb.append("- ").append(a.getFileName())
                    .append(" (").append(a.getMimeType() == null ? "unknown" : a.getMimeType())
                    .append(", ").append(a.getSizeBytes()).append(" bytes)");
            String extracted = decodeAttachmentPreview(a);
            if (extracted != null && !extracted.isBlank()) {
                sb.append("\n  Preview: ").append(truncate(extracted.replaceAll("\\s+", " "), 1000));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String decodeAttachmentPreview(ChatRequest.Attachment attachment) {
        if (attachment.getContentBase64() == null || attachment.getContentBase64().isBlank()) return null;
        String name = attachment.getFileName().toLowerCase();
        if (!(name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".csv")
                || name.endsWith(".json")
                || name.endsWith(".xml")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".log"))) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(attachment.getContentBase64());
            return new String(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void restoreHistory(AgentSession session, String sessionId) {
        try {
            List<ChatMessage> history = messageRepository.findBySessionId(sessionId);
            if (history.isEmpty()) return;
            for (ChatMessage m : history) {
                if ("user".equals(m.getRole())) {
                    session.getMessages().add(new UserMessage(m.getContent() != null ? m.getContent() : ""));
                } else if ("assistant".equals(m.getRole())) {
                    session.getMessages().add(new AssistantMessage(m.getContent() != null ? m.getContent() : ""));
                }
            }
            log.info("[ChatService] Restored {} messages for session={}", history.size(), sessionId);
        } catch (Exception e) {
            log.warn("[ChatService] Could not restore history for session={}: {}", sessionId, e.getMessage());
        }
    }

    private void saveMessage(String sessionId, String role, String content, String turnId) {
        try {
            messageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(role)
                    .content(content)
                    .turnId(turnId)
                    .createdAt(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.error("[ChatService] Failed to persist {} message for session={}: {}", role, sessionId, e.getMessage());
        }
    }

    private ChatState buildEffectiveState(AgentSession session, ChatRequest request) {
        ChatState state;
        if (session.getActiveState() != null) {
            state = session.getActiveState().copy();
        } else {
            state = sessionContextStateRepository.findBySessionId(session.getSessionId())
                    .map(this::toChatState)
                    .orElse(new ChatState());
        }

        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            state.setTimezone(request.getTimezone());
        }
        if (request.getRuntimeMode() != null && !request.getRuntimeMode().isBlank()) {
            state.setRuntimeMode(request.getRuntimeMode());
        } else if (state.getRuntimeMode() == null || state.getRuntimeMode().isBlank()) {
            state.setRuntimeMode("aicore_loop");
        }
        if (request.getModelSelectionMode() != null && !request.getModelSelectionMode().isBlank()) {
            state.setModelSelectionMode(request.getModelSelectionMode());
        }
        if (request.getAgentProfileId() != null && !request.getAgentProfileId().isBlank()) {
            state.setAgentProfileId(request.getAgentProfileId());
        }
        if (request.getToolChainId() != null && !request.getToolChainId().isBlank()) {
            state.setToolChainId(request.getToolChainId());
        }
        if (request.getToolChainVersion() != null) {
            state.setToolChainVersion(request.getToolChainVersion());
        }
        var resolved = request.resolvedModel();
        if (resolved != null) {
            state.setModel(resolved);
        }
        if (request.getEmbeddingModel() != null) {
            state.setEmbeddingModel(request.getEmbeddingModel());
        }
        return state;
    }

    private ChatState toChatState(SessionContextState persisted) {
        ChatState state = new ChatState();
        state.setRuntimeMode(persisted.getRuntimeMode());
        state.setModelSelectionMode(persisted.getModelSelectionMode());
        state.setRollingSummary(persisted.getRollingSummary());
        if (persisted.getModelRef() != null && persisted.getModelRef().contains("/")) {
            var parts = persisted.getModelRef().split("/", 2);
            state.setModel(new com.pods.agent.domain.ModelRef(parts[0], parts[1]));
        }
        return state;
    }

    private void persistContextState(String sessionId,
                                     ChatState state,
                                     ContextSummarizationService.CompactionResult compactionResult) {
        String modelRef = state.getModel() != null ? state.getModel().toString() : null;
        String providerHint = state.getModel() != null ? state.getModel().providerID() : null;
        long summaryTokens = summarizationService.estimateTokens(state.getRollingSummary(), providerHint);
        String stateJson = buildStateJson(compactionResult);
        sessionContextStateRepository.upsert(SessionContextState.builder()
                .sessionId(sessionId)
                .runtimeMode(state.getRuntimeMode())
                .modelSelectionMode(state.getModelSelectionMode())
                .modelRef(modelRef)
                .stateJson(stateJson)
                .rollingSummary(state.getRollingSummary())
                .summaryTokens(summaryTokens)
                .build());
    }

    private String buildStateJson(ContextSummarizationService.CompactionResult compactionResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (compactionResult != null) {
            payload.put("compacted", compactionResult.compacted());
            payload.put("removedMessages", compactionResult.removedMessages());
            payload.put("retainedMessages", compactionResult.retainedMessages());
            payload.put("estimatedTokens", compactionResult.estimatedTokens());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private CostUsage estimateCost(String sessionId, ChatState state, String prompt, String completion, long elapsedMs) {
        String providerHint = state.getModel() != null ? state.getModel().providerID() : null;
        long promptTokens = summarizationService.estimateTokens(prompt, providerHint);
        long completionTokens = summarizationService.estimateTokens(completion, providerHint);
        var parsedUsage = parseUsageIfPresent(completion);
        if (parsedUsage != null) {
            promptTokens = parsedUsage.promptTokens();
            completionTokens = parsedUsage.completionTokens();
        }
        long total = promptTokens + completionTokens;
        double inputPerM = 3.0;
        double outputPerM = 9.0;
        if (state.getModel() != null) {
            var modelOpt = modelRegistryService.findById(state.getModel().providerID(), state.getModel().modelID());
            if (modelOpt.isPresent()) {
                var m = modelOpt.get();
                if (m.getCostInput() != null) inputPerM = m.getCostInput();
                if (m.getCostOutput() != null) outputPerM = m.getCostOutput();
            }
        }
        double cost = (promptTokens / 1_000_000.0) * inputPerM + (completionTokens / 1_000_000.0) * outputPerM;
        String usageProvenance = parsedUsage != null ? "provider_usage" : "fallback_estimate";
        return CostUsage.builder()
                .sessionId(sessionId)
                .turnId(System.currentTimeMillis() + ":" + usageProvenance)
                .providerId(state.getModel() != null ? state.getModel().providerID() : null)
                .modelId(state.getModel() != null ? state.getModel().modelID() : null)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(total)
                .estimatedCostUsd(cost)
                .createdAt(System.currentTimeMillis() + elapsedMs)
                .build();
    }

    private Usage parseUsageIfPresent(String completion) {
        if (completion == null || !completion.contains("\"usage\"")) return null;
        try {
            Map<?, ?> parsed = objectMapper.readValue(completion, Map.class);
            Object usageObj = parsed.get("usage");
            if (!(usageObj instanceof Map<?, ?> usageMap)) return null;
            long pt = numberFromMap(usageMap, "prompt_tokens");
            long ct = numberFromMap(usageMap, "completion_tokens");
            if (pt <= 0 && ct <= 0) return null;
            return new Usage(Math.max(1, pt), Math.max(1, ct));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long numberFromMap(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v));
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private record Usage(long promptTokens, long completionTokens) {}

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
