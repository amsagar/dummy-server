package com.pods.agent.ordervalidation.service;

import com.pods.agent.ordervalidation.model.OrderValidationUiSettings;
import com.pods.agent.ordervalidation.repository.OrderValidationSettingsRepository;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the current OV allow-list scope. Skill + rule-domain
 * restrictions are derived from the user's chosen workflow (the
 * workflowId is the skill_id), so the user only manages one scope
 * concept in the UI. Decision-table allow-list is still user-managed
 * because it's an independent global resource.
 */
@Service
public class OvScopeService {

    private final OrderValidationSettingsRepository settingsRepo;
    private final RuleDomainRepository ruleDomainRepo;

    public OvScopeService(OrderValidationSettingsRepository settingsRepo,
                          RuleDomainRepository ruleDomainRepo) {
        this.settingsRepo = settingsRepo;
        this.ruleDomainRepo = ruleDomainRepo;
    }

    public OvScope loadCurrent() {
        OrderValidationUiSettings s = settingsRepo.load();
        String workflowId = s.workflowId();
        Set<String> extraSkills = toSet(s.allowedSkillIds());

        // Skill scope = workflow's skill (implicit, can't be removed)
        // UNION user-chosen extras. Rule-domain scope = every non-FAILED
        // rule_domain belonging to any allowed skill (auto-derived).
        Set<String> allowedSkills;
        if (workflowId == null || workflowId.isBlank()) {
            // No workflow yet — only honor explicit extras if set, else
            // leave unrestricted so the user can still browse.
            allowedSkills = extraSkills;
        } else {
            Set<String> combined = new HashSet<>();
            combined.add(workflowId);
            if (extraSkills != null) combined.addAll(extraSkills);
            allowedSkills = combined;
        }

        Set<String> allowedRuleDomains = null;
        if (allowedSkills != null) {
            allowedRuleDomains = new HashSet<>();
            for (String skillId : allowedSkills) {
                for (RuleDomain d : ruleDomainRepo.listBySkill(skillId)) {
                    if (!RuleDomain.STATUS_FAILED.equals(d.getStatus())) {
                        allowedRuleDomains.add(d.getId());
                    }
                }
            }
        }

        return new OvScope(
                allowedSkills,
                allowedRuleDomains,
                toSet(s.allowedDecisionTables()));
    }

    private static Set<String> toSet(List<String> list) {
        return list == null ? null : new HashSet<>(list);
    }
}
