package com.pods.agent.service;

import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.domain.ToolChainVersion;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.ToolChainVersionRepository;
import com.pods.agent.service.expression.PathResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Powers the inline mapping editor in the System Toolchains UI.
 *
 * - {@link #testMapping}: evaluates an expression against the recorded turn that produced the
 *   chain (the runtime context the chain saw on its first run), so the user can iterate on a
 *   JSONata expression and see the resolved value before saving.
 * - {@link #updateMapping}: persists a full mapping (expr, fallback, policy) onto a single node
 *   argument inside the chain's latest version. Mirrors the self-heal write path but accepts a
 *   user-authored mapping rather than an LLM-derived one.
 */
@Service
@Slf4j
public class ToolChainMappingEditorService {

    private final ToolChainService toolChainService;
    private final ToolChainVersionRepository toolChainVersionRepository;
    private final RuntimeEventRepository runtimeEventRepository;
    private final ArgMappingResolver argMappingResolver;
    private final ObjectMapper objectMapper;

    public ToolChainMappingEditorService(ToolChainService toolChainService,
                                         ToolChainVersionRepository toolChainVersionRepository,
                                         RuntimeEventRepository runtimeEventRepository,
                                         ArgMappingResolver argMappingResolver,
                                         ObjectMapper objectMapper) {
        this.toolChainService = toolChainService;
        this.toolChainVersionRepository = toolChainVersionRepository;
        this.runtimeEventRepository = runtimeEventRepository;
        this.argMappingResolver = argMappingResolver;
        this.objectMapper = objectMapper;
    }

    public TestResult testMapping(String chainId, Object expression) {
        try {
            Map<String, Object> context = buildRecordedContext(chainId);
            if (context.isEmpty()) {
                return TestResult.error("No recorded turn available for this chain. Run it once first.");
            }
            Object value = argMappingResolver.resolveOne(expression, context, key -> resolvePath(context, key));
            return TestResult.success(value, context);
        } catch (Exception e) {
            return TestResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public boolean updateMapping(String chainId,
                                 String nodeId,
                                 String argName,
                                 Map<String, Object> mapping) {
        if (chainId == null || nodeId == null || argName == null) return false;
        ToolChain chain = toolChainService.getRequired(chainId);
        Optional<ToolChainVersion> versionOpt = toolChainService.resolveVersion(chainId, chain.getCurrentVersion());
        if (versionOpt.isEmpty()) return false;
        ToolChainVersion version = versionOpt.get();
        try {
            Map<String, Object> graph = objectMapper.readValue(version.getGraphJson(), Map.class);
            if (!applyMappingToGraph(graph, nodeId, argName, mapping)) return false;
            String updated = objectMapper.writeValueAsString(graph);
            toolChainVersionRepository.updateGraphJson(version.getId(), updated);
            return true;
        } catch (Exception e) {
            log.warn("[ToolChainMappingEditor] update failed for {}#{}/{}: {}", chainId, nodeId, argName, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean applyMappingToGraph(Map<String, Object> graph,
                                        String nodeId,
                                        String argName,
                                        Map<String, Object> mapping) {
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) return false;
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> node)) continue;
            if (!nodeId.equals(String.valueOf(node.get("id")))) continue;
            Object configObj = node.get("config");
            if (!(configObj instanceof Map<?, ?> config)) return false;
            Map<String, Object> argMappings;
            Object existing = config.get("argMappings");
            if (existing instanceof Map<?, ?> em) {
                argMappings = (Map<String, Object>) em;
            } else {
                argMappings = new LinkedHashMap<>();
                ((Map<String, Object>) config).put("argMappings", argMappings);
            }
            if (mapping == null || mapping.isEmpty()) {
                argMappings.remove(argName);
            } else {
                Map<String, Object> normalized = new LinkedHashMap<>();
                if (mapping.get("expr") != null) normalized.put("expr", mapping.get("expr"));
                if (mapping.containsKey("fallback")) normalized.put("fallback", mapping.get("fallback"));
                Object policy = mapping.get("policy");
                normalized.put("policy", policy == null ? "strict" : String.valueOf(policy));
                normalized.put("editedAt", System.currentTimeMillis());
                argMappings.put(argName, normalized);
            }
            return true;
        }
        return false;
    }

    /**
     * Reconstructs the runtime context the chain would have seen on the recorded turn that
     * spawned the suggestion. Used as the evaluation environment for the Test button.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> buildRecordedContext(String chainId) {
        ToolChain chain = toolChainService.getRequired(chainId);
        Map<String, Object> meta = readMap(chain.getMetadataJson());
        String turnId = String.valueOf(meta.getOrDefault("turnId", ""));
        if (turnId.isBlank()) return Map.of();

        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        if (events == null || events.isEmpty()) return Map.of();

        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> chainInput = new LinkedHashMap<>();
        // Best-effort original prompt — most chats stash the user prompt on the first user message
        // event under "content". Fall back to empty string.
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            String type = event.getEventType().toLowerCase(Locale.ROOT);
            if (!"user.message".equals(type) && !"chat.user".equals(type)) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            Object text = payload.get("content");
            if (text == null) text = payload.get("message");
            if (text != null) {
                chainInput.put("message", String.valueOf(text));
                break;
            }
        }
        context.put("chainInput", chainInput);

        Map<String, Map<String, Object>> stepsByCallId = new LinkedHashMap<>();
        List<String> callOrder = new java.util.ArrayList<>();
        for (RuntimeEvent event : events) {
            if (event == null || event.getEventType() == null) continue;
            Map<String, Object> payload = readMap(event.getPayload());
            String callId = String.valueOf(payload.getOrDefault("callId", ""));
            if (callId.isBlank()) continue;
            String type = event.getEventType().toLowerCase(Locale.ROOT);
            if ("tool.call".equals(type)) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("toolName", payload.get("toolName"));
                step.put("input", parseFlexible(payload.get("input")));
                stepsByCallId.put(callId, step);
                callOrder.add(callId);
            } else if ("tool.done".equals(type)) {
                Map<String, Object> step = stepsByCallId.get(callId);
                if (step != null) step.put("output", parseFlexible(payload.get("output")));
            }
        }
        int n = 1;
        for (String callId : callOrder) {
            Map<String, Object> step = stepsByCallId.get(callId);
            if (step == null) continue;
            Map<String, Object> nodeStep = new LinkedHashMap<>();
            nodeStep.put("input", step.getOrDefault("input", Map.of()));
            nodeStep.put("output", step.getOrDefault("output", Map.of()));
            context.put("tool_" + n, nodeStep);
            n++;
        }
        return context;
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
        } catch (Exception e) {
            return s;
        }
    }

    private Object resolvePath(Map<String, Object> context, String keyPath) {
        return PathResolver.resolvePath(context, keyPath, true);
    }

    public record TestResult(boolean ok, Object value, String error, Map<String, Object> contextPreview) {
        public static TestResult success(Object value, Map<String, Object> context) {
            return new TestResult(true, value, null, contextPreview(context));
        }
        public static TestResult error(String message) {
            return new TestResult(false, null, message, Map.of());
        }
        private static Map<String, Object> contextPreview(Map<String, Object> context) {
            // Surface just the top-level keys so the UI can hint at what's referenceable.
            Map<String, Object> preview = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> m) {
                    preview.put(entry.getKey(), m.keySet());
                } else {
                    preview.put(entry.getKey(), value);
                }
            }
            return preview;
        }
    }
}
