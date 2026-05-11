package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PendingApprovalRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public PendingApprovalRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void insert(PendingApprovalRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_PENDING_APPROVAL.INSERT"),
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("instId", row.instId())
                        .addValue("activityInstId", row.activityInstId())
                        .addValue("activityDefId", row.activityDefId())
                        .addValue("requestedBy", row.requestedBy())
                        .addValue("requestedAt", row.requestedAt())
                        .addValue("reason", row.reason()));
    }

    public List<PendingApprovalRow> findPending(int limit) {
        return jdbc.query(sql.getQuery("WORKFLOW_PENDING_APPROVAL.FIND_PENDING"),
                new MapSqlParameterSource("limit", limit), JOINED);
    }

    public Optional<PendingApprovalRow> findById(String id) {
        List<PendingApprovalRow> rs = jdbc.query(sql.getQuery("WORKFLOW_PENDING_APPROVAL.FIND_BY_ID"),
                new MapSqlParameterSource("id", id), BARE);
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(0));
    }

    public Optional<PendingApprovalRow> findOpenByInstance(String instId) {
        List<PendingApprovalRow> rs = jdbc.query(sql.getQuery("WORKFLOW_PENDING_APPROVAL.FIND_BY_INST"),
                new MapSqlParameterSource("instId", instId), BARE);
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(0));
    }

    public boolean decide(String id, String decidedBy, long decidedAt, String decision, String comment) {
        int rows = jdbc.update(sql.getQuery("WORKFLOW_PENDING_APPROVAL.DECIDE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("decidedBy", decidedBy)
                        .addValue("decidedAt", decidedAt)
                        .addValue("decision", decision)
                        .addValue("comment", comment));
        return rows > 0;
    }

    public long countPending() {
        Long n = jdbc.queryForObject(sql.getQuery("WORKFLOW_PENDING_APPROVAL.COUNT_PENDING"),
                new MapSqlParameterSource(), Long.class);
        return n == null ? 0L : n;
    }

    private static final RowMapper<PendingApprovalRow> BARE = (rs, i) -> new PendingApprovalRow(
            rs.getString("id"),
            rs.getString("inst_id"),
            rs.getString("activity_inst_id"),
            rs.getString("activity_def_id"),
            rs.getString("requested_by"),
            rs.getLong("requested_at"),
            rs.getString("reason"),
            rs.getString("decided_by"),
            (Long) rs.getObject("decided_at"),
            rs.getString("decision"),
            rs.getString("comment"),
            null,
            null);

    private static final RowMapper<PendingApprovalRow> JOINED = (rs, i) -> new PendingApprovalRow(
            rs.getString("id"),
            rs.getString("inst_id"),
            rs.getString("activity_inst_id"),
            rs.getString("activity_def_id"),
            rs.getString("requested_by"),
            rs.getLong("requested_at"),
            rs.getString("reason"),
            rs.getString("decided_by"),
            (Long) rs.getObject("decided_at"),
            rs.getString("decision"),
            rs.getString("comment"),
            rs.getString("def_id"),
            rs.getString("workflow_name"));
}
