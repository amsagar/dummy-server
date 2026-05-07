package com.pods.agent.repository;

import com.pods.agent.domain.SystemToolChainProposal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SystemToolChainProposalRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public SystemToolChainProposalRepository(JdbcTemplate jdbc,
                                             NamedParameterJdbcTemplate namedJdbc,
                                             SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public SystemToolChainProposal save(SystemToolChainProposal proposal) {
        if (proposal.getId() == null) proposal.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        if (proposal.getCreatedAt() == 0) proposal.setCreatedAt(now);
        if (proposal.getUpdatedAt() == 0) proposal.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.INSERT"), params(proposal));
        return proposal;
    }

    public SystemToolChainProposal update(SystemToolChainProposal proposal) {
        proposal.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.UPDATE"), params(proposal));
        return proposal;
    }

    public Optional<SystemToolChainProposal> findById(String id) {
        var rows = jdbc.query(sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SystemToolChainProposal> findBySessionTurn(String sessionId, String turnId) {
        var rows = namedJdbc.query(
                sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.FIND_BY_SESSION_TURN"),
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("turnId", turnId),
                (rs, n) -> map(rs)
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SystemToolChainProposal> findPendingByUser(String userId) {
        return namedJdbc.query(
                sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.FIND_PENDING_BY_USER"),
                new MapSqlParameterSource().addValue("userId", userId),
                (rs, n) -> map(rs)
        );
    }

    public List<SystemToolChainProposal> findActiveByUser(String userId) {
        return namedJdbc.query(
                sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.FIND_ACTIVE_BY_USER"),
                new MapSqlParameterSource().addValue("userId", userId),
                (rs, n) -> map(rs)
        );
    }

    public List<SystemToolChainProposal> findApprovedForRecovery(int limit) {
        return namedJdbc.query(
                sql.getQuery("SYSTEM_TOOLCHAIN_PROPOSAL.FIND_APPROVED_FOR_RECOVERY"),
                new MapSqlParameterSource().addValue("limit", Math.max(1, limit)),
                (rs, n) -> map(rs)
        );
    }

    private MapSqlParameterSource params(SystemToolChainProposal proposal) {
        return new MapSqlParameterSource()
                .addValue("id", proposal.getId())
                .addValue("sessionId", proposal.getSessionId())
                .addValue("turnId", proposal.getTurnId())
                .addValue("userId", proposal.getUserId())
                .addValue("status", proposal.getStatus())
                .addValue("reason", proposal.getReason())
                .addValue("confidence", proposal.getConfidence())
                .addValue("tracePath", proposal.getTracePath())
                .addValue("durableTraceJson", blankToNull(proposal.getDurableTraceJson()))
                .addValue("userPrompt", proposal.getUserPrompt())
                .addValue("assistantResponse", proposal.getAssistantResponse())
                .addValue("modelProviderId", proposal.getModelProviderId())
                .addValue("modelId", proposal.getModelId())
                .addValue("decisionComment", proposal.getDecisionComment())
                .addValue("decidedBy", proposal.getDecidedBy())
                .addValue("decidedAt", proposal.getDecidedAt())
                .addValue("toolChainId", proposal.getToolChainId())
                .addValue("errorMessage", proposal.getErrorMessage())
                .addValue("createdAt", proposal.getCreatedAt())
                .addValue("updatedAt", proposal.getUpdatedAt());
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private SystemToolChainProposal map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return SystemToolChainProposal.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .userId(rs.getString("user_id"))
                .status(rs.getString("status"))
                .reason(rs.getString("reason"))
                .confidence(rs.getString("confidence"))
                .tracePath(rs.getString("trace_path"))
                .durableTraceJson(rs.getString("durable_trace_json"))
                .userPrompt(rs.getString("user_prompt"))
                .assistantResponse(rs.getString("assistant_response"))
                .modelProviderId(rs.getString("model_provider_id"))
                .modelId(rs.getString("model_id"))
                .decisionComment(rs.getString("decision_comment"))
                .decidedBy(rs.getString("decided_by"))
                .decidedAt((Long) rs.getObject("decided_at"))
                .toolChainId(rs.getString("tool_chain_id"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
