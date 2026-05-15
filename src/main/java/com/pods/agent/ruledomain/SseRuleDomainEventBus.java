package com.pods.agent.ruledomain;

import com.pods.agent.agent.SseEventSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private final ConcurrentHashMap<String, SseEventSender> byTurnId = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_TURN = new ThreadLocal<>();

    /** Register a sender for the duration of a chat turn. Call from a try/finally. */
    public void bind(String turnId, SseEventSender sender) {
        if (turnId == null || sender == null) return;
        byTurnId.put(turnId, sender);
        CURRENT_TURN.set(turnId);
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
        try {
            SseEventSender s = senderFor(payload);
            if (s != null) s.sendCustom(type, payload);
        } catch (Exception ex) {
            log.debug("[SseRuleDomainEventBus] emit failed (suppressed): {}", ex.getMessage());
        }
    }

    private SseEventSender senderFor(Map<String, Object> payload) {
        String tid = CURRENT_TURN.get();
        if (tid != null) {
            SseEventSender s = byTurnId.get(tid);
            if (s != null) return s;
        }
        Object t = payload == null ? null : payload.get("turnId");
        return t == null ? null : byTurnId.get(t.toString());
    }
}
