package com.pods.agent.ruledomain.matcher;

import org.springframework.stereotype.Component;

/**
 * Produces a slug-ish intent label for a (skill, user message) pair.
 *
 * Intent labels are used for human readability in the admin UI and to enforce
 * the {@code (skill_id, intent_label, version)} unique constraint. Two
 * variants of the same intent (e.g. "validate order 1" vs "validate order 2")
 * should produce the same label so we don't accumulate one compiled domain
 * per order id.
 *
 * Strategy: take the first 60 chars of the lowercased message, replace
 * digit-heavy runs with an asterisk, strip punctuation. This is intentionally
 * coarse; the actual deduplication happens via cosine similarity on the
 * embedding, not the label.
 */
@Component
public class IntentLabeller {

    public String labelFor(String skillName, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return safeSkill(skillName);
        String s = userMessage.toLowerCase().trim();
        if (s.length() > 60) s = s.substring(0, 60);
        // Replace numeric runs (4+ digits) with a single * so "order 600030447"
        // and "order 600030500" collapse to the same label.
        s = s.replaceAll("\\b\\d{4,}\\b", "*");
        s = s.replaceAll("[^a-z0-9*\\s-]+", "");
        s = s.replaceAll("\\s+", "-").replaceAll("-+", "-");
        if (s.isBlank()) s = safeSkill(skillName);
        return s;
    }

    private static String safeSkill(String skill) {
        return skill == null || skill.isBlank() ? "untitled-intent"
                : skill.toLowerCase().replaceAll("[^a-z0-9-]+", "-");
    }
}
