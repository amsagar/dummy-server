package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts an {@link AgentTool} to Spring AI's {@link ToolCallback} so the LLM
 * can invoke it via native tool/function calling. Tool calls and results are
 * surfaced as SSE events on the per-turn {@link SseEventSender}.
 */
@Slf4j
public class AgentToolCallback implements ToolCallback {

    private final AgentTool tool;
    private final ToolExecutionService toolExecutionService;
    private final GuardrailPolicyEngine policyEngine;
    private final PendingInteractionService pendingInteractionService;
    private final SseEventSender sender;
    private final String sessionId;
    private final String turnId;
    private final String userId;
    private final long approvalTimeoutMs;
    private final ObjectMapper objectMapper;
    private final RuntimeEventRepository runtimeEventRepository;
    private final SkillExecutionGate skillExecutionGate;
    private final java.nio.file.Path workspace;
    private final boolean bypassApprovalGate;

    public AgentToolCallback(AgentTool tool,
                             ToolExecutionService toolExecutionService,
                             GuardrailPolicyEngine policyEngine,
                             PendingInteractionService pendingInteractionService,
                             SseEventSender sender,
                             String sessionId,
                             String turnId,
                             String userId,
                             long approvalTimeoutMs,
                             ObjectMapper objectMapper,
                             RuntimeEventRepository runtimeEventRepository,
                             SkillExecutionGate skillExecutionGate) {
        this(tool, toolExecutionService, policyEngine, pendingInteractionService, sender,
                sessionId, turnId, userId, approvalTimeoutMs, objectMapper,
                runtimeEventRepository, skillExecutionGate, null, false);
    }

    public AgentToolCallback(AgentTool tool,
                             ToolExecutionService toolExecutionService,
                             GuardrailPolicyEngine policyEngine,
                             PendingInteractionService pendingInteractionService,
                             SseEventSender sender,
                             String sessionId,
                             String turnId,
                             String userId,
                             long approvalTimeoutMs,
                             ObjectMapper objectMapper,
                             RuntimeEventRepository runtimeEventRepository,
                             SkillExecutionGate skillExecutionGate,
                             java.nio.file.Path workspace,
                             boolean bypassApprovalGate) {
        this.tool = tool;
        this.toolExecutionService = toolExecutionService;
        this.policyEngine = policyEngine;
        this.pendingInteractionService = pendingInteractionService;
        this.sender = sender;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.userId = userId;
        this.approvalTimeoutMs = approvalTimeoutMs;
        this.objectMapper = objectMapper;
        this.runtimeEventRepository = runtimeEventRepository;
        this.skillExecutionGate = skillExecutionGate;
        this.workspace = workspace;
        this.bypassApprovalGate = bypassApprovalGate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(sanitizeName(tool.getName()))
                .description(tool.getDescription() == null ? "" : tool.getDescription())
                .inputSchema(resolveInputSchema(tool))
                .build();
    }

    @Override
    public String call(String jsonInput) {
        return call(jsonInput, null);
    }

    @Override
    public String call(String jsonInput, ToolContext ctx) {
        String payload = jsonInput == null ? "{}" : jsonInput;
        String callId = UUID.randomUUID().toString();
        boolean isSkillTool = tool.getName() != null && "skill".equalsIgnoreCase(tool.getName().trim());

        if (!isSkillTool && skillExecutionGate != null && skillExecutionGate.isRequired() && !skillExecutionGate.isSkillLoaded()) {
            String blocked = "Skill-first gate active: call the `skill` tool first to load relevant instructions before invoking domain tools.";
            sender.sendToolCall(sessionId, callId, tool.getName(), payload);
            saveRuntimeEvent("tool.call", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"input\":" + json(payload) + "}");
            sender.sendToolResult(sessionId, callId, tool.getName(), blocked, "blocked");
            saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":\"blocked\",\"output\":" + json(blocked) + "}");
            return blocked;
        }

        GuardrailPolicyEngine.Decision decision = policyEngine.evaluateTool(tool);
        // ToolChain designer turns operate inside the per-session workspace sandbox
        // (read/edit/apply_patch are bounded by safePath). Approval prompts on every
        // edit would block the architect's silent VFS edits; allow the runtime to opt
        // out via bypassApprovalGate.
        if (bypassApprovalGate && "ask".equalsIgnoreCase(decision.decision())) {
            decision = new GuardrailPolicyEngine.Decision("allow", "designer-bypass");
        }
        if ("deny".equalsIgnoreCase(decision.decision())) {
            String denied = "Denied by policy: " + decision.reason();
            sender.sendToolCall(sessionId, callId, tool.getName(), payload);
            saveRuntimeEvent("tool.call", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"input\":" + json(payload) + "}");
            sender.sendToolResult(sessionId, callId, tool.getName(), denied, "denied");
            saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":\"denied\",\"output\":" + json(denied) + "}");
            return denied;
        }
        if ("ask".equalsIgnoreCase(decision.decision())) {
            String approvalReason = "Tool requires approval: " + tool.getName();
            String requestId = pendingInteractionService.create(sessionId, turnId, "approval_required", approvalReason);
            sender.sendApprovalRequired(sessionId, requestId, approvalReason);
            saveRuntimeEvent("approval_required", "{\"requestId\":" + json(requestId) + ",\"reason\":" + json(approvalReason) + "}");
            try {
                PendingInteractionService.InteractionReply reply =
                        pendingInteractionService.awaitReply(requestId, approvalTimeoutMs);
                if (reply == null || "rejected".equalsIgnoreCase(reply.action())) {
                    String msg = reply == null ? "Approval timed out." : "Action rejected by user.";
                    sender.sendToolCall(sessionId, callId, tool.getName(), payload);
                    saveRuntimeEvent("tool.call", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"input\":" + json(payload) + "}");
                    sender.sendToolResult(sessionId, callId, tool.getName(), msg, "denied");
                    saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":\"denied\",\"output\":" + json(msg) + "}");
                    return msg;
                }
            } catch (TimeoutException e) {
                String msg = "Approval timed out.";
                sender.sendToolCall(sessionId, callId, tool.getName(), payload);
                saveRuntimeEvent("tool.call", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"input\":" + json(payload) + "}");
                sender.sendToolResult(sessionId, callId, tool.getName(), msg, "denied");
                saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":\"denied\",\"output\":" + json(msg) + "}");
                return msg;
            }
        }

        sender.sendToolCall(sessionId, callId, tool.getName(), payload);
        saveRuntimeEvent("tool.call", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"input\":" + json(payload) + "}");
        log.debug("[AgentToolCallback] tool={} input={}", tool.getName(), payload);

        ToolExecutionService.ExecutionResult execution;
        try {
            // Bind the per-session workspace path so filesystem tools (read/edit/apply_patch
            // /write) resolve relative paths against it. Spring AI runs tool callbacks on a
            // separate scheduler thread, so the WorkspaceContextHolder ThreadLocal set by
            // the parent runTurn doesn't propagate here — we re-bind explicitly per tool call.
            execution = com.pods.agent.service.workspace.WorkspaceContextHolder.withWorkspace(
                    workspace,
                    () -> UserContextHolder.withUser(userId, () -> toolExecutionService.execute(tool, payload)));
        } catch (Exception e) {
            String err = "Tool execution exception: " + e.getMessage();
            sender.sendToolResult(sessionId, callId, tool.getName(), err, "error");
            saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":\"error\",\"output\":" + json(err) + "}");
            return err;
        }
        log.info("[AgentToolCallback] tool={} execution success={} error={} bodySnippet={}",
                tool.getName(),
                execution.success(),
                execution.error(),
                truncate(execution.body(), 1000));

        String output = execution.success() ? execution.body() : execution.error();
        if (output == null || output.isBlank()) {
            output = execution.success()
                    ? "Tool '" + tool.getName() + "' returned an empty response. The service may be unavailable, the record may not exist, or the query returned no results. Do not mark this as unverifiable without reporting this specific issue to the user."
                    : "Tool '" + tool.getName() + "' failed with no error detail. The service endpoint may be unreachable.";
        }
        sender.sendToolResult(sessionId, callId, tool.getName(), output,
                execution.success() ? "success" : "error");
        saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":" + json(execution.success() ? "success" : "error") + ",\"output\":" + json(output) + "}");
        return output;
    }

    private void saveRuntimeEvent(String eventType, String payload) {
        if (runtimeEventRepository == null) return;
        try {
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .eventType(eventType)
                    .payload(payload)
                    .build());
        } catch (Exception e) {
            log.debug("[AgentToolCallback] runtime event save failed type={} error={}", eventType, e.getMessage());
        }
    }

    private String json(String value) {
        if (value == null) return "null";
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    private static final String EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private String resolveInputSchema(AgentTool t) {
        String raw = t.getRequestSchema();
        if (raw == null || raw.isBlank()) {
            return ensureEndpointPathParams(EMPTY_OBJECT_SCHEMA, t);
        }
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            Object schema = parsed;
            if (parsed instanceof java.util.Map<?, ?> outer && outer.get("inputSchema") != null) {
                schema = outer.get("inputSchema");
            }
            return ensureEndpointPathParams(normalizeObjectSchema(schema), t);
        } catch (Exception e) {
            String legacy = resolveLegacyFallbackSchema(t, raw);
            if (legacy != null) {
                return ensureEndpointPathParams(legacy, t);
            }
            log.debug("[AgentToolCallback] schema parse failed for tool={}, falling back to empty object. error={}, schemaSnippet={}",
                    t.getName(), e.getMessage(), truncate(raw, 220));
            return ensureEndpointPathParams(EMPTY_OBJECT_SCHEMA, t);
        }
    }

    @SuppressWarnings("unchecked")
    private String normalizeObjectSchema(Object schema) throws Exception {
        if (!(schema instanceof java.util.Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return EMPTY_OBJECT_SCHEMA;
        }
        java.util.Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<?, ?> e : rawMap.entrySet()) {
            if (e.getKey() == null) continue;
            normalized.put(String.valueOf(e.getKey()), e.getValue());
        }
        Object type = normalized.get("type");
        if (!(type instanceof String typeStr) || !"object".equalsIgnoreCase(typeStr)) {
            normalized.put("type", "object");
        }
        if (!(normalized.get("properties") instanceof java.util.Map)) {
            // If the schema looks like a flat {field: "string", ...} map, wrap into properties
            java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> e : normalized.entrySet()) {
                String k = e.getKey();
                if ("type".equals(k) || "required".equals(k) || "description".equals(k)
                        || "additionalProperties".equals(k) || "$schema".equals(k) || "title".equals(k)) {
                    continue;
                }
                Object v = e.getValue();
                if (v instanceof String typeName) {
                    properties.put(k, java.util.Map.of("type", typeName));
                } else if (v instanceof java.util.Map) {
                    properties.put(k, v);
                }
            }
            // Drop the wrapped fields now that they're in properties
            properties.keySet().forEach(normalized::remove);
            normalized.put("properties", properties);
        }
        normalizeSchemaNode(normalized);
        return objectMapper.writeValueAsString(normalized);
    }

    @SuppressWarnings("unchecked")
    private void normalizeSchemaNode(Object node) {
        if (node instanceof java.util.Map<?, ?> rawMap) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    map.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            Object type = map.get("type");
            if (type instanceof String typeName && "array".equalsIgnoreCase(typeName)) {
                Object items = map.get("items");
                if (items == null || (items instanceof java.util.Map<?, ?> itemsMap && itemsMap.isEmpty())) {
                    // Azure/OpenAI tool schema requires explicit items for array properties.
                    map.put("items", java.util.Map.of("type", "object"));
                }
            }
            if (map.containsKey("$ref")) {
                // Tool schemas sent to model providers cannot include unresolved OpenAPI refs.
                map.remove("$ref");
                map.putIfAbsent("type", "object");
            }

            Object properties = map.get("properties");
            if (properties instanceof java.util.Map<?, ?> props) {
                for (Object value : props.values()) {
                    normalizeSchemaNode(value);
                }
            }

            Object items = map.get("items");
            if (items != null) {
                normalizeSchemaNode(items);
            }

            Object anyOf = map.get("anyOf");
            if (anyOf instanceof java.util.List<?> list) {
                for (Object value : list) {
                    normalizeSchemaNode(value);
                }
            }

            Object oneOf = map.get("oneOf");
            if (oneOf instanceof java.util.List<?> list) {
                for (Object value : list) {
                    normalizeSchemaNode(value);
                }
            }

            Object allOf = map.get("allOf");
            if (allOf instanceof java.util.List<?> list) {
                for (Object value : list) {
                    normalizeSchemaNode(value);
                }
            }
            return;
        }

        if (node instanceof java.util.List<?> list) {
            for (Object value : list) {
                normalizeSchemaNode(value);
            }
        }
    }

    private String resolveLegacyFallbackSchema(AgentTool tool, String raw) {
        try {
            String trimmed = raw == null ? "" : raw.trim();
            if (!trimmed.startsWith("{") || !trimmed.contains("=")) {
                return null;
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            // Always recover path parameters from endpoint placeholders.
            String endpoint = tool.getEndpoint() == null ? "" : tool.getEndpoint();
            Matcher matcher = Pattern.compile("\\{([^}/]+)}").matcher(endpoint);
            while (matcher.find()) {
                String key = matcher.group(1);
                if (key == null || key.isBlank()) continue;
                properties.put(key, Map.of("type", "string"));
                required.add(key);
            }

            // Best-effort request body recovery for old non-JSON request schema rows.
            if (trimmed.contains("schema={type=array")) {
                properties.putIfAbsent("body", Map.of("type", "array", "items", Map.of("type", "object")));
            } else if (trimmed.contains("multipart/form-data") && trimmed.contains("file")) {
                properties.putIfAbsent("file", Map.of("type", "string"));
                required.add("file");
            } else if (trimmed.contains("content=") || trimmed.contains("schema={")) {
                properties.putIfAbsent("body", Map.of("type", "object"));
            }

            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required.stream().distinct().toList());
            }
            normalizeSchemaNode(schema);
            return objectMapper.writeValueAsString(schema);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String ensureEndpointPathParams(String schemaJson, AgentTool tool) {
        try {
            Object parsed = objectMapper.readValue(schemaJson, Object.class);
            if (!(parsed instanceof Map<?, ?> rawMap)) {
                return schemaJson;
            }
            Map<String, Object> schema = new LinkedHashMap<>((Map<String, Object>) rawMap);
            schema.put("type", "object");

            Map<String, Object> properties;
            Object propertiesObj = schema.get("properties");
            if (propertiesObj instanceof Map<?, ?> map) {
                properties = new LinkedHashMap<>((Map<String, Object>) map);
            } else {
                properties = new LinkedHashMap<>();
            }

            List<String> required = new ArrayList<>();
            Object requiredObj = schema.get("required");
            if (requiredObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) required.add(String.valueOf(item));
                }
            }

            String endpoint = tool.getEndpoint() == null ? "" : tool.getEndpoint();
            Matcher matcher = Pattern.compile("\\{([^}/]+)}").matcher(endpoint);
            while (matcher.find()) {
                String key = matcher.group(1);
                if (key == null || key.isBlank()) continue;
                properties.putIfAbsent(key, Map.of("type", "string"));
                required.add(key);
            }

            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required.stream().distinct().toList());
            }
            normalizeSchemaNode(schema);
            return objectMapper.writeValueAsString(schema);
        } catch (Exception ignored) {
            return schemaJson;
        }
    }

    private String sanitizeName(String name) {
        if (name == null) return "tool";
        String cleaned = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned.isBlank() ? "tool" : cleaned;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
