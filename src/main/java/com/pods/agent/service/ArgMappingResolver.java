package com.pods.agent.service;

import com.api.jsonata4java.expressions.Expressions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pods.agent.agent.toolchain.ArgMapping;
import com.pods.agent.service.expression.BooleanExpressionEvaluator;
import com.pods.agent.service.mapping.SystemFunctions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves a node's argMappings entry against the runtime context.
 *
 * Mapping value forms accepted (in priority order at the same call site):
 *   1. JSONata expression: any String starting with "$".
 *      Example: "$.tool_1.output.customer.id"
 *   2. Legacy dot-path: any other String.
 *      Example: "tool_1.customerId"  (resolved via the caller-provided dotPathFallback)
 *   3. Literal: any non-string value (Number, Boolean, Map, List).
 *   4. Object form {expr, fallback, policy}: handled by Phase 2 (ArgMapping record).
 *
 * Returns null when the mapping does not produce a value; the caller decides
 * whether that triggers a fallback, llm-assisted resolution, or a strict failure.
 */
@Service
@Slf4j
public class ArgMappingResolver {

    /**
     * Self-managed Jackson 2 mapper.
     * The rest of the codebase has migrated to Jackson 3 (tools.jackson.*), but JSONata4Java
     * still ships with Jackson 2 (com.fasterxml.jackson.*). Constructing a private mapper here
     * avoids forcing a global Jackson version downgrade.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BooleanExpressionEvaluator booleanExpressionEvaluator;
    private final SystemFunctions systemFunctions;

    public ArgMappingResolver(BooleanExpressionEvaluator booleanExpressionEvaluator) {
        this.booleanExpressionEvaluator = booleanExpressionEvaluator;
        this.systemFunctions = new SystemFunctions();
    }

    public Map<String, Object> resolve(Map<String, Object> argMappings,
                                       Map<String, Object> context,
                                       Function<String, Object> dotPathFallback) {
        return resolveAll(argMappings, context, dotPathFallback).resolved();
    }

    /**
     * Resolve every mapping, separating deterministic results from those that came back
     * empty under an "llm_assisted" policy. The caller (runtime) is responsible for
     * dispatching deferred entries to LlmArgResolver if a model is available; if not,
     * deferred args simply remain unset.
     */
    public ResolutionResult resolveAll(Map<String, Object> argMappings,
                                       Map<String, Object> context,
                                       Function<String, Object> dotPathFallback) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        java.util.List<DeferredArg> deferred = new java.util.ArrayList<>();
        if (argMappings == null || argMappings.isEmpty()) {
            return new ResolutionResult(resolved, deferred);
        }
        for (Map.Entry<String, Object> entry : argMappings.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) continue;
            Object source = entry.getValue();
            ArgMapping mapping = source instanceof Map<?, ?> ? ArgMapping.from(source) : null;
            Object value = resolveOne(source, context, dotPathFallback);
            if (value != null) {
                resolved.put(name, value);
            } else if (mapping != null && mapping.isLlmAssisted()) {
                deferred.add(new DeferredArg(name, mapping));
            }
        }
        return new ResolutionResult(resolved, deferred);
    }

    /**
     * Resolves a single mapping source. Accepts:
     *   - String  (JSONata if starts with "$", else legacy dot-path)
     *   - Map     (object form: {expr, fallback, policy})
     *   - Other   (literal value passed through)
     *
     * For object form: evaluates expr, then fallback, then for "llm_assisted"
     * policy returns null (caller dispatches to LlmArgResolver in Phase 4).
     */
    public Object resolveOne(Object source,
                             Map<String, Object> context,
                             Function<String, Object> dotPathFallback) {
        if (source instanceof Map<?, ?>) {
            ArgMapping mapping = ArgMapping.from(source);
            return resolveMapping(mapping, context, dotPathFallback);
        }
        if (source instanceof String s) {
            if (s.startsWith("ref:")) return s;
            if (s.startsWith("#if(")) return resolveConditional(s, context, dotPathFallback);
            if (s.startsWith("$")) return evaluateJsonata(s, context);
            Object fnResult = tryResolveFunctionCall(s, context, dotPathFallback);
            if (fnResult != FunctionCallMiss.INSTANCE) return fnResult;
            return dotPathFallback.apply(s);
        }
        return source;
    }

    private Object resolveMapping(ArgMapping mapping,
                                  Map<String, Object> context,
                                  Function<String, Object> dotPathFallback) {
        if (mapping == null) return null;
        Object value = resolveOne(mapping.expr(), context, dotPathFallback);
        if (value != null) return value;
        if (mapping.fallback() != null) return mapping.fallback();
        return null;
    }

    public record ResolutionResult(Map<String, Object> resolved,
                                   java.util.List<DeferredArg> deferred) {}

    public record DeferredArg(String argName, ArgMapping mapping) {}

    public Object evaluateJsonata(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) return null;
        try {
            JsonNode contextNode = objectMapper.valueToTree(context);
            Expressions parsed = Expressions.parse(expression);
            JsonNode result = parsed.evaluate(contextNode);
            if (result == null || result.isMissingNode() || result.isNull()) return null;
            return objectMapper.treeToValue(result, Object.class);
        } catch (Exception e) {
            log.warn("JSONata evaluation failed for [{}]: {}", expression, e.getMessage());
            return null;
        }
    }

    private Object resolveConditional(String raw,
                                      Map<String, Object> context,
                                      Function<String, Object> dotPathFallback) {
        ConditionalBranches branches = parseConditional(raw);
        for (ConditionalBranch branch : branches.whenBranches()) {
            if (booleanExpressionEvaluator.eval(branch.predicate(), context)) {
                return resolveBranchValue(branch.valueExpr(), context, dotPathFallback);
            }
        }
        if (branches.elseExpr() != null) {
            return resolveBranchValue(branches.elseExpr(), context, dotPathFallback);
        }
        return null;
    }

    private Object resolveBranchValue(String raw,
                                      Map<String, Object> context,
                                      Function<String, Object> dotPathFallback) {
        if (raw == null) return null;
        String value = raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        if ("null".equalsIgnoreCase(value)) return null;
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.parseBoolean(value);
        try {
            return new BigDecimal(value);
        } catch (Exception ignored) {
        }
        return resolveOne(value, context, dotPathFallback);
    }

    private ConditionalBranches parseConditional(String raw) {
        String cursor = raw == null ? "" : raw.trim();
        List<ConditionalBranch> branches = new ArrayList<>();
        String elseExpr = null;
        while (!cursor.isBlank()) {
            if (cursor.startsWith("#if(") || cursor.startsWith("#elseIf(")) {
                boolean elseIf = cursor.startsWith("#elseIf(");
                int start = elseIf ? "#elseIf(".length() : "#if(".length();
                int close = findMatchingParen(cursor, start - 1);
                if (close < 0) throw new IllegalArgumentException("Unbalanced conditional predicate");
                String predicate = cursor.substring(start, close).trim();
                String afterPredicate = cursor.substring(close + 1).trim();
                NextTag next = nextConditionalTag(afterPredicate);
                String valueExpr = next.index() < 0 ? afterPredicate : afterPredicate.substring(0, next.index()).trim();
                branches.add(new ConditionalBranch(predicate, valueExpr));
                cursor = next.index() < 0 ? "" : afterPredicate.substring(next.index()).trim();
                continue;
            }
            if (cursor.startsWith("#else")) {
                String afterElse = cursor.substring("#else".length()).trim();
                int endif = afterElse.indexOf("#endif");
                elseExpr = (endif < 0 ? afterElse : afterElse.substring(0, endif)).trim();
                cursor = "";
                continue;
            }
            if (cursor.startsWith("#endif")) {
                cursor = "";
                continue;
            }
            throw new IllegalArgumentException("Invalid conditional mapping syntax");
        }
        return new ConditionalBranches(branches, elseExpr);
    }

    private NextTag nextConditionalTag(String raw) {
        int idx = raw.length();
        for (String tag : List.of("#elseIf(", "#else", "#endif")) {
            int hit = raw.indexOf(tag);
            if (hit >= 0) idx = Math.min(idx, hit);
        }
        return new NextTag(idx == raw.length() ? -1 : idx);
    }

    private int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private Object tryResolveFunctionCall(String source,
                                          Map<String, Object> context,
                                          Function<String, Object> dotPathFallback) {
        String raw = source == null ? "" : source.trim();
        int open = raw.indexOf('(');
        if (open <= 0 || !raw.endsWith(")")) return FunctionCallMiss.INSTANCE;
        String name = raw.substring(0, open).trim();
        if (!systemFunctions.hasFunction(name)) return FunctionCallMiss.INSTANCE;
        String argsBody = raw.substring(open + 1, raw.length() - 1);
        List<String> argExprs = splitArgs(argsBody);
        List<Object> args = new ArrayList<>();
        for (String argExpr : argExprs) {
            args.add(resolveBranchValue(argExpr, context, dotPathFallback));
        }
        return systemFunctions.invoke(name, args);
    }

    private List<String> splitArgs(String argsBody) {
        List<String> args = new ArrayList<>();
        if (argsBody == null || argsBody.isBlank()) return args;
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < argsBody.length(); i++) {
            char c = argsBody.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || argsBody.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    quote = c;
                } else if (quote == c) {
                    inString = false;
                }
            }
            if (!inString) {
                if (c == '(') depth++;
                if (c == ')') depth = Math.max(0, depth - 1);
                if (c == ',' && depth == 0) {
                    args.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (!current.isEmpty()) args.add(current.toString().trim());
        return args;
    }

    private enum FunctionCallMiss { INSTANCE }
    private record ConditionalBranch(String predicate, String valueExpr) {}
    private record ConditionalBranches(List<ConditionalBranch> whenBranches, String elseExpr) {}
    private record NextTag(int index) {}
}
