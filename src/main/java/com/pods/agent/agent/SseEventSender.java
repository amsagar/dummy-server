package com.pods.agent.agent;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wraps SseEmitter and sends typed JSON events.
 * Event types: connected, text.delta, session.updated, done, error
 */
@Slf4j
public class SseEventSender {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private java.util.function.BooleanSupplier cancelledPredicate;

    public SseEventSender(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    /**
     * Install a "should I stop emitting?" predicate. When the predicate returns true
     * every send becomes a no-op. The toolchain config service uses this together with
     * its activeStreams map so a POST .../stop endpoint can flip a flag and silence
     * the running worker thread without restructuring the loop's reactive subscription.
     */
    public void setCancelledPredicate(java.util.function.BooleanSupplier predicate) {
        this.cancelledPredicate = predicate;
    }

    public boolean isCancelled() {
        return cancelledPredicate != null && cancelledPredicate.getAsBoolean();
    }

    public void send(Map<String, Object> payload) {
        if (emitter == null) return;
        if (isCancelled()) return;
        try {
            var envelope = new LinkedHashMap<String, Object>(payload);
            envelope.putIfAbsent("schemaVersion", "v2");
            envelope.putIfAbsent("eventId", UUID.randomUUID().toString());
            envelope.putIfAbsent("emittedAt", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(envelope);
            emitter.send(SseEmitter.event().data(json));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("already") || msg.contains("completed") || msg.contains("Broken pipe")) {
                log.debug("[SseEventSender] Stream already closed; skipping event send.");
            } else {
                log.warn("[SseEventSender] Failed to send event: {}", msg);
            }
        }
    }

    public void sendConnected(String sessionId) {
        send(Map.of("type", "connected", "sessionId", sessionId));
    }

    /**
     * Emit an arbitrary event type. Used for first-class events not yet covered by a
     * typed helper (e.g. toolchain.run.bound which links a chat session to a run id).
     */
    public void sendCustom(String type, Map<String, Object> payload) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("type", type);
        if (payload != null) envelope.putAll(payload);
        send(envelope);
    }

    public void sendTextDelta(String content) {
        send(Map.of("type", "text.delta", "content", content));
    }

    public void sendReasoningDelta(String content) {
        send(Map.of("type", "reasoning.delta", "content", content));
    }

    public void sendPlanCreated(String sessionId, String mode, String plan) {
        send(Map.of("type", "plan.created", "sessionId", sessionId, "mode", mode, "plan", plan));
    }

    public void sendTaskStarted(String sessionId, String taskId, String taskName) {
        send(Map.of("type", "task.started", "sessionId", sessionId, "taskId", taskId, "taskName", taskName));
    }

    public void sendTaskDone(String sessionId, String taskId, String result) {
        send(Map.of("type", "task.done", "sessionId", sessionId, "taskId", taskId, "result", result));
    }

    public void sendStepStarted(String sessionId, int step, Integer maxSteps) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "step.started");
        payload.put("sessionId", sessionId);
        payload.put("step", step);
        if (maxSteps != null) {
            payload.put("maxSteps", maxSteps);
        }
        send(payload);
    }

    public void sendStepFinished(String sessionId, int step, String mode, boolean executedAny) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "step.finished");
        payload.put("sessionId", sessionId);
        payload.put("step", step);
        payload.put("mode", mode);
        payload.put("executedAny", executedAny);
        send(payload);
    }

    public void sendToolCall(String sessionId, String toolName, Object input) {
        send(Map.of("type", "tool.call", "sessionId", sessionId, "toolName", toolName, "input", input));
    }

    public void sendToolCall(String sessionId, String callId, String toolName, Object input) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "tool.call");
        payload.put("sessionId", sessionId);
        payload.put("callId", callId);
        payload.put("toolName", toolName);
        payload.put("input", input);
        send(payload);
    }

    public void sendToolDone(String sessionId, String toolName, Object output) {
        send(Map.of("type", "tool.done", "sessionId", sessionId, "toolName", toolName, "output", output));
    }

    public void sendToolResult(String sessionId, String callId, String toolName, Object output, String status) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "tool.result");
        payload.put("sessionId", sessionId);
        payload.put("callId", callId);
        payload.put("toolName", toolName);
        payload.put("output", output);
        payload.put("status", status);
        send(payload);
    }

    public void sendToolMatch(String sessionId,
                              String selectedTool,
                              int score,
                              boolean needsClarification,
                              String reason,
                              List<String> candidates) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "tool.match");
        payload.put("sessionId", sessionId);
        payload.put("selectedTool", selectedTool);
        payload.put("score", score);
        payload.put("needsClarification", needsClarification);
        payload.put("reason", reason);
        payload.put("candidates", candidates);
        send(payload);
    }

    public void sendQuestion(String sessionId, String requestId, String question) {
        send(Map.of("type", "question", "sessionId", sessionId, "requestId", requestId, "question", question));
    }

    public void sendQuestion(String sessionId, String requestId, String question, Object metadata) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "question");
        payload.put("sessionId", sessionId);
        payload.put("requestId", requestId);
        payload.put("question", question);
        if (metadata != null) {
            payload.put("metadata", metadata);
        }
        send(payload);
    }

    public void sendApprovalRequired(String sessionId, String requestId, String reason) {
        send(Map.of("type", "approval_required", "sessionId", sessionId, "requestId", requestId, "reason", reason));
    }

    public void sendStateUpdated(String sessionId, Object state) {
        send(Map.of("type", "state.updated", "sessionId", sessionId, "state", state));
    }

    public void sendCostUpdated(String sessionId, Object cost) {
        send(Map.of("type", "cost.updated", "sessionId", sessionId, "cost", cost));
    }

    public void sendSummaryUpdated(String sessionId, String summary) {
        send(Map.of("type", "summary.updated", "sessionId", sessionId, "summary", summary));
    }

    public void sendSessionUpdated(String sessionId, String title) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "session.updated");
        payload.put("sessionId", sessionId);
        payload.put("title", title);
        send(payload);
    }

    public void sendDone(String sessionId, String content) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "done");
        payload.put("sessionId", sessionId);
        payload.put("content", content != null ? content : "");
        send(payload);
    }

    public void sendDone(String sessionId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "done");
        payload.put("sessionId", sessionId);
        send(payload);
    }

    public void sendError(String message) {
        send(Map.of("type", "error", "message", message));
    }

    public void complete() {
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (Exception e) {
            log.warn("[SseEventSender] complete() failed (likely already-closed stream): {}", e.getMessage());
        }
    }

    public void completeWithError(Throwable t) {
        if (emitter == null) return;
        try {
            emitter.completeWithError(t);
        } catch (Exception e) {
            log.warn("[SseEventSender] completeWithError() failed (likely already-closed stream): {}", e.getMessage());
        }
    }
}
