package com.pods.agent.ruledomain.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flowable.engine.delegate.DelegateExecution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes process variables in a form Flowable can serialize natively, so that
 * features which inspect variable contents (notably {@code flowable:variableAggregation}
 * inside multi-instance subprocesses) can read them back.
 *
 * <p>The problem: when a delegate calls {@code execution.setVariable(name, map)}
 * with a {@link java.util.Map} or {@link java.util.Collection}, Flowable has
 * no built-in {@code Map}/{@code Collection} variable type and falls through
 * to Java serialization. The stored variable shows up as {@code type=serializable}
 * (an opaque byte array), which the aggregation engine refuses with
 * "Cannot aggregate variable: VariableInstanceEntity[...type=serializable...]".
 *
 * <p>The fix: convert structured values to a Jackson 2.x {@link JsonNode}
 * before storing. Flowable's {@code JsonType} / {@code LongJsonType} pick up
 * the {@code JsonNode}, store it as JSON, and the aggregator merges each
 * iteration's source value into the target array transparently.
 *
 * <p>This class uses a private Jackson 2.x {@code ObjectMapper} (Flowable's
 * runtime expects 2.x, while the rest of the app uses Jackson 3.x via
 * {@code tools.jackson.databind.ObjectMapper}). Keeping the conversion local
 * means we never mix the two object models in the same call site.
 */
public final class BpmnVariables {

    private static final ObjectMapper FLOWABLE_OM = new ObjectMapper();

    private BpmnVariables() {}

    /**
     * Set a process variable, converting structured values (Map, Collection,
     * POJO graphs) to a Jackson 2.x {@link JsonNode} so Flowable can store
     * them as JSON instead of a serialized blob. Scalars (String, Number,
     * Boolean) and {@code null} are passed through unchanged.
     */
    public static void set(DelegateExecution execution, String name, Object value) {
        execution.setVariable(name, normalize(value));
    }

    /**
     * Convert {@code value} to a Flowable-friendly form. Public so tests and
     * callers that need the converted value (rather than a side effect) can
     * use it directly.
     */
    public static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof JsonNode) {
            return value;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return FLOWABLE_OM.valueToTree(value);
        }
        // Arbitrary POJO graph — convert to JsonNode so Flowable doesn't
        // fall back to Java serialization for an opaque blob.
        try {
            return FLOWABLE_OM.valueToTree(value);
        } catch (IllegalArgumentException ex) {
            // Truly non-serializable object — leave it to Flowable's default
            // handling rather than guessing.
            return value;
        }
    }

    /**
     * Snapshot the current execution's variables into a FEEL-friendly map.
     * Any {@link JsonNode} (left over from a previous {@link #set} call or
     * produced by Flowable's variableAggregation) is recursively unwrapped
     * to plain {@link Map}/{@link List}/scalar values, because the Camunda
     * FEEL engine has no notion of Jackson types and can't traverse them
     * with dotted property access like {@code order.Lines[ItemCode = "IDEL"]}.
     */
    public static Map<String, Object> readContext(DelegateExecution execution) {
        Map<String, Object> raw = execution.getVariables();
        Map<String, Object> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), toJavaNative(e.getValue()));
        }
        return out;
    }

    /**
     * Recursively convert any {@link JsonNode} subtree to plain Java
     * {@code Map}/{@code List}/scalar values. Non-Jackson types pass
     * through with their nested structures also normalized so the result
     * is always a Java-native graph.
     */
    public static Object toJavaNative(Object value) {
        if (value == null) return null;
        if (value instanceof JsonNode node) return fromJsonNode(node);
        if (value instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey(), toJavaNative(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(toJavaNative(e));
            return out;
        }
        return value;
    }

    private static Object fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) {
            long v = node.asLong();
            // Preserve int-vs-long sizing for FEEL number coercion sanity.
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
            return v;
        }
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            List<Object> out = new ArrayList<>(arr.size());
            for (JsonNode child : arr) out.add(fromJsonNode(child));
            return out;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Map<String, Object> out = new LinkedHashMap<>(obj.size());
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), fromJsonNode(e.getValue()));
            }
            return out;
        }
        // POJONode, BinaryNode, etc. — fall back to its toString().
        return node.toString();
    }
}
