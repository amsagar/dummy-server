package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
public class ActivityInstRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public ActivityInstRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void insert(ActivityInstRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_ACTIVITY_INST.INSERT"), params(row));
    }

    public void updateState(String id,
                            String state,
                            Long endedAt,
                            String outputSnapshot,
                            String errorClass,
                            String errorMessage) {
        jdbc.update(sql.getQuery("WORKFLOW_ACTIVITY_INST.UPDATE_STATE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("state", state)
                        .addValue("endedAt", endedAt)
                        .addValue("outputSnapshot", outputSnapshot)
                        .addValue("errorClass", errorClass)
                        .addValue("errorMessage", errorMessage));
    }

    public List<ActivityInstRow> findByInstId(String instId) {
        return jdbc.query(sql.getQuery("WORKFLOW_ACTIVITY_INST.FIND_BY_INST_ID"),
                new MapSqlParameterSource("instId", instId), RowMappers.ACTIVITY_INST);
    }

    public List<ActivityInstRow> findByInstIdAndDefIdOrdered(String instId, String activityDefId) {
        return jdbc.query(sql.getQuery("WORKFLOW_ACTIVITY_INST.FIND_BY_INST_ID_AND_DEF_ID"),
                new MapSqlParameterSource()
                        .addValue("instId", instId)
                        .addValue("activityDefId", activityDefId),
                RowMappers.ACTIVITY_INST);
    }

    /**
     * Batch lookup for output snapshots: returns a map of {@code inst_id} →
     * {@code output_snapshot} JSON text for the given activity definition.
     * Used by analytics to fold per-activity payloads (e.g. {@code fetchOrder}'s
     * order JSON) back into per-run views without an N+1 query.
     */
    public Map<String, String> findOutputSnapshotsByInstIdsAndDefId(Collection<String> instIds,
                                                                   String activityDefId) {
        if (instIds == null || instIds.isEmpty()) return Map.of();
        return jdbc.query(
                sql.getQuery("WORKFLOW_ACTIVITY_INST.FIND_OUTPUT_BY_INST_IDS_AND_DEF_ID"),
                new MapSqlParameterSource()
                        .addValue("instIds", instIds)
                        .addValue("activityDefId", activityDefId),
                rs -> {
                    Map<String, String> out = new HashMap<>();
                    while (rs.next()) {
                        String snap = rs.getString("output_snapshot");
                        if (snap != null) out.put(rs.getString("inst_id"), snap);
                    }
                    return out;
                });
    }

    public java.util.List<java.util.Map<String, Object>> failureHotspots(int limit) {
        return jdbc.query(sql.getQuery("WORKFLOW_ACTIVITY_INST.FAILURE_HOTSPOTS"),
                new MapSqlParameterSource("limit", limit),
                (rs, n) -> java.util.Map.of(
                        "activityDefId", rs.getString("activity_def_id"),
                        "count", rs.getLong("c")));
    }

    public java.util.List<java.util.Map<String, Object>> performanceMetrics(int limit) {
        return jdbc.query(sql.getQuery("WORKFLOW_ACTIVITY_INST.PERFORMANCE_METRICS"),
                new MapSqlParameterSource("limit", limit),
                (rs, n) -> java.util.Map.of(
                        "activityDefId", rs.getString("activity_def_id"),
                        "avgDurationMs", rs.getObject("avg_duration_ms"),
                        "executions", rs.getLong("executions")));
    }

    public Map<String, Long> countsByType() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                sql.getQuery("WORKFLOW_ACTIVITY_INST.TYPE_COUNTS"),
                new MapSqlParameterSource());
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String type = String.valueOf(row.get("type"));
            Object c = row.get("c");
            out.put(type, c instanceof Number n ? n.longValue() : 0L);
        }
        return out;
    }

    public Map<String, Long> retryAndTimeoutStats() {
        Map<String, Object> row = jdbc.queryForMap(
                sql.getQuery("WORKFLOW_ACTIVITY_INST.RETRY_AND_TIMEOUT_STATS"),
                new MapSqlParameterSource());
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("timeouts", row.get("timeout_count") instanceof Number n1 ? n1.longValue() : 0L);
        out.put("retried", row.get("retried_count") instanceof Number n2 ? n2.longValue() : 0L);
        return out;
    }

    private static MapSqlParameterSource params(ActivityInstRow r) {
        return new MapSqlParameterSource()
                .addValue("id", r.id())
                .addValue("instId", r.instId())
                .addValue("activityDefId", r.activityDefId())
                .addValue("type", r.type())
                .addValue("state", r.state())
                .addValue("startedAt", r.startedAt())
                .addValue("endedAt", r.endedAt())
                .addValue("dueAt", r.dueAt())
                .addValue("assignee", r.assignee())
                .addValue("attempt", r.attempt())
                .addValue("pluginName", r.pluginName())
                .addValue("inputSnapshot", r.inputSnapshot())
                .addValue("outputSnapshot", r.outputSnapshot())
                .addValue("errorClass", r.errorClass())
                .addValue("errorMessage", r.errorMessage());
    }
}
