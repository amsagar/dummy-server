package com.pods.agent.ruledomain.api;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.ruledomain.repository.RuleDomainConfigRepository;
import com.pods.agent.ruledomain.repository.RuleDomainConfigRepository.ConfigRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin REST for the rule-domain feature toggle and tunables. Single-row config
 * stored in {@code agent.rule_domain_config}.
 *
 *   GET  /api/v1/rule-domain-config           current settings
 *   PUT  /api/v1/rule-domain-config           upsert + refresh in-memory cache
 */
@RestController
@RequestMapping("/api/v1/rule-domain-config")
public class RuleDomainConfigController {

    private final RuleDomainConfigRepository repository;
    private final RuleDomainProperties properties;

    public RuleDomainConfigController(RuleDomainConfigRepository repository,
                                      RuleDomainProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @GetMapping
    public Map<String, Object> get() {
        ConfigRow row = repository.getConfig();
        return toResponse(row);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> put(@RequestBody UpdateRequest body) {
        ConfigRow saved = repository.save(new ConfigRow(
                body.enabled,
                joinSkills(body.enabledSkills),
                body.matchThreshold,
                body.maxCompileAttempts,
                body.promoteAfterSuccessfulRuns,
                body.shadowMode,
                body.autoDeprecateErrorRate,
                nz(body.compilerProviderId, "anthropic"),
                nz(body.compilerModelId, "claude-opus-4-5"),
                nz(body.summarizerProviderId, "anthropic"),
                nz(body.summarizerModelId, "claude-haiku-4-5"),
                nz(body.embeddingProviderId, ""),
                nz(body.embeddingModelId, ""),
                0L
        ));
        // Refresh the in-memory bean so subsequent requests see the new settings.
        properties.refreshFromDb();
        return ResponseEntity.ok(toResponse(saved));
    }

    private static Map<String, Object> toResponse(ConfigRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) return out;
        out.put("enabled", row.enabled());
        out.put("enabledSkills", row.enabledSkills() == null || row.enabledSkills().isBlank()
                ? List.of()
                : List.of(row.enabledSkills().split("\\s*,\\s*")));
        out.put("matchThreshold", row.matchThreshold());
        out.put("maxCompileAttempts", row.maxCompileAttempts());
        out.put("promoteAfterSuccessfulRuns", row.promoteAfterSuccessfulRuns());
        out.put("shadowMode", row.shadowMode());
        out.put("autoDeprecateErrorRate", row.autoDeprecateErrorRate());
        out.put("compilerProviderId", row.compilerProviderId());
        out.put("compilerModelId", row.compilerModelId());
        out.put("summarizerProviderId", row.summarizerProviderId());
        out.put("summarizerModelId", row.summarizerModelId());
        out.put("embeddingProviderId", row.embeddingProviderId());
        out.put("embeddingModelId", row.embeddingModelId());
        out.put("updatedAt", row.updatedAt());
        return out;
    }

    private static String joinSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) return "";
        return skills.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
    }

    private static String nz(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    /** Request payload accepted by PUT. All fields required to keep validation simple. */
    public static class UpdateRequest {
        public boolean enabled;
        public List<String> enabledSkills;
        public double matchThreshold;
        public int maxCompileAttempts;
        public int promoteAfterSuccessfulRuns;
        public boolean shadowMode;
        public double autoDeprecateErrorRate;
        public String compilerProviderId;
        public String compilerModelId;
        public String summarizerProviderId;
        public String summarizerModelId;
        public String embeddingProviderId;
        public String embeddingModelId;
    }
}
