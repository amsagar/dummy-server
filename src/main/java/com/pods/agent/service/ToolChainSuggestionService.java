package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ToolChainSuggestionService {
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private final RuntimeEventRepository runtimeEventRepository;
    private final ToolChainService toolChainService;
    private final ToolChainAuthoringService authoringService;
    private final ToolRegistryService toolRegistryService;
    private final MappingValidator mappingValidator;
    private final ObjectMapper objectMapper;

    public ToolChainSuggestionService(RuntimeEventRepository runtimeEventRepository,
                                      ToolChainService toolChainService,
                                      ToolChainAuthoringService authoringService,
                                      ToolRegistryService toolRegistryService,
                                      MappingValidator mappingValidator,
                                      ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.toolChainService = toolChainService;
        this.authoringService = authoringService;
        this.toolRegistryService = toolRegistryService;
        this.mappingValidator = mappingValidator;
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
        MappingValidator.ValidationReport finalReport = new MappingValidator.ValidationReport(List.of());
        if (authored.isPresent()) {
            applyAuthoredMappings(graph, authored.get().nodeMappings());
            // One round of validate-and-retry. If the LLM-authored expressions resolve to
            // the wrong values when replayed against the recorded turn, ask the LLM to fix
            // the failures and re-author. Bounded to a single retry to cap cost — bigger
            // gains come from the upgraded prompt (skillContexts + JSONata gotchas) at
            // attempt #1.
            finalReport = runValidator(graph, calls, authored.get(), userPrompt);
            if (finalReport.hasFailures() && modelRef != null) {
                String feedback = finalReport.renderForLlmFeedback();
                Optional<ToolChainAuthoringService.AuthoringResult> retried =
                        authoringService.author(userPrompt, events, modelRef, feedback);
                if (retried.isPresent()) {
                    Map<String, Object> retriedGraph = buildGraph(userPrompt, calls);
                    applyAuthoredMappings(retriedGraph, retried.get().nodeMappings());
                    MappingValidator.ValidationReport retryReport =
                            runValidator(retriedGraph, calls, retried.get(), userPrompt);
                    if (retryReport.size() < finalReport.size()) {
                        graph = retriedGraph;
                        authored = retried;
                        finalReport = retryReport;
                    }
                }
            }
            if (finalReport.hasFailures()) {
                log.info("[ToolChainSuggestionService] Authored chain has {} unresolved validation "
                        + "failures after retry; persisting with mappingConfidence=ai_authored_with_failures",
                        finalReport.size());
            }
        }
        // Final safety net: any required arg on a tool node that still has no mapping (neither
        // inferred from key matches nor authored by the LLM) gets policy=llm_assisted. The runtime
        // will resolve it via LlmArgResolver on first execution and self-heal a deterministic
        // JSONata afterward.
        ensureRequiredArgsHaveFallback(graph);
        // Iterator nodes need a deterministic items expression. If the LLM produced an
        // llm_assisted items mapping, downgrade it to strict — the iterator can't produce
        // a different number of iterations on each run without becoming non-cacheable.
        promoteIteratorItemsToStrict(graph);
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
        boolean validationFailed = finalReport.hasFailures();
        String confidence = !authoredByLlm ? "inferred"
                : (validationFailed ? "ai_authored_with_failures" : "ai_authored");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "runtime_suggestion");
        metadata.put("mappingConfidence", confidence);
        metadata.put("requiresMappingReview", true);
        metadata.put("authoredByLlm", authoredByLlm);
        metadata.put("sessionId", sessionId == null ? "" : sessionId);
        metadata.put("turnId", turnId);
        if (validationFailed) {
            List<String> summaries = new ArrayList<>();
            for (MappingValidator.Failure f : finalReport.failures()) {
                summaries.add(f.summary());
            }
            metadata.put("validationFailures", summaries);
        }
        // Persist the model used during the recorded turn so the runtime can call
        // LlmArgResolver for llm_assisted mappings even when the chain is invoked
        // without an explicit model in the run options (e.g. matched-chain dialog).
        if (modelRef != null) {
            metadata.put("defaultModelRef", Map.of(
                    "providerID", modelRef.providerID(),
                    "modelID", modelRef.modelID()));
        }
        // Stash extraction hints so the chat-layer parameter extractor has concrete examples
        // of how a user phrase maps to the chain's typed params.
        if (authored.isPresent() && authored.get().paramExtractionHints() != null
                && !authored.get().paramExtractionHints().isBlank()) {
            metadata.put("paramExtractionHints", authored.get().paramExtractionHints());
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

        // Resolve the chain's typed input schema. Prefer LLM-authored paramSchema; if absent,
        // fall back to the legacy free-form {message: string} so old behavior is preserved.
        String inputSchemaJson = authored
                .filter(ToolChainAuthoringService.AuthoringResult::hasParamSchema)
                .map(r -> toJson(r.paramSchema()))
                .orElse("{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}");

        ToolChainDtos.ToolChainVersionRequest req = new ToolChainDtos.ToolChainVersionRequest();
        req.setGraphJson(graphJson);
        req.setInputSchema(inputSchemaJson);
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

    /**
     * Run the validator against the freshly authored graph. Synthesizes a chainInput by
     * walking tool_1's argMappings: any expr of the form "$.chainInput.<paramName>" implies
     * chainInput.<paramName> should equal the recorded tool_1 input field with the same
     * argName. Returns an empty report if no recorded calls or no usable tool_1 mappings.
     */
    @SuppressWarnings("unchecked")
    private MappingValidator.ValidationReport runValidator(Map<String, Object> graph,
                                                           List<ToolCallRow> calls,
                                                           ToolChainAuthoringService.AuthoringResult authored,
                                                           String userPrompt) {
        if (calls == null || calls.isEmpty()) return new MappingValidator.ValidationReport(List.of());
        List<MappingValidator.RecordedCall> recorded = new ArrayList<>();
        for (ToolCallRow row : calls) {
            // We don't carry the recorded output at this layer (the suggestion service only
            // captures inputs). The validator can still check argMapping resolution against
            // chainInput + previously-recorded tool inputs — which is enough for the common
            // "ORD_ID = $.chainInput.orderId" style failures we want to catch.
            recorded.add(new MappingValidator.RecordedCall(row.toolName(), row.input(), Map.of()));
        }
        Map<String, Object> chainInput = synthesizeChainInputForValidation(graph, calls.get(0));
        if (chainInput.isEmpty()) {
            log.debug("[ToolChainSuggestionService] Skipping validation — no $.chainInput.* refs in tool_1 mappings");
            return new MappingValidator.ValidationReport(List.of());
        }
        try {
            return mappingValidator.validate(graph, recorded, chainInput);
        } catch (Exception e) {
            log.warn("[ToolChainSuggestionService] Mapping validation crashed: {}", e.getMessage());
            return new MappingValidator.ValidationReport(List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> synthesizeChainInputForValidation(Map<String, Object> graph,
                                                                  ToolCallRow firstCall) {
        Map<String, Object> chainInput = new LinkedHashMap<>();
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) return chainInput;
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) continue;
            if (!"tool_1".equals(String.valueOf(nodeMap.get("id")))) continue;
            Object configObj = nodeMap.get("config");
            if (!(configObj instanceof Map<?, ?> cfg)) break;
            Object mappingsObj = ((Map<String, Object>) cfg).get("argMappings");
            if (!(mappingsObj instanceof Map<?, ?> mappings)) break;
            for (Map.Entry<?, ?> e : mappings.entrySet()) {
                String argName = String.valueOf(e.getKey());
                String expr = extractExpr(e.getValue());
                if (expr == null) continue;
                String paramName = paramNameFromChainInputExpr(expr);
                if (paramName == null) continue;
                Object recordedValue = firstCall.input().get(argName);
                if (recordedValue != null) chainInput.put(paramName, recordedValue);
            }
            break;
        }
        return chainInput;
    }

    private String extractExpr(Object mapping) {
        if (mapping instanceof String s) return s;
        if (mapping instanceof Map<?, ?> m && m.get("expr") != null) return String.valueOf(m.get("expr"));
        return null;
    }

    /** Returns "orderId" for "$.chainInput.orderId", null otherwise. */
    private String paramNameFromChainInputExpr(String expr) {
        if (expr == null) return null;
        String trimmed = expr.trim();
        String prefix = "$.chainInput.";
        if (!trimmed.startsWith(prefix)) return null;
        String tail = trimmed.substring(prefix.length());
        // Strip anything past the first dot/bracket — we only want the top-level param name.
        int cut = tail.length();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '.' || c == '[' || c == '(' || Character.isWhitespace(c)) { cut = i; break; }
        }
        String name = tail.substring(0, cut);
        return name.isEmpty() ? null : name;
    }

    /**
     * Iterator nodes must produce a deterministic, cacheable iteration list. If the LLM
     * authored `items.policy: "llm_assisted"`, force it back to strict — the runtime LLM
     * fallback path is per-arg and isn't safe for "what items to iterate over". A bad
     * items expression should fail loud at first execution, not silently call the LLM
     * to produce a different list per run.
     */
    @SuppressWarnings("unchecked")
    private void promoteIteratorItemsToStrict(Map<String, Object> graph) {
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) return;
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) continue;
            String type = String.valueOf(nodeMap.get("type"));
            if (!"iterator".equalsIgnoreCase(type)) continue;
            Object configObj = nodeMap.get("config");
            if (!(configObj instanceof Map<?, ?> cfg)) continue;
            Object mappingsObj = ((Map<String, Object>) cfg).get("argMappings");
            if (!(mappingsObj instanceof Map<?, ?> mappings)) continue;
            Object itemsMapping = ((Map<String, Object>) mappings).get("items");
            if (itemsMapping instanceof Map<?, ?> itemsMap) {
                Map<String, Object> mutable = (Map<String, Object>) itemsMap;
                Object policy = mutable.get("policy");
                if (policy != null && "llm_assisted".equalsIgnoreCase(String.valueOf(policy))) {
                    mutable.put("policy", "strict");
                }
            }
        }
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
