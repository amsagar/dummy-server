package com.pods.agent.service.mapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SystemFunctions {
    private final Map<String, Function<List<Object>, Object>> functions;

    public SystemFunctions() {
        this.functions = Map.ofEntries(
                Map.entry("upper", args -> text(args, 0).toUpperCase(Locale.ROOT)),
                Map.entry("touppercase", args -> text(args, 0).toUpperCase(Locale.ROOT)),
                Map.entry("lower", args -> text(args, 0).toLowerCase(Locale.ROOT)),
                Map.entry("tolowercase", args -> text(args, 0).toLowerCase(Locale.ROOT)),
                Map.entry("trim", args -> text(args, 0).trim()),
                Map.entry("coalesce", this::coalesce),
                Map.entry("len", args -> length(args.isEmpty() ? null : args.get(0))),
                Map.entry("length", args -> length(args.isEmpty() ? null : args.get(0))),
                Map.entry("first", args -> first(args.isEmpty() ? null : args.get(0))),
                Map.entry("last", args -> last(args.isEmpty() ? null : args.get(0))),
                Map.entry("concat", args -> args.stream().map(v -> v == null ? "" : String.valueOf(v)).collect(Collectors.joining())),
                Map.entry("contains", args -> contains(args)),
                Map.entry("join", args -> join(args)),
                Map.entry("split", args -> split(args)),
                Map.entry("slice", args -> slice(args)),
                Map.entry("replace", args -> text(args, 0).replace(text(args, 1), text(args, 2))),
                Map.entry("parseint", args -> parseInt(args)),
                Map.entry("now", args -> Instant.now().toString()),
                Map.entry("uuid", args -> UUID.randomUUID().toString()),
                Map.entry("keys", args -> keys(args)),
                Map.entry("values", args -> values(args)),
                Map.entry("hash", this::hash),
                Map.entry("parsedate", this::parseDate),
                Map.entry("formatdate", this::formatDate)
        );
    }

    public boolean hasFunction(String name) {
        return name != null && functions.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Object invoke(String name, List<Object> args) {
        Function<List<Object>, Object> fn = functions.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (fn == null) throw new IllegalArgumentException("Unknown function: " + name);
        return fn.apply(args == null ? List.of() : args);
    }

    public List<String> functionNames() {
        return functions.keySet().stream().sorted().toList();
    }

    private Object coalesce(List<Object> args) {
        for (Object arg : args) {
            if (arg != null && !String.valueOf(arg).isBlank()) return arg;
        }
        return null;
    }

    private Integer length(Object value) {
        if (value == null) return 0;
        if (value instanceof CharSequence s) return s.length();
        if (value instanceof Collection<?> c) return c.size();
        if (value instanceof Map<?, ?> m) return m.size();
        return String.valueOf(value).length();
    }

    private Object first(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) return list.get(0);
        return null;
    }

    private Object last(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) return list.get(list.size() - 1);
        return null;
    }

    private Object contains(List<Object> args) {
        if (args.size() < 2) return false;
        Object container = args.get(0);
        Object needle = args.get(1);
        if (container instanceof Collection<?> list) {
            return list.stream().anyMatch(v -> String.valueOf(v).equals(String.valueOf(needle)));
        }
        return String.valueOf(container).contains(String.valueOf(needle));
    }

    private Object join(List<Object> args) {
        if (args.isEmpty()) return "";
        Object source = args.get(0);
        String sep = args.size() > 1 ? String.valueOf(args.get(1)) : ",";
        if (!(source instanceof Collection<?> list)) return String.valueOf(source);
        return list.stream().map(v -> v == null ? "" : String.valueOf(v)).collect(Collectors.joining(sep));
    }

    private Object split(List<Object> args) {
        if (args.isEmpty()) return List.of();
        String text = String.valueOf(args.get(0));
        String sep = args.size() > 1 ? String.valueOf(args.get(1)) : ",";
        if (sep.isEmpty()) return Arrays.stream(text.split("")).toList();
        return Arrays.stream(text.split(java.util.regex.Pattern.quote(sep))).toList();
    }

    private Object slice(List<Object> args) {
        if (args.isEmpty()) return null;
        Object source = args.get(0);
        int start = args.size() > 1 ? toInt(args.get(1), 0) : 0;
        int end = args.size() > 2 ? toInt(args.get(2), Integer.MAX_VALUE) : Integer.MAX_VALUE;
        if (source instanceof List<?> list) {
            int from = Math.max(0, Math.min(start, list.size()));
            int to = Math.max(from, Math.min(end, list.size()));
            return list.subList(from, to);
        }
        String s = String.valueOf(source);
        int from = Math.max(0, Math.min(start, s.length()));
        int to = Math.max(from, Math.min(end, s.length()));
        return s.substring(from, to);
    }

    private Object parseInt(List<Object> args) {
        if (args.isEmpty() || args.get(0) == null) return null;
        return Integer.parseInt(String.valueOf(args.get(0)).trim());
    }

    private Object keys(List<Object> args) {
        if (args.isEmpty() || !(args.get(0) instanceof Map<?, ?> map)) return List.of();
        return map.keySet().stream().map(String::valueOf).toList();
    }

    private Object values(List<Object> args) {
        if (args.isEmpty() || !(args.get(0) instanceof Map<?, ?> map)) return List.of();
        return List.copyOf(map.values());
    }

    private Object hash(List<Object> args) {
        String value = text(args, 0);
        String algo = args.size() > 1 ? text(args, 1) : "sha256";
        try {
            String normalized = "sha256".equalsIgnoreCase(algo) ? "SHA-256" : algo.toUpperCase(Locale.ROOT);
            MessageDigest digest = MessageDigest.getInstance(normalized);
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported hash algorithm: " + algo);
        }
    }

    private Object parseDate(List<Object> args) {
        if (args.isEmpty()) return null;
        String raw = text(args, 0);
        String fmt = args.size() > 1 ? text(args, 1) : "yyyy-MM-dd";
        return LocalDate.parse(raw, DateTimeFormatter.ofPattern(fmt)).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
    }

    private Object formatDate(List<Object> args) {
        if (args.isEmpty()) return null;
        String raw = text(args, 0);
        String fmt = args.size() > 1 ? text(args, 1) : "yyyy-MM-dd";
        Instant instant = Instant.parse(raw);
        return DateTimeFormatter.ofPattern(fmt).withZone(ZoneOffset.UTC).format(instant);
    }

    private String text(List<Object> args, int index) {
        if (index >= args.size() || args.get(index) == null) return "";
        return String.valueOf(args.get(index));
    }

    private int toInt(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }
}
