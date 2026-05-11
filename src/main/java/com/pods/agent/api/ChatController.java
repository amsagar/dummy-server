package com.pods.agent.api;

import com.pods.agent.api.dto.ChatRequest;
import com.pods.agent.api.dto.InteractionReplyRequest;
import com.pods.agent.api.dto.ChatTruncateRequest;
import com.pods.agent.domain.HitlInteraction;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.ChatMessageRepository;
import com.pods.agent.repository.ChatSessionRepository;
import com.pods.agent.repository.CostUsageRepository;
import com.pods.agent.repository.HitlInteractionRepository;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.SystemToolChainProposalRepository;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.AgentSessionManager;
import com.pods.agent.service.ChatService;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.SecurityContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Streaming chat interface")
@Slf4j
public class ChatController {

    private static final Set<String> SESSION_EVENT_TYPES = Set.of(
            "tool.match", "tool.call", "tool.done", "tool.result",
            "question", "approval_required",
            "reasoning",
            "task.started", "task.done",
            "toolchain.run.bound", "workflow.run.bound"
    );

    private final ChatService chatService;
    private final AgentSessionManager sessionManager;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final PendingInteractionService pendingInteractionService;
    private final CostUsageRepository costUsageRepository;
    private final RuntimeEventRepository runtimeEventRepository;
    private final HitlInteractionRepository hitlInteractionRepository;
    private final SystemToolChainProposalRepository systemToolChainProposalRepository;
    private final SecurityContextService securityContextService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService,
                          AgentSessionManager sessionManager,
                          ChatSessionRepository sessionRepository,
                          ChatMessageRepository messageRepository,
                          PendingInteractionService pendingInteractionService,
                          CostUsageRepository costUsageRepository,
                          RuntimeEventRepository runtimeEventRepository,
                          HitlInteractionRepository hitlInteractionRepository,
                          SystemToolChainProposalRepository systemToolChainProposalRepository,
                          SecurityContextService securityContextService,
                          ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.pendingInteractionService = pendingInteractionService;
        this.costUsageRepository = costUsageRepository;
        this.runtimeEventRepository = runtimeEventRepository;
        this.hitlInteractionRepository = hitlInteractionRepository;
        this.systemToolChainProposalRepository = systemToolChainProposalRepository;
        this.securityContextService = securityContextService;
        this.objectMapper = objectMapper;
    }

    /**
     * Main SSE streaming chat endpoint.
     *
     * SSE event types:
     *   connected       - session established
     *   text.delta      - incremental text chunk from the LLM
     *   session.updated - session metadata (e.g. title) was updated
     *   done            - turn complete
     *   error           - unrecoverable error
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send a message and receive a streaming SSE response")
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, jakarta.servlet.http.HttpServletResponse response) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (!request.hasMessageOrAttachments()) {
            throw new IllegalArgumentException("Message or attachments are required");
        }
        log.info("[ChatController] New chat request: sessionId={}", request.getSessionId());
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        // Keep SSE open for long-running token generation; close only on done/cancel/error.
        SseEmitter emitter = new SseEmitter(-1L);
        chatService.handleChatAsync(request, emitter, userId);
        return emitter;
    }

    @PostMapping("/reply")
    @Operation(summary = "Reply to a question event")
    public ResponseEntity<?> reply(@Valid @RequestBody InteractionReplyRequest request) {
        ResponseEntity<?> ownership = ensureInteractionOwnership(request.getRequestId());
        if (ownership != null) return ownership;
        try {
            pendingInteractionService.reply(request.getRequestId(), "reply", request.getMessage(), request.getSelectedOptionIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("ok", true, "requestId", request.getRequestId()));
    }

    @PostMapping("/approve")
    @Operation(summary = "Approve a pending action")
    public ResponseEntity<?> approve(@Valid @RequestBody InteractionReplyRequest request) {
        ResponseEntity<?> ownership = ensureInteractionOwnership(request.getRequestId());
        if (ownership != null) return ownership;
        try {
            pendingInteractionService.reply(request.getRequestId(), "approve", request.getMessage(), request.getSelectedOptionIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("ok", true, "requestId", request.getRequestId(), "action", "approve"));
    }

    @PostMapping("/reject")
    @Operation(summary = "Reject a pending action")
    public ResponseEntity<?> reject(@Valid @RequestBody InteractionReplyRequest request) {
        ResponseEntity<?> ownership = ensureInteractionOwnership(request.getRequestId());
        if (ownership != null) return ownership;
        try {
            pendingInteractionService.reply(request.getRequestId(), "reject", request.getMessage(), request.getSelectedOptionIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("ok", true, "requestId", request.getRequestId(), "action", "reject"));
    }

    @DeleteMapping("/messages/{messageId}")
    @Operation(summary = "Delete a single chat message by ID")
    public ResponseEntity<?> deleteMessage(@PathVariable String messageId) {
        String userId = securityContextService.currentUserIdOrThrow();
        var message = messageRepository.findById(messageId);
        if (message.isEmpty()) {
            return ResponseEntityFactory.notFound("Message not found: " + messageId);
        }
        if (sessionRepository.findByUserIdAndSessionId(userId, message.get().getSessionId()).isEmpty()) {
            return ResponseEntityFactory.forbidden("Message does not belong to current user");
        }
        messageRepository.deleteById(messageId);
        return ResponseEntity.ok(Map.of("deleted", true, "messageId", messageId));
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    @Operation(summary = "Cancel the active streaming turn for a session")
    public ResponseEntity<?> cancelTurn(@PathVariable String sessionId) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        AgentSession session = sessionManager.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "note", "no active session"));
        }
        session.cancel();
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = session.getActiveEmitter();
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        log.info("[ChatController] Turn cancelled for session={}", sessionId);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "cancelled", true));
    }

    @PostMapping("/sessions/{sessionId}/truncate")
    @Operation(summary = "Truncate chat history from a message (inclusive) within the same session")
    public ResponseEntity<?> truncateFromMessage(@PathVariable String sessionId,
                                                 @Valid @RequestBody ChatTruncateRequest request) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }

        var targetMessage = messageRepository.findById(request.getMessageId());
        if (targetMessage.isEmpty()) {
            return ResponseEntityFactory.notFound("Message not found: " + request.getMessageId());
        }
        if (!sessionId.equals(targetMessage.get().getSessionId())) {
            return ResponseEntityFactory.badRequest("Message does not belong to session: " + sessionId);
        }

        int deleted = chatService.truncateSessionFromMessage(userId, sessionId, request.getMessageId());
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "sessionId", sessionId,
                "fromMessageId", request.getMessageId(),
                "deletedCount", deleted
        ));
    }

    @GetMapping("/history/{sessionId}")
    @Operation(summary = "Get chat message history for a session")
    public ResponseEntity<?> history(@PathVariable String sessionId,
                                     @RequestParam(defaultValue = "100") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        var dbMessages = messageRepository.findBySessionId(sessionId, limit, offset);
        long total = messageRepository.countBySessionId(sessionId);
        List<Map<String, Object>> messages = dbMessages.stream()
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", m.getId());
                    row.put("role", m.getRole());
                    row.put("content", m.getContent() != null ? m.getContent() : "");
                    row.put("turnId", m.getTurnId());
                    row.put("createdAt", m.getCreatedAt());
                    return row;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "limit", limit,
                "offset", offset,
                "total", total,
                "hasMore", (long) offset + messages.size() < total,
                "messageCount", messages.size(),
                "messages", messages
        ));
    }

    @GetMapping("/cost/{sessionId}")
    @Operation(summary = "Get session cost and token usage")
    public ResponseEntity<?> cost(@PathVariable String sessionId) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "summary", costUsageRepository.summarizeBySession(sessionId),
                "turns", costUsageRepository.findBySessionId(sessionId)
        ));
    }

    @GetMapping("/pending/{sessionId}")
    @Operation(summary = "List pending HITL interactions for session")
    public ResponseEntity<?> pending(@PathVariable String sessionId) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "pending", pendingInteractionService.listPendingBySession(sessionId)
        ));
    }

    @GetMapping("/pending/system-toolchains")
    @Operation(summary = "List pending system toolchain approvals for current user")
    public ResponseEntity<?> pendingSystemToolchainApprovals() {
        String userId = securityContextService.currentUserIdOrThrow();
        var approvals = systemToolChainProposalRepository.findPendingByUser(userId).stream()
                .map(p -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("requestId", p.getId());
                    row.put("sessionId", p.getSessionId());
                    row.put("turnId", p.getTurnId());
                    row.put("type", "approval_required");
                    row.put("prompt", "Create a reusable toolchain from this turn? " + (p.getReason() == null ? "" : p.getReason()));
                    row.put("createdAt", p.getCreatedAt());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(Map.of(
                "approvals", approvals
        ));
    }

    @GetMapping("/events/{sessionId}")
    @Operation(summary = "Get session event trace (tool calls, questions, approvals)")
    public ResponseEntity<?> sessionEvents(@PathVariable String sessionId) {
        String userId = securityContextService.currentUserIdOrThrow();
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }

        var events = runtimeEventRepository.findBySessionId(sessionId).stream()
                .filter(e -> SESSION_EVENT_TYPES.contains(e.getEventType()))
                .toList();

        var interactions = hitlInteractionRepository.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(HitlInteraction::getId, i -> i));

        var result = events.stream().map(e -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", e.getId());
            row.put("turnId", e.getTurnId());
            row.put("eventType", e.getEventType());
            row.put("payload", e.getPayload());
            row.put("createdAt", e.getCreatedAt());
            try {
                var p = objectMapper.readValue(e.getPayload(), Map.class);
                var reqId = (String) p.get("requestId");
                if (reqId != null && interactions.containsKey(reqId)) {
                    var hi = interactions.get(reqId);
                    row.put("hitlStatus", hi.getStatus());
                    row.put("hitlResponse", hi.getResponseText());
                }
            } catch (Exception ignored) {}
            return row;
        }).toList();

        return ResponseEntity.ok(Map.of("sessionId", sessionId, "events", result));
    }

    private ResponseEntity<?> ensureInteractionOwnership(String requestId) {
        String userId = securityContextService.currentUserIdOrThrow();
        var interaction = hitlInteractionRepository.findById(requestId);
        if (interaction.isEmpty()) {
            return ResponseEntityFactory.notFound("Interaction not found: " + requestId);
        }
        if (sessionRepository.findByUserIdAndSessionId(userId, interaction.get().getSessionId()).isEmpty()) {
            return ResponseEntityFactory.forbidden("Interaction does not belong to current user");
        }
        return null;
    }
}
