package com.pods.agent.agent.toolchain;

import java.util.Map;

/**
 * Structured form of a single arg mapping inside a node's argMappings block.
 *
 * Stored in graphJson as one of:
 *   - String   (legacy):           "tool_1.customerId" or "$.tool_1.output.id"
 *   - Map      (Phase 2 onward):   { expr, fallback, policy }
 *   - Other    (literal):          numbers, booleans, lists, plain objects
 *
 * Fields:
 *   expr     - JSONata (starts with "$") or legacy dot-path
 *   fallback - deterministic value used when expr resolves to null
 *   policy   - "strict"        : throw mapping failure when expr+fallback are null
 *              "llm_assisted"  : invoke a narrow LLM call to fill the value (Phase 4)
 */
public record ArgMapping(Object expr, Object fallback, String policy) {

    public static final String POLICY_STRICT = "strict";
    public static final String POLICY_LLM_ASSISTED = "llm_assisted";

    public ArgMapping {
        if (policy == null || policy.isBlank()) policy = POLICY_STRICT;
    }

    /**
     * Coerces any of the three storage forms into an ArgMapping.
     * Returns null only when the input itself is null.
     */
    public static ArgMapping from(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            Object expr = m.get("expr");
            if (expr == null) expr = m.get("expression");
            Object fallback = m.get("fallback");
            Object policy = m.get("policy");
            return new ArgMapping(expr, fallback, policy == null ? POLICY_STRICT : String.valueOf(policy));
        }
        return new ArgMapping(raw, null, POLICY_STRICT);
    }

    public boolean isLlmAssisted() {
        return POLICY_LLM_ASSISTED.equalsIgnoreCase(policy);
    }
}
