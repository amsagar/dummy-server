package com.pods.agent.workflow.proposal;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowProposalRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public WorkflowProposalRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public WorkflowProposal save(WorkflowProposal proposal) {
        if (proposal.getId() == null) proposal.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        if (proposal.getCreatedAt() == 0L) proposal.setCreatedAt(now);
        if (proposal.getUpdatedAt() == 0L) proposal.setUpdatedAt(now);
        jdbc.update(sql.getQuery("WORKFLOW_PROPOSAL.INSERT"), params(proposal));
        return proposal;
    }

    public WorkflowProposal update(WorkflowProposal proposal) {
        proposal.setUpdatedAt(System.currentTimeMillis());
        jdbc.update(sql.getQuery("WORKFLOW_PROPOSAL.UPDATE"), params(proposal));
        return proposal;
    }

    public Optional<WorkflowProposal> findById(String id) {
        List<WorkflowProposal> rows = jdbc.query(
                sql.getQuery("WORKFLOW_PROPOSAL.FIND_BY_ID"),
                new MapSqlParameterSource().addValue("id", id),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<WorkflowProposal> findBySessionTurn(String sessionId, String turnId) {
        List<WorkflowProposal> rows = jdbc.query(
                sql.getQuery("WORKFLOW_PROPOSAL.FIND_BY_SESSION_TURN"),
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("turnId", turnId),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<WorkflowProposal> findPendingByUser(String userId) {
        return jdbc.query(
                sql.getQuery("WORKFLOW_PROPOSAL.FIND_PENDING_BY_USER"),
                new MapSqlParameterSource().addValue("userId", userId),
                (rs, n) -> map(rs));
    }

    public List<WorkflowProposal> findActiveByUser(String userId) {
        return jdbc.query(
                sql.getQuery("WORKFLOW_PROPOSAL.FIND_ACTIVE_BY_USER"),
                new MapSqlParameterSource().addValue("userId", userId),
                (rs, n) -> map(rs));
    }

    public List<WorkflowProposal> findMaterializedByUser(String userId) {
        return jdbc.query(
                sql.getQuery("WORKFLOW_PROPOSAL.FIND_MATERIALIZED_BY_USER"),
                new MapSqlParameterSource().addValue("userId", userId),
                (rs, n) -> map(rs));
    }

    private MapSqlParameterSource params(WorkflowProposal proposal) {
        return new MapSqlParameterSource()
                .addValue("id", proposal.getId())
                .addValue("sessionId", proposal.getSessionId())
                .addValue("turnId", proposal.getTurnId())
                .addValue("userId", proposal.getUserId())
                .addValue("status", proposal.getStatus())
                .addValue("reason", proposal.getReason())
                .addValue("confidence", proposal.getConfidence())
                .addValue("intentSignature", proposal.getIntentSignature())
                .addValue("traceRef", blankToNull(proposal.getTraceRef()))
                .addValue("userPrompt", blankToNull(proposal.getUserPrompt()))
                .addValue("modelProviderId", blankToNull(proposal.getModelProviderId()))
                .addValue("modelId", blankToNull(proposal.getModelId()))
                .addValue("proposedWorkflowJson", proposal.getProposedWorkflowJson())
                .addValue("matchedToolNamesJson", blankToNull(proposal.getMatchedToolNamesJson()))
                .addValue("decisionComment", blankToNull(proposal.getDecisionComment()))
                .addValue("decidedBy", blankToNull(proposal.getDecidedBy()))
                .addValue("decidedAt", proposal.getDecidedAt())
                .addValue("materializedDefId", blankToNull(proposal.getMaterializedDefId()))
                .addValue("errorMessage", blankToNull(proposal.getErrorMessage()))
                .addValue("createdAt", proposal.getCreatedAt())
                .addValue("updatedAt", proposal.getUpdatedAt());
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private WorkflowProposal map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return WorkflowProposal.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .userId(rs.getString("user_id"))
                .status(rs.getString("status"))
                .reason(rs.getString("reason"))
                .confidence((Double) rs.getObject("confidence"))
                .intentSignature(rs.getString("intent_signature"))
                .traceRef(rs.getString("trace_ref"))
                .userPrompt(rs.getString("user_prompt"))
                .modelProviderId(rs.getString("model_provider_id"))
                .modelId(rs.getString("model_id"))
                .proposedWorkflowJson(rs.getString("proposed_workflow_json"))
                .matchedToolNamesJson(rs.getString("matched_tool_names_json"))
                .decisionComment(rs.getString("decision_comment"))
                .decidedBy(rs.getString("decided_by"))
                .decidedAt((Long) rs.getObject("decided_at"))
                .materializedDefId(rs.getString("materialized_def_id"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
