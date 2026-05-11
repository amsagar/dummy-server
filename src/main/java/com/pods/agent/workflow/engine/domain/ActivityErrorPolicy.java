package com.pods.agent.workflow.engine.domain;

/**
 * Error behavior for an activity.
 */
public record ActivityErrorPolicy(
        int retryCount,
        long backoffMs,
        Long timeoutMs,
        boolean failFast,
        boolean continueOnError
) {
    public ActivityErrorPolicy {
        if (retryCount < 0) retryCount = 0;
        if (backoffMs < 0) backoffMs = 0L;
    }

    public static ActivityErrorPolicy defaults() {
        return new ActivityErrorPolicy(0, 0L, null, false, false);
    }
}
