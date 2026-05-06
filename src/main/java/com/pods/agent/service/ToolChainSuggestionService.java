package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ToolChainSuggestionService {
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private final RuntimeEventRepository runtimeEventRepository;
    private final ToolChainService toolChainService;
    private final ObjectMapper objectMapper;

    public ToolChainSuggestionService(RuntimeEventRepository runtimeEventRepository,
                                      ToolChainService toolChainService,
                                      ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.toolChainService = toolChainService;
        this.objectMapper = objectMapper;
    }

    public Optional<ToolChain> createSuggestionFromTurn(String sessionId,
                                                        String turnId,
                                                        String userPrompt,
                                                        String createdBy) {
        if (turnId == null || turnId.isBlank()) return Optional.empty();
        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        if (events == null || events.isEmpty()) return Optional.empty();

        List<ToolCallRow> calls = extractToolCalls(events);
        if (calls.size() < 2 || hasTerminalToolFailure(events)) {
            return Optional.empty();
        }

        String intentSignature = hashHex(buildIntentSignatureSeed(userPrompt, calls));
        String graphJson = toJson(buildGraph(userPrompt, calls));
        String structureSignature = hashHex(graphJson);
        Optional<ToolChain> existing = toolChainService.findBySignatures(intentSignature, structureSignature);
        if (existing.isPresent()) return existing;

        String signatureSuffix = structureSignature.substring(0, Math.min(6, structureSignature.length()));
        String chainName = buildName(calls, signatureSuffix);
        ToolChain chain = toolChainService.createSystemSuggested(
                chainName,
                "System suggested from repeated tool-call pattern",
                createdBy,
                intentSignature,
                structureSignature,
                Map.of(
                        "source", "runtime_suggestion",
                        "mappingConfidence", "inferred",
                        "requiresMappingReview", true,
                        "sessionId", sessionId == null ? "" : sessionId,
                        "turnId", turnId
                )
        );

        ToolChainDtos.ToolChainVersionRequest req = new ToolChainDtos.ToolChainVersionRequest();
        req.setGraphJson(graphJson);
        req.setInputSchema("{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}");
        req.setOutputSchema("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},\"data\":{\"type\":\"object\"}}}");
        req.setResponseMode("hybrid");
        req.setSynthesisPrompt("Summarize the outputs from each tool step and produce an actionable final response.");
        req.setIntents(List.of(normalizeIntentText(userPrompt, calls)));
        req.setIntentSignature(intentSignature);
        req.setStructureSignature(structureSignature);
        req.setRagConfig(Map.of());
        toolChainService.createVersion(chain.getId(), req, createdBy);
        return Optional.of(chain);
    }

    private List<ToolCallRow> extractToolCalls(List<RuntimeEvent> events) {
        List<ToolCallRow> calls = new ArrayList<>();
        for (RuntimeEvent event : events) {
            if (event == null || !"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String toolName = string(payload.get("toolName"));
            if (toolName.isBlank()) continue;
            Map<String, Object> input = readMap(string(payload.get("input")));
            calls.add(new ToolCallRow(toolName, input));
        }
        return calls;
    }

    private boolean hasTerminalToolFailure(List<RuntimeEvent> events) {
        for (RuntimeEvent event : events) {
            if (event == null || !"tool.done".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String status = string(payload.get("status")).toLowerCase(Locale.ROOT);
            if ("error".equals(status) || "denied".equals(status)) return true;
        }
        return false;
    }

    private Map<String, Object> buildGraph(String userPrompt, List<ToolCallRow> calls) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(Map.of("id", "start", "type", "start", "label", "Start"));
        String previousId = "start";
        for (int i = 0; i < calls.size(); i++) {
            ToolCallRow call = calls.get(i);
            String nodeId = "tool_" + (i + 1);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("toolName", call.toolName());
            Map<String, Object> mappings = inferMappings(i, calls);
            if (!mappings.isEmpty()) {
                config.put("argMappings", mappings);
                config.put("mappingStatus", "inferred");
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", nodeId);
            node.put("type", "tool");
            node.put("label", call.toolName());
            node.put("config", config);
            nodes.add(node);
            edges.add(Map.of("from", previousId, "to", nodeId));
            previousId = nodeId;
        }
        nodes.add(Map.of("id", "end", "type", "end", "label", "End"));
        edges.add(Map.of("from", previousId, "to", "end"));

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("description", normalizeIntentText(userPrompt, calls));
        return graph;
    }

    private Map<String, Object> inferMappings(int index, List<ToolCallRow> calls) {
        if (index <= 0) return Map.of();
        ToolCallRow current = calls.get(index);
        ToolCallRow previous = calls.get(index - 1);
        if (current.input().isEmpty() || previous.input().isEmpty()) return Map.of();
        Map<String, Object> mappings = new LinkedHashMap<>();
        String sourcePrefix = "tool_" + index;
        for (String key : current.input().keySet()) {
            if (key == null || key.isBlank()) continue;
            if (previous.input().containsKey(key)) {
                mappings.put(key, sourcePrefix + "." + key);
            }
        }
        return mappings;
    }

    private String buildIntentSignatureSeed(String userPrompt, List<ToolCallRow> calls) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : tokenize(userPrompt)) tokens.add(token);
        for (ToolCallRow call : calls) {
            for (String token : tokenize(call.toolName())) tokens.add(token);
        }
        return String.join("|", tokens);
    }

    private String normalizeIntentText(String userPrompt, List<ToolCallRow> calls) {
        String prompt = userPrompt == null ? "" : userPrompt.trim();
        if (!prompt.isBlank()) return prompt;
        return "Flow using tools: " + calls.stream().map(ToolCallRow::toolName).distinct().reduce((a, b) -> a + " -> " + b).orElse("generated");
    }

    private String buildName(List<ToolCallRow> calls, String suffix) {
        String first = calls.get(0).toolName();
        String last = calls.get(calls.size() - 1).toolName();
        if (first.equalsIgnoreCase(last)) {
            return "System Suggested: " + first + " [" + suffix + "]";
        }
        return "System Suggested: " + first + " -> " + last + " [" + suffix + "]";
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        return TOKEN_SPLIT.splitAsStream(value.toLowerCase(Locale.ROOT))
                .filter(token -> token.length() > 1)
                .toList();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) return Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String hashHex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }

    private record ToolCallRow(String toolName, Map<String, Object> input) {}
}
