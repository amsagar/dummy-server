package com.pods.agent.ruledomain;

import java.util.Map;

/**
 * Pub/sub seam for fine-grained progress events emitted by the compiled
 * rule-domain pipeline (skill routing → intent match → compile → execute).
 *
 * <p>Decouples emitters ({@code RuleDomainOrchestrator}, {@code BpmnCompiler},
 * delegates, the Flowable activity listener) from the concrete transport
 * (server-sent events). The default {@link #NOOP} implementation lets the
 * pipeline run when no SSE stream is attached (e.g. background batch jobs,
 * tests).
 */
public interface RuleDomainEventBus {

    /**
     * Emit an event of the given type with the given payload. Implementations
     * must never throw — emit failures should be swallowed so a missing
     * subscriber can never break the pipeline.
     */
    void emit(String type, Map<String, Object> payload);

    RuleDomainEventBus NOOP = (type, payload) -> {};
}
