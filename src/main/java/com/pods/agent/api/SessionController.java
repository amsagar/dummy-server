package com.pods.agent.api;

import com.pods.agent.agent.AgentSessionManager;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.ChatMessageRepository;
import com.pods.agent.repository.ChatSessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import com.pods.agent.service.SecurityContextService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CRUD for chat sessions.
 *
 * GET    /api/v1/sessions               — list sessions (?archived=true|false|absent)
 * GET    /api/v1/sessions/{id}          — get session metadata
 * PATCH  /api/v1/sessions/{id}          — rename session title
 * POST   /api/v1/sessions/{id}/archive  — archive or restore a session
 * GET    /api/v1/sessions/{id}/messages — get messages for a session
 * DELETE /api/v1/sessions/{id}          — delete session + messages
 */
@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "Sessions", description = "Chat session management")
@Slf4j
public class SessionController {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AgentSessionManager sessionManager;
    private final SecurityContextService securityContextService;

    public SessionController(ChatSessionRepository sessionRepository,
                             ChatMessageRepository messageRepository,
                             AgentSessionManager sessionManager,
                             SecurityContextService securityContextService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionManager = sessionManager;
        this.securityContextService = securityContextService;
    }

    @GetMapping
    @Operation(summary = "List chat sessions (optionally filtered by archived status)")
    public ResponseEntity<?> listSessions(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) Boolean archived) {
        String userId = securityContextService.currentUserIdOrDefault("order-validation-ui");
        List<Map<String, Object>> sessions = sessionRepository.findAllByUser(userId, limit, offset, archived);
        long total = sessionRepository.countAllByUser(userId, archived);
        return ResponseEntity.ok(Map.of(
                "sessions", sessions,
                "limit", limit,
                "offset", offset,
                "count", sessions.size(),
                "total", total,
                "hasMore", (long) offset + sessions.size() < total
        ));
    }

    @PatchMapping("/{sessionId}")
    @Operation(summary = "Rename a session's title")
    public ResponseEntity<?> renameSession(@PathVariable String sessionId,
                                           @RequestBody Map<String, String> body) {
        String userId = securityContextService.currentUserIdOrDefault("order-validation-ui");
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntityFactory.badRequest("title is required");
        }
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        sessionRepository.renameTitle(sessionId, userId, title.trim());
        log.info("[SessionController] Renamed session={} to '{}'", sessionId, title.trim());
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "title", title.trim()));
    }

    @PostMapping("/{sessionId}/archive")
    @Operation(summary = "Archive or restore a session")
    public ResponseEntity<?> archiveSession(@PathVariable String sessionId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        String userId = securityContextService.currentUserIdOrDefault("order-validation-ui");
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        boolean restore = body != null && Boolean.TRUE.equals(body.get("restore"));
        Long archivedAt = restore ? null : System.currentTimeMillis();
        sessionRepository.archive(sessionId, userId, archivedAt);
        log.info("[SessionController] {} session={}", restore ? "Restored" : "Archived", sessionId);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "archived", !restore));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get session metadata")
    public ResponseEntity<?> getSession(@PathVariable String sessionId) {
        String userId = securityContextService.currentUserIdOrDefault("order-validation-ui");
        return sessionRepository.findByUserIdAndSessionId(userId, sessionId)
                .<ResponseEntity<?>>map(session -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("sessionId", session.getSessionId());
                    body.put("userId", session.getUserId());
                    body.put("createdAt", session.getCreatedAt());
                    body.put("lastActive", session.getLastActive());
                    body.put("timezone", session.getTimezone() != null ? session.getTimezone() : "");
                    body.put("title", session.getTitle() != null ? session.getTitle() : "");
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntityFactory.notFound("Session not found: " + sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(summary = "Get all messages for a session")
    public ResponseEntity<?> getMessages(@PathVariable String sessionId,
                                         @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        String userId = securityContextService.currentUserIdOrDefault("order-validation-ui");
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }
        var messages = messageRepository.findBySessionId(sessionId, limit, offset);
        long total = messageRepository.countBySessionId(sessionId);
        List<Map<String, Object>> response = messages.stream()
                .map(m -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("id", m.getId());
                    map.put("sessionId", m.getSessionId());
                    map.put("role", m.getRole());
                    map.put("content", m.getContent() != null ? m.getContent() : "");
                    map.put("createdAt", m.getCreatedAt());
                    return (Map<String, Object>) map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "limit", limit,
                "offset", offset,
                "total", total,
                "hasMore", (long) offset + response.size() < total,
                "messageCount", response.size(),
                "messages", response
        ));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Delete a session and all its messages")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId) {
        String userId = securityContextService.currentUserIdOrDefault("order-validation-ui");
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
            return ResponseEntityFactory.notFound("Session not found: " + sessionId);
        }

        sessionManager.evict(sessionId);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(sessionId, userId);

        log.info("[SessionController] Deleted session={}", sessionId);
        return ResponseEntity.ok(Map.of("deleted", true, "sessionId", sessionId));
    }
}
