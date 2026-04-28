package com.pods.agent.agent.tool;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-turn gate used to enforce skill-first execution for relevant requests.
 */
public class SkillExecutionGate {

    private final boolean required;
    private final AtomicBoolean skillLoaded = new AtomicBoolean(false);

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
}
