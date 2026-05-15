package com.pods.agent.ruledomain;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.Skill;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.SkillRegistryService.SkillSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Decides which (if any) allowlisted skill an incoming user message should
 * route to for compiled-domain handling. Cheap first-pass: keyword match
 * against the skill name. Future work can swap this for an embedding-based
 * classifier — but for the enabled-skill set on the order of 1–10, keyword
 * matching is more than sufficient.
 *
 * Returns empty when:
 *   - feature is globally disabled
 *   - no allowlisted skill matches the message
 *   - matched skill name isn't backed by a {@link Skill} in the registry
 */
@Component
@Slf4j
public class SkillRouter {

    private final SkillRegistryService skillRegistryService;
    private final RuleDomainProperties props;

    public SkillRouter(SkillRegistryService skillRegistryService,
                       RuleDomainProperties props) {
        this.skillRegistryService = skillRegistryService;
        this.props = props;
    }

    public Optional<RoutedSkill> route(String userMessage) {
        if (!props.isEnabled() || userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        Set<String> allow = props.normalizedAllowlist();
        if (allow.isEmpty()) return Optional.empty();

        String lower = userMessage.toLowerCase();

        for (SkillSnapshot snapshot : skillRegistryService.getEnabledSkills()) {
            Skill skill = snapshot.skill();
            if (!allow.contains(skill.getName().toLowerCase())) continue;

            // Keyword heuristic: any token from the skill name (split on '-' or whitespace)
            // appearing in the user message counts as a match. Cheap, deterministic.
            String[] tokens = skill.getName().toLowerCase().split("[-\\s_]+");
            boolean hit = false;
            for (String t : tokens) {
                if (t.length() >= 4 && lower.contains(t)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                String markdown = snapshot.files() == null ? "" :
                        snapshot.files().values().stream().findFirst().orElse("");
                SkillRuleManifest manifest = SkillRegistryService.parseRuleManifest(markdown);
                return Optional.of(new RoutedSkill(skill, markdown, manifest));
            }
        }
        return Optional.empty();
    }

    /**
     * Bundle returned to the orchestrator on a successful skill match. Carries
     * the raw skill markdown (passed to the compiler when no rule manifest is
     * present) plus the parsed manifest (used by Phase 2 trace-based compile
     * to know which rules to extract).
     */
    public record RoutedSkill(Skill skill, String markdown, SkillRuleManifest manifest) {
        /** Back-compat overload — older callers used (skill, markdown). */
        public RoutedSkill(Skill skill, String markdown) {
            this(skill, markdown, SkillRuleManifest.EMPTY);
        }
    }
}
