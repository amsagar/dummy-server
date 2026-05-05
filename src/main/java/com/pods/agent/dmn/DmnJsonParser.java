package com.pods.agent.dmn;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DmnJsonParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DmnJsonParser() {
    }

    public static DmnDecisionTable parse(String name, String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            HitPolicy hitPolicy = HitPolicy.from(root.path("hitPolicy").asText("FIRST"));
            List<DmnDecisionTable.InputColumn> inputs = parseInputColumns(root.path("inputs"));
            List<DmnDecisionTable.OutputColumn> outputs = parseOutputColumns(root.path("outputs"));
            List<DmnDecisionTable.Rule> rules = parseRules(root.path("rules"));
            return new DmnDecisionTable(name, hitPolicy, inputs, outputs, rules);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid decision table JSON: " + e.getMessage(), e);
        }
    }

    private static List<DmnDecisionTable.InputColumn> parseInputColumns(JsonNode node) {
        List<DmnDecisionTable.InputColumn> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode row : node) {
            out.add(new DmnDecisionTable.InputColumn(
                    row.path("name").asText(),
                    row.path("type").asText("string"),
                    row.path("label").asText(row.path("name").asText())
            ));
        }
        return out;
    }

    private static List<DmnDecisionTable.OutputColumn> parseOutputColumns(JsonNode node) {
        List<DmnDecisionTable.OutputColumn> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode row : node) {
            out.add(new DmnDecisionTable.OutputColumn(
                    row.path("name").asText(),
                    row.path("type").asText("string"),
                    row.path("label").asText(row.path("name").asText())
            ));
        }
        return out;
    }

    private static List<DmnDecisionTable.Rule> parseRules(JsonNode node) {
        List<DmnDecisionTable.Rule> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (int i = 0; i < node.size(); i++) {
            JsonNode row = node.get(i);
            Map<String, String> inputEntries = toStringMap(firstObject(row, "inputs", "inputEntries"));
            Map<String, Object> outputEntries = toObjectMap(firstObject(row, "outputs", "outputEntries"));
            String ruleId = row.path("id").asText("rule-" + (i + 1));
            out.add(new DmnDecisionTable.Rule(ruleId, inputEntries, outputEntries));
        }
        return out;
    }

    private static JsonNode firstObject(JsonNode row, String primary, String secondary) {
        JsonNode first = row.path(primary);
        if (first.isObject()) return first;
        JsonNode second = row.path(secondary);
        if (second.isObject()) return second;
        return OBJECT_MAPPER.createObjectNode();
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        Map<String, Object> raw = OBJECT_MAPPER.convertValue(node, Map.class);
        if (raw == null) return out;
        for (Map.Entry<String, Object> field : raw.entrySet()) {
            out.put(field.getKey(), field.getValue() == null ? "" : String.valueOf(field.getValue()));
        }
        return out;
    }

    private static Map<String, Object> toObjectMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        Map<String, Object> raw = OBJECT_MAPPER.convertValue(node, Map.class);
        if (raw == null) return out;
        for (Map.Entry<String, Object> field : raw.entrySet()) {
            out.put(field.getKey(), field.getValue());
        }
        return out;
    }

    private static Object jsonNodeValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isFloat() || node.isDouble() || node.isBigDecimal()) return node.asDouble();
        if (node.isArray() || node.isObject()) {
            try {
                return OBJECT_MAPPER.convertValue(node, Object.class);
            } catch (Exception ignored) {
                return node.toString();
            }
        }
        return node.asText();
    }
}
