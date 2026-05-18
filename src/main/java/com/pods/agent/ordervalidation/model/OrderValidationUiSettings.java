package com.pods.agent.ordervalidation.model;

import java.util.List;

/**
 * Server-stored settings for the order-validation-ui. Singleton row keyed
 * by id='default'. Mirrors the shape in
 * order-validation-ui/src/services/api.ts:OrderValidationUiSettings,
 * extended with the allow-list arrays that drive the scoped AI assistant.
 *
 * <p>Allow-list semantics: {@code null} means "no restriction" (every
 * resource of that kind is allowed). An empty list means "deny all".
 */
public record OrderValidationUiSettings(
        String chatModelRef,
        String responseMode,
        String workflowId,
        List<String> allowedSkillIds,
        List<String> allowedRuleDomainIds,
        List<String> allowedDecisionTables
) {
    public static OrderValidationUiSettings defaults() {
        return new OrderValidationUiSettings(null, "basic", null, null, null, null);
    }
}
