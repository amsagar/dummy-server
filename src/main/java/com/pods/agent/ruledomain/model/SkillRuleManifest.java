package com.pods.agent.ruledomain.model;

import java.util.List;

/**
 * Parsed {@code rules:} block from a skill's YAML frontmatter. When a skill
 * opts into the domain→rules architecture, its {@code SKILL.md} declares
 * each rule's name + sample intent phrasing + which tools the rule owns.
 *
 * <p>Example:
 * <pre>
 * ---
 * name: pods-order-validation
 * rules:
 *   - name: leg-sequence-check
 *     intent_examples:
 *       - "check leg sequence for {order}"
 *     result_key: legSequence
 *     tools: [Get_OrderID, dtEvaluate]
 *   - name: serviceability-check
 *     intent_examples: ["is order {order} serviceable"]
 *     result_key: serviceability
 *     tools: [Get_OrderID, Serviceability]
 * domain_intent_examples:
 *   - "validate order {order}"
 * ---
 * </pre>
 *
 * <p>Skills without a {@code rules:} block parse to an empty manifest, which
 * the orchestrator treats as "compile as one monolithic legacy rule" — full
 * back-compat.
 */
public record SkillRuleManifest(
        List<Rule> rules,
        List<String> domainIntentExamples
) {
    public static final SkillRuleManifest EMPTY = new SkillRuleManifest(List.of(), List.of());

    public boolean isEmpty() {
        return rules == null || rules.isEmpty();
    }

    /**
     * One rule entry in the manifest.
     *
     * @param name           rule identifier within the group (e.g. "leg-sequence-check")
     * @param intentExamples sample user phrasings — embedded to produce this rule's
     *                       narrow intent vector
     * @param resultKey      key under which this rule's output is merged into the
     *                       composite outcome (e.g. "legSequence"); falls back to
     *                       {@code name} when omitted
     * @param tools          which tools (by AgentTool.name) are owned by this rule;
     *                       used by Phase 2's {@code RuleSlicer} to partition a
     *                       full execution trace into per-rule slices
     * @param skillSection   optional markdown sub-section (heading text) of the
     *                       SKILL.md that describes this rule — passed to the
     *                       compiler so its prompt sees only the relevant prose
     */
    public record Rule(
            String name,
            List<String> intentExamples,
            String resultKey,
            List<String> tools,
            String skillSection
    ) {
        public String effectiveResultKey() {
            return resultKey == null || resultKey.isBlank() ? name : resultKey;
        }
    }
}
