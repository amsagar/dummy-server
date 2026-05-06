package com.pods.agent.service;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Narrow per-argument fallback. Invoked by ToolChainRuntimeService for argMappings
 * marked {@code policy: "llm_assisted"} when the deterministic JSONata expression and
 * the configured fallback both produced no value.
 *
 * Returns the single value the tool needs, validated against the tool's request schema
 * fragment for that argument. Best-effort: any failure returns null and the runtime
 * proceeds without the value (the tool may still succeed, fail loudly, or skip).
 */
@Service
@Slf4j
public class LlmArgResolver {

    private static final int MAX_CONTEXT_CHARS = 6000;

    private final ModelProviderRouter modelProviderRouter;
    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public LlmArgResolver(ModelProviderRouter modelProviderRouter,
                          ToolRegistryService toolRegistryService,
                          ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    public java.util.Optional<ResolvedArg> resolve(String toolName,
                                                   String argName,
                                                   Map<String, Object> context,
                                                   ModelRef modelRef,
                                                   String hintExpression) {
        if (modelRef == null || toolName == null || argName == null) return java.util.Optional.empty();
        try {
            Map<String, Object> argSchema = extractArgSchema(toolName, argName);
            String userPayload = buildUserPayload(toolName, argName, argSchema, context, hintExpression);

            ChatClient client = modelProviderRouter.resolve(modelRef, true).client();
            String raw = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPayload)
                    .call()
                    .content();
            return parseEnvelope(raw, argSchema);
        } catch (Exception e) {
            log.warn("[LlmArgResolver] {} arg '{}' resolution failed: {}", toolName, argName, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private Map<String, Object> extractArgSchema(String toolName, String argName) {
        AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
        if (tool == null || tool.getRequestSchema() == null || tool.getRequestSchema().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(tool.getRequestSchema(), Map.class);
            Object props = root.get("properties");
            if (props instanceof Map<?, ?> propsMap) {
                Object argProp = propsMap.get(argName);
                if (argProp instanceof Map<?, ?> argMap) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    argMap.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return out;
                }
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private String buildUserPayload(String toolName,
                                    String argName,
                                    Map<String, Object> argSchema,
                                    Map<String, Object> context,
                                    String hintExpression) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("argName", argName);
        payload.put("argSchema", argSchema);
        if (hintExpression != null && !hintExpression.isBlank()) {
            payload.put("hintExpression", hintExpression);
        }
        String contextJson = objectMapper.writeValueAsString(context);
        if (contextJson.length() > MAX_CONTEXT_CHARS) {
            contextJson = contextJson.substring(0, MAX_CONTEXT_CHARS);
            payload.put("contextTruncated", true);
        }
        payload.put("context", objectMapper.readValue(contextJson, Object.class));
        return objectMapper.writeValueAsString(payload);
    }

    private java.util.Optional<ResolvedArg> parseEnvelope(String raw, Map<String, Object> argSchema) throws Exception {
        if (raw == null) return java.util.Optional.empty();
        String text = raw.trim();
        // Strip code fences if present
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            int closing = text.lastIndexOf("```");
            if (closing > 0) text = text.substring(0, closing);
            text = text.trim();
        }
        if (text.isEmpty()) return java.util.Optional.empty();
        Map<String, Object> envelope = objectMapper.readValue(text, Map.class);
        Object value = envelope.get("value");
        if (value == null) return java.util.Optional.empty();
        Object coerced = coerceToSchema(value, argSchema);
        Object exprRaw = envelope.get("expr");
        String expr = exprRaw == null ? null : String.valueOf(exprRaw).trim();
        if (expr != null && (expr.isEmpty() || "null".equalsIgnoreCase(expr))) expr = null;
        return java.util.Optional.of(new ResolvedArg(coerced, expr));
    }

    private Object coerceToSchema(Object value, Map<String, Object> argSchema) {
        Object type = argSchema == null ? null : argSchema.get("type");
        if (!(type instanceof String typeName)) return value;
        return switch (typeName.toLowerCase()) {
            case "string" -> value instanceof String s ? s : String.valueOf(value);
            case "integer" -> value instanceof Number n ? n.longValue() : tryParseLong(value);
            case "number" -> value instanceof Number n ? n.doubleValue() : tryParseDouble(value);
            case "boolean" -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
            default -> value;
        };
    }

    private Long tryParseLong(Object value) {
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return null; }
    }

    private Double tryParseDouble(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { return null; }
    }

    private static final String SYSTEM_PROMPT = """
            You resolve a single argument value for a tool call inside a deterministic toolchain.
            You will receive: the tool name, the argument name, the argument's JSON Schema, the
            full runtime context (chainInput and prior step inputs/outputs), and optionally a
            hintExpression that the deterministic JSONata evaluator already tried.

            Return ONLY valid JSON in this exact shape, with no prose, no code fences:
              {"value": <the value, matching the schema>, "expr": "<JSONata or null>"}

            Rules:
            - "value" is the value the tool needs, derived from the runtime context. Match the JSON
              Schema exactly (string -> string, integer -> integer, etc.).
            - "expr" is a JSONata expression that, evaluated against this same runtime context,
              would produce the same value. The runtime will VERIFY this expression and, on a
              match, persist it so subsequent runs are deterministic and skip this LLM call.
              - Reference $.chainInput.<key>, $.tool_<N>.input.<key>, $.tool_<N>.output.<key>.
              - Use JSONata transforms (filter, map, format) where the value derives from prior
                outputs. Use a literal expression like "'WEB'" for a constant string.
              - If you cannot express the derivation cleanly, return "expr": null and only the
                value will be used (this run only).
            - Do not invent fields that are not in the context.
            - If you genuinely cannot determine the value from the context, return
              {"value": null, "expr": null}.
            - No explanation. No commentary. Only the JSON.
            """;

    public record ResolvedArg(Object value, String learnedExpr) {}
}
