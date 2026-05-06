package com.pods.agent.service;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls an LLM to author the human-facing pieces of a system-suggested toolchain
 * from a recorded turn (tool inputs + outputs + the user prompt).
 *
 * Output: name, description, example intents, and a per-node argMappings block
 * expressed as JSONata against the runtime context shape (chainInput,
 * tool_N.input, tool_N.output).
 *
 * Always best-effort. Any failure (LLM down, schema validation fails, parse fails)
 * returns Optional.empty() so the caller can fall back to deterministic suggestion.
 */
@Service
@Slf4j
public class ToolChainAuthoringService {

    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TOOL_OUTPUT_CHARS = 4000;
    /** Mirror of ToolChainSuggestionService.AGENT_LOOP_INFRASTRUCTURE_TOOLS — keep in sync. */
    private static final java.util.Set<String> AGENT_LOOP_INFRASTRUCTURE_TOOLS = java.util.Set.of("skill");

    private final ModelProviderRouter modelProviderRouter;
    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public ToolChainAuthoringService(ModelProviderRouter modelProviderRouter,
                                     ToolRegistryService toolRegistryService,
                                     ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    public Optional<AuthoringResult> author(String userPrompt,
                                            List<RuntimeEvent> turnEvents,
                                            ModelRef modelRef) {
        if (modelRef == null) return Optional.empty();
        List<RecordedToolCall> calls = pairCallsAndResults(turnEvents);
        if (calls.size() < 2) return Optional.empty();

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("userPrompt", userPrompt == null ? "" : userPrompt);
        userPayload.put("toolCalls", calls.stream().map(this::renderCall).toList());

        try {
            ChatClient client = modelProviderRouter.resolve(modelRef, true).client();
            String raw = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(toJson(userPayload))
                    .call()
                    .content();
            String jsonBlock = extractJsonObject(raw);
            if (jsonBlock == null || jsonBlock.isBlank()) {
                log.warn("[ToolChainAuthoringService] LLM returned no JSON block; raw={}", truncate(raw, 400));
                return Optional.empty();
            }
            Map<String, Object> parsed = objectMapper.readValue(jsonBlock, Map.class);
            return Optional.of(buildResult(parsed));
        } catch (Exception e) {
            log.warn("[ToolChainAuthoringService] Authoring call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private AuthoringResult buildResult(Map<String, Object> parsed) {
        String name = stringOrNull(parsed.get("name"));
        String description = stringOrNull(parsed.get("description"));
        List<String> intents = readStringList(parsed.get("intents"));
        Map<String, Map<String, Object>> nodeMappings = readNodeMappings(parsed.get("nodeMappings"));
        return new AuthoringResult(name, description, intents, nodeMappings);
    }

    private List<RecordedToolCall> pairCallsAndResults(List<RuntimeEvent> events) {
        List<RecordedToolCall> calls = new ArrayList<>();
        Map<String, RecordedToolCall> byCallId = new LinkedHashMap<>();
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String callId = stringOrNull(payload.get("callId"));
            String toolName = stringOrNull(payload.get("toolName"));
            String type = event.getEventType().toLowerCase(Locale.ROOT);
            if ("tool.call".equals(type)) {
                if (callId == null || toolName == null || toolName.isBlank()) continue;
                if (AGENT_LOOP_INFRASTRUCTURE_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) continue;
                Object input = parseFlexible(payload.get("input"));
                RecordedToolCall call = new RecordedToolCall(toolName, input, null);
                byCallId.put(callId, call);
                calls.add(call);
            } else if ("tool.done".equals(type)) {
                if (callId == null) continue;
                RecordedToolCall existing = byCallId.get(callId);
                if (existing == null) continue;
                Object output = parseFlexible(payload.get("output"));
                String status = stringOrNull(payload.get("status"));
                int idx = calls.indexOf(existing);
                if (idx >= 0) {
                    calls.set(idx, new RecordedToolCall(existing.toolName(), existing.input(), output));
                    byCallId.put(callId, calls.get(idx));
                }
                if ("error".equalsIgnoreCase(status) || "denied".equalsIgnoreCase(status)) {
                    return List.of();
                }
            }
        }
        // Drop calls without a matching result (incomplete turns are not safe to cache)
        List<RecordedToolCall> complete = new ArrayList<>();
        for (RecordedToolCall call : calls) {
            if (call.output() != null) complete.add(call);
        }
        return complete;
    }

    private Map<String, Object> renderCall(RecordedToolCall call) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("toolName", call.toolName());
        rendered.put("input", call.input());
        rendered.put("output", truncateForPrompt(call.output()));
        AgentTool tool = toolRegistryService.getEnabledToolByName(call.toolName());
        if (tool != null && tool.getRequestSchema() != null && !tool.getRequestSchema().isBlank()) {
            try {
                rendered.put("requestSchema", objectMapper.readValue(tool.getRequestSchema(), Object.class));
            } catch (Exception ignored) {
                rendered.put("requestSchema", tool.getRequestSchema());
            }
        }
        return rendered;
    }

    private Object truncateForPrompt(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() <= MAX_TOOL_OUTPUT_CHARS) return value;
            return Map.of(
                    "_truncated", true,
                    "_originalLength", json.length(),
                    "preview", json.substring(0, MAX_TOOL_OUTPUT_CHARS)
            );
        } catch (Exception e) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readNodeMappings(Object raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> outer)) return out;
        for (Map.Entry<?, ?> nodeEntry : outer.entrySet()) {
            String nodeId = String.valueOf(nodeEntry.getKey());
            if (!(nodeEntry.getValue() instanceof Map<?, ?> args)) continue;
            Map<String, Object> argMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> argEntry : args.entrySet()) {
                String argName = String.valueOf(argEntry.getKey());
                if (argName == null || argName.isBlank()) continue;
                argMap.put(argName, argEntry.getValue());
            }
            if (!argMap.isEmpty()) out.put(nodeId, argMap);
        }
        return out;
    }

    private List<String> readStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                String s = String.valueOf(item).trim();
                if (!s.isBlank()) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        Matcher fenced = FENCED_JSON.matcher(text);
        if (fenced.find()) {
            text = fenced.group(1).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private Object parseFlexible(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) return raw;
        if (s.isBlank()) return s;
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception ignored) {
            return s;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static final String SYSTEM_PROMPT = """
            You design a reusable workflow ("toolchain") from one recorded execution.
            You will receive: the user's original prompt and an ordered list of tool calls
            (each with input, output, and the tool's request JSON Schema).

            Return ONLY valid JSON, no prose, no code fences:
            {
              "name": "<2-8 word business-purpose name, no implementation jargon>",
              "description": "<one or two sentences describing what the chain does>",
              "intents": ["<paraphrase of likely user prompts that should match this chain>"],
              "nodeMappings": {
                "tool_<N>": {
                  "<argName>": { "expr": "<JSONata>", "policy": "strict" | "llm_assisted", "fallback": <optional> }
                }
              }
            }

            Rules for argMappings:
            - The runtime context exposes:
                $.chainInput.<key>            — the original user-level input passed to the chain
                $.tool_<N>.input.<key>        — the resolved arguments sent to step N
                $.tool_<N>.output.<key>       — the response from step N
            - Use JSONata expressions starting with "$". For static literals, use a literal expression
              like "'WEB'" (quoted string) or "1" (number) or "false".
            - When a value can be derived deterministically from prior outputs/inputs, set
              "policy": "strict".
            - When a value requires multi-rule reasoning, lookup tables, or judgement that cannot be
              cleanly expressed in JSONata, set "policy": "llm_assisted" and still provide your best
              JSONata as a hint (it will be tried first, the LLM is only invoked as fallback).
            - Cover EVERY argument the tool actually received in the recorded run; do not omit args.
            - Do not hallucinate fields that are not present in the recorded outputs.

            Naming rules:
            - Name: 2-8 words, business outcome (not "Step 1 then Step 2"). Title Case. No quotes.
            - Description: factual, present tense, 1-2 sentences. No marketing language.
            - Intents: 2-5 entries. Examples: "validate order {orderId}", "check order availability".

            If the recorded run has fewer than 2 tool calls, errored, or is too partial to generalize,
            return an empty JSON object {} and nothing else.
            """;

    public record AuthoringResult(String name,
                                  String description,
                                  List<String> intents,
                                  Map<String, Map<String, Object>> nodeMappings) {
        public boolean hasName() { return name != null && !name.isBlank(); }
        public boolean hasDescription() { return description != null && !description.isBlank(); }
    }

    public record RecordedToolCall(String toolName, Object input, Object output) {}
}
