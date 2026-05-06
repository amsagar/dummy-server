package com.pods.agent.service;

import com.api.jsonata4java.expressions.Expressions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pods.agent.agent.toolchain.ArgMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
            if (s.startsWith("$")) return evaluateJsonata(s, context);
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
}
