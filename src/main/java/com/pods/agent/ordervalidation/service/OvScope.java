package com.pods.agent.ordervalidation.service;

import java.util.Set;

/**
 * Immutable allow-list snapshot for the OV-scoped assistant. Each field
 * is non-null only when the user has explicitly configured a restriction
 * for that resource type. Code that consults the scope should treat
 * {@code null} as "no restriction" and an empty set as "deny all".
 */
public record OvScope(
        Set<String> allowedSkillIds,
        Set<String> allowedRuleDomainIds,
        Set<String> allowedDecisionTables
) {
    public static final OvScope UNRESTRICTED = new OvScope(null, null, null);

    public boolean isSkillAllowed(String skillId) {
        return allowedSkillIds == null || allowedSkillIds.contains(skillId);
    }

    public boolean isRuleDomainAllowed(String domainId) {
        return allowedRuleDomainIds == null || allowedRuleDomainIds.contains(domainId);
    }

    public boolean isDecisionTableAllowed(String name) {
        return allowedDecisionTables == null || allowedDecisionTables.contains(name);
    }

    public boolean isUnrestricted() {
        return allowedSkillIds == null && allowedRuleDomainIds == null && allowedDecisionTables == null;
    }
}
