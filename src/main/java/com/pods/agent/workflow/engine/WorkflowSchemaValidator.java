package com.pods.agent.workflow.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Minimal JSON-schema-like validator for activity input/output contracts.
 */
@Component
public class WorkflowSchemaValidator {

    public List<String> validate(Map<String, Object> schema, Object value) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) return errors;
        validateNode(schema, value, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void validateNode(Map<String, Object> schema, Object value, String path, List<String> errors) {
        String type = schema.get("type") == null ? null : String.valueOf(schema.get("type"));
        if (type != null && !type.isBlank() && !matchesType(type, value)) {
            errors.add(path + " expected type " + type + " but got " + typeName(value));
            return;
        }
        if ("object".equalsIgnoreCase(type) && value instanceof Map<?, ?> map) {
            Object requiredObj = schema.get("required");
            if (requiredObj instanceof List<?> requiredList) {
                for (Object item : requiredList) {
                    String key = String.valueOf(item);
                    if (!map.containsKey(key) || map.get(key) == null) {
                        errors.add(path + "." + key + " is required");
                    }
                }
            }
            Object propsObj = schema.get("properties");
            if (propsObj instanceof Map<?, ?> props) {
                for (Map.Entry<?, ?> entry : props.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    Object childSchema = entry.getValue();
                    if (!(childSchema instanceof Map<?, ?> childSchemaMap)) continue;
                    if (!map.containsKey(key) || map.get(key) == null) continue;
                    validateNode((Map<String, Object>) childSchemaMap, map.get(key), path + "." + key, errors);
                }
            }
            return;
        }
        if ("array".equalsIgnoreCase(type) && value instanceof List<?> list) {
            Object items = schema.get("items");
            if (items instanceof Map<?, ?> itemSchema) {
                for (int i = 0; i < list.size(); i++) {
                    validateNode((Map<String, Object>) itemSchema, list.get(i), path + "[" + i + "]", errors);
                }
            }
        }
    }

    private boolean matchesType(String type, Object value) {
        if (value == null) return true;
        return switch (type.toLowerCase()) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }

    private String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
