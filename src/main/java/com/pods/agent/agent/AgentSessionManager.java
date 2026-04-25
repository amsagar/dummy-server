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
            if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isEmpty()) {
                throw new IllegalArgumentException("Session does not belong to current user");
            }
            session.touch();
            return session;
        }

        if (sessionRepository.findByUserIdAndSessionId(userId, sessionId).isPresent()) {
            session = new AgentSession(sessionId);
            sessions.put(sessionId, session);
            log.info("[SessionManager] Restored session from DB: {}", sessionId);
            return session;
        }

        session = new AgentSession(sessionId);
        sessions.put(sessionId, session);
        persistNewSession(session, userId, null);
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
        try {
            long now = System.currentTimeMillis();
            sessionRepository.save(ChatSession.builder()
                    .sessionId(session.getSessionId())
                    .userId(userId)
                    .createdAt(now)
                    .lastActive(now)
                    .timezone(timezone)
                    .build());
        } catch (Exception e) {
            log.error("[SessionManager] Failed to persist session {}: {}", session.getSessionId(), e.getMessage());
        }
    }
}
