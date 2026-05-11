package com.pods.agent.workflow.metadata;

import com.pods.agent.repository.SqlQueryLoader;
import com.pods.agent.workflow.engine.domain.ProcessState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Maintains the rolling aggregate columns on {@code agent.process_def} after
 * each materialized workflow completion. This is the data source the Phase D
 * intent matcher and the Insights UI both read from.
 *
 * <h3>What gets recorded per completion</h3>
 * <ul>
 *   <li><b>All-time counters on {@code process_def}:</b> total_runs,
 *       total_successes, total_latency_ms — atomically incremented in a single
 *       UPDATE so we never lose increments under concurrency.</li>
 *   <li><b>Derived columns:</b> success_rate = successes/runs,
 *       avg_latency_ms = total_latency/runs. Recomputed in the same UPDATE so
 *       readers always see consistent values.</li>
 *   <li><b>{@code ai_nodes_json}:</b> recomputed dynamically from
 *       {@code activity_inst} rows where {@code type='ai_reasoning'} and
 *       {@code state='complete'}. {@code invokeWhen}-skipped nodes are
 *       deliberately excluded — we only count reasoning steps that actually
 *       fired.</li>
 *   <li><b>Rolling window in {@code process_def_runs_recent}:</b> one row per
 *       run, trimmed to the most recent {@value #RECENT_WINDOW} rows per def.
 *       Lets Insights show "last N" stats without scanning historical
 *       process_inst rows.</li>
 * </ul>
 *
 * <h3>Failure isolation</h3>
 * <p>Every public entry point swallows {@link DataAccessException} and logs a
 * warning. Metadata is best-effort telemetry; a failure here must never
 * cascade into the engine's terminal-state persistence.
 */
@Service
@Slf4j
public class WorkflowMetadataService {

    /** Hard cap on rows kept in {@code process_def_runs_recent} per def. */
    public static final int RECENT_WINDOW = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;
    private final ObjectMapper objectMapper;

    public WorkflowMetadataService(NamedParameterJdbcTemplate jdbc,
                                   SqlQueryLoader sql,
                                   ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.sql = sql;
        this.objectMapper = objectMapper;
    }

    /**
     * Called by {@code WorkflowManager} after {@code persistProcessEnd} for
     * any terminal state. Suspended runs are excluded by the caller.
     *
     * @param defId       process_def.id of the materialized workflow
     * @param instanceId  process_inst.id of the run that just completed
     * @param terminal    final {@link ProcessState}
     * @param startedAt   epoch ms; from process_inst.started_at
     * @param endedAt     epoch ms; from process_inst.ended_at
     */
    public void recordRunCompletion(String defId,
                                    String instanceId,
                                    ProcessState terminal,
                                    long startedAt,
                                    long endedAt) {
        if (defId == null || defId.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        if (terminal == null) {
            return;
        }
        long latency = Math.max(0L, endedAt - startedAt);
        boolean succeeded = terminal == ProcessState.CLOSED_COMPLETED;

        // 1. Recompute the per-def AI-node fingerprint from activity_inst.
        //    Done before the UPDATE so the new value is in the same write.
        String aiNodesJson = computeAiNodesJson(defId);

        // 2. Bump counters + derived columns atomically.
        try {
            jdbc.update(sql.getQuery("WORKFLOW_PROCESS_DEF.RECORD_RUN"),
                    new MapSqlParameterSource()
                            .addValue("defId", defId)
                            .addValue("successDelta", succeeded ? 1 : 0)
                            .addValue("latency", latency)
                            .addValue("endedAt", endedAt)
                            .addValue("aiNodesJson", aiNodesJson));
        } catch (DataAccessException e) {
            log.warn("[WorkflowMetadata] failed to update process_def aggregates for def={} run={}: {}",
                    defId, instanceId, rootMessage(e));
            // No early return: still try to write the rolling window row so the
            // recent-runs view doesn't drift.
        }

        // 3. Append to rolling window + trim to last RECENT_WINDOW.
        try {
            jdbc.update(sql.getQuery("WORKFLOW_PROCESS_DEF_RUNS_RECENT.INSERT"),
                    new MapSqlParameterSource()
                            .addValue("defId", defId)
                            .addValue("runId", instanceId)
                            .addValue("succeeded", succeeded)
                            .addValue("latency", latency)
                            .addValue("completedAt", endedAt));
            jdbc.update(sql.getQuery("WORKFLOW_PROCESS_DEF_RUNS_RECENT.TRIM"),
                    new MapSqlParameterSource()
                            .addValue("defId", defId)
                            .addValue("keep", RECENT_WINDOW));
        } catch (DataAccessException e) {
            log.warn("[WorkflowMetadata] failed to append rolling-window row for def={} run={}: {}",
                    defId, instanceId, rootMessage(e));
        }
    }

    /**
     * All-time aggregate snapshot for a single definition. Returns
     * {@link Snapshot#empty(String)} when the def has no runs yet so callers
     * never have to null-check the response.
     */
    public Snapshot getSnapshot(String defId) {
        if (defId == null || defId.isBlank()) {
            return Snapshot.empty(defId);
        }
        try {
            List<Snapshot> rows = jdbc.query(
                    sql.getQuery("WORKFLOW_PROCESS_DEF.METADATA_BY_ID"),
                    new MapSqlParameterSource("id", defId),
                    (rs, n) -> new Snapshot(
                            defId,
                            rs.getInt("total_runs"),
                            rs.getInt("total_successes"),
                            rs.getLong("total_latency_ms"),
                            (Float) rs.getObject("success_rate"),
                            (Long) rs.getObject("avg_latency_ms"),
                            rs.getString("ai_nodes_json"),
                            (Long) rs.getObject("last_run_at")));
            return rows.isEmpty() ? Snapshot.empty(defId) : rows.get(0);
        } catch (DataAccessException e) {
            log.warn("[WorkflowMetadata] failed to read snapshot for def={}: {}", defId, rootMessage(e));
            return Snapshot.empty(defId);
        }
    }

    /**
     * Last-N window stats from {@code process_def_runs_recent} (default 50).
     * Useful for Insights to show "latest" success-rate vs the lifetime number.
     */
    public WindowStats getRecentWindow(String defId) {
        if (defId == null || defId.isBlank()) {
            return WindowStats.empty();
        }
        try {
            return jdbc.queryForObject(
                    sql.getQuery("WORKFLOW_PROCESS_DEF_RUNS_RECENT.WINDOW_STATS"),
                    new MapSqlParameterSource("defId", defId),
                    (rs, n) -> {
                        long runs = rs.getLong("runs");
                        long successes = rs.getLong("successes");
                        long avgLatency = rs.getLong("avg_latency_ms");
                        long lastRunAt = rs.getLong("last_run_at");
                        Double rate = runs == 0 ? null : (double) successes / runs;
                        return new WindowStats(runs, successes, rate, avgLatency, lastRunAt == 0 ? null : lastRunAt);
                    });
        } catch (DataAccessException e) {
            log.warn("[WorkflowMetadata] failed to read window stats for def={}: {}", defId, rootMessage(e));
            return WindowStats.empty();
        }
    }

    /**
     * Per-def aggregation of every {@code ai_reasoning} activity that has
     * actually fired (state=complete). Excludes invokeWhen-skipped runs, which
     * never enter the 'complete' state. Returns a JSON string ready to be
     * stored in the JSONB column.
     */
    private String computeAiNodesJson(String defId) {
        try {
            List<Map<String, Object>> rows = jdbc.query(
                    sql.getQuery("WORKFLOW_PROCESS_DEF.AI_NODES_FOR_DEF"),
                    new MapSqlParameterSource("defId", defId),
                    (rs, n) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("activityDefId", rs.getString("activity_def_id"));
                        row.put("count", rs.getLong("c"));
                        return row;
                    });
            if (rows.isEmpty()) {
                return "[]";
            }
            return objectMapper.writeValueAsString(rows);
        } catch (DataAccessException | JacksonException e) {
            log.debug("[WorkflowMetadata] could not compute ai_nodes_json for def={}: {}", defId, e.getMessage());
            return null;
        }
    }

    /**
     * True when the {@code embedding} column is populated for this def. Used
     * by the metadata API to flag rows that the Phase D intent matcher can
     * already retrieve. Returns false (not throws) when pgvector — and
     * therefore the column itself — isn't available in this deployment.
     */
    public boolean hasEmbedding(String defId) {
        if (defId == null || defId.isBlank()) return false;
        try {
            Boolean v = jdbc.queryForObject(
                    sql.getQuery("WORKFLOW_PROCESS_DEF.HAS_EMBEDDING"),
                    new MapSqlParameterSource("id", defId),
                    Boolean.class);
            return Boolean.TRUE.equals(v);
        } catch (DataAccessException e) {
            // Column may legitimately not exist (pgvector unavailable). Quiet log.
            log.trace("[WorkflowMetadata] hasEmbedding({}) failed: {}", defId, rootMessage(e));
            return false;
        }
    }

    private static String rootMessage(DataAccessException e) {
        return e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
    }

    /**
     * All-time per-def metrics. {@code aiNodes} is the parsed view of
     * {@code ai_nodes_json} so the API layer doesn't need to re-parse it.
     */
    public record Snapshot(
            String defId,
            int totalRuns,
            int totalSuccesses,
            long totalLatencyMs,
            Float successRate,
            Long avgLatencyMs,
            String aiNodesJson,
            Long lastRunAt
    ) {
        public static Snapshot empty(String defId) {
            return new Snapshot(defId, 0, 0, 0L, null, null, "[]", null);
        }

        /** Lazy parse — most readers only want a quick "did any AI nodes fire?" boolean. */
        public List<Map<String, Object>> aiNodes(ObjectMapper mapper) {
            if (aiNodesJson == null || aiNodesJson.isBlank()) return List.of();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = mapper.readValue(aiNodesJson, List.class);
                return parsed == null ? List.of() : parsed;
            } catch (JacksonException e) {
                return new ArrayList<>();
            }
        }
    }

    /** Rolling-window snapshot from {@code process_def_runs_recent}. */
    public record WindowStats(
            long runs,
            long successes,
            Double successRate,
            long avgLatencyMs,
            Long lastRunAt
    ) {
        public static WindowStats empty() {
            return new WindowStats(0L, 0L, null, 0L, null);
        }
    }
}
