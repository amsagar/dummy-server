package com.pods.agent.ruledomain.invalidation;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.SkillRegistryService.SkillSnapshot;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final SkillRegistryService skillRegistryService;
    private final ToolRegistryService toolRegistryService;
    private final SkillSourceHasher skillHasher;
    private final ToolSignatureHasher toolHasher;
    private final RuleDomainProperties props;

    public RuleDomainInvalidator(RuleDomainRepository domainRepo,
                                 SkillRegistryService skillRegistryService,
                                 ToolRegistryService toolRegistryService,
                                 SkillSourceHasher skillHasher,
                                 ToolSignatureHasher toolHasher,
                                 RuleDomainProperties props) {
        this.domainRepo = domainRepo;
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
            for (RuleDomain d : all) {
                if (!RuleDomain.STATUS_ACTIVE.equals(d.getStatus())
                        && !RuleDomain.STATUS_DRAFT.equals(d.getStatus())) continue;

                String reason = staleReason(d);
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

    /**
     * Deprecation triggers are limited to <em>structural</em> drift: the skill
     * markdown changed, or a referenced tool's schema changed. We deliberately
     * do <b>not</b> auto-deprecate based on recent error rate — a transient
     * upstream outage shouldn't ditch a working BPMN. The soft circuit-breaker
     * in {@code RuleDomainOrchestrator} handles temporary skipping, and the
     * advisory message in {@code AgentOrchestrator} tells the user when a
     * compiled workflow has been falling back so they can investigate.
     */
    private String staleReason(RuleDomain d) {
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

        return null;
    }
}
