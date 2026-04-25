package com.pods.agent.repository;

import com.pods.agent.domain.CostUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CostUsageRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public CostUsageRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public CostUsage save(CostUsage usage) {
        if (usage.getId() == null) usage.setId(UUID.randomUUID().toString());
        if (usage.getCreatedAt() == 0) usage.setCreatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("COST_USAGE.INSERT"), new MapSqlParameterSource()
                .addValue("id", usage.getId())
                .addValue("sessionId", usage.getSessionId())
                .addValue("turnId", usage.getTurnId())
                .addValue("providerId", usage.getProviderId())
                .addValue("modelId", usage.getModelId())
                .addValue("promptTokens", usage.getPromptTokens())
                .addValue("completionTokens", usage.getCompletionTokens())
                .addValue("totalTokens", usage.getTotalTokens())
                .addValue("estimatedCostUsd", usage.getEstimatedCostUsd())
                .addValue("createdAt", usage.getCreatedAt()));
        return usage;
    }

    public List<CostUsage> findBySessionId(String sessionId) {
        return jdbc.query(sql.getQuery("COST_USAGE.FIND_BY_SESSION"), (rs, n) -> CostUsage.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .providerId(rs.getString("provider_id"))
                .modelId(rs.getString("model_id"))
                .promptTokens(rs.getLong("prompt_tokens"))
                .completionTokens(rs.getLong("completion_tokens"))
                .totalTokens(rs.getLong("total_tokens"))
                .estimatedCostUsd(rs.getDouble("estimated_cost_usd"))
                .createdAt(rs.getLong("created_at"))
                .build(), sessionId);
    }

    public Map<String, Object> summarizeBySession(String sessionId) {
        return jdbc.query(sql.getQuery("COST_USAGE.SUMMARY_BY_SESSION"), rs -> {
            if (!rs.next()) return Map.of(
                    "sessionId", sessionId,
                    "promptTokens", 0L,
                    "completionTokens", 0L,
                    "totalTokens", 0L,
                    "estimatedCostUsd", 0.0
            );
            return Map.of(
                    "sessionId", sessionId,
                    "promptTokens", rs.getLong("prompt_tokens"),
                    "completionTokens", rs.getLong("completion_tokens"),
                    "totalTokens", rs.getLong("total_tokens"),
                    "estimatedCostUsd", rs.getDouble("estimated_cost_usd")
            );
        }, sessionId);
    }

    public int deleteFromTime(String sessionId, long createdAt) {
        return namedJdbc.update(sql.getQuery("COST_USAGE.DELETE_FROM_TIME"), new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("createdAt", createdAt));
    }
}
