package com.pods.agent.service.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PathResolver {
    private PathResolver() {}

    public static Object resolvePath(Map<String, Object> context, String keyPath) {
        return resolvePath(context, keyPath, true);
    }

    public static Object resolvePath(Map<String, Object> context, String keyPath, boolean stepRecordFallback) {
        if (context == null || keyPath == null || keyPath.isBlank()) return null;
        String normalized = normalizeRootPath(keyPath);
        if (context.containsKey(normalized)) return context.get(normalized);
        List<String> segments = splitSegments(normalized);
        Object current = context;
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) continue;
            if (!(current instanceof Map<?, ?> map)) return null;
            Segment parsed = parseSegment(segment);
            Object next = lookupKey(map, parsed.name());
            if (next == null && stepRecordFallback && isStepRecord(map)
                    && !"input".equalsIgnoreCase(parsed.name())
                    && !"output".equalsIgnoreCase(parsed.name())) {
                Object output = lookupKey(map, "output");
                if (output instanceof Map<?, ?> out) {
                    next = lookupKey(out, parsed.name());
                }
            }
            if (next == null) return null;
            current = applySelectors(next, parsed.selectors());
            if (current == null) return null;
        }
        return current;
    }

    private static Object applySelectors(Object value, List<String> selectors) {
        Object current = value;
        for (String selector : selectors) {
            if (selector == null || selector.isBlank()) continue;
            String raw = selector.trim();
            if (raw.matches("-?\\d+")) {
                if (!(current instanceof List<?> list)) return null;
                int idx = Integer.parseInt(raw);
                if (idx < 0 || idx >= list.size()) return null;
                current = list.get(idx);
                continue;
            }
            if (!(current instanceof List<?> list)) return null;
            if (raw.contains("!=") || raw.contains("=")) {
                String op = raw.contains("!=") ? "!=" : "=";
                int split = raw.indexOf(op);
                if (split <= 0) return null;
                String key = raw.substring(0, split).trim();
                String val = unquote(raw.substring(split + op.length()).trim());
                List<Object> filtered = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) continue;
                    Object itemVal = lookupKey(map, key);
                    String itemText = itemVal == null ? null : String.valueOf(itemVal);
                    boolean matched = val.equals(itemText);
                    if (("=".equals(op) && matched) || ("!=".equals(op) && !matched)) {
                        filtered.add(item);
                    }
                }
                current = filtered;
                continue;
            }
            String existsKey = raw.trim();
            List<Object> filtered = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                if (lookupKey(map, existsKey) != null) filtered.add(item);
            }
            current = filtered;
        }
        return current;
    }

    private static List<String> splitSegments(String keyPath) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        for (int i = 0; i < keyPath.length(); i++) {
            char c = keyPath.charAt(i);
            if (c == '[') bracketDepth++;
            if (c == ']') bracketDepth = Math.max(0, bracketDepth - 1);
            if (c == '.' && bracketDepth == 0) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }

    private static Segment parseSegment(String raw) {
        StringBuilder name = new StringBuilder();
        List<String> selectors = new ArrayList<>();
        StringBuilder selector = new StringBuilder();
        boolean inSelector = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '[') {
                inSelector = true;
                selector.setLength(0);
                continue;
            }
            if (c == ']') {
                if (inSelector) selectors.add(selector.toString());
                inSelector = false;
                continue;
            }
            if (inSelector) selector.append(c);
            else name.append(c);
        }
        return new Segment(name.toString().trim(), selectors);
    }

    private static String normalizeRootPath(String keyPath) {
        String key = keyPath.trim();
        if (key.startsWith("$.")) return key.substring(2);
        if (key.equals("$")) return "";
        if (key.startsWith("$")) return key.substring(1);
        return key;
    }

    private static String unquote(String value) {
        if (value == null) return null;
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static Object lookupKey(Map<?, ?> map, String part) {
        if (map == null || part == null) return null;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && String.valueOf(entry.getKey()).equalsIgnoreCase(part)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isStepRecord(Map<?, ?> map) {
        return map.size() == 2
                && (map.containsKey("input") || map.containsKey("Input"))
                && (map.containsKey("output") || map.containsKey("Output"));
    }

    private record Segment(String name, List<String> selectors) {}
}
