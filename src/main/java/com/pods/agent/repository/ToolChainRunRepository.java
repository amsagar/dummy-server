package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainRunRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainRunRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainRun save(ToolChainRun run) {
        if (run.getId() == null) run.setId(UUID.randomUUID().toString());
        if (run.getStartedAt() == 0) run.setStartedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_RUN.INSERT"), params(run));
        return run;
    }

    public void updateStatus(String id, String status, String outputSnapshot, String errorMessage) {
        ToolChainRun current = findById(id).orElse(null);
        long now = System.currentTimeMillis();
        Long endedAt = status.equalsIgnoreCase("running")
                || status.equalsIgnoreCase("queued")
                || status.equalsIgnoreCase("waiting_for_approval")
                ? null : now;
        Long durationMs = endedAt == null || current == null ? null : (endedAt - current.getStartedAt());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_RUN.UPDATE_STATUS"), new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("status", status)
            .addValue("endedAt", endedAt)
            .addValue("durationMs", durationMs)
            .addValue("outputSnapshot", outputSnapshot)
            .addValue("errorMessage", errorMessage));
    }

    public Optional<ToolChainRun> findById(String id) {
        var rows = jdbc.query(sql.getQuery("TOOL_CHAIN_RUN.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ToolChainRun> findByChain(String toolChainId, int limit, int offset) {
        return namedJdbc.query(sql.getQuery("TOOL_CHAIN_RUN.FIND_BY_CHAIN"),
                new MapSqlParameterSource()
                        .addValue("toolChainId", toolChainId)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, n) -> map(rs));
    }

    public List<ToolChainRun> findAll(int limit, int offset) {
        return namedJdbc.query(sql.getQuery("TOOL_CHAIN_RUN.FIND_ALL"),
                new MapSqlParameterSource()
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, n) -> map(rs));
    }

    public long countByChain(String toolChainId) {
        return namedJdbc.queryForObject(sql.getQuery("TOOL_CHAIN_RUN.COUNT_BY_CHAIN"),
                new MapSqlParameterSource().addValue("toolChainId", toolChainId), Long.class);
    }

    public long countAll() {
        Long count = jdbc.queryForObject(sql.getQuery("TOOL_CHAIN_RUN.COUNT_ALL"), Long.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> statusCounts() {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_RUN.STATUS_COUNTS"), (rs, n) -> Map.of(
                "status", rs.getString("status"),
                "count", rs.getLong("count")));
    }

    private MapSqlParameterSource params(ToolChainRun run) {
        return new MapSqlParameterSource()
                .addValue("id", run.getId())
                .addValue("toolChainId", run.getToolChainId())
                .addValue("toolChainVersionId", run.getToolChainVersionId())
                .addValue("version", run.getVersion())
                .addValue("triggerSource", run.getTriggerSource())
                .addValue("initiatedBy", run.getInitiatedBy())
                .addValue("status", run.getStatus())
                .addValue("startedAt", run.getStartedAt())
                .addValue("endedAt", run.getEndedAt())
                .addValue("durationMs", run.getDurationMs())
                .addValue("inputSnapshot", run.getInputSnapshot())
                .addValue("outputSnapshot", run.getOutputSnapshot())
                .addValue("errorMessage", run.getErrorMessage());
    }

    private ToolChainRun map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChainRun.builder()
                .id(rs.getString("id"))
                .toolChainId(rs.getString("tool_chain_id"))
                .toolChainVersionId(rs.getString("tool_chain_version_id"))
                .version(rs.getInt("version"))
                .triggerSource(rs.getString("trigger_source"))
                .initiatedBy(rs.getString("initiated_by"))
                .status(rs.getString("status"))
                .startedAt(rs.getLong("started_at"))
                .endedAt((Long) rs.getObject("ended_at"))
                .durationMs((Long) rs.getObject("duration_ms"))
                .inputSnapshot(rs.getString("input_snapshot"))
                .outputSnapshot(rs.getString("output_snapshot"))
                .errorMessage(rs.getString("error_message"))
                .build();
    }
}
