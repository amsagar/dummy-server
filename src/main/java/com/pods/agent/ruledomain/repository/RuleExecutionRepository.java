package com.pods.agent.ruledomain.repository;

import com.pods.agent.ruledomain.model.RuleExecution;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public java.util.Optional<RuleExecution> findByFlowableProcId(String procInstanceId) {
        if (procInstanceId == null || procInstanceId.isBlank()) return java.util.Optional.empty();
        var rows = jdbc.query("""
                SELECT * FROM agent.rule_executions
                WHERE flowable_proc_id = :pid
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource("pid", procInstanceId), ROW);
        return rows.stream().findFirst();
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

    // ── Paginated cross-rule executions browser ──────────────────────────
    // Joined with rule_domains so the UI table can show skill + rule names
    // without a per-row fetch. Filters are all optional; sort is fixed at
    // created_at DESC (newest first).

    public record ExecutionFilters(String skillId,
                                   String domainId,
                                   String ruleName,
                                   Boolean success,
                                   Long sinceMillis,
                                   Long untilMillis,
                                   int page,
                                   int pageSize) {}

    public java.util.Optional<Map<String, Object>> findEnrichedById(String execId) {
        var rows = jdbc.query(
                "SELECT e.id, e.domain_id, e.session_id, e.turn_id, e.flowable_proc_id, "
                + "       e.inputs_json, e.outputs_json, "
                + "       e.success, e.error_message, e.latency_ms, e.created_at, "
                + "       d.skill_id, d.skill_name, d.intent_label, d.rule_name, d.flowable_proc_key, d.result_key "
                + "FROM agent.rule_executions e "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                + "WHERE e.id = :id",
                new MapSqlParameterSource("id", execId),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("domainId", rs.getString("domain_id"));
                    m.put("sessionId", rs.getString("session_id"));
                    m.put("turnId", rs.getString("turn_id"));
                    m.put("flowableProcId", rs.getString("flowable_proc_id"));
                    m.put("inputsJson", rs.getString("inputs_json"));
                    m.put("outputsJson", rs.getString("outputs_json"));
                    m.put("success", rs.getBoolean("success"));
                    m.put("errorMessage", rs.getString("error_message"));
                    m.put("latencyMs", rs.getObject("latency_ms"));
                    m.put("createdAt", rs.getLong("created_at"));
                    m.put("skillId", rs.getString("skill_id"));
                    m.put("skillName", rs.getString("skill_name"));
                    m.put("intentLabel", rs.getString("intent_label"));
                    String rn = rs.getString("rule_name");
                    m.put("ruleName", rn != null ? rn : rs.getString("intent_label"));
                    m.put("flowableProcKey", rs.getString("flowable_proc_key"));
                    m.put("resultKey", rs.getString("result_key"));
                    return m;
                });
        return rows.stream().findFirst();
    }

    public PageResult findPage(ExecutionFilters f) {
        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (f.skillId() != null && !f.skillId().isBlank()) {
            where.append("AND d.skill_id = :skillId ");
            p.addValue("skillId", f.skillId());
        }
        if (f.domainId() != null && !f.domainId().isBlank()) {
            where.append("AND e.domain_id = :domainId ");
            p.addValue("domainId", f.domainId());
        }
        if (f.ruleName() != null && !f.ruleName().isBlank()) {
            where.append("AND (d.rule_name = :ruleName OR d.intent_label = :ruleName) ");
            p.addValue("ruleName", f.ruleName());
        }
        if (f.success() != null) {
            where.append("AND e.success = :success ");
            p.addValue("success", f.success());
        }
        if (f.sinceMillis() != null) {
            where.append("AND e.created_at >= :since ");
            p.addValue("since", f.sinceMillis());
        }
        if (f.untilMillis() != null) {
            where.append("AND e.created_at < :until ");
            p.addValue("until", f.untilMillis());
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.rule_executions e "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id " + where, p, Integer.class);

        int pageSize = Math.min(Math.max(f.pageSize(), 1), 200);
        int page = Math.max(f.page(), 0);
        p.addValue("limit", pageSize);
        p.addValue("offset", page * pageSize);

        List<Map<String, Object>> rows = jdbc.query(
                "SELECT e.id, e.domain_id, e.session_id, e.turn_id, e.flowable_proc_id, "
                + "       e.success, e.error_message, e.latency_ms, e.created_at, "
                + "       d.skill_id, d.skill_name, d.intent_label, d.rule_name "
                + "FROM agent.rule_executions e "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                + where
                + "ORDER BY e.created_at DESC "
                + "LIMIT :limit OFFSET :offset",
                p,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("domainId", rs.getString("domain_id"));
                    m.put("sessionId", rs.getString("session_id"));
                    m.put("turnId", rs.getString("turn_id"));
                    m.put("flowableProcId", rs.getString("flowable_proc_id"));
                    m.put("success", rs.getBoolean("success"));
                    m.put("errorMessage", rs.getString("error_message"));
                    m.put("latencyMs", rs.getObject("latency_ms"));
                    m.put("createdAt", rs.getLong("created_at"));
                    m.put("skillId", rs.getString("skill_id"));
                    m.put("skillName", rs.getString("skill_name"));
                    m.put("intentLabel", rs.getString("intent_label"));
                    String rn = rs.getString("rule_name");
                    m.put("ruleName", rn != null ? rn : rs.getString("intent_label"));
                    return m;
                });

        return new PageResult(rows, total == null ? 0 : total, page, pageSize);
    }

    public record PageResult(List<Map<String, Object>> items, int total, int page, int pageSize) {}

    // ── Analytics aggregates ─────────────────────────────────────────────

    public Map<String, Object> analyticsSummary(long sinceMillis) {
        Map<String, Object> out = new LinkedHashMap<>();

        Integer totalRules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.rule_domains",
                new MapSqlParameterSource(), Integer.class);
        Integer activeRules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.rule_domains WHERE status = 'ACTIVE'",
                new MapSqlParameterSource(), Integer.class);
        out.put("totalRules", totalRules == null ? 0 : totalRules);
        out.put("activeRules", activeRules == null ? 0 : activeRules);

        long dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000;
        Integer runs24h = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.rule_executions WHERE created_at >= :since",
                new MapSqlParameterSource("since", dayAgo), Integer.class);
        Integer runsWindow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.rule_executions WHERE created_at >= :since",
                new MapSqlParameterSource("since", sinceMillis), Integer.class);
        out.put("runs24h", runs24h == null ? 0 : runs24h);
        out.put("runsWindow", runsWindow == null ? 0 : runsWindow);

        Map<String, Object> rates = jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE success) AS ok, COUNT(*) AS total "
                + "FROM agent.rule_executions WHERE created_at >= :since",
                new MapSqlParameterSource("since", sinceMillis));
        long ok = ((Number) rates.getOrDefault("ok", 0L)).longValue();
        long total = ((Number) rates.getOrDefault("total", 0L)).longValue();
        out.put("successRate", total == 0 ? 0.0 : (double) ok / total);

        Map<String, Object> latency = jdbc.queryForMap(
                "SELECT COALESCE(AVG(latency_ms)::int, 0) AS avg_ms, "
                + "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0)::int AS p95_ms "
                + "FROM agent.rule_executions "
                + "WHERE created_at >= :since AND latency_ms IS NOT NULL",
                new MapSqlParameterSource("since", sinceMillis));
        out.put("avgLatencyMs", latency.getOrDefault("avg_ms", 0));
        out.put("p95LatencyMs", latency.getOrDefault("p95_ms", 0));
        return out;
    }

    public List<Map<String, Object>> analyticsTimeseries(long sinceMillis) {
        return jdbc.query(
                "SELECT to_char(date_trunc('day', to_timestamp(created_at/1000.0)), 'YYYY-MM-DD') AS day, "
                + "       COUNT(*) FILTER (WHERE success) AS ok, "
                + "       COUNT(*) FILTER (WHERE NOT success) AS fail "
                + "FROM agent.rule_executions WHERE created_at >= :since "
                + "GROUP BY day ORDER BY day ASC",
                new MapSqlParameterSource("since", sinceMillis),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", rs.getString("day"));
                    m.put("successCount", rs.getInt("ok"));
                    m.put("failureCount", rs.getInt("fail"));
                    return m;
                });
    }

    public List<Map<String, Object>> analyticsTopErrors(long sinceMillis, int limit) {
        return jdbc.query(
                "SELECT error_message, COUNT(*) AS cnt, MAX(created_at) AS last_seen "
                + "FROM agent.rule_executions "
                + "WHERE created_at >= :since AND success = FALSE "
                + "  AND error_message IS NOT NULL AND error_message <> '' "
                + "GROUP BY error_message ORDER BY cnt DESC LIMIT :lim",
                new MapSqlParameterSource().addValue("since", sinceMillis).addValue("lim", limit),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("errorMessage", rs.getString("error_message"));
                    m.put("count", rs.getInt("cnt"));
                    m.put("lastSeen", rs.getLong("last_seen"));
                    return m;
                });
    }

    public List<Map<String, Object>> analyticsSlowRules(long sinceMillis, int limit) {
        return jdbc.query(
                "SELECT d.id, d.skill_name, COALESCE(d.rule_name, d.intent_label) AS rule_name, "
                + "       COUNT(*) AS runs, "
                + "       COALESCE(AVG(e.latency_ms)::int, 0) AS avg_ms, "
                + "       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY e.latency_ms), 0)::int AS p95_ms "
                + "FROM agent.rule_executions e "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                + "WHERE e.created_at >= :since AND e.latency_ms IS NOT NULL "
                + "GROUP BY d.id, d.skill_name, rule_name "
                + "ORDER BY p95_ms DESC LIMIT :lim",
                new MapSqlParameterSource().addValue("since", sinceMillis).addValue("lim", limit),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("domainId", rs.getString("id"));
                    m.put("skillName", rs.getString("skill_name"));
                    m.put("ruleName", rs.getString("rule_name"));
                    m.put("runs", rs.getInt("runs"));
                    m.put("avgMs", rs.getInt("avg_ms"));
                    m.put("p95Ms", rs.getInt("p95_ms"));
                    return m;
                });
    }

    public List<Map<String, Object>> analyticsPerSkill(long sinceMillis) {
        return jdbc.query(
                "SELECT d.skill_id, d.skill_name, "
                + "       COUNT(DISTINCT d.id) AS rule_count, "
                + "       COUNT(e.id) AS runs, "
                + "       COUNT(e.id) FILTER (WHERE e.success) AS ok "
                + "FROM agent.rule_domains d "
                + "LEFT JOIN agent.rule_executions e "
                + "       ON e.domain_id = d.id AND e.created_at >= :since "
                + "GROUP BY d.skill_id, d.skill_name "
                + "ORDER BY runs DESC, d.skill_name ASC",
                new MapSqlParameterSource("since", sinceMillis),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("skillId", rs.getString("skill_id"));
                    m.put("skillName", rs.getString("skill_name"));
                    m.put("ruleCount", rs.getInt("rule_count"));
                    int runs = rs.getInt("runs");
                    int ok = rs.getInt("ok");
                    m.put("runs", runs);
                    m.put("successRate", runs == 0 ? 0.0 : (double) ok / runs);
                    return m;
                });
    }
}
