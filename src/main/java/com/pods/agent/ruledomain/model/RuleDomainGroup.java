package com.pods.agent.ruledomain.model;

import java.util.List;

/**
 * Logical grouping of related {@link RuleDomain} rules under one umbrella
 * intent. A group is the addressable unit users think in (e.g.
 * "Pods-Order-Validation"); each member rule (e.g. "leg-sequence-check",
 * "serviceability-check") is independently compiled, embedded, and
 * versioned.
 *
 * <p>Not a persisted entity — domain-group identity lives on each
 * {@link RuleDomain} row's {@code domain_group_id} / {@code domain_group_name}
 * columns. This record is a query-result shape returned by the repository
 * when callers want a group-level view of all member rules.
 *
 * @param groupId          stable UUID for the group, shared by every member rule
 * @param groupName        human-readable group name (e.g. "Pods-Order-Validation")
 * @param skillId          the owning skill (group can't span skills)
 * @param skillName        skill name for display
 * @param rules            the member {@link RuleDomain} rows in this group
 * @param umbrellaRuleId   id of the synthetic {@code DOMAIN_FANOUT} row whose
 *                         embedding represents the broad umbrella intent
 *                         ("validate order X"); {@code null} when the group
 *                         has only narrow rule-level intents.
 */
public record RuleDomainGroup(
        String groupId,
        String groupName,
        String skillId,
        String skillName,
        List<RuleDomain> rules,
        String umbrellaRuleId
) {
    /** Just the rules with {@code match_scope=RULE} — what the orchestrator
     *  iterates when fanning out a domain-level match. */
    public List<RuleDomain> narrowRules() {
        return rules == null ? List.of() : rules.stream()
                .filter(r -> RuleDomain.SCOPE_RULE.equals(r.getMatchScope()))
                .toList();
    }
}
