package com.pods.agent.agent;

import com.pods.agent.domain.ChatSession;
import com.pods.agent.repository.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chat sessions — in-memory cache for active connections, DB-backed for persistence.
 */
@Component
@Slf4j
public class AgentSessionManager {

    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ChatSessionRepository sessionRepository;

    public AgentSessionManager(ChatSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public AgentSession getOrCreate(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return create(userId);
        }

        AgentSession session = sessions.get(sessionId);
        if (session != null) {
            ensureSessionRow(sessionId, userId);
            session.touch();
            return session;
        }

        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isPresent()) {
            session = new AgentSession(sessionId);
            sessions.put(sessionId, session);
            log.info("[SessionManager] Restored session from DB: {}", sessionId);
            return session;
        }

        // Either the row does not exist at all, or it exists for a different
        // user. ensureSessionRow throws on the latter and self-heals the
        // former by writing an idempotent INSERT.
        session = new AgentSession(sessionId);
        sessions.put(sessionId, session);
        ensureSessionRow(sessionId, userId);
        log.info("[SessionManager] Created session with provided ID: {}", sessionId);
        return session;
    }

    public AgentSession create(String userId) {
        String id = UUID.randomUUID().toString();
        AgentSession session = new AgentSession(id);
        sessions.put(id, session);
        persistNewSession(session, userId, null);
        log.info("[SessionManager] New session: {}", id);
        return session;
    }

    /**
     * Guarantees that {@code chat_sessions} has a row owned by {@code userId}
     * for the given {@code sessionId}. Used both at session creation and as a
     * self-healing checkpoint when an in-memory session outlives its DB row
     * (dev DB resets, manual cleanups, container restarts on a shared DB).
     *
     * @throws IllegalArgumentException when a chat_sessions row already exists
     *         for a different user — never reassign ownership silently.
     * @throws RuntimeException when persistence itself failed and the row is
     *         still missing afterwards.
     */
    void ensureSessionRow(String sessionId, String userId) {
        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isPresent()) {
            return;
        }
        if (sessionRepository.findById(sessionId)
                .filter(existing -> existing.getUserId() != null
                        && !existing.getUserId().equals(userId))
                .isPresent()) {
            throw new IllegalArgumentException("Session does not belong to current user");
        }
        log.warn("[SessionManager] chat_sessions row missing for {} (user={}); reseeding",
                sessionId, userId);
        AgentSession proxy = new AgentSession(sessionId);
        persistNewSession(proxy, userId, null);
        if (sessionRepository.findById(sessionId).isEmpty()) {
            throw new IllegalStateException(
                    "chat_sessions row could not be created for session " + sessionId);
        }
    }

    public AgentSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void evict(String sessionId) {
        sessions.remove(sessionId);
        log.info("[SessionManager] Evicted session: {}", sessionId);
    }

    public void evictExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            boolean expired = (now - e.getValue().getLastActiveAt()) > SESSION_TTL_MS;
            if (expired) log.info("[SessionManager] Evicted expired session: {}", e.getKey());
            return expired;
        });
    }

    private void persistNewSession(AgentSession session, String userId, String timezone) {
        long now = System.currentTimeMillis();
        try {
            // CHAT_SESSION.INSERT uses ON CONFLICT DO NOTHING so concurrent
            // first-message races and self-heal calls are safe to retry.
            sessionRepository.save(ChatSession.builder()
                    .sessionId(session.getSessionId())
                    .userId(userId)
                    .createdAt(now)
                    .lastActive(now)
                    .timezone(timezone)
                    .build());
        } catch (RuntimeException e) {
            // Don't silently drop the failure — every downstream FK insert
            // (chat_messages, runtime_events, runtime_traces, …) will start
            // failing if the row is missing. Surface the error clearly so the
            // caller sees it and the user gets a real message instead of a
            // confusing FK violation 30 seconds later.
            log.error("[SessionManager] Failed to persist session {}: {}",
                    session.getSessionId(), e.getMessage(), e);
            throw e;
        }
    }
}
