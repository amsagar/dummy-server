package com.pods.agent.config;

import com.pods.agent.ruledomain.repository.RuleDomainConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime feature flags + tunables for the compiled rule-domain path.
 *
 * Data is loaded from {@code agent.rule_domain_config} on startup and refreshed
 * whenever an operator saves changes through the admin UI ({@code refreshFromDb()}).
 * Defaults are conservative — feature is OFF unless a skill name appears in
 * {@code enabledSkills}, so installing the code is a no-op until an operator
 * explicitly opts a skill in.
 */
@Data
@Component
@Slf4j
public class RuleDomainProperties {

    /** Master kill switch. When false, all requests go through the existing LLM loop. */
    private boolean enabled = false;

    /** Per-skill allowlist. A skill name must appear here for compilation to be attempted. */
    private List<String> enabledSkills = List.of();

    /** Minimum cosine similarity to consider a stored intent embedding a match. */
    private double matchThreshold = 0.92;

    /** Maximum compile attempts (initial + repair retries). */
    private int maxCompileAttempts = 2;

    /** After this many successful executions, a DRAFT domain auto-promotes to ACTIVE. */
    private int promoteAfterSuccessfulRuns = 1;

    /** Shadow mode: always compile + execute even on hit, compare outputs, log divergence. */
    private boolean shadowMode = false;

    /** Error rate above which a domain auto-deprecates (last 1h). */
    private double autoDeprecateErrorRate = 0.30;

    /** Which model to use for BPMN compilation (cold path). */
    private ModelSelector compilerModel = new ModelSelector("anthropic", "claude-opus-4-5");

    /** Which model to use for final response summarization (hot path). */
    private ModelSelector summarizerModel = new ModelSelector("anthropic", "claude-haiku-4-5");

    /**
     * Which embedding model to use for intent matching. When provider/model are
     * blank, the system-default embedding model is used.
     */
    private ModelSelector embeddingModel = new ModelSelector("", "");

    /** Tool-call retry config used by ToolCallDelegate when a tool times out. */
    private ToolRetry toolRetry = new ToolRetry();

    /** Soft circuit-breaker: skip a compiled domain temporarily after this many consecutive failures. */
    private int circuitBreakerConsecutiveFailures = 3;

    /** Soft circuit-breaker: how long to skip a domain after the threshold is hit, in seconds. */
    private int circuitBreakerOpenSeconds = 300;

    private final RuleDomainConfigRepository configRepository;

    @Autowired
    public RuleDomainProperties(RuleDomainConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @PostConstruct
    public void init() {
        refreshFromDb();
    }

    /** Reload the live configuration from the database. Called on startup and after admin edits. */
    public synchronized void refreshFromDb() {
        try {
            RuleDomainConfigRepository.ConfigRow row = configRepository.getConfig();
            if (row == null) {
                log.warn("rule_domain_config row missing — using built-in defaults");
                return;
            }
            this.enabled = row.enabled();
            this.enabledSkills = parseSkills(row.enabledSkills());
            this.matchThreshold = row.matchThreshold();
            this.maxCompileAttempts = row.maxCompileAttempts();
            this.promoteAfterSuccessfulRuns = row.promoteAfterSuccessfulRuns();
            this.shadowMode = row.shadowMode();
            this.autoDeprecateErrorRate = row.autoDeprecateErrorRate();
            this.compilerModel = new ModelSelector(row.compilerProviderId(), row.compilerModelId());
            this.summarizerModel = new ModelSelector(row.summarizerProviderId(), row.summarizerModelId());
            this.embeddingModel = new ModelSelector(row.embeddingProviderId(), row.embeddingModelId());
            log.info("Loaded rule_domain_config: enabled={}, allowlist={}, compiler={}/{}, summarizer={}/{}",
                    enabled, enabledSkills, compilerModel.providerId, compilerModel.modelId,
                    summarizerModel.providerId, summarizerModel.modelId);
        } catch (Exception ex) {
            log.warn("Failed to load rule_domain_config — keeping current values: {}", ex.getMessage());
        }
    }

    private static List<String> parseSkills(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    /** Hot path is enabled for this skill. */
    public boolean isEnabledFor(String skillName) {
        if (!enabled || skillName == null) return false;
        return enabledSkills.stream().anyMatch(s -> s.equalsIgnoreCase(skillName));
    }

    /** Distinct, lower-cased copy of the allowlist for quick lookup. */
    public Set<String> normalizedAllowlist() {
        Set<String> out = new HashSet<>();
        for (String s : enabledSkills) if (s != null) out.add(s.toLowerCase());
        return Collections.unmodifiableSet(out);
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ModelSelector {
        private String providerId;
        private String modelId;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ToolRetry {
        /** Maximum attempts (initial + retries) when a tool call times out. */
        private int toolTimeoutMaxAttempts = 3;
        /** Backoff between attempts in milliseconds. Last entry is used for any remaining attempts. */
        private long[] toolTimeoutBackoffMs = {0L, 2000L, 5000L};
    }
}
