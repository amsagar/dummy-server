package com.pods.agent.workflow.engine.domain;

/**
 * Activity instance lifecycle states. READY → RUNNING → COMPLETED is the happy
 * path. FAILED, DEADLINE_BREACHED, CANCELLED are terminal failure variants
 * that the engine routes via error edges.
 */
public enum ActivityState {

    READY,
    RUNNING,
    /**
     * Activity is awaiting external input (e.g. human approval). Non-terminal:
     * the activity will be re-dispatched on resume. The process instance is
     * placed into {@code OPEN_SUSPENDED} while any of its activities are in
     * this state.
     */
    SUSPENDED,
    COMPLETED,
    FAILED,
    DEADLINE_BREACHED,
    CANCELLED;

    public String wire() {
        return name().toLowerCase();
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == DEADLINE_BREACHED || this == CANCELLED;
    }

    public boolean isSuspended() {
        return this == SUSPENDED;
    }

    public boolean isFailure() {
        return this == FAILED || this == DEADLINE_BREACHED || this == CANCELLED;
    }

    public static ActivityState fromWire(String wire) {
        return ActivityState.valueOf(wire.toUpperCase());
    }
}
