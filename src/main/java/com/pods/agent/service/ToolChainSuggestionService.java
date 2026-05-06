package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.ModelRef;
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
    private final ToolChainAuthoringService authoringService;
    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public ToolChainSuggestionService(RuntimeEventRepository runtimeEventRepository,
                                      ToolChainService toolChainService,
                                      ToolChainAuthoringService authoringService,
                                      ToolRegistryService toolRegistryService,
                                      ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.toolChainService = toolChainService;
        this.authoringService = authoringService;
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    public Optional<ToolChain> createSuggestionFromTurn(String sessionId,
                                                        String turnId,
                                                        String userPrompt,
                                                        String createdBy) {
        return createSuggestionFromTurn(sessionId, turnId, userPrompt, createdBy, null);
    }

    public Optional<ToolChain> createSuggestionFromTurn(String sessionId,
                                                        String turnId,
                                                        String userPrompt,
                                                        String createdBy,
                                                        ModelRef modelRef) {
        if (turnId == null || turnId.isBlank()) return Optional.empty();
        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        if (events == null || events.isEmpty()) return Optional.empty();

        List<ToolCallRow> calls = extractToolCalls(events);
        if (calls.size() < 2 || hasTerminalToolFailure(events)) {
            return Optional.empty();
        }

        // LLM authoring is best-effort. On any failure, fall back to deterministic name/description
        // and the literal-key argMappings produced by inferMappings(). This keeps suggestions
        // working even when the model is unreachable.
        Optional<ToolChainAuthoringService.AuthoringResult> authored =
                authoringService.author(userPrompt, events, modelRef);

        Map<String, Object> graph = buildGraph(userPrompt, calls);
        if (authored.isPresent()) {
            applyAuthoredMappings(graph, authored.get().nodeMappings());
        }
        // Final safety net: any required arg on a tool node that still has no mapping (neither
        // inferred from key matches nor authored by the LLM) gets policy=llm_assisted. The runtime
        // will resolve it via LlmArgResolver on first execution and self-heal a deterministic
        // JSONata afterward. Without this, a chain like Get_OrderID would receive the entire
        // context as its payload and the tool would reject it for missing required fields.
        ensureRequiredArgsHaveFallback(graph);
        String graphJson = toJson(graph);

        String intentSignature = hashHex(buildIntentSignatureSeed(userPrompt, calls));
        String structureSignature = hashHex(graphJson);
        Optional<ToolChain> existing = toolChainService.findBySignatures(intentSignature, structureSignature);
        if (existing.isPresent()) return existing;

        String signatureSuffix = structureSignature.substring(0, Math.min(6, structureSignature.length()));
        String chainName = authored.filter(ToolChainAuthoringService.AuthoringResult::hasName)
                .map(ToolChainAuthoringService.AuthoringResult::name)
                .orElseGet(() -> buildName(calls, signatureSuffix));
        String description = authored.filter(ToolChainAuthoringService.AuthoringResult::hasDescription)
                .map(ToolChainAuthoringService.AuthoringResult::description)
                .orElse("System suggested from repeated tool-call pattern");
        boolean authoredByLlm = authored.isPresent();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "runtime_suggestion");
        metadata.put("mappingConfidence", authoredByLlm ? "ai_authored" : "inferred");
        metadata.put("requiresMappingReview", true);
        metadata.put("authoredByLlm", authoredByLlm);
        metadata.put("sessionId", sessionId == null ? "" : sessionId);
        metadata.put("turnId", turnId);
        // Persist the model used during the recorded turn so the runtime can call
        // LlmArgResolver for llm_assisted mappings even when the chain is invoked
        // without an explicit model in the run options (e.g. matched-chain dialog).
        if (modelRef != null) {
            metadata.put("defaultModelRef", Map.of(
                    "providerID", modelRef.providerID(),
                    "modelID", modelRef.modelID()));
        }

        ToolChain chain = toolChainService.createSystemSuggested(
                chainName,
                description,
                createdBy,
                intentSignature,
                structureSignature,
                metadata
        );

        List<String> intents = authored.map(ToolChainAuthoringService.AuthoringResult::intents)
                .filter(list -> list != null && !list.isEmpty())
                .orElseGet(() -> List.of(normalizeIntentText(userPrompt, calls)));

        ToolChainDtos.ToolChainVersionRequest req = new ToolChainDtos.ToolChainVersionRequest();
        req.setGraphJson(graphJson);
        req.setInputSchema("{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}");
        req.setOutputSchema("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},\"data\":{\"type\":\"object\"}}}");
        req.setResponseMode("hybrid");
        req.setSynthesisPrompt("Summarize the outputs from each tool step and produce an actionable final response.");
        req.setIntents(intents);
        req.setIntentSignature(intentSignature);
        req.setStructureSignature(structureSignature);
        req.setRagConfig(Map.of());
        toolChainService.createVersion(chain.getId(), req, createdBy);
        return Optional.of(chain);
    }

    @SuppressWarnings("unchecked")
    private void ensureRequiredArgsHaveFallback(Map<String, Object> graph) {
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) return;
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) continue;
            String type = String.valueOf(nodeMap.get("type"));
            if (!"tool".equalsIgnoreCase(type) && !"mcp_tool".equalsIgnoreCase(type)) continue;
            Object configObj = nodeMap.get("config");
            if (!(configObj instanceof Map<?, ?> cfg)) continue;
            String toolName = String.valueOf(((Map<String, Object>) cfg).get("toolName"));
            if (toolName == null || toolName.isBlank()) continue;
            List<String> requiredFields = lookupRequiredFields(toolName);
            if (requiredFields.isEmpty()) continue;
            Map<String, Object> argMappings;
            Object existing = ((Map<String, Object>) cfg).get("argMappings");
            if (existing instanceof Map<?, ?> em) {
                argMappings = (Map<String, Object>) em;
            } else {
                argMappings = new LinkedHashMap<>();
                ((Map<String, Object>) cfg).put("argMappings", argMappings);
            }
            for (String field : requiredFields) {
                if (argMappings.containsKey(field)) continue;
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("policy", "llm_assisted");
                argMappings.put(field, fallback);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> lookupRequiredFields(String toolName) {
        try {
            var tool = toolRegistryService.getEnabledToolByName(toolName);
            if (tool == null) return List.of();
            String schemaJson = tool.getRequestSchema();
            if (schemaJson == null || schemaJson.isBlank()) return List.of();
            Map<String, Object> schema = objectMapper.readValue(schemaJson, Map.class);
            Object req = schema.get("required");
            if (!(req instanceof List<?> list)) return List.of();
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                String s = String.valueOf(item);
                if (!s.isBlank()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void applyAuthoredMappings(Map<String, Object> graph,
                                       Map<String, Map<String, Object>> nodeMappings) {
        if (nodeMappings == null || nodeMappings.isEmpty()) return;
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> list)) return;
        for (Object nodeObj : list) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) continue;
            String nodeId = String.valueOf(nodeMap.get("id"));
            Object configObj = nodeMap.get("config");
            if (!(configObj instanceof Map<?, ?> cfg)) continue;
            String toolName = String.valueOf(((Map<String, Object>) cfg).get("toolName"));
            // The LLM is told to key by node id (tool_1, tool_2…) but occasionally it keys
            // by tool name instead. Accept either to avoid silently dropping the entire
            // mapping block.
            Map<String, Object> overrides = nodeMappings.get(nodeId);
            if ((overrides == null || overrides.isEmpty()) && toolName != null && !toolName.isBlank()) {
                overrides = nodeMappings.get(toolName);
            }
            if (overrides == null || overrides.isEmpty()) continue;
            ((Map<String, Object>) cfg).put("argMappings", overrides);
            ((Map<String, Object>) cfg).put("mappingStatus", "ai_authored");
        }
    }

    /**
     * Tool calls that exist purely as agent-loop infrastructure and have no place in a
     * cached deterministic chain. The {@code skill} tool only loads instructional markdown
     * for the LLM agent — once we're caching the flow, those instructions are no longer
     * consumed, and including the call as a chain step makes it run against a generic
     * stub at runtime which corrupts the downstream context.
     */
    private static final Set<String> AGENT_LOOP_INFRASTRUCTURE_TOOLS = Set.of("skill");

    private List<ToolCallRow> extractToolCalls(List<RuntimeEvent> events) {
        List<ToolCallRow> calls = new ArrayList<>();
        for (RuntimeEvent event : events) {
            if (event == null || !"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String toolName = string(payload.get("toolName"));
            if (toolName.isBlank()) continue;
            if (AGENT_LOOP_INFRASTRUCTURE_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) continue;
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
        // Group consecutive calls to the same tool into a single iterator node — e.g. four
        // ContainerAvailability calls (one per leg) become one node with type=iterator.
        // The runtime expands the iterator at execution time, calling the tool once per item.
        List<List<ToolCallRow>> groups = groupConsecutiveSameTool(calls);

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(Map.of("id", "start", "type", "start", "label", "Start"));
        String previousId = "start";
        int nodeIndex = 0;
        int globalCallIndex = 0;
        for (List<ToolCallRow> group : groups) {
            nodeIndex++;
            String nodeId = "tool_" + nodeIndex;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", nodeId);
            Map<String, Object> config = new LinkedHashMap<>();
            String toolName = group.get(0).toolName();
            config.put("toolName", toolName);

            if (group.size() == 1) {
                ToolCallRow call = group.get(0);
                node.put("type", "tool");
                node.put("label", toolName);
                Map<String, Object> mappings = inferMappings(globalCallIndex, calls);
                if (!mappings.isEmpty()) {
                    config.put("argMappings", mappings);
                    config.put("mappingStatus", "inferred");
                }
                // Capture the recorded input as a hint for downstream LLM authoring.
                if (!call.input().isEmpty()) {
                    config.put("recordedInputSample", call.input());
                }
                globalCallIndex++;
            } else {
                // Iterator node — same tool, called once per item in a list. The list is
                // resolved at runtime from a JSONata expression, so the iteration count is
                // data-driven (a different order may produce 2 legs or 7 legs, not exactly
                // the count we recorded). The recorded inputs are kept only as samples for
                // the LLM authoring step to learn the per-item shape.
                node.put("type", "iterator");
                node.put("label", toolName);
                config.put("recordedSampleCount", group.size());
                List<Map<String, Object>> samples = new ArrayList<>();
                for (ToolCallRow row : group) {
                    if (!row.input().isEmpty()) samples.add(row.input());
                }
                if (!samples.isEmpty()) config.put("recordedInputSamples", samples);
                // The LLM authoring step is responsible for filling in `items` (a JSONata
                // expression yielding the per-iteration list, evaluated at runtime) and
                // `argMappings` that reference `$item.X` for each iteration's data.
                config.put("mappingStatus", "needs_iterator_authoring");
                globalCallIndex += group.size();
            }
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

    private List<List<ToolCallRow>> groupConsecutiveSameTool(List<ToolCallRow> calls) {
        List<List<ToolCallRow>> groups = new ArrayList<>();
        List<ToolCallRow> current = new ArrayList<>();
        String currentTool = null;
        for (ToolCallRow call : calls) {
            if (currentTool != null && currentTool.equals(call.toolName())) {
                current.add(call);
            } else {
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<>();
                current.add(call);
                currentTool = call.toolName();
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
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
