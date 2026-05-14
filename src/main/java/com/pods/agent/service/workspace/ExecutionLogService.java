package com.pods.agent.service.workspace;

import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds and maintains the per-turn <strong>execution log</strong> — the
 * "GOLD" structured trace that drafting flows consume to convert one
 * exploratory chat turn into a reusable toolchain definition.
 *
 * <p>Output shape (single JSON document at
 * {@code <sessionWorkspace>/.pods-agent/toolchains/execution-log-<turnId>.json}):
 * <pre>
 * {
 *   "executionId": &lt;turnId&gt;,
 *   "sessionId":   "...",
 *   "userId":      "...",
 *   "userPrompt":  "...",
 *   "modelRef":    { "providerID": "...", "modelID": "..." },
 *   "startedAt":   1778..., "completedAt": 1778...,
 *   "assistantResponse": "...",
 *   "steps": [
 *     { "seq": 0, "ts": ..., "type": "state_transition", "state": "PLAN",      "reason": "turn-start" },
 *     { "seq": 1, "ts": ..., "type": "tool",             "tool": "getCart",    "input": {...}, "output": {...}, "status": "success", "elapsedMs": 412 },
 *     { "seq": 2, "ts": ..., "type": "architect_note",   "note": "Going to loop products and fetch detail per item" },
 *     { "seq": 3, "ts": ..., "type": "tool",             "tool": "getProduct", "input": {...}, "output": {...}, "status": "success", "elapsedMs": 173 },
 *     ...
 *   ],
 *   "summary": {
 *     "toolNames": ["getCart","getProduct"],
 *     "stepCount": 14,
 *     "stepsByType": { "tool": 11, "state_transition": 3 }
 *   }
 * }
 * </pre>
 *
 * <p>Step type taxonomy (extensible — see {@link #STEP_TOOL} constants):
 * <ul>
 *   <li>{@code tool} — a tool call (input + output + status + elapsedMs).
 *       Built by pairing {@code tool.call}/{@code tool.done} runtime events
 *       on {@code callId}.</li>
 *   <li>{@code state_transition} — planner FSM transition.</li>
 *   <li>{@code architect_note} — free-form note dropped by the chat agent
 *       through the {@code architect_note} write tool. Phase A surface for
 *       "chat agent supplements with reasoning notes / decisions".</li>
 *   <li>{@code reasoning} — accumulated &lt;think&gt; / native-thinking from
 *       the LLM during the turn.</li>
 *   <li>Reserved for future phases: {@code loop}, {@code condition},
 *       {@code parallel}, {@code ai_reasoning}. Phase C will populate these
 *       from engine-side typed events when materialized chains execute.</li>
 *   <li>{@code other} — fallback bucket for unrecognised event types so the
 *       log stays comprehensive without forcing a schema change every time a
 *       new event type is introduced.</li>
 * </ul>
 *
 * <p>Implementation strategy: assemble at finalize time by reading the
 * already-persisted {@code runtime_events} for the turn. Single source of
 * truth stays in Postgres; the only new write surface is one atomic file
 * write per turn.
 */
@Service
@Slf4j
public class ExecutionLogService {

    /** Root directory inside the session VFS for execution-log artifacts. */
    public static final String EXECUTION_LOG_DIR = ".pods-agent/toolchains";

    public static final String STEP_TOOL = "tool";
    public static final String STEP_STATE_TRANSITION = "state_transition";
    public static final String STEP_ARCHITECT_NOTE = "architect_note";
    public static final String STEP_REASONING = "reasoning";
    public static final String STEP_OTHER = "other";

    private final SessionWorkspaceService workspaceService;
    private final RuntimeEventRepository runtimeEventRepository;
    private final ObjectMapper objectMapper;

    public ExecutionLogService(SessionWorkspaceService workspaceService,
                               RuntimeEventRepository runtimeEventRepository,
                               ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.runtimeEventRepository = runtimeEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Build the canonical execution log document and write it atomically into
     * the session VFS. Returns the absolute path on success, or {@link
     * Optional#empty()} when the turn has no events worth persisting (rare —
     * mostly happens on early-aborted turns) or when the workspace cannot be
     * resolved.
     */
    public Optional<Path> finalizeTurnLog(String sessionId,
                                          String turnId,
                                          String userId,
                                          String userPrompt,
                                          String assistantResponse,
                                          ModelRef modelRef,
                                          long startedAt,
                                          long completedAt) {
        if (sessionId == null || sessionId.isBlank() || turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        Path workspace = workspaceService.get(sessionId);
        if (workspace == null) {
            // Should not happen — ChatService creates the workspace before
            // running the turn. Belt-and-suspenders fallback so the architect
            // pipeline still gets a file path.
            workspace = workspaceService.getOrCreate(sessionId);
        }

        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("executionId", turnId);
        root.put("sessionId", sessionId);
        if (userId != null) root.put("userId", userId);
        if (userPrompt != null) root.put("userPrompt", userPrompt);
        if (modelRef != null) {
            ObjectNode modelNode = root.putObject("modelRef");
            modelNode.put("providerID", modelRef.providerID());
            modelNode.put("modelID", modelRef.modelID());
        }
        if (startedAt > 0) root.put("startedAt", startedAt);
        if (completedAt > 0) root.put("completedAt", completedAt);
        if (assistantResponse != null) root.put("assistantResponse", assistantResponse);

        TypedSteps typed = buildTypedSteps(events);
        root.set("steps", typed.steps);

        ObjectNode summary = root.putObject("summary");
        summary.set("toolNames", objectMapper.valueToTree(typed.toolNames));
        summary.put("stepCount", typed.steps.size());
        ObjectNode stepsByType = summary.putObject("stepsByType");
        typed.countsByType.forEach(stepsByType::put);

        try {
            Path file = workspaceService.ensureFile(
                    workspace,
                    EXECUTION_LOG_DIR + "/execution-log-" + turnId + ".json");
            // Write to a sibling temp file then atomically move into place so
            // a partial write can never be observed by the architect subagent.
            Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("[ExecutionLog] wrote {} ({} steps, {} tool calls)",
                    file, typed.steps.size(), typed.countsByType.getOrDefault(STEP_TOOL, 0));
            return Optional.of(file);
        } catch (IOException | RuntimeException e) {
            log.warn("[ExecutionLog] failed to write log for session={} turn={}: {}",
                    sessionId, turnId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read a previously finalized execution-log document. Returns empty when
     * the file is missing or unreadable.
     */
    public Optional<JsonNode> readTurnLog(String sessionId, String turnId) {
        if (sessionId == null || turnId == null) return Optional.empty();
        Path workspace = workspaceService.get(sessionId);
        if (workspace == null) return Optional.empty();
        Path file = workspace.resolve(EXECUTION_LOG_DIR).resolve("execution-log-" + turnId + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readTree(file.toFile()));
        } catch (Exception e) {
            log.warn("[ExecutionLog] failed to read log {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Where approved-proposal JSON files live so they sit next to the
     * execution log that produced them.
     */
    public Path approvedProposalPath(String sessionId, String defId) {
        Path workspace = workspaceService.getOrCreate(sessionId);
        return workspaceService.ensureFile(
                workspace, EXECUTION_LOG_DIR + "/proposals/" + defId + ".json");
    }

    /**
     * Append a free-form note to the in-flight execution log. Recorded as a
     * runtime event with type {@code architect.note}; will materialize as an
     * {@code architect_note} step when {@link #finalizeTurnLog} runs at the
     * end of the turn. Used by the chat agent's {@code architect_note}
     * write tool.
     */
    public void appendArchitectNote(String sessionId, String turnId, String note) {
        if (sessionId == null || sessionId.isBlank() || turnId == null || turnId.isBlank()) return;
        if (note == null || note.isBlank()) return;
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("note", note);
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .eventType("architect.note")
                    .payload(objectMapper.writeValueAsString(payload))
                    .build());
        } catch (Exception e) {
            // RuntimeEventRepository already tolerates FK / persistence noise; this is a
            // last-ditch guard so a logging failure never breaks the chat turn.
            log.debug("[ExecutionLog] failed to persist architect.note for turn={}: {}", turnId, e.getMessage());
        }
    }

    // --- internal --------------------------------------------------------

    private TypedSteps buildTypedSteps(List<RuntimeEvent> events) {
        ArrayNode steps = objectMapper.createArrayNode();
        Map<String, Integer> countsByType = new LinkedHashMap<>();
        List<String> toolNames = new ArrayList<>();
        Map<String, ObjectNode> openCalls = new LinkedHashMap<>();
        if (events == null) return new TypedSteps(steps, countsByType, toolNames);

        int seq = 0;
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            String type = event.getEventType().toLowerCase(Locale.ROOT);
            JsonNode payload = parsePayload(event.getPayload());

            switch (type) {
                case "state.transition" -> {
                    ObjectNode step = newStep(seq++, event.getCreatedAt(), STEP_STATE_TRANSITION);
                    step.put("state", payload.path("state").asText(null));
                    String reason = payload.path("reason").asText(null);
                    if (reason != null && !"null".equals(reason)) step.put("reason", reason);
                    steps.add(step);
                    bump(countsByType, STEP_STATE_TRANSITION);
                }
                case "tool.call" -> {
                    ObjectNode step = newStep(seq++, event.getCreatedAt(), STEP_TOOL);
                    String callId = payload.path("callId").asText(null);
                    String toolName = payload.path("toolName").asText(null);
                    step.put("callId", callId);
                    step.put("tool", toolName);
                    step.set("input", payload.get("input"));
                    step.put("status", "running");
                    steps.add(step);
                    if (toolName != null && !toolName.isBlank() && !toolNames.contains(toolName)) {
                        toolNames.add(toolName);
                    }
                    if (callId != null && !callId.isBlank()) {
                        openCalls.put(callId, step);
                    }
                    bump(countsByType, STEP_TOOL);
                }
                case "tool.done" -> {
                    String callId = payload.path("callId").asText(null);
                    ObjectNode step = callId == null ? null : openCalls.remove(callId);
                    if (step == null) {
                        // Orphan tool.done (e.g. event ordering issue or tool.call was dropped):
                        // synthesize a partial step so the architect still sees the result.
                        step = newStep(seq++, event.getCreatedAt(), STEP_TOOL);
                        step.put("callId", callId);
                        step.put("tool", payload.path("toolName").asText(null));
                        steps.add(step);
                        bump(countsByType, STEP_TOOL);
                    }
                    step.put("doneTs", event.getCreatedAt());
                    step.put("status", payload.path("status").asText("success"));
                    step.set("output", payload.get("output"));
                    long startTs = step.path("ts").asLong(0);
                    if (startTs > 0) {
                        step.put("elapsedMs", event.getCreatedAt() - startTs);
                    }
                }
                case "architect.note" -> {
                    ObjectNode step = newStep(seq++, event.getCreatedAt(), STEP_ARCHITECT_NOTE);
                    step.put("note", payload.path("note").asText(null));
                    steps.add(step);
                    bump(countsByType, STEP_ARCHITECT_NOTE);
                }
                case "reasoning" -> {
                    ObjectNode step = newStep(seq++, event.getCreatedAt(), STEP_REASONING);
                    step.put("content", payload.path("content").asText(null));
                    steps.add(step);
                    bump(countsByType, STEP_REASONING);
                }
                default -> {
                    ObjectNode step = newStep(seq++, event.getCreatedAt(), STEP_OTHER);
                    step.put("eventType", event.getEventType());
                    step.set("payload", payload);
                    steps.add(step);
                    bump(countsByType, STEP_OTHER);
                }
            }
        }
        return new TypedSteps(steps, countsByType, toolNames);
    }

    private ObjectNode newStep(int seq, long ts, String type) {
        ObjectNode step = objectMapper.createObjectNode();
        step.put("seq", seq);
        step.put("ts", ts);
        step.put("type", type);
        return step;
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private JsonNode parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return objectMapper.getNodeFactory().textNode(raw);
        }
    }

    private record TypedSteps(ArrayNode steps,
                              Map<String, Integer> countsByType,
                              List<String> toolNames) {}
}
