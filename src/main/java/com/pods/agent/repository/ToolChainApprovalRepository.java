package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainApproval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainApprovalRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainApprovalRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainApproval save(ToolChainApproval approval) {
        if (approval.getId() == null) approval.setId(UUID.randomUUID().toString());
        if (approval.getCreatedAt() == 0) approval.setCreatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_APPROVAL.INSERT"), new MapSqlParameterSource()
                .addValue("id", approval.getId())
                .addValue("runId", approval.getRunId())
                .addValue("stepId", approval.getStepId())
                .addValue("nodeId", approval.getNodeId())
                .addValue("requestId", approval.getRequestId())
                .addValue("approvalGroup", approval.getApprovalGroup())
                .addValue("prompt", approval.getPrompt())
                .addValue("status", approval.getStatus())
                .addValue("decisionBy", approval.getDecisionBy())
                .addValue("decisionComment", approval.getDecisionComment())
                .addValue("createdAt", approval.getCreatedAt())
                .addValue("decidedAt", approval.getDecidedAt()));
        return approval;
    }

    public void decide(String requestId, String status, String decisionBy, String comment) {
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_APPROVAL.DECIDE"), new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("status", status)
                .addValue("decisionBy", decisionBy)
                .addValue("decisionComment", comment)
                .addValue("decidedAt", System.currentTimeMillis()));
    }

    public List<ToolChainApproval> findPending() {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_APPROVAL.FIND_PENDING"), (rs, n) -> map(rs));
    }

    public List<ToolChainApproval> findByRun(String runId) {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_APPROVAL.FIND_BY_RUN"), (rs, n) -> map(rs), runId);
    }

    public Optional<ToolChainApproval> findByRequestId(String requestId) {
        var rows = jdbc.query(sql.getQuery("TOOL_CHAIN_APPROVAL.FIND_BY_REQUEST_ID"), (rs, n) -> map(rs), requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private ToolChainApproval map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChainApproval.builder()
                .id(rs.getString("id"))
                .runId(rs.getString("run_id"))
                .stepId(rs.getString("step_id"))
                .nodeId(rs.getString("node_id"))
                .requestId(rs.getString("request_id"))
                .approvalGroup(rs.getString("approval_group"))
                .prompt(rs.getString("prompt"))
                .status(rs.getString("status"))
                .decisionBy(rs.getString("decision_by"))
                .decisionComment(rs.getString("decision_comment"))
                .createdAt(rs.getLong("created_at"))
                .decidedAt((Long) rs.getObject("decided_at"))
                .build();
    }
}
