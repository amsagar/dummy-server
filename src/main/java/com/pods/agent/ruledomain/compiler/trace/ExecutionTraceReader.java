package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.service.workspace.ExecutionLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a turn's execution log and normalizes it into an
 * {@link ExecutionTrace} the compiler can consume.
 *
 * <p>The source log is written by {@link ExecutionLogService} to
 * {@code <workspace>/.pods-agent/execution-logs/execution-log-<turnId>.json}
 * with paired tool calls + reasoning + state transitions. We pick out only
 * the deterministic, replayable steps (tools + reasoning) and drop noise
 * (state transitions, architect notes, etc.).
 *
 * <p>Falls back to empty when the log isn't available — this can happen on a
 * crashed turn or before the file write has flushed. The async compiler
 * retries with backoff in that case.
 */
@Component
@Slf4j
public class ExecutionTraceReader {

    private final ExecutionLogService executionLogService;

    public ExecutionTraceReader(ExecutionLogService executionLogService) {
        this.executionLogService = executionLogService;
    }

    public Optional<ExecutionTrace> read(String sessionId, String turnId) {
        Optional<JsonNode> rootOpt = executionLogService.readTurnLog(sessionId, turnId);
        if (rootOpt.isEmpty()) {
            log.debug("[ExecutionTraceReader] no log for session={} turn={}", sessionId, turnId);
            return Optional.empty();
        }
        JsonNode root = rootOpt.get();
        String userPrompt = root.path("userPrompt").asString("");
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            return Optional.of(new ExecutionTrace(turnId, sessionId, userPrompt, List.of()));
        }

        List<ExecutionTrace.TraceStep> out = new ArrayList<>();
        for (JsonNode step : steps) {
            String type = step.path("type").asString("");
            if ("tool".equals(type)) {
                out.add(new ExecutionTrace.TraceStep(
                        step.path("seq").asInt(out.size()),
                        step.path("ts").asLong(0L),
                        "tool",
                        step.path("tool").asString(null),
                        step.path("input"),
                        step.path("output"),
                        step.path("status").asString(null),
                        step.path("elapsedMs").asLong(0L),
                        null));
            } else if ("reasoning".equals(type) || "architect_note".equals(type)) {
                String text = step.path("text").asString(
                        step.path("note").asString(
                                step.path("content").asString("")));
                if (!text.isBlank()) {
                    out.add(new ExecutionTrace.TraceStep(
                            step.path("seq").asInt(out.size()),
                            step.path("ts").asLong(0L),
                            "reasoning",
                            null, null, null, null, 0L,
                            text));
                }
            }
            // state_transition / other → dropped. They're flow-control
            // metadata, not part of the deterministic execution path.
        }

        return Optional.of(new ExecutionTrace(turnId, sessionId, userPrompt, out));
    }
}
