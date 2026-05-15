package com.pods.agent.ruledomain.compiler.trace;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Normalized in-memory view of a chat turn's execution trace. Built by
 * {@link ExecutionTraceReader} from the persisted per-turn log on disk
 * (or directly from {@code runtime_events}). This is the input to
 * {@link RuleSlicer} (partitioning) and {@link TraceBasedBpmnCompiler}
 * (per-rule BPMN synthesis).
 *
 * <p>Steps are ordered chronologically. Tool calls have been paired
 * (input + output + status + latency); reasoning blocks land between
 * tool calls so the compiler can read the LLM's intent between actions
 * when the explicit tool ownership is ambiguous.
 *
 * @param turnId      the chat turn that produced this trace
 * @param sessionId   the session (for context — not used by the compiler)
 * @param userPrompt  the original user message
 * @param steps       chronological list of tool / decision / reasoning steps
 */
public record ExecutionTrace(
        String turnId,
        String sessionId,
        String userPrompt,
        List<TraceStep> steps
) {

    /** Tool calls only, in order. */
    public List<TraceStep> toolSteps() {
        return steps == null ? List.of() : steps.stream()
                .filter(s -> "tool".equals(s.type()))
                .toList();
    }

    /**
     * One unit of execution. Type discriminates between tool calls,
     * decision-table evaluations, and reasoning blocks. Other step types
     * from the source log (state transitions, architect notes, planner
     * decisions) are dropped — the compiler only needs the deterministic,
     * replayable bits.
     *
     * @param seq          zero-indexed position in the trace
     * @param tsMs         timestamp millis
     * @param type         "tool" | "reasoning" | "other"
     * @param name         tool name, decision table name, or null
     * @param input        tool input JSON (parsed)
     * @param output       tool output JSON (parsed)
     * @param status       "success" | "failed" | null
     * @param elapsedMs    wall time for the step
     * @param text         reasoning text (when type=reasoning)
     */
    public record TraceStep(
            int seq,
            long tsMs,
            String type,
            String name,
            JsonNode input,
            JsonNode output,
            String status,
            long elapsedMs,
            String text
    ) {
        public boolean isTool() { return "tool".equals(type); }
        public boolean isReasoning() { return "reasoning".equals(type); }
    }
}
