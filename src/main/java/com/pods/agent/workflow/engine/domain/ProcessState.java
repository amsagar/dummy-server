package com.pods.agent.workflow.engine.domain;

/**
 * Process instance lifecycle states. Strings match Joget's WfMC-derived
 * convention so audit trails are familiar to anyone coming from Joget.
 */
public enum ProcessState {

    OPEN_RUNNING("open.running"),
    OPEN_SUSPENDED("open.not_running.suspended"),
    CLOSED_COMPLETED("closed.completed"),
    CLOSED_TERMINATED("closed.terminated"),
    CLOSED_ABORTED("closed.aborted");

    private final String wire;

    ProcessState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean isClosed() {
        return this == CLOSED_COMPLETED || this == CLOSED_TERMINATED || this == CLOSED_ABORTED;
    }

    public static ProcessState fromWire(String wire) {
        for (ProcessState s : values()) {
            if (s.wire.equals(wire)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown ProcessState: " + wire);
    }
}
