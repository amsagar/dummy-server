package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.RuleDomainEventBus;
import org.flowable.common.engine.api.delegate.event.AbstractFlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.FlowableActivityEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flowable-engine global event listener that translates activity lifecycle
 * events on rule-domain process instances into {@code rule_domain.node.*}
 * SSE events.
 *
 * <p>Listens on {@link FlowableEngineEventType#ACTIVITY_STARTED} and
 * {@link FlowableEngineEventType#ACTIVITY_COMPLETED}/{@code ACTIVITY_CANCELLED}.
 * Only emits when the running process has a {@code _turnId} variable set —
 * non-rule-domain processes (if any) stay silent.
 *
 * <p>The {@code _turnId} variable is propagated into the event payload so
 * {@link com.pods.agent.ruledomain.SseRuleDomainEventBus} can resolve the
 * right SSE stream even when the listener runs on a thread without the
 * matching {@code ThreadLocal} binding (e.g. Flowable's async executor).
 */
public class BpmnTraceListener extends AbstractFlowableEventListener {

    private final RuleDomainEventBus bus;
    private final RuntimeService runtimeService;

    public BpmnTraceListener(RuleDomainEventBus bus, RuntimeService runtimeService) {
        this.bus = bus;
        this.runtimeService = runtimeService;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableActivityEvent ae)) return;
        FlowableEngineEventType type = (FlowableEngineEventType) event.getType();

        String eventType;
        switch (type) {
            case ACTIVITY_STARTED -> eventType = "rule_domain.node.started";
            case ACTIVITY_COMPLETED -> eventType = "rule_domain.node.finished";
            case ACTIVITY_CANCELLED -> eventType = "rule_domain.node.cancelled";
            default -> { return; }
        }

        String turnId = lookupTurnId(ae.getProcessInstanceId());
        if (turnId == null) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turnId", turnId);
        payload.put("nodeId", ae.getActivityId());
        payload.put("nodeName", ae.getActivityName());
        payload.put("nodeType", ae.getActivityType());
        bus.emit(eventType, payload);
    }

    private String lookupTurnId(String processInstanceId) {
        if (processInstanceId == null) return null;
        try {
            Object v = runtimeService.getVariable(processInstanceId, "_turnId");
            return v == null ? null : v.toString();
        } catch (Exception ex) {
            // Process may already have ended at completion time — no turnId to resolve.
            return null;
        }
    }

    @Override
    public boolean isFailOnException() {
        return false;
    }
}
