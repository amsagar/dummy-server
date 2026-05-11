package com.pods.agent.workflow.engine.domain;

/**
 * Explicit transition trigger semantics.
 */
public enum TransitionTrigger {
    ON_SUCCESS,
    ON_NO_MATCH,
    ON_ERROR,
    ON_TIMEOUT,
    ON_VALIDATION_ERROR
}
