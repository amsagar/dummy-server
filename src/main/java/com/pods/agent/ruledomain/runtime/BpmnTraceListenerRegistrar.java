package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.RuleDomainEventBus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;

/**
 * Registers {@link BpmnTraceListener} on the Flowable {@link RuntimeService}
 * after Spring's context is up so that activity lifecycle events on rule-domain
 * BPMNs surface as SSE events.
 *
 * <p>Registration happens here (in a {@code @PostConstruct}) rather than in
 * {@code FlowableConfig#processEngineConfigurer()} because the configurer
 * runs before {@link RuleDomainEventBus} and {@link RuntimeService} beans
 * are fully instantiated — wiring them via constructor injection would create
 * a cycle. Doing it after startup is safe: no rule-domain processes can run
 * until the application is fully booted anyway.
 */
@Component
@Slf4j
public class BpmnTraceListenerRegistrar {

    private final RuntimeService runtimeService;
    private final RuleDomainEventBus bus;

    public BpmnTraceListenerRegistrar(RuntimeService runtimeService, RuleDomainEventBus bus) {
        this.runtimeService = runtimeService;
        this.bus = bus;
    }

    @PostConstruct
    public void register() {
        BpmnTraceListener listener = new BpmnTraceListener(bus, runtimeService);
        runtimeService.addEventListener(listener,
                FlowableEngineEventType.ACTIVITY_STARTED,
                FlowableEngineEventType.ACTIVITY_COMPLETED,
                FlowableEngineEventType.ACTIVITY_CANCELLED);
        log.info("[BpmnTraceListenerRegistrar] registered BpmnTraceListener on Flowable RuntimeService");
    }
}
