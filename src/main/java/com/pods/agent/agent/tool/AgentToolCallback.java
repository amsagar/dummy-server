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
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final int toolOutputVfsSpillThresholdChars;

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
                runtimeEventRepository, skillExecutionGate, null, false, 16_384);
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
                             boolean bypassApprovalGate,
                             int toolOutputVfsSpillThresholdChars) {
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
        this.toolOutputVfsSpillThresholdChars = Math.max(0, toolOutputVfsSpillThresholdChars);
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
        String toolCallSignature = buildToolCallSignature(tool.getName(), payload);

        if (!isSkillTool && skillExecutionGate != null && skillExecutionGate.isRequired() && !skillExecutionGate.isSkillLoaded()) {
            // Soft gate: don't block the first domain tool call. Hard-blocking causes
            // noisy duplicate calls and stalls practical flows when the model has already
            // enough context from system prompt/skill catalog.
            log.info("[AgentToolCallback] soft-bypassing skill-first gate for tool={} sessionId={} turnId={}",
                    tool.getName(), sessionId, turnId);
            skillExecutionGate.markSkillLoaded();
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

        if (skillExecutionGate != null && toolCallSignature != null) {
            String cached = skillExecutionGate.getCachedToolResult(toolCallSignature);
            if (cached != null) {
                log.info("[AgentToolCallback] deduped tool={} sessionId={} turnId={}",
                        tool.getName(), sessionId, turnId);
                sender.sendToolResult(sessionId, callId, tool.getName(), cached, "success");
                saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":\"success\",\"output\":" + json(cached) + "}");
                return cached;
            }
        }

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

        // Workflow question bridge:
        // "question" tool currently returns a marker payload (approval_required:<prompt>).
        // Convert that marker into a real interactive HITL question card and wait for reply.
        if (execution.success() && isQuestionWorkflowPrompt(output)) {
            output = handleWorkflowQuestionPrompt(output);
        }

        String emittedOutput = maybeSpillToolOutput(output, callId, execution.success() ? "success" : "error");
        sender.sendToolResult(sessionId, callId, tool.getName(), emittedOutput,
                execution.success() ? "success" : "error");
        saveRuntimeEvent("tool.done", "{\"callId\":" + json(callId) + ",\"toolName\":" + json(tool.getName()) + ",\"status\":" + json(execution.success() ? "success" : "error") + ",\"output\":" + json(emittedOutput) + "}");
        if (skillExecutionGate != null && toolCallSignature != null) {
            skillExecutionGate.cacheToolResult(toolCallSignature, emittedOutput);
        }
        return emittedOutput;
    }

    private boolean isQuestionWorkflowPrompt(String output) {
        if (output == null || output.isBlank()) return false;
        String normalizedTool = tool.getName() == null ? "" : tool.getName().trim().toLowerCase();
        return "question".equals(normalizedTool) && output.startsWith("approval_required:");
    }

    private String handleWorkflowQuestionPrompt(String output) {
        String prompt = output.substring("approval_required:".length()).trim();
        if (prompt.isBlank()) {
            prompt = "Please provide the required clarification.";
        }
        String requestId = pendingInteractionService.create(sessionId, turnId, "question", prompt);
        sender.sendQuestion(sessionId, requestId, prompt);
        saveRuntimeEvent("question", "{\"requestId\":" + json(requestId) + ",\"question\":" + json(prompt) + "}");
        try {
            PendingInteractionService.InteractionReply reply =
                    pendingInteractionService.awaitReply(requestId, approvalTimeoutMs);
            if (reply == null || reply.message() == null || reply.message().isBlank()) {
                return "No clarification received from user.";
            }
            return reply.message();
        } catch (TimeoutException e) {
            return "Question timed out waiting for user response.";
        } catch (Exception e) {
            return "Question handling failed: " + e.getMessage();
        }
    }

    private String maybeSpillToolOutput(String output, String callId, String status) {
        if (output == null || output.isBlank()) return output;
        if (toolOutputVfsSpillThresholdChars <= 0) return output;
        if (output.length() < toolOutputVfsSpillThresholdChars) return output;
        if (workspace == null || isFilesystemTool()) return output;
        try {
            String extension = looksLikeJson(output) ? "json" : "txt";
            String safeToolName = sanitizeName(tool.getName());
            Path relative = Path.of(".pods-agent", "turns", turnId, "tool-results",
                    safeToolName + "-" + callId + "." + extension);
            Path target = workspace.resolve(relative).normalize();
            if (!target.startsWith(workspace)) {
                log.warn("[AgentToolCallback] refused tool output spill outside workspace: {}", target);
                return output;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, output, StandardCharsets.UTF_8);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("status", status);
            envelope.put("toolName", tool.getName());
            envelope.put("storedInVfs", true);
            envelope.put("path", relative.toString().replace("\\", "/"));
            envelope.put("chars", output.length());
            envelope.put("bytes", output.getBytes(StandardCharsets.UTF_8).length);
            envelope.put("preview", truncate(output, 500));
            envelope.put("guidance",
                    "Large output was saved to workspace VFS. Use read/grep on the provided path to analyze details.");
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("[AgentToolCallback] failed spilling large tool output to VFS, returning inline output: {}", e.getMessage());
            return output;
        }
    }

    private boolean looksLikeJson(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
        try {
            objectMapper.readTree(trimmed);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isFilesystemTool() {
        String executionKind = tool == null || tool.getExecutionKind() == null
                ? ""
                : tool.getExecutionKind().trim().toLowerCase();
        if ("filesystem".equals(executionKind)) return true;
        String name = tool == null || tool.getName() == null ? "" : tool.getName().trim().toLowerCase();
        return "read".equals(name)
                || "glob".equals(name)
                || "grep".equals(name)
                || "write".equals(name)
                || "edit".equals(name)
                || "apply_patch".equals(name);
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
            return ensureEndpointPathParams(inferSchemaFromSampleRequest(t, EMPTY_OBJECT_SCHEMA), t);
        }
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            Object schema = parsed;
            if (parsed instanceof java.util.Map<?, ?> outer && outer.get("inputSchema") != null) {
                schema = outer.get("inputSchema");
            }
            String normalized = normalizeObjectSchema(schema);
            return ensureEndpointPathParams(inferSchemaFromSampleRequest(t, normalized), t);
        } catch (Exception e) {
            String legacy = resolveLegacyFallbackSchema(t, raw);
            if (legacy != null) {
                return ensureEndpointPathParams(inferSchemaFromSampleRequest(t, legacy), t);
            }
            log.debug("[AgentToolCallback] schema parse failed for tool={}, falling back to empty object. error={}, schemaSnippet={}",
                    t.getName(), e.getMessage(), truncate(raw, 220));
            return ensureEndpointPathParams(inferSchemaFromSampleRequest(t, EMPTY_OBJECT_SCHEMA), t);
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

    @SuppressWarnings("unchecked")
    private String inferSchemaFromSampleRequest(AgentTool tool, String schemaJson) {
        if (tool == null || tool.getSampleRequest() == null || tool.getSampleRequest().isBlank()) {
            return schemaJson;
        }
        try {
            Object schemaParsed = objectMapper.readValue(schemaJson, Object.class);
            if (!(schemaParsed instanceof Map<?, ?> rawSchema)) return schemaJson;
            Map<String, Object> schema = new LinkedHashMap<>((Map<String, Object>) rawSchema);
            schema.putIfAbsent("type", "object");

            Object propsObj = schema.get("properties");
            Map<String, Object> properties = propsObj instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : new LinkedHashMap<>();
            if (!properties.isEmpty()) return schemaJson;

            Object sampleParsed = objectMapper.readValue(tool.getSampleRequest(), Object.class);
            if (!(sampleParsed instanceof Map<?, ?> sampleMap) || sampleMap.isEmpty()) return schemaJson;

            Map<String, Object> inferredProps = new LinkedHashMap<>();
            List<String> inferredRequired = new ArrayList<>();
            for (Map.Entry<?, ?> entry : sampleMap.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                inferredProps.put(key, inferSchemaNode(value));
                if (value != null) inferredRequired.add(key);
            }
            if (inferredProps.isEmpty()) return schemaJson;

            schema.put("properties", inferredProps);
            if (!inferredRequired.isEmpty()) {
                schema.put("required", inferredRequired.stream().distinct().toList());
            }
            normalizeSchemaNode(schema);
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.debug("[AgentToolCallback] sampleRequest schema inference skipped for tool={} error={}",
                    tool.getName(), e.getMessage());
            return schemaJson;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inferSchemaNode(Object value) {
        if (value == null) return Map.of("type", "string");
        if (value instanceof Boolean) return Map.of("type", "boolean");
        if (value instanceof Integer || value instanceof Long) return Map.of("type", "integer");
        if (value instanceof Number) return Map.of("type", "number");
        if (value instanceof String) return Map.of("type", "string");
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return Map.of("type", "array", "items", Map.of("type", "string"));
            }
            return Map.of("type", "array", "items", inferSchemaNode(list.get(0)));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nestedProps = new LinkedHashMap<>();
            List<String> nestedRequired = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = String.valueOf(entry.getKey());
                Object nestedValue = entry.getValue();
                nestedProps.put(key, inferSchemaNode(nestedValue));
                if (nestedValue != null) nestedRequired.add(key);
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", "object");
            node.put("properties", nestedProps);
            if (!nestedRequired.isEmpty()) {
                node.put("required", nestedRequired.stream().distinct().toList());
            }
            return node;
        }
        return Map.of("type", "string");
    }

    private String buildToolCallSignature(String toolName, String payload) {
        if (toolName == null || toolName.isBlank()) return null;
        String normalizedToolName = toolName.trim().toLowerCase();
        String canonicalPayload = canonicalizeJsonPayload(payload);
        return normalizedToolName + "|" + canonicalPayload;
    }

    private String canonicalizeJsonPayload(String payload) {
        if (payload == null || payload.isBlank()) return "{}";
        try {
            Object parsed = objectMapper.readValue(payload, Object.class);
            Object normalized = normalizeJson(parsed);
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return payload.trim();
        }
    }

    @SuppressWarnings("unchecked")
    private Object normalizeJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), normalizeJson(v)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeJson(item));
            }
            return normalized;
        }
        return value;
    }
}
