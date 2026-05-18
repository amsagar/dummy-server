package com.pods.agent.ordervalidation.model;

/**
 * Response shape for {@code POST /api/v1/workflow/runs}. Mirrors
 * order-validation-ui/src/types/orderValidation.ts:RunSummary.
 */
public record RunSummary(
        String instanceId,
        String defId,
        String state,
        Long startedAt,
        Long endedAt,
        String requesterId,
        String errorClass,
        String errorMessage,
        Object result
) {}
