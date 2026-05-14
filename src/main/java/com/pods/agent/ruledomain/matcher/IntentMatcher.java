package com.pods.agent.ruledomain.matcher;

import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

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
