package com.pods.agent.workflow.engine.domain;

import java.util.Map;

/**
 * What an activity execution produced. Returned from
 * {@link com.pods.agent.workflow.engine.ActivityDispatcher#dispatch}.
 *
 * One of {@link #success} or {@link #failure} should be used; never construct
 * a Result with {@code state=COMPLETED} and a non-null {@code errorClass}.
 */
public record ActivityResult(
        ActivityState state,
        Object output,
        Map<String, Object> variableUpdates,
        ErrorClass errorClass,
        String errorMessage
) {
    public static ActivityResult success(Object output) {
        return new ActivityResult(ActivityState.COMPLETED, output, Map.of(), null, null);
    }

    public static ActivityResult success(Object output, Map<String, Object> variableUpdates) {
        return new ActivityResult(ActivityState.COMPLETED, output,
                variableUpdates == null ? Map.of() : Map.copyOf(variableUpdates),
                null, null);
    }

    public static ActivityResult failure(ErrorClass errorClass, String errorMessage) {
        return new ActivityResult(ActivityState.FAILED, null, Map.of(), errorClass, errorMessage);
    }

    public static ActivityResult deadlineBreached(String message) {
        return new ActivityResult(ActivityState.DEADLINE_BREACHED, null, Map.of(),
                ErrorClass.TIMEOUT, message);
    }

    /**
     * The activity is awaiting external input (e.g. human approval). The
     * executor will end the activity instance in state {@code suspended},
     * re-add it to the worklist front, persist the checkpoint, and return
     * {@code OPEN_SUSPENDED}. {@link #errorMessage()} carries the human
     * reason ("approval required: …").
     */
    public static ActivityResult suspended(String reason) {
        return new ActivityResult(ActivityState.SUSPENDED, null, Map.of(), null, reason);
    }

    public boolean isSuccess() {
        return state == ActivityState.COMPLETED;
    }

    public boolean isSuspended() {
        return state == ActivityState.SUSPENDED;
    }
}
