package com.pods.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replays the LLM-authored argMappings against the recorded turn that seeded the
 * suggestion, and reports any mapping whose resolved value disagrees with what the
 * recorded tool call actually received.
 *
 * Zero LLM calls. Pure JSONata + equality. Catches the common authoring failure modes:
 *   - wrong predicate (e.g. Lines[0] instead of Lines[ServiceCode='WRT'][0])
 *   - missing IsCustomerAddress filter
 *   - missing $-prefix on JSONata expressions
 *   - using ServiceCode where ItemCode is required
 *   - returning array where scalar expected (Phase 7.5 also handles this at runtime)
 */
@Service
@Slf4j
public class MappingValidator {

    private final ArgMappingResolver argMappingResolver;
    private final ObjectMapper objectMapper;

    public MappingValidator(ArgMappingResolver argMappingResolver, ObjectMapper objectMapper) {
        this.argMappingResolver = argMappingResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * @param graph         the LLM-authored chain graph (parsed map, will not be mutated)
     * @param recordedCalls ordered tool calls from the original turn ({toolName, input, output})
     * @param chainInput    the typed param map the chain would have received at runtime
     *                      (derived from the recorded user prompt; for the validation pass
     *                      we use the recorded first-tool input as the canonical chainInput)
     * @return ValidationReport with one Failure per arg whose resolved value disagrees with
     *         the recorded value. Empty list = mappings passed validation.
     */
    @SuppressWarnings("unchecked")
    public ValidationReport validate(Map<String, Object> graph,
                                     List<RecordedCall> recordedCalls,
                                     Map<String, Object> chainInput) {
        List<Failure> failures = new ArrayList<>();
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) return new ValidationReport(failures);

        // Build per-step context as the runtime would: chainInput at root, plus tool_N for
        // each completed step's {input, output}.
        Map<String, Object> context = new LinkedHashMap<>();
        if (chainInput != null) context.put("chainInput", chainInput);

        int callIndex = 0;
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) continue;
            String type = String.valueOf(nodeMap.get("type"));
            if ("start".equalsIgnoreCase(type) || "end".equalsIgnoreCase(type)) continue;
            String nodeId = String.valueOf(nodeMap.get("id"));
            Object configObj = nodeMap.get("config");
            if (!(configObj instanceof Map<?, ?> cfg)) continue;
            Map<String, Object> config = (Map<String, Object>) cfg;
            Object mappingsObj = config.get("argMappings");
            Map<String, Object> argMappings = mappingsObj instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();

            if ("iterator".equalsIgnoreCase(type)) {
                int sampleCount = config.get("recordedSampleCount") instanceof Number n ? n.intValue() : 0;
                List<Map<String, Object>> samples = config.get("recordedInputSamples") instanceof List<?> l
                        ? (List<Map<String, Object>>) l : List.of();
                validateIterator(nodeId, argMappings, samples, sampleCount, context, failures);
                // Advance callIndex past all the iterator's recorded calls; populate the
                // iterator's own {input, output} envelope so downstream JSONata works.
                Map<String, Object> iteratorEnvelope = buildIteratorEnvelope(samples, recordedCalls, callIndex);
                context.put(nodeId, iteratorEnvelope);
                callIndex += sampleCount;
            } else {
                if (callIndex >= recordedCalls.size()) {
                    failures.add(new Failure(nodeId, null, null, null,
                            "No recorded call for node — chain has more steps than the recorded turn"));
                    continue;
                }
                RecordedCall recorded = recordedCalls.get(callIndex);
                validateSingleNode(nodeId, argMappings, recorded, context, failures);
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("input", recorded.input());
                envelope.put("output", recorded.output());
                context.put(nodeId, envelope);
                callIndex++;
            }
        }
        return new ValidationReport(failures);
    }

    private void validateSingleNode(String nodeId,
                                    Map<String, Object> argMappings,
                                    RecordedCall recorded,
                                    Map<String, Object> context,
                                    List<Failure> failures) {
        if (argMappings.isEmpty()) return;
        Object recordedInput = recorded.input();
        Map<String, Object> expected = recordedInput instanceof Map<?, ?> m
                ? coerceKeys((Map<?, ?>) m) : Map.of();
        for (Map.Entry<String, Object> entry : argMappings.entrySet()) {
            String argName = entry.getKey();
            Object source = entry.getValue();
            // Skip llm_assisted args — the LLM is expected to fill them at runtime, so a
            // null at validation time is acceptable.
            if (source instanceof Map<?, ?> mappingMap
                    && "llm_assisted".equalsIgnoreCase(String.valueOf(mappingMap.get("policy")))) {
                continue;
            }
            Object resolved = argMappingResolver.resolveOne(source, context, key -> dotPath(context, key));
            Object expectedValue = expected.get(argName);
            if (!valuesEqual(resolved, expectedValue)) {
                failures.add(new Failure(nodeId, argName, expectedValue, resolved,
                        describeMappingSource(source)));
            }
        }
    }

    private void validateIterator(String nodeId,
                                  Map<String, Object> argMappings,
                                  List<Map<String, Object>> samples,
                                  int sampleCount,
                                  Map<String, Object> context,
                                  List<Failure> failures) {
        if (argMappings.isEmpty()) return;
        Object itemsMapping = argMappings.get("items");
        if (itemsMapping == null) {
            failures.add(new Failure(nodeId, "items", "<list of " + sampleCount + " items>", null,
                    "iterator missing 'items' mapping"));
            return;
        }
        Object itemsResolved = argMappingResolver.resolveOne(itemsMapping, context, key -> dotPath(context, key));
        if (!(itemsResolved instanceof List<?> resolvedItems)) {
            failures.add(new Failure(nodeId, "items", "<list>", itemsResolved,
                    "items expression did not resolve to a list"));
            return;
        }
        if (resolvedItems.size() != sampleCount) {
            failures.add(new Failure(nodeId, "items", sampleCount, resolvedItems.size(),
                    "items expression yielded " + resolvedItems.size() + " items but recorded turn had "
                            + sampleCount + " — predicate likely too narrow or too broad"));
            // Don't try to validate per-item mappings against mismatched lists.
            return;
        }
        // Validate per-item args by pairing resolved items with recorded samples in order.
        for (int i = 0; i < resolvedItems.size(); i++) {
            Object item = resolvedItems.get(i);
            Map<String, Object> sample = samples.size() > i ? samples.get(i) : Map.of();
            Map<String, Object> itemContext = new LinkedHashMap<>(context);
            itemContext.put("item", item);
            itemContext.put("$item", item);
            for (Map.Entry<String, Object> entry : argMappings.entrySet()) {
                String argName = entry.getKey();
                if ("items".equals(argName)) continue;
                Object source = entry.getValue();
                if (source instanceof Map<?, ?> mappingMap
                        && "llm_assisted".equalsIgnoreCase(String.valueOf(mappingMap.get("policy")))) {
                    continue;
                }
                Object resolved = argMappingResolver.resolveOne(source, itemContext, key -> dotPath(itemContext, key));
                Object expected = sample.get(argName);
                if (!valuesEqual(resolved, expected)) {
                    failures.add(new Failure(nodeId, "items[" + i + "]." + argName, expected, resolved,
                            describeMappingSource(source)));
                }
            }
        }
    }

    private Map<String, Object> buildIteratorEnvelope(List<Map<String, Object>> samples,
                                                      List<RecordedCall> recordedCalls,
                                                      int startIndex) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (int i = 0; i < samples.size() && (startIndex + i) < recordedCalls.size(); i++) {
            RecordedCall c = recordedCalls.get(startIndex + i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("input", c.input());
            entry.put("output", c.output());
            outputs.add(entry);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("input", Map.of("items", List.copyOf(samples)));
        envelope.put("output", outputs);
        return envelope;
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == b) return true;
        // Single-element list vs its sole element — equal for validation purposes.
        // (Phase 7.5 unwraps these at runtime; we accept either shape here.)
        if (a instanceof List<?> al && al.size() == 1) a = al.get(0);
        if (b instanceof List<?> bl && bl.size() == 1) b = bl.get(0);
        if (a == null || b == null) return a == b;
        try {
            return objectMapper.writeValueAsString(a).equals(objectMapper.writeValueAsString(b));
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    private String describeMappingSource(Object source) {
        if (source instanceof Map<?, ?> m) {
            Object expr = m.get("expr");
            if (expr != null) return String.valueOf(expr);
        }
        return source == null ? "<null>" : String.valueOf(source);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceKeys(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private Object dotPath(Map<String, Object> context, String key) {
        if (context == null || key == null) return null;
        String[] parts = key.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) return null;
            Object next = null;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && String.valueOf(e.getKey()).equalsIgnoreCase(part)) {
                    next = e.getValue();
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    public record RecordedCall(String toolName, Object input, Object output) {}

    public record Failure(String nodeId,
                          String argName,
                          Object expected,
                          Object actual,
                          String mappingExprOrReason) {
        public String summary() {
            return nodeId + (argName == null ? "" : "." + argName)
                    + ": expected " + truncate(expected) + ", got " + truncate(actual)
                    + " [" + truncate(mappingExprOrReason) + "]";
        }
        private static String truncate(Object value) {
            if (value == null) return "null";
            String s = String.valueOf(value);
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        }
    }

    public record ValidationReport(List<Failure> failures) {
        public boolean isSuccess() { return failures.isEmpty(); }
        public boolean hasFailures() { return !failures.isEmpty(); }
        public int size() { return failures.size(); }
        /** Render a tight feedback block to send back to the authoring LLM for retry. */
        public String renderForLlmFeedback() {
            if (failures.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("Your previous mappings produced the following resolution failures when ");
            sb.append("evaluated against the recorded turn. Fix each one:\n\n");
            for (Failure f : failures) {
                sb.append("  - ").append(f.summary()).append("\n");
            }
            return sb.toString();
        }
    }
}
