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

    public SkillExecutionGate(boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
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
