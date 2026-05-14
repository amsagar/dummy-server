package com.pods.agent.ruledomain.invalidation;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.SkillRegistryService.SkillSnapshot;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Periodically scans ACTIVE rule domains and deprecates any whose skill
 * markdown hash or referenced-tool signature has drifted since compile time.
 * Also auto-deprecates domains with high recent error rates.
 *
 * Runs every 5 minutes by default. Cheap — most clusters will have a handful
 * of domains, not thousands.
 */
@Component
@Slf4j
public class RuleDomainInvalidator {

    private final RuleDomainRepository domainRepo;
    private final RuleExecutionRepository executionRepo;
    private final SkillRegistryService skillRegistryService;
    private final ToolRegistryService toolRegistryService;
    private final SkillSourceHasher skillHasher;
    private final ToolSignatureHasher toolHasher;
    private final RuleDomainProperties props;

    public RuleDomainInvalidator(RuleDomainRepository domainRepo,
                                 RuleExecutionRepository executionRepo,
                                 SkillRegistryService skillRegistryService,
                                 ToolRegistryService toolRegistryService,
                                 SkillSourceHasher skillHasher,
                                 ToolSignatureHasher toolHasher,
                                 RuleDomainProperties props) {
        this.domainRepo = domainRepo;
        this.executionRepo = executionRepo;
        this.skillRegistryService = skillRegistryService;
        this.toolRegistryService = toolRegistryService;
        this.skillHasher = skillHasher;
        this.toolHasher = toolHasher;
        this.props = props;
    }

    @Scheduled(fixedDelay = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void scan() {
        if (!props.isEnabled()) return;
        try {
            List<RuleDomain> all = domainRepo.listAll();
            long oneHourAgo = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
            for (RuleDomain d : all) {
                if (!RuleDomain.STATUS_ACTIVE.equals(d.getStatus())
                        && !RuleDomain.STATUS_DRAFT.equals(d.getStatus())) continue;

                String reason = staleReason(d, oneHourAgo);
                if (reason != null) {
                    log.info("[RuleDomainInvalidator] Deprecating domain {} ({}): {}",
                            d.getId(), d.getIntentLabel(), reason);
                    domainRepo.updateStatus(d.getId(), RuleDomain.STATUS_DEPRECATED, reason);
                }
            }
        } catch (Exception ex) {
            log.warn("Invalidator scan failed: {}", ex.getMessage());
        }
    }

    private String staleReason(RuleDomain d, long oneHourAgo) {
        Optional<SkillSnapshot> snap = skillRegistryService.getEnabledSkills().stream()
                .filter(s -> s.skill().getId().equals(d.getSkillId())
                        || s.skill().getName().equalsIgnoreCase(d.getSkillName()))
                .findFirst();
        if (snap.isEmpty()) {
            return "Skill no longer registered";
        }

        String currentMd = snap.get().files() == null ? "" :
                snap.get().files().values().stream().findFirst().orElse("");
        String currentSourceHash = skillHasher.hash(currentMd);
        if (!currentSourceHash.equals(d.getSourceHash())) {
            return "Skill source changed";
        }

        List<AgentTool> tools = toolRegistryService.getEnabledTools();
        String currentToolSig = toolHasher.hash(tools);
        if (!currentToolSig.equals(d.getToolSignature())) {
            return "Referenced tool schemas changed";
        }

        double err = executionRepo.recentErrorRate(d.getId(), oneHourAgo);
        if (err >= props.getAutoDeprecateErrorRate()) {
            return "Recent error rate " + Math.round(err * 100) + "% exceeds threshold";
        }

        return null;
    }
}
