package com.pods.agent.agent.tool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-turn gate used to enforce skill-first execution for relevant requests.
 */
public class SkillExecutionGate {

    private final boolean required;
    private final AtomicBoolean skillLoaded = new AtomicBoolean(false);
    private final ConcurrentMap<String, String> toolResultBySignature = new ConcurrentHashMap<>();
    private volatile java.util.List<String> suggestedSkillNames = java.util.List.of();

    public SkillExecutionGate(boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * Optional hint — names of skills the planner lexically matched against
     * the user prompt. Surfaced verbatim in the skill-first-required error
     * so the model can call {@code skill(name="...")} with the correct name
     * on the next step instead of guessing from the catalog.
     */
    public void setSuggestedSkillNames(java.util.List<String> names) {
        this.suggestedSkillNames = names == null ? java.util.List.of() : java.util.List.copyOf(names);
    }

    public java.util.List<String> getSuggestedSkillNames() {
        return suggestedSkillNames;
    }

    public boolean isSkillLoaded() {
        return skillLoaded.get();
    }

    public void markSkillLoaded() {
        skillLoaded.set(true);
    }

    public String getCachedToolResult(String signature) {
        if (signature == null || signature.isBlank()) return null;
        return toolResultBySignature.get(signature);
    }

    public void cacheToolResult(String signature, String output) {
        if (signature == null || signature.isBlank() || output == null) return;
        toolResultBySignature.putIfAbsent(signature, output);
    }
}
