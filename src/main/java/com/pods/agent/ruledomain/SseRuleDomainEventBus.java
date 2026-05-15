package com.pods.agent.ruledomain;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE-backed event bus for the rule-domain pipeline.
 *
 * <p>Senders are registered per {@code turnId} so emitters running on threads
 * other than the chat request thread (Flowable's command-context threads,
 * async executor threads if any task is ever marked {@code flowable:async})
 * can still resolve the right stream by reading {@code turnId} from a
 * process variable or event payload.
 *
 * <p>The {@link #CURRENT_TURN} ThreadLocal provides a fast path for the
 * common synchronous case where the emitter is on the same thread that
 * called {@link #bind(String, SseEventSender)}.
 */
@Component
@Slf4j
public class SseRuleDomainEventBus implements RuleDomainEventBus {

    private final RuntimeEventRepository runtimeEventRepository;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Binding> byTurnId = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_TURN = new ThreadLocal<>();

    public SseRuleDomainEventBus(RuntimeEventRepository runtimeEventRepository,
                                 ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Register a sender and (optionally) a sessionId for the duration of a chat
     * turn. Call from a try/finally so cross-turn leaks of the ThreadLocal /
     * registry don't accumulate.
     */
    public void bind(String turnId, String sessionId, SseEventSender sender) {
        if (turnId == null || sender == null) return;
        byTurnId.put(turnId, new Binding(sessionId, sender));
        CURRENT_TURN.set(turnId);
    }

    /** Back-compat overload — turn without sessionId, persistence becomes no-op. */
    public void bind(String turnId, SseEventSender sender) {
        bind(turnId, null, sender);
    }

    public void unbind(String turnId) {
        if (turnId != null) byTurnId.remove(turnId);
        CURRENT_TURN.remove();
    }

    /** Read the turn id currently bound to this thread, or null if none. */
    public static String currentTurnId() {
        return CURRENT_TURN.get();
    }

    @Override
    public void emit(String type, Map<String, Object> payload) {
        Binding b = bindingFor(payload);
        if (b == null) return;

        // 1) Live SSE
        try {
            b.sender.sendCustom(type, payload);
        } catch (Exception ex) {
            log.debug("[SseRuleDomainEventBus] sse emit failed (suppressed): {}", ex.getMessage());
        }

        // 2) Persist to runtime_events so the chat history endpoint can replay
        //    the rule-domain timeline. Mirrors what AgentToolCallback /
        //    SkillToolCallback already do for tool.call / tool.result events.
        if (b.sessionId == null || b.sessionId.isBlank()) return;
        try {
            String turnId = resolveTurnId(payload);
            String payloadJson = serialize(payload);
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(b.sessionId)
                    .turnId(turnId)
                    .eventType(type)
                    .payload(payloadJson)
                    .build());
        } catch (Exception ex) {
            log.debug("[SseRuleDomainEventBus] persist failed (suppressed): {}", ex.getMessage());
        }
    }

    private Binding bindingFor(Map<String, Object> payload) {
        String tid = CURRENT_TURN.get();
        if (tid != null) {
            Binding b = byTurnId.get(tid);
            if (b != null) return b;
        }
        Object t = payload == null ? null : payload.get("turnId");
        return t == null ? null : byTurnId.get(t.toString());
    }

    private static String resolveTurnId(Map<String, Object> payload) {
        Object t = payload == null ? null : payload.get("turnId");
        if (t != null) return t.toString();
        return CURRENT_TURN.get();
    }

    private String serialize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            // Fall back to a degraded representation so the event row still
            // lands (better than dropping the whole timeline because one
            // field has a non-serializable value).
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                Object v = e.getValue();
                safe.put(e.getKey(), v == null ? null : v.toString());
            }
            try { return objectMapper.writeValueAsString(safe); }
            catch (Exception ignored) { return "{}"; }
        }
    }

    private record Binding(String sessionId, SseEventSender sender) {}
}
