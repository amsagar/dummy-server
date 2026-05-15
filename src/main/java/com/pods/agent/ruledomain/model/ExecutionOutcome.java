package com.pods.agent.ruledomain.model;

import java.util.Map;

/**
 * The result of running a compiled rule domain end-to-end. Returned to
 * {@code RuleDomainOrchestrator} so the summarizer can produce a user-facing
 * response.
 *
 * @param handled         did the compiled path actually handle this request? (false → caller falls back to LLM loop)
 * @param domainId        the rule domain that produced this outcome (null when handled=false)
 * @param flowableProcId  Flowable's process instance id, for joining to its history tables
 * @param outputs         final process variables (typically a single "result" map)
 * @param fromCacheHit    true if the intent matched an existing domain; false if compiled fresh
 * @param latencyMs       wall time from orchestrator entry to BPMN end event
 * @param error           non-null if the BPMN run completed but with a recorded error
 * @param errorMeta       optional structured metadata about the error (e.g. {"failedTool": "Get_OrderID"})
 */
public record ExecutionOutcome(
        boolean handled,
        String domainId,
        String flowableProcId,
        Map<String, Object> outputs,
        boolean fromCacheHit,
        long latencyMs,
        String error,
        Map<String, String> errorMeta
) {
    public static ExecutionOutcome notHandled() {
        return new ExecutionOutcome(false, null, null, Map.of(), false, 0L, null, null);
    }

    public static ExecutionOutcome handled(String domainId,
                                           String procId,
                                           Map<String, Object> outputs,
                                           boolean fromCacheHit,
                                           long latencyMs) {
        return new ExecutionOutcome(true, domainId, procId, outputs, fromCacheHit, latencyMs, null, null);
    }

    public static ExecutionOutcome failed(String domainId, String procId, String error, long latencyMs) {
        return new ExecutionOutcome(true, domainId, procId, Map.of(), false, latencyMs, error, null);
    }

    public static ExecutionOutcome failed(String domainId,
                                          String procId,
                                          String error,
                                          long latencyMs,
                                          Map<String, String> errorMeta) {
        return new ExecutionOutcome(true, domainId, procId, Map.of(), false, latencyMs, error, errorMeta);
    }
}
