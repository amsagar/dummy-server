package com.pods.agent.dmn.feel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FeelExpressionEvaluator {
    private static final Pattern COMPARISON_PATTERN = Pattern.compile("^(<=|>=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)$");
    private static final Pattern RANGE_PATTERN = Pattern.compile("^([\\[\\(])\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*([\\]\\)])$");
    private static final Pattern LIST_LITERAL_PATTERN = Pattern.compile("^\\[\\s*(.*?)\\s*\\]$", Pattern.DOTALL);
    /** {@code list contains(<list literal>, <scalar literal>)} — only matches when both
     *  the haystack and the needle are constants. The needle is compared against the
     *  actual value; the haystack list is treated as the candidate set. */
    private static final Pattern LIST_CONTAINS_PATTERN = Pattern.compile(
            "^list\\s+contains\\s*\\(\\s*(\\[.*?\\])\\s*,\\s*(.*?)\\s*\\)\\s*$",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private FeelExpressionEvaluator() {
    }

    public static boolean matches(String expression, Object actual) {
        if (expression == null || expression.isBlank() || "*".equals(expression.trim()) || "-".equals(expression.trim())) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        String expr = expression.trim();
        Matcher comparison = COMPARISON_PATTERN.matcher(expr);
        if (comparison.matches()) {
            Double value = toDouble(actual);
            if (value == null) return false;
            double threshold = Double.parseDouble(comparison.group(2));
            return switch (comparison.group(1)) {
                case "<" -> value < threshold;
                case "<=" -> value <= threshold;
                case ">" -> value > threshold;
                case ">=" -> value >= threshold;
                default -> false;
            };
        }
        Matcher range = RANGE_PATTERN.matcher(expr);
        if (range.matches()) {
            Double value = toDouble(actual);
            if (value == null) return false;
            double lower = Double.parseDouble(range.group(2));
            double upper = Double.parseDouble(range.group(3));
            boolean lowerOk = "[".equals(range.group(1)) ? value >= lower : value > lower;
            boolean upperOk = "]".equals(range.group(4)) ? value <= upper : value < upper;
            return lowerOk && upperOk;
        }
        // FEEL `list contains(<list>, <item>)` as a rule cell — `actual` is ignored,
        // we evaluate the literal predicate. This is the form a DMN author writes
        // when the column's value isn't directly comparable to the cell but the
        // membership check is what they want.
        Matcher listContains = LIST_CONTAINS_PATTERN.matcher(expr);
        if (listContains.matches()) {
            List<Object> haystack = parseListLiteral(listContains.group(1));
            if (haystack == null) return false;
            Object needle = parseScalarLiteral(listContains.group(2).trim());
            return containsNormalized(haystack, needle);
        }
        Matcher listLiteral = LIST_LITERAL_PATTERN.matcher(expr);
        if (listLiteral.matches()) {
            List<Object> literal = parseListLiteral(listLiteral.group(1));
            if (literal != null) {
                List<Object> actualList = coerceToList(actual);
                if (actualList != null) {
                    // Two lists → ordered element-wise equality (FEEL list equality semantics).
                    return listsEqualNormalized(literal, actualList);
                }
                // Scalar actual against a list literal → membership check (FEEL `in [...]`
                // is the user intent ~99% of the time when a rule cell is `[a, b, c]`
                // and the input column is scalar).
                return containsNormalized(literal, actual);
            }
        }
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
            expr = expr.substring(1, expr.length() - 1);
        }
        if ("true".equalsIgnoreCase(expr) || "false".equalsIgnoreCase(expr)) {
            return Boolean.parseBoolean(expr) == Boolean.parseBoolean(String.valueOf(actual));
        }
        Double exprNumber = toDouble(expr);
        Double actualNumber = toDouble(actual);
        if (exprNumber != null && actualNumber != null) {
            return Double.compare(actualNumber, exprNumber) == 0;
        }
        return String.valueOf(actual).trim().toLowerCase(Locale.ROOT).equals(expr.toLowerCase(Locale.ROOT));
    }

    private static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Parse the body of a FEEL list literal (everything between {@code [} and
     * {@code ]}) into its element values. Handles double-quoted strings,
     * single-quoted strings, bare numbers, and bare identifiers. Returns
     * {@code null} when parsing fails so the caller can fall back to the
     * string-equality path rather than silently match.
     */
    private static List<Object> parseListLiteral(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        List<Object> out = new ArrayList<>();
        if (trimmed.isEmpty()) return out;
        int i = 0;
        int len = trimmed.length();
        while (i < len) {
            // skip leading whitespace + commas
            while (i < len && (Character.isWhitespace(trimmed.charAt(i)) || trimmed.charAt(i) == ',')) i++;
            if (i >= len) break;
            char c = trimmed.charAt(i);
            if (c == '"' || c == '\'') {
                int end = findClosingQuote(trimmed, i, c);
                if (end < 0) return null;
                out.add(trimmed.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = i;
                while (end < len && trimmed.charAt(end) != ',') end++;
                String token = trimmed.substring(i, end).trim();
                if (token.isEmpty()) return null;
                out.add(parseScalarLiteral(token));
                i = end;
            }
        }
        return out;
    }

    /** Parse a single FEEL literal — quoted string, number, or bare identifier. */
    private static Object parseScalarLiteral(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.isEmpty()) return null;
        if ((t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)
                || (t.startsWith("'") && t.endsWith("'") && t.length() >= 2)) {
            return t.substring(1, t.length() - 1);
        }
        Double n = toDouble(t);
        if (n != null) return n;
        if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) {
            return Boolean.parseBoolean(t);
        }
        return t;
    }

    private static int findClosingQuote(String s, int openIdx, char quote) {
        for (int j = openIdx + 1; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\\' && j + 1 < s.length()) { j++; continue; }
            if (ch == quote) return j;
        }
        return -1;
    }

    /** Convert anything list-like (Java List, array, FEEL-evaluated Iterable) to a List. */
    private static List<Object> coerceToList(Object actual) {
        if (actual instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            out.addAll(list);
            return out;
        }
        if (actual instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            for (Object o : it) out.add(o);
            return out;
        }
        if (actual != null && actual.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(actual);
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(java.lang.reflect.Array.get(actual, i));
            return out;
        }
        return null;
    }

    /** Ordered element-wise equality. Strings compared case-insensitively and
     *  trimmed; numbers compared numerically. */
    private static boolean listsEqualNormalized(List<Object> expected, List<Object> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!scalarsEqualNormalized(expected.get(i), actual.get(i))) return false;
        }
        return true;
    }

    private static boolean containsNormalized(List<Object> haystack, Object needle) {
        for (Object h : haystack) {
            if (scalarsEqualNormalized(h, needle)) return true;
        }
        return false;
    }

    private static boolean scalarsEqualNormalized(Object a, Object b) {
        if (a == null || b == null) return a == b;
        Double an = toDouble(a);
        Double bn = toDouble(b);
        if (an != null && bn != null) return Double.compare(an, bn) == 0;
        return String.valueOf(a).trim().toLowerCase(Locale.ROOT)
                .equals(String.valueOf(b).trim().toLowerCase(Locale.ROOT));
    }
}
