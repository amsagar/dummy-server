package com.pods.agent.service;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.ModelRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a free-form user message into the chain's typed input parameters.
 *
 * Runs ONCE at chat→chain entry so the chain itself never has to extract structured
 * values from prose. The chain's tools then receive deterministic, schema-clean inputs
 * via $.chainInput.<paramName> JSONata expressions.
 *
 * Failure mode: if the message can't satisfy the schema's required fields, throws
 * {@link ExtractionFailed}. The caller (AgentRuntimeService) is expected to catch and
 * fall back to the normal AI loop — same behavior as the user picking "Use normal AI
 * loop" in the chain-match dialog.
 */
@Service
@Slf4j
public class ChainParameterExtractor {

    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ModelProviderRouter modelProviderRouter;
    private final ObjectMapper objectMapper;

    public ChainParameterExtractor(ModelProviderRouter modelProviderRouter, ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> extract(String userMessage,
                                       String paramSchemaJson,
                                       String extractionHints,
                                       ModelRef modelRef) throws ExtractionFailed {
        Map<String, Object> schema = parseSchema(paramSchemaJson);
        if (schema.isEmpty()) {
            // No declared schema — chain runs on free-form message (legacy chains pre Phase 6).
            return Map.of("message", userMessage == null ? "" : userMessage);
        }

        // Legacy back-compat: chains created before Phase 6 declare exactly one required
        // string param called "message". Skip the LLM hop entirely for them.
        if (isLegacyMessageOnlySchema(schema)) {
            return Map.of("message", userMessage == null ? "" : userMessage);
        }

        if (modelRef == null) {
            throw new ExtractionFailed("No model available to extract chain parameters");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new ExtractionFailed("Empty user message — cannot extract chain parameters");
        }

        String userPayload = buildUserPayload(userMessage, schema, extractionHints);
        String raw;
        try {
            ChatClient client = modelProviderRouter.resolve(modelRef, true).client();
            raw = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPayload)
                    .call()
                    .content();
        } catch (Exception e) {
            throw new ExtractionFailed("Parameter extraction LLM call failed: " + e.getMessage(), e);
        }
        Map<String, Object> extracted = parseEnvelope(raw);
        validateAgainstSchema(extracted, schema);
        return extracted;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception e) {
            log.warn("[ChainParameterExtractor] Invalid paramSchema: {}", e.getMessage());
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private boolean isLegacyMessageOnlySchema(Map<String, Object> schema) {
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> propsMap)) return false;
        if (propsMap.size() != 1) return false;
        return propsMap.containsKey("message");
    }

    private String buildUserPayload(String userMessage, Map<String, Object> schema, String hints) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userMessage", userMessage);
        payload.put("paramSchema", schema);
        if (hints != null && !hints.isBlank()) payload.put("hints", hints);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"userMessage\":\"" + userMessage.replace("\"", "\\\"") + "\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEnvelope(String raw) throws ExtractionFailed {
        if (raw == null) throw new ExtractionFailed("LLM returned no content");
        String text = raw.trim();
        Matcher fenced = FENCED_JSON.matcher(text);
        if (fenced.find()) text = fenced.group(1).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new ExtractionFailed("LLM did not return a JSON object");
        try {
            Object parsed = objectMapper.readValue(text.substring(start, end + 1), Object.class);
            if (!(parsed instanceof Map<?, ?> m)) throw new ExtractionFailed("LLM returned non-object JSON");
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } catch (Exception e) {
            throw new ExtractionFailed("Could not parse LLM JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateAgainstSchema(Map<String, Object> extracted, Map<String, Object> schema) throws ExtractionFailed {
        Object reqObj = schema.get("required");
        if (!(reqObj instanceof List<?> required)) return;
        List<String> missing = new java.util.ArrayList<>();
        for (Object fieldObj : required) {
            String field = String.valueOf(fieldObj);
            Object value = extracted.get(field);
            if (value == null || (value instanceof String s && s.isBlank())) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new ExtractionFailed("Missing required parameters: " + String.join(", ", missing));
        }
    }

    private static final String SYSTEM_PROMPT = """
            You map a free-form user message to a structured object that matches a JSON Schema.
            You will receive: the user's message, the target paramSchema, and optional hints.

            Return ONLY valid JSON in this exact shape, with no prose, no code fences:
              {"<paramName>": <value>, ...}

            Rules:
            - Match the schema's property names and types exactly (string/integer/number/boolean).
            - Each property may carry a "description" field — read it carefully. It tells you
              what the property represents and what kind of phrase in the message to look for.
              Example: property "orderId" with description "the order identity number to validate"
              means scan the message for an order ID number, even if the user phrases it as
              "validate order 5038081" or "check 5038081" or "is order #5038081 ok?".
            - Read the optional "hints" field if present — it often contains concrete examples
              of how a likely user phrase maps to the param object.
            - Fill EVERY required property. If a required value cannot be determined from the
              user message, return null for that property — the caller will detect this and
              fall back to a different code path.
            - Do NOT invent values. Only extract what's in the message.
            - Do NOT include a "message" key unless the schema explicitly asks for one.
            - Coerce numeric strings appropriately: if the schema says integer/number and the
              message has digits, return them as the right JSON type, not as a string.
            - No explanation, no commentary, only the JSON object.
            """;

    public static class ExtractionFailed extends Exception {
        public ExtractionFailed(String message) { super(message); }
        public ExtractionFailed(String message, Throwable cause) { super(message, cause); }
    }
}
