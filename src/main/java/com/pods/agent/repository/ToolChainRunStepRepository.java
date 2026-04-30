package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainRunStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainRunStepRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainRunStepRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainRunStep save(ToolChainRunStep step) {
        if (step.getId() == null) step.setId(UUID.randomUUID().toString());
        if (step.getStartedAt() == 0) step.setStartedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_STEP.INSERT"), params(step));
        return step;
    }

    public void update(ToolChainRunStep step) {
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_STEP.UPDATE"), new MapSqlParameterSource()
                .addValue("id", step.getId())
                .addValue("status", step.getStatus())
                .addValue("retryCount", step.getRetryCount())
                .addValue("outputPayload", step.getOutputPayload())
                .addValue("errorMessage", step.getErrorMessage())
                .addValue("endedAt", step.getEndedAt() == null ? System.currentTimeMillis() : step.getEndedAt()));
    }

    public List<ToolChainRunStep> findByRun(String runId) {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_STEP.FIND_BY_RUN"), (rs, n) -> map(rs), runId);
    }

    public Optional<ToolChainRunStep> findById(String id) {
        var rows = jdbc.query(sql.getQuery("TOOL_CHAIN_STEP.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ToolChainRunStep> findLatestByRunAndNode(String runId, String nodeId) {
        var rows = namedJdbc.query(sql.getQuery("TOOL_CHAIN_STEP.FIND_BY_RUN_AND_NODE"),
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("nodeId", nodeId),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> failureHotspots() {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_STEP.FAILURE_HOTSPOTS"), (rs, n) -> Map.of(
                "nodeId", rs.getString("node_id"),
                "failures", rs.getLong("failures")
        ));
    }

    public List<Map<String, Object>> performanceMetrics() {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_STEP.PERFORMANCE"), (rs, n) -> Map.of(
                "nodeId", rs.getString("node_id"),
                "avgDurationMs", rs.getLong("avg_duration_ms"),
                "executions", rs.getLong("executions")
        ));
    }

    private MapSqlParameterSource params(ToolChainRunStep step) {
        return new MapSqlParameterSource()
                .addValue("id", step.getId())
                .addValue("runId", step.getRunId())
                .addValue("nodeId", step.getNodeId())
                .addValue("nodeType", step.getNodeType())
                .addValue("toolRef", step.getToolRef())
                .addValue("branchPath", step.getBranchPath())
                .addValue("status", step.getStatus())
                .addValue("retryCount", step.getRetryCount())
                .addValue("inputPayload", step.getInputPayload())
                .addValue("outputPayload", step.getOutputPayload())
                .addValue("errorMessage", step.getErrorMessage())
                .addValue("startedAt", step.getStartedAt())
                .addValue("endedAt", step.getEndedAt());
    }

    private ToolChainRunStep map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChainRunStep.builder()
                .id(rs.getString("id"))
                .runId(rs.getString("run_id"))
                .nodeId(rs.getString("node_id"))
                .nodeType(rs.getString("node_type"))
                .toolRef(rs.getString("tool_ref"))
                .branchPath(rs.getString("branch_path"))
                .status(rs.getString("status"))
                .retryCount(rs.getInt("retry_count"))
                .inputPayload(rs.getString("input_payload"))
                .outputPayload(rs.getString("output_payload"))
                .errorMessage(rs.getString("error_message"))
                .startedAt(rs.getLong("started_at"))
                .endedAt((Long) rs.getObject("ended_at"))
                .build();
    }
}
