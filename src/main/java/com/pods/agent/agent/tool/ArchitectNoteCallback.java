package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.service.workspace.ExecutionLogService;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Native tool callback that lets the chat agent drop a short reasoning note
 * into the in-flight execution log. The note becomes an
 * {@code architect_note} step in the typed execution log produced at end of
 * turn, giving the {@link com.pods.agent.workflow.proposal.WorkflowArchitectService}
 * concrete intent annotations to anchor on (loop boundaries, condition
 * predicates, parallel fan-outs, decision points) when it converts the run
 * into a reusable workflow graph.
 *
 * <p>Scope: write-only, restricted to the in-flight turn. The chat agent
 * cannot read existing notes back, list them, or write anywhere else on the
 * filesystem through this callback. The actual persistence happens through
 * {@link ExecutionLogService#appendArchitectNote} — the same audit pipeline
 * that handles every other runtime event.
 *
 * <p>Recommended use (described in the workflow-architect skill the chat
 * agent loads on demand): one short note before a logical block, e.g.
 * <em>"loop products: for each item in #products"</em> or
 * <em>"condition: only review when total > 500"</em>.
 */
@Slf4j
public class ArchitectNoteCallback implements ToolCallback {

    private static final String TOOL_NAME = "architect_note";
    private static final String DESCRIPTION =
            "Append a short reasoning note to the current turn's execution log. Use this to mark "
                    + "logical structure that the Workflow Architect should pick up when converting "
                    + "this run into a reusable workflow \u2014 e.g. \"loop over products\", "
                    + "\"condition: total > 500\", \"parallel fan-out for inventory + pricing\", "
                    + "\"ai_reasoning here: classify fraud risk\". Notes are append-only, scoped to "
                    + "the current turn, and stored alongside tool calls in execution-log-<turnId>.json.";
    private static final String INPUT_SCHEMA = """
            {"type":"object",
             "required":["note"],
             "properties":{
               "note":{"type":"string","description":"Concise reasoning note (one sentence is ideal)."}
             }}
            """;

    private final ExecutionLogService executionLogService;
    private final SseEventSender sender;
    private final String sessionId;
    private final String turnId;
    private final ObjectMapper objectMapper;

    public ArchitectNoteCallback(ExecutionLogService executionLogService,
                                 SseEventSender sender,
                                 String sessionId,
                                 String turnId,
                                 ObjectMapper objectMapper) {
        this.executionLogService = executionLogService;
        this.sender = sender;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String jsonInput) {
        return call(jsonInput, null);
    }

    @Override
    public String call(String jsonInput, ToolContext toolContext) {
        String callId = UUID.randomUUID().toString();
        String payload = jsonInput == null ? "{}" : jsonInput;
        try {
            sender.sendToolCall(sessionId, callId, TOOL_NAME, payload);
        } catch (Exception ignored) {
            // SSE may be closed by the time the LLM emits the call — non-fatal.
        }

        String note = extractNote(payload);
        if (note == null || note.isBlank()) {
            String msg = "Missing required input: note";
            sendResultSafely(callId, msg, "error");
            return msg;
        }
        try {
            executionLogService.appendArchitectNote(sessionId, turnId, note);
        } catch (Exception e) {
            String msg = "Note persistence failed: " + e.getMessage();
            log.debug("[ArchitectNoteCallback] {}", msg);
            sendResultSafely(callId, msg, "error");
            return msg;
        }
        String ack = "Note recorded.";
        sendResultSafely(callId, ack, "success");
        return ack;
    }

    private void sendResultSafely(String callId, String body, String status) {
        try {
            sender.sendToolResult(sessionId, callId, TOOL_NAME, body, status);
        } catch (Exception ignored) {
            // Same logic as above — never let a UI broadcast failure poison the tool result.
        }
    }

    private String extractNote(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            Object value = parsed.get("note");
            return value == null ? null : String.valueOf(value).trim();
        } catch (Exception ignored) {
            return payload.trim();
        }
    }
}
