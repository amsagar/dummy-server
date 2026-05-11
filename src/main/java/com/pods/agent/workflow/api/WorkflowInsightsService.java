package com.pods.agent.workflow.api;

import com.pods.agent.repository.SqlQueryLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Time-bucketed analytics for the Insights dashboard. The endpoint is
 * deliberately small — five SQL queries served as Maps so the frontend can
 * iterate quickly without a wire schema lock-in.
 */
@Service
@Slf4j
public class WorkflowInsightsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public WorkflowInsightsService(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public Map<String, Object> insights(String period, int limit) {
        Duration window = parsePeriod(period);
        long now = Instant.now().toEpochMilli();
        long fromTs = now - window.toMillis();
        MapSqlParameterSource range = new MapSqlParameterSource()
                .addValue("fromTs", fromTs)
                .addValue("toTs", now);

        Map<String, Object> summary = jdbc.query(sql.getQuery("WORKFLOW_INSIGHTS.SUMMARY"), range, rs -> {
            if (!rs.next()) return Map.of();
            return Map.of(
                    "total", rs.getLong("total"),
                    "failed", rs.getLong("failed"),
                    "completed", rs.getLong("completed"),
                    "p50Ms", nullableLong(rs, "p50_ms"),
                    "p95Ms", nullableLong(rs, "p95_ms"),
                    "p99Ms", nullableLong(rs, "p99_ms")
            );
        });

        List<Map<String, Object>> byDay = jdbc.query(sql.getQuery("WORKFLOW_INSIGHTS.BY_DAY"), range, (rs, i) -> Map.of(
                "day", rs.getString("day"),
                "total", rs.getLong("total"),
                "failed", rs.getLong("failed"),
                "completed", rs.getLong("completed"),
                "p50Ms", nullableLong(rs, "p50_ms"),
                "p95Ms", nullableLong(rs, "p95_ms")
        ));

        List<Map<String, Object>> byWorkflow = jdbc.query(
                sql.getQuery("WORKFLOW_INSIGHTS.BY_WORKFLOW"),
                range.addValue("limit", limit),
                (rs, i) -> {
                    long completed = rs.getLong("completed");
                    long perRunSecs = rs.getLong("time_saved_seconds_per_run");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("defId", rs.getString("def_id"));
                    row.put("name", rs.getString("name"));
                    row.put("total", rs.getLong("total"));
                    row.put("failed", rs.getLong("failed"));
                    row.put("completed", completed);
                    row.put("p50Ms", nullableLong(rs, "p50_ms"));
                    row.put("p95Ms", nullableLong(rs, "p95_ms"));
                    row.put("timeSavedSecondsPerRun", perRunSecs);
                    row.put("timeSavedSeconds", perRunSecs * completed);
                    return row;
                });

        List<Map<String, Object>> hotspots = jdbc.query(
                sql.getQuery("WORKFLOW_INSIGHTS.HOTSPOTS"),
                range.addValue("limit", limit),
                (rs, i) -> Map.of(
                        "activityDefId", rs.getString("activity_def_id"),
                        "pluginName", rs.getString("plugin_name") == null ? "" : rs.getString("plugin_name"),
                        "failures", rs.getLong("failures"),
                        "lastErrorClass", rs.getString("last_error_class") == null ? "" : rs.getString("last_error_class"),
                        "lastErrorMessage", rs.getString("last_error_message") == null ? "" : rs.getString("last_error_message")
                ));

        long totalTimeSaved = 0L;
        for (Map<String, Object> w : byWorkflow) {
            Object v = w.get("timeSavedSeconds");
            if (v instanceof Number n) totalTimeSaved += n.longValue();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period == null || period.isBlank() ? "7d" : period);
        out.put("fromTs", fromTs);
        out.put("toTs", now);
        out.put("summary", summary == null ? Map.of() : summary);
        out.put("byDay", byDay == null ? List.<Map<String, Object>>of() : byDay);
        out.put("byWorkflow", byWorkflow == null ? List.<Map<String, Object>>of() : byWorkflow);
        out.put("hotspots", hotspots == null ? List.<Map<String, Object>>of() : hotspots);
        out.put("totalTimeSavedSeconds", totalTimeSaved);
        return out;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Duration parsePeriod(String period) {
        if (period == null || period.isBlank()) return Duration.ofDays(7);
        return switch (period.toLowerCase()) {
            case "24h", "1d" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            case "14d" -> Duration.ofDays(14);
            case "30d" -> Duration.ofDays(30);
            case "90d" -> Duration.ofDays(90);
            case "6mo", "180d" -> Duration.ofDays(180);
            case "1y", "365d" -> Duration.ofDays(365);
            default -> {
                log.warn("[Insights] unknown period '{}' — defaulting to 7d", period);
                yield Duration.ofDays(7);
            }
        };
    }

    /** Convenience wrapper used by tests. */
    public List<Map<String, Object>> empty() { return new ArrayList<>(); }
}
