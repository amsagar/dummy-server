package com.pods.agent.dmn.feel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FeelExpressionEvaluator {
    private static final Pattern COMPARISON_PATTERN = Pattern.compile("^(<=|>=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)$");
    private static final Pattern RANGE_PATTERN = Pattern.compile("^([\\[\\(])\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*([\\]\\)])$");

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
}
