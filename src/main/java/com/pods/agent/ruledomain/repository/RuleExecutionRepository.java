package com.pods.agent.ruledomain.repository;

import com.pods.agent.ruledomain.model.RuleExecution;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RuleExecutionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RuleExecutionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RuleExecution> ROW = (rs, i) -> RuleExecution.builder()
            .id(rs.getString("id"))
            .domainId(rs.getString("domain_id"))
            .sessionId(rs.getString("session_id"))
            .turnId(rs.getString("turn_id"))
            .flowableProcId(rs.getString("flowable_proc_id"))
            .inputsJson(rs.getString("inputs_json"))
            .outputsJson(rs.getString("outputs_json"))
            .success(rs.getBoolean("success"))
            .fallbackTriggered(rs.getBoolean("fallback_triggered"))
            .errorMessage(rs.getString("error_message"))
            .latencyMs((Integer) rs.getObject("latency_ms"))
            .createdAt(rs.getLong("created_at"))
            .build();

    public RuleExecution save(RuleExecution e) {
        if (e.getId() == null) e.setId(UUID.randomUUID().toString());
        if (e.getCreatedAt() == 0) e.setCreatedAt(System.currentTimeMillis());
        var params = new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("domain_id", e.getDomainId())
                .addValue("session_id", e.getSessionId())
                .addValue("turn_id", e.getTurnId())
                .addValue("flowable_proc_id", e.getFlowableProcId())
                .addValue("inputs_json", e.getInputsJson())
                .addValue("outputs_json", e.getOutputsJson())
                .addValue("success", e.isSuccess())
                .addValue("fallback_triggered", e.isFallbackTriggered())
                .addValue("error_message", e.getErrorMessage())
                .addValue("latency_ms", e.getLatencyMs())
                .addValue("created_at", e.getCreatedAt());

        jdbc.update("""
                INSERT INTO agent.rule_executions
                  (id, domain_id, session_id, turn_id, flowable_proc_id,
                   inputs_json, outputs_json, success, fallback_triggered,
                   error_message, latency_ms, created_at)
                VALUES
                  (:id, :domain_id, :session_id, :turn_id, :flowable_proc_id,
                   :inputs_json, :outputs_json, :success, :fallback_triggered,
                   :error_message, :latency_ms, :created_at)
                """, params);
        return e;
    }

    public List<RuleExecution> listForDomain(String domainId, int limit) {
        return jdbc.query("""
                SELECT * FROM agent.rule_executions
                WHERE domain_id = :did
                ORDER BY created_at DESC
                LIMIT :lim
                """, new MapSqlParameterSource()
                .addValue("did", domainId)
                .addValue("lim", limit),
                ROW);
    }

    public int countRecentSuccesses(String domainId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent.rule_executions
                WHERE domain_id = :did AND success = TRUE
                """, new MapSqlParameterSource("did", domainId), Integer.class);
        return n == null ? 0 : n;
    }

    public double recentErrorRate(String domainId, long sinceMillis) {
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent.rule_executions
                WHERE domain_id = :did AND created_at >= :since
                """, new MapSqlParameterSource()
                .addValue("did", domainId)
                .addValue("since", sinceMillis), Integer.class);
        if (total == null || total == 0) return 0.0;
        Integer failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent.rule_executions
                WHERE domain_id = :did AND created_at >= :since AND success = FALSE
                """, new MapSqlParameterSource()
                .addValue("did", domainId)
                .addValue("since", sinceMillis), Integer.class);
        return (failed == null ? 0 : failed) / (double) total;
    }
}
