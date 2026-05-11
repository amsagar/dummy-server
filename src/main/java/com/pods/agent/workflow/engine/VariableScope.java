package com.pods.agent.workflow.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-process variable map with optional parent-scope read-through.
 *
 * <p>Resolves audit finding #5 ("context corruption on partial failure"):
 * sub-flows get their <em>own</em> {@code VariableScope} with the parent as a
 * read-only fallback. Writes go to the current scope only; the parent is
 * untouched until the sub-flow successfully completes and the engine merges
 * declared outputs back in one atomic step (handled by SubChainExecutor in
 * Phase 5).
 *
 * Not thread-safe by design: the engine guarantees a scope is only mutated by
 * one activity at a time.
 */
public final class VariableScope {

    private final String scopeId;
    private final VariableScope parent;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public VariableScope(String scopeId) {
        this(scopeId, null);
    }

    public VariableScope(String scopeId, VariableScope parent) {
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }
        this.scopeId = scopeId;
        this.parent = parent;
    }

    public String scopeId() {
        return scopeId;
    }

    public VariableScope parent() {
        return parent;
    }

    /**
     * Read variable: current scope first, then walk up parents.
     */
    public Object get(String name) {
        if (values.containsKey(name)) {
            return values.get(name);
        }
        return parent == null ? null : parent.get(name);
    }

    public boolean has(String name) {
        if (values.containsKey(name)) {
            return true;
        }
        return parent != null && parent.has(name);
    }

    /**
     * Write to <em>current</em> scope only. Even if {@code name} also exists
     * in a parent scope, this method does not modify the parent.
     */
    public void set(String name, Object value) {
        values.put(name, value);
    }

    public void setAll(Map<String, ?> updates) {
        if (updates == null) {
            return;
        }
        for (Map.Entry<String, ?> e : updates.entrySet()) {
            values.put(e.getKey(), e.getValue());
        }
    }

    /**
     * Snapshot of the current scope only (no parent merge). Used by the audit
     * trail and persistence; parent values are recorded against their own
     * scopeId.
     */
    public Map<String, Object> localSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Snapshot with parent values merged in (children override). Used by the
     * SecureSpelEvaluator binding so expressions see the full effective scope.
     */
    public Map<String, Object> effectiveSnapshot() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (parent != null) {
            merged.putAll(parent.effectiveSnapshot());
        }
        merged.putAll(values);
        return Collections.unmodifiableMap(merged);
    }
}
