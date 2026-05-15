package com.pods.agent.ruledomain.matcher;

import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a user message can be served by an already-compiled rule
 * domain for the given skill. Embeds the message, queries pgvector for the
 * closest ACTIVE domain on this skill, returns the match only when cosine
 * similarity meets the configured threshold.
 *
 * Embedding generation can fail (model down, quota exceeded). In that case
 * we log and return empty — the caller will fall through to the cold-path
 * compile or the LLM loop.
 */
@Component
@Slf4j
public class IntentMatcher {

    private final RuleDomainRepository repo;
    private final EmbeddingProviderRouter embeddingRouter;
    private final RuleDomainProperties props;

    public IntentMatcher(RuleDomainRepository repo,
                         EmbeddingProviderRouter embeddingRouter,
                         RuleDomainProperties props) {
        this.repo = repo;
        this.embeddingRouter = embeddingRouter;
        this.props = props;
    }

    public Optional<RuleDomainRepository.Match> findMatch(String skillId, String userMessage) {
        float[] vec = embed(userMessage);
        if (vec == null) return Optional.empty();
        Optional<RuleDomainRepository.Match> match =
                repo.findBestMatch(skillId, vec, props.getMatchThreshold());
        match.ifPresent(m -> log.debug(
                "Intent match: skill={} domain={} similarity={} threshold={}",
                skillId, m.domain().getId(), m.similarity(), props.getMatchThreshold()));
        return match;
    }

    /**
     * Two-tier intent match for the domain-group → rules architecture.
     *
     * <ol>
     *   <li><b>Rule-level pass</b> — query rows with {@code match_scope='RULE'}.
     *       If the top match clears a tight threshold (default 0.95, configurable
     *       via {@link RuleDomainProperties#getMatchThreshold()} plus a small
     *       boost), return just that one rule. The user wants the narrowest
     *       possible action.</li>
     *   <li><b>Umbrella pass</b> — query rows with {@code match_scope='DOMAIN_FANOUT'}.
     *       If a synthetic umbrella row matches above the standard threshold,
     *       expand to every {@code match_scope='RULE'} sibling in the same
     *       {@code domain_group_id}. The user wants "the whole bundle."</li>
     *   <li><b>Legacy fallback</b> — if neither tier matches, fall back to the
     *       single-row {@link #findMatch} for back-compat with un-split skills.</li>
     * </ol>
     *
     * <p>Returns either a single {@link RuleMatch} (narrow target), a multi-rule
     * list (fan-out), or empty (cold path — caller compiles).
     */
    public List<RuleMatch> findMatches(String skillId, String userMessage) {
        return findMatches(skillId, userMessage, /*skipLegacyFallback*/ false);
    }

    /**
     * Variant that lets the caller suppress the legacy single-row fallback.
     * Callers with a skill that opts into the rule manifest should pass
     * {@code skipLegacyFallback = true} so old prose-compiled monolithic rows
     * (created before the skill was opted in) don't keep serving requests
     * and prevent the new trace-based per-rule BPMNs from getting compiled.
     */
    public List<RuleMatch> findMatches(String skillId, String userMessage, boolean skipLegacyFallback) {
        float[] vec = embed(userMessage);
        if (vec == null) return List.of();

        double baseThreshold = props.getMatchThreshold();
        // Narrow intents cluster tighter; require a slightly higher similarity
        // to avoid stealing the umbrella's traffic.
        double ruleThreshold = Math.min(0.99, baseThreshold + 0.03);

        // ── Pass 1: rule-level (narrow) ──
        Optional<RuleDomainRepository.Match> ruleHit =
                repo.findBestMatchByScope(skillId, RuleDomain.SCOPE_RULE, vec, ruleThreshold);
        if (ruleHit.isPresent()) {
            log.debug("Intent match (rule-level): skill={} rule={} similarity={}",
                    skillId, ruleHit.get().domain().getRuleName(), ruleHit.get().similarity());
            return List.of(RuleMatch.narrow(ruleHit.get()));
        }

        // ── Pass 2: domain-fanout (umbrella) ──
        Optional<RuleDomainRepository.Match> umbrellaHit =
                repo.findBestMatchByScope(skillId, RuleDomain.SCOPE_DOMAIN_FANOUT, vec, baseThreshold);
        if (umbrellaHit.isPresent()) {
            RuleDomain umbrella = umbrellaHit.get().domain();
            log.debug("Intent match (umbrella): skill={} group={} similarity={}",
                    skillId, umbrella.getDomainGroupName(), umbrellaHit.get().similarity());
            List<RuleDomain> rules = repo.findActiveRulesInGroup(umbrella.getDomainGroupId());
            if (rules.isEmpty()) return List.of();
            List<RuleMatch> matches = new ArrayList<>(rules.size());
            for (RuleDomain r : rules) {
                matches.add(RuleMatch.fanout(r, umbrellaHit.get().similarity()));
            }
            return matches;
        }

        // ── Pass 3: legacy single-row fallback (no domain_group_id) ──
        // Suppressed when caller indicates this skill has a manifest —
        // returning a legacy row would short-circuit the trace-based
        // compile that the skill is opted into.
        if (skipLegacyFallback) return List.of();
        Optional<RuleDomainRepository.Match> legacy = findMatch(skillId, userMessage);
        return legacy.map(m -> List.of(RuleMatch.narrow(m))).orElse(List.of());
    }

    /** One matched rule with provenance (was it a narrow rule-level hit, or
     *  came from expanding a domain-level umbrella match). */
    public record RuleMatch(RuleDomain rule, double similarity, boolean fromFanout) {
        public static RuleMatch narrow(RuleDomainRepository.Match m) {
            return new RuleMatch(m.domain(), m.similarity(), false);
        }

        public static RuleMatch fanout(RuleDomain rule, double umbrellaSimilarity) {
            return new RuleMatch(rule, umbrellaSimilarity, true);
        }
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String pid = props.getEmbeddingModel().getProviderId();
            String mid = props.getEmbeddingModel().getModelId();
            ModelRef ref;
            if (pid == null || pid.isBlank()) {
                ref = embeddingRouter.findDefault()
                        .map(mc -> new ModelRef(mc.getProviderId(), mc.getModelId()))
                        .orElse(null);
            } else {
                ref = new ModelRef(pid, mid);
            }
            if (ref == null) return null;
            EmbeddingModel model = embeddingRouter.resolve(ref);
            return model.embed(text);
        } catch (Exception ex) {
            log.warn("Embedding failed for intent matching: {}", ex.getMessage());
            return null;
        }
    }
}
