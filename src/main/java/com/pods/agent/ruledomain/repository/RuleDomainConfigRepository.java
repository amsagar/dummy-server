package com.pods.agent.ruledomain.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuleDomainConfigRepository {

    private static final String ID = "default";

    private final NamedParameterJdbcTemplate jdbc;

    public RuleDomainConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ConfigRow> ROW = (rs, i) -> new ConfigRow(
            rs.getBoolean("enabled"),
            rs.getString("enabled_skills"),
            rs.getDouble("match_threshold"),
            rs.getInt("max_compile_attempts"),
            rs.getInt("promote_after_successful_runs"),
            rs.getBoolean("shadow_mode"),
            rs.getDouble("auto_deprecate_error_rate"),
            rs.getString("compiler_provider_id"),
            rs.getString("compiler_model_id"),
            rs.getString("summarizer_provider_id"),
            rs.getString("summarizer_model_id"),
            rs.getString("embedding_provider_id"),
            rs.getString("embedding_model_id"),
            rs.getLong("updated_at")
    );

    /** Returns the singleton config row, or null if the table is empty (shouldn't happen — seed in model.sql). */
    public ConfigRow getConfig() {
        var rows = jdbc.query(
                "SELECT * FROM agent.rule_domain_config WHERE id = :id",
                new MapSqlParameterSource("id", ID),
                ROW);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Upserts the singleton config row. */
    public ConfigRow save(ConfigRow row) {
        long now = System.currentTimeMillis();
        var params = new MapSqlParameterSource()
                .addValue("id", ID)
                .addValue("enabled", row.enabled())
                .addValue("enabled_skills", row.enabledSkills() == null ? "" : row.enabledSkills())
                .addValue("match_threshold", row.matchThreshold())
                .addValue("max_compile_attempts", row.maxCompileAttempts())
                .addValue("promote_after_successful_runs", row.promoteAfterSuccessfulRuns())
                .addValue("shadow_mode", row.shadowMode())
                .addValue("auto_deprecate_error_rate", row.autoDeprecateErrorRate())
                .addValue("compiler_provider_id", nz(row.compilerProviderId()))
                .addValue("compiler_model_id", nz(row.compilerModelId()))
                .addValue("summarizer_provider_id", nz(row.summarizerProviderId()))
                .addValue("summarizer_model_id", nz(row.summarizerModelId()))
                .addValue("embedding_provider_id", nz(row.embeddingProviderId()))
                .addValue("embedding_model_id", nz(row.embeddingModelId()))
                .addValue("updated_at", now);

        jdbc.update("""
                INSERT INTO agent.rule_domain_config
                  (id, enabled, enabled_skills, match_threshold, max_compile_attempts,
                   promote_after_successful_runs, shadow_mode, auto_deprecate_error_rate,
                   compiler_provider_id, compiler_model_id,
                   summarizer_provider_id, summarizer_model_id,
                   embedding_provider_id, embedding_model_id, updated_at)
                VALUES
                  (:id, :enabled, :enabled_skills, :match_threshold, :max_compile_attempts,
                   :promote_after_successful_runs, :shadow_mode, :auto_deprecate_error_rate,
                   :compiler_provider_id, :compiler_model_id,
                   :summarizer_provider_id, :summarizer_model_id,
                   :embedding_provider_id, :embedding_model_id, :updated_at)
                ON CONFLICT (id) DO UPDATE SET
                  enabled = EXCLUDED.enabled,
                  enabled_skills = EXCLUDED.enabled_skills,
                  match_threshold = EXCLUDED.match_threshold,
                  max_compile_attempts = EXCLUDED.max_compile_attempts,
                  promote_after_successful_runs = EXCLUDED.promote_after_successful_runs,
                  shadow_mode = EXCLUDED.shadow_mode,
                  auto_deprecate_error_rate = EXCLUDED.auto_deprecate_error_rate,
                  compiler_provider_id = EXCLUDED.compiler_provider_id,
                  compiler_model_id = EXCLUDED.compiler_model_id,
                  summarizer_provider_id = EXCLUDED.summarizer_provider_id,
                  summarizer_model_id = EXCLUDED.summarizer_model_id,
                  embedding_provider_id = EXCLUDED.embedding_provider_id,
                  embedding_model_id = EXCLUDED.embedding_model_id,
                  updated_at = EXCLUDED.updated_at
                """, params);
        return getConfig();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** DB row projection — accessed by name from {@link com.pods.agent.config.RuleDomainProperties}. */
    public record ConfigRow(
            boolean enabled,
            String enabledSkills,
            double matchThreshold,
            int maxCompileAttempts,
            int promoteAfterSuccessfulRuns,
            boolean shadowMode,
            double autoDeprecateErrorRate,
            String compilerProviderId,
            String compilerModelId,
            String summarizerProviderId,
            String summarizerModelId,
            String embeddingProviderId,
            String embeddingModelId,
            long updatedAt
    ) {}
}
