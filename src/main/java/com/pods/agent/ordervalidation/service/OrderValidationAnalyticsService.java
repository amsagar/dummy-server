package com.pods.agent.ordervalidation.service;

import com.pods.agent.domain.Skill;
import com.pods.agent.ordervalidation.model.ActivityInvocation;
import com.pods.agent.ordervalidation.model.CheckResults;
import com.pods.agent.ordervalidation.model.DashboardMetrics;
import com.pods.agent.ordervalidation.model.OrderQueue;
import com.pods.agent.ordervalidation.model.OrderValidationRun;
import com.pods.agent.ordervalidation.model.RunDetail;
import com.pods.agent.ordervalidation.model.WorkflowSummary;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-side aggregations for the order-validation-ui. There is no
 * dedicated parent table or view — every metric is derived live from
 * {@code agent.rule_executions} joined with {@code agent.rule_domains}
 * and filtered by the configured workflow's {@code skill_id}.
 *
 * <p>An "OV run" is just a group of per-rule executions that share
 * {@code (session_id, turn_id)}; for rows without a session_id (legacy
 * paths) each row is its own single-rule run keyed by {@code e.id}.
 * The synthetic run id used as the URL param is the same SQL
 * expression ({@link #OV_ID_EXPR}) so JOINs back to rule_executions
 * always work.
 */
@Service
@Slf4j
public class OrderValidationAnalyticsService {

    public static final String RULE_LEG_SEQUENCE = "leg-sequence-validation";
    public static final String RULE_SERVICEABILITY = "serviceability-validation";
    public static final String RULE_CONTAINER = "container-availability-check";

    /** SQL expression that maps a {@code rule_executions} row to its synthetic OV-run id. */
    private static final String OV_ID_EXPR =
            "(CASE WHEN e.session_id IS NOT NULL AND e.session_id <> '' "
            + "THEN e.session_id || '__' || COALESCE(e.turn_id, '') "
            + "ELSE e.id END)";

    /**
     * CTE that materializes the "ov_runs" view at query time, scoped to
     * the {@code :workflow_id} parameter. Every analytics query starts
     * with this CTE and then SELECTs from {@code ov_runs r}. Aliases
     * match the old column names so the rest of the service reads
     * exactly like it did before.
     */
    private static final String OV_RUNS_CTE =
            "WITH ov_runs AS ("
            + "  SELECT "
            + "    " + OV_ID_EXPR + " AS id, "
            + "    d.skill_id AS workflow_id, "
            + "    COALESCE("
            + "      NULLIF(MAX(NULLIF(e.inputs_json, '')::jsonb ->> 'orderId'), ''), "
            + "      'ad-hoc-' || LEFT(MIN(e.id), 8)"
            + "    ) AS order_id, "
            + "    NULL::text AS journey_type, "
            + "    NULL::integer AS leg_lines, "
            + "    'COMPLETED' AS state, "
            + "    CASE "
            + "      WHEN BOOL_AND(e.success) THEN 'clear' "
            + "      WHEN BOOL_OR(NOT e.success) THEN 'failed' "
            + "      ELSE 'review' END AS overall_status, "
            + "    MAX(CASE "
            + "          WHEN COALESCE(d.rule_name, d.intent_label) ILIKE '%leg-sequence%' "
            + "            OR COALESCE(d.rule_name, d.intent_label) ILIKE '%legsequence%' "
            + "          THEN CASE WHEN e.success THEN 'pass' ELSE 'fail' END END) AS leg_sequence_status, "
            + "    MAX(CASE "
            + "          WHEN COALESCE(d.rule_name, d.intent_label) ILIKE '%serviceability%' "
            + "          THEN CASE WHEN e.success THEN 'pass' ELSE 'fail' END END) AS serviceability_status, "
            + "    MAX(CASE "
            + "          WHEN COALESCE(d.rule_name, d.intent_label) ILIKE '%container%' "
            + "          THEN CASE WHEN e.success THEN 'pass' ELSE 'fail' END END) AS container_status, "
            + "    MAX(CASE WHEN NOT e.success THEN e.error_message END) AS error_message, "
            + "    MIN(e.created_at) AS started_at, "
            + "    MAX(e.created_at) AS ended_at, "
            + "    GREATEST((MAX(e.created_at) - MIN(e.created_at))::integer, 0) AS duration_ms, "
            + "    CASE "
            + "      WHEN MAX(e.session_id) LIKE 'test-%'   THEN 'QUICK_TEST' "
            + "      WHEN MAX(e.session_id) LIKE 'ov-run-%' THEN 'AGENT' "
            + "      ELSE 'INGESTED' END AS source "
            + "  FROM agent.rule_executions e "
            + "  JOIN agent.rule_domains d ON d.id = e.domain_id "
            + "  WHERE d.skill_id = :workflow_id "
            + "  GROUP BY " + OV_ID_EXPR + ", d.skill_id"
            + ") ";

    private final NamedParameterJdbcTemplate jdbc;
    private final SkillRepository skillRepository;
    private final RuleDomainRepository ruleDomainRepository;
    private final ObjectMapper objectMapper;

    public OrderValidationAnalyticsService(NamedParameterJdbcTemplate jdbc,
                                           SkillRepository skillRepository,
                                           RuleDomainRepository ruleDomainRepository,
                                           ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.skillRepository = skillRepository;
        this.ruleDomainRepository = ruleDomainRepository;
        this.objectMapper = objectMapper;
    }

    // ── Workflows ─────────────────────────────────────────────────────────

    public List<WorkflowSummary> listWorkflows() {
        // A "workflow" is any enabled skill that owns at least one
        // compiled rule_domain (any status) — the assistant can still
        // select the workflow while rules are still DRAFT.
        Set<String> skillsWithRules = ruleDomainRepository.listAll().stream()
                .map(RuleDomain::getSkillId)
                .collect(java.util.stream.Collectors.toSet());

        List<WorkflowSummary> out = new ArrayList<>();
        for (Skill s : skillRepository.findAll()) {
            if (!s.isEnabled()) continue;
            if (!skillsWithRules.contains(s.getId())) continue;
            int maxVersion = ruleDomainRepository.listBySkill(s.getId()).stream()
                    .mapToInt(RuleDomain::getVersion)
                    .max().orElse(1);
            out.add(new WorkflowSummary(s.getId(), s.getName(), "v" + maxVersion, s.getDescription()));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    // ── Dashboard ─────────────────────────────────────────────────────────

    public DashboardMetrics dashboard(String workflowId, Long fromTs, Long toTs) {
        long since = fromTs != null ? fromTs : 0L;
        long until = toTs != null ? toTs : Long.MAX_VALUE;
        var base = new MapSqlParameterSource()
                .addValue("workflow_id", workflowId)
                .addValue("from_ts", since)
                .addValue("to_ts", until);

        Map<String, Object> agg = jdbc.queryForMap(OV_RUNS_CTE
                + "SELECT "
                + "  COUNT(*) AS total, "
                + "  COUNT(*) FILTER (WHERE overall_status IN ('clear','review')) AS passed, "
                + "  COUNT(*) FILTER (WHERE overall_status = 'failed') AS failed, "
                + "  COALESCE(AVG(duration_ms)::int, 0) AS avg_ms "
                + "FROM ov_runs "
                + "WHERE started_at >= :from_ts AND started_at <= :to_ts",
                base);

        long total = num(agg.get("total"));
        long passed = num(agg.get("passed"));
        long failed = num(agg.get("failed"));
        double passRate = total == 0 ? 0.0 : (passed * 100.0 / total);
        Long avgMs = total == 0 ? null : num(agg.get("avg_ms"));

        DashboardMetrics.PassFailByCheck breakdown = aggregateBreakdown(base);
        List<DashboardMetrics.VolumeBucket> volumeBuckets = volumeBuckets(base);
        List<DashboardMetrics.RecentResult> recent = recentResults(workflowId, since, until, 20);

        return new DashboardMetrics(total, passRate, failed, avgMs, breakdown, volumeBuckets, recent);
    }

    private DashboardMetrics.PassFailByCheck aggregateBreakdown(MapSqlParameterSource base) {
        Map<String, Object> row = jdbc.queryForMap(OV_RUNS_CTE
                + "SELECT "
                + "  COUNT(*) FILTER (WHERE leg_sequence_status='pass') AS ls_pass, "
                + "  COUNT(*) FILTER (WHERE leg_sequence_status='fail') AS ls_fail, "
                + "  COUNT(*) FILTER (WHERE serviceability_status='pass') AS sv_pass, "
                + "  COUNT(*) FILTER (WHERE serviceability_status='fail') AS sv_fail, "
                + "  COUNT(*) FILTER (WHERE container_status='pass') AS ct_pass, "
                + "  COUNT(*) FILTER (WHERE container_status='fail') AS ct_fail, "
                + "  COUNT(*) FILTER (WHERE container_status IS NULL) AS ct_skip "
                + "FROM ov_runs "
                + "WHERE started_at >= :from_ts AND started_at <= :to_ts",
                base);
        return new DashboardMetrics.PassFailByCheck(
                num(row.get("ls_pass")), num(row.get("ls_fail")),
                num(row.get("sv_pass")), num(row.get("sv_fail")),
                num(row.get("ct_pass")), num(row.get("ct_fail")), num(row.get("ct_skip"))
        );
    }

    private List<DashboardMetrics.VolumeBucket> volumeBuckets(MapSqlParameterSource base) {
        return jdbc.query(OV_RUNS_CTE
                + "SELECT "
                + "  (EXTRACT(EPOCH FROM date_trunc('day', to_timestamp(started_at/1000.0))) * 1000)::BIGINT AS day_start, "
                + "  COUNT(*) AS total, "
                + "  COUNT(*) FILTER (WHERE overall_status='failed') AS failures "
                + "FROM ov_runs "
                + "WHERE started_at >= :from_ts AND started_at <= :to_ts "
                + "GROUP BY day_start ORDER BY day_start ASC",
                base,
                (rs, i) -> new DashboardMetrics.VolumeBucket(
                        rs.getLong("day_start"), rs.getLong("total"), rs.getLong("failures")));
    }

    private List<DashboardMetrics.RecentResult> recentResults(String workflowId, long from, long to, int limit) {
        return jdbc.query(OV_RUNS_CTE
                + "SELECT id, order_id, journey_type, "
                + "       leg_sequence_status, serviceability_status, container_status, "
                + "       overall_status, started_at, source "
                + "FROM ov_runs "
                + "WHERE started_at >= :from_ts AND started_at <= :to_ts "
                + "ORDER BY started_at DESC LIMIT :lim",
                new MapSqlParameterSource()
                        .addValue("workflow_id", workflowId)
                        .addValue("from_ts", from)
                        .addValue("to_ts", to)
                        .addValue("lim", limit),
                (rs, i) -> new DashboardMetrics.RecentResult(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("journey_type"),
                        nz(rs.getString("leg_sequence_status")),
                        nz(rs.getString("serviceability_status")),
                        nz(rs.getString("container_status")),
                        rs.getString("overall_status"),
                        rs.getLong("started_at"),
                        rs.getString("source")));
    }

    // ── Order queue ───────────────────────────────────────────────────────

    public OrderQueue.Response orderQueue(String workflowId, Long fromTs, Long toTs,
                                          String status, String search, int limit, int offset) {
        long since = fromTs != null ? fromTs : 0L;
        long until = toTs != null ? toTs : Long.MAX_VALUE;
        StringBuilder where = new StringBuilder(
                "WHERE started_at >= :from_ts AND started_at <= :to_ts ");
        var p = new MapSqlParameterSource()
                .addValue("workflow_id", workflowId)
                .addValue("from_ts", since)
                .addValue("to_ts", until);

        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            where.append("AND overall_status = :status ");
            p.addValue("status", status);
        }
        if (search != null && !search.isBlank()) {
            where.append("AND (order_id ILIKE :search OR COALESCE(journey_type,'') ILIKE :search) ");
            p.addValue("search", "%" + search + "%");
        }

        Map<String, Object> totals = jdbc.queryForMap(OV_RUNS_CTE
                + "SELECT COUNT(*) AS total, "
                + "       COUNT(*) FILTER (WHERE overall_status='clear') AS passed, "
                + "       COUNT(*) FILTER (WHERE overall_status='review') AS review, "
                + "       COUNT(*) FILTER (WHERE overall_status='failed') AS failed "
                + "FROM ov_runs " + where,
                p);

        p.addValue("lim", Math.min(Math.max(limit, 1), 200));
        p.addValue("off", Math.max(offset, 0));
        List<OrderQueue.Row> rows = jdbc.query(OV_RUNS_CTE
                + "SELECT id, order_id, journey_type, leg_lines, leg_sequence_status, "
                + "       serviceability_status, container_status, overall_status, "
                + "       started_at, ended_at, state, error_message, source "
                + "FROM ov_runs " + where
                + "ORDER BY started_at DESC LIMIT :lim OFFSET :off",
                p, (rs, i) -> new OrderQueue.Row(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("journey_type"),
                        (Integer) rs.getObject("leg_lines"),
                        nz(rs.getString("leg_sequence_status")),
                        nz(rs.getString("serviceability_status")),
                        nz(rs.getString("container_status")),
                        rs.getString("overall_status"),
                        rs.getLong("started_at"),
                        (Long) rs.getObject("ended_at"),
                        rs.getString("state"),
                        rs.getString("error_message"),
                        rs.getString("source")));

        return new OrderQueue.Response(
                num(totals.get("total")), num(totals.get("passed")),
                num(totals.get("review")), num(totals.get("failed")), rows);
    }

    // ── Run detail ────────────────────────────────────────────────────────

    /**
     * Run detail does NOT need the workflow filter to look up a single
     * row by its synthetic id — the id is unique within the data set —
     * but we still scope the CTE by the row's own skill so the per-run
     * pivots stay consistent. We resolve the run by fetching its
     * underlying rule_executions, then derive the OrderValidationRun
     * shape in Java.
     */
    public RunDetail runDetail(String instId) {
        // Pull every per-rule execution belonging to this synthetic run.
        List<Map<String, Object>> execs = jdbc.queryForList(
                "SELECT e.id, e.session_id, e.turn_id, e.outputs_json, e.error_message, "
                + "       e.success, e.created_at, "
                + "       d.rule_name, d.intent_label, d.skill_id "
                + "FROM agent.rule_executions e "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                + "WHERE " + OV_ID_EXPR + " = :ov "
                + "ORDER BY e.created_at ASC",
                new MapSqlParameterSource("ov", instId));

        if (execs.isEmpty()) return null;

        // Derive the OV-run shape from the per-rule rows.
        OrderValidationRun run = synthesizeRun(instId, execs);

        CheckResults.LegSequenceResult legSeq = null;
        List<CheckResults.ServiceabilityResult> serviceability = new ArrayList<>();
        List<CheckResults.ContainerAvailabilityResult> container = new ArrayList<>();
        for (Map<String, Object> e : execs) {
            String ruleName = stringOf(e.getOrDefault("rule_name", e.get("intent_label")));
            JsonNode outputs = parseJson(stringOf(e.get("outputs_json")));
            if (RULE_LEG_SEQUENCE.equals(ruleName)) {
                legSeq = parseLegSequence(run, outputs);
            } else if (RULE_SERVICEABILITY.equals(ruleName)) {
                serviceability.addAll(parseServiceability(run, outputs));
            } else if (RULE_CONTAINER.equals(ruleName)) {
                container.addAll(parseContainerAvailability(run, outputs));
            }
        }

        // The compiled rule's postTransform often discards the request
        // payload, so the rule's outputs_json doesn't carry origin /
        // destination / itemCode etc. We enrich the table rows here by
        // looking up the per-iteration toolCall input — which IS the
        // request payload — and merging extra fields in by lineId.
        enrichFromToolCallInputs(instId, RULE_SERVICEABILITY, serviceability,
                ServiceabilityFieldMerger.INSTANCE);
        enrichFromToolCallInputs(instId, RULE_CONTAINER, container,
                ContainerFieldMerger.INSTANCE);

        List<RunDetail.ActivityTimeline> timeline = jdbc.query(
                "SELECT activity_id, activity_type, error_code, error_message, "
                + "       start_ts, end_ts, iteration_index "
                + "FROM agent.rule_activity_events "
                + "WHERE execution_id IN ("
                + "  SELECT e.id FROM agent.rule_executions e "
                + "  WHERE " + OV_ID_EXPR + " = :ov) "
                + "ORDER BY start_ts ASC",
                new MapSqlParameterSource("ov", instId),
                (rs, i) -> new RunDetail.ActivityTimeline(
                        rs.getString("activity_id"),
                        rs.getString("activity_type"),
                        rs.getString("error_code") != null ? "failed" : "completed",
                        rs.getLong("start_ts"),
                        (Long) rs.getObject("end_ts"),
                        (Integer) rs.getObject("iteration_index"),
                        rs.getString("error_message")));

        return new RunDetail(
                run.getId(), run.getOrderId(), run.getJourneyType(),
                run.getState(), run.getOverallStatus(),
                run.getStartedAt(), run.getEndedAt(), run.getDurationMs(),
                run.getErrorClass(), run.getErrorMessage(),
                legSeq, serviceability, container, timeline);
    }

    /**
     * Returns the activity events that match the UI's high-level
     * concept ({@code evaluateLegSequence}, {@code callServiceability},
     * {@code callContainerAvailability}) within this synthetic run.
     * Each concept narrows the scope to a specific rule AND a specific
     * delegate kind so the user sees only the step they clicked on —
     * e.g. {@code callServiceability} shows the 9 HTTP calls (one per
     * leg) and nothing else, not the FEEL guards or post-transforms.
     *
     * <p>If the concept doesn't map to a known rule (an exact activity
     * id is passed instead), we fall back to a strict
     * {@code activity_id} match so existing callers still work.
     */
    public List<ActivityInvocation> runActivities(String instId, String activityDefId) {
        ConceptMatch match = resolveConcept(activityDefId);
        if (match == null) {
            // Unknown concept — strict activity_id match.
            return queryActivityEvents(instId, activityDefId, null, null, false);
        }
        // Try the narrowed query first (rule + specific delegate). If
        // that's empty — e.g. the compiler structured the rule with a
        // different delegate kind — fall back to "all events for the
        // rule" so the user still sees something useful.
        List<ActivityInvocation> rows = queryActivityEvents(
                instId, null, match.ruleName(), match.delegateBean(), match.loopedOnly());
        if (rows.isEmpty() && match.delegateBean() != null) {
            rows = queryActivityEvents(instId, null, match.ruleName(), null, false);
        }
        return rows;
    }

    private List<ActivityInvocation> queryActivityEvents(String instId,
                                                         String exactActivityId,
                                                         String ruleName,
                                                         String delegateBean,
                                                         boolean loopedOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.id, a.iteration_index, a.error_code, a.error_message, "
                + "       a.input_json, a.output_json, a.start_ts, a.end_ts, "
                + "       a.activity_id, a.delegate_bean "
                + "FROM agent.rule_activity_events a "
                + "JOIN agent.rule_executions e ON e.id = a.execution_id ");
        MapSqlParameterSource params = new MapSqlParameterSource("ov", instId);

        if (ruleName != null) {
            sql.append("JOIN agent.rule_domains d ON d.id = e.domain_id ")
               .append("WHERE ").append(OV_ID_EXPR).append(" = :ov ")
               .append("  AND COALESCE(d.rule_name, d.intent_label) = :rn ");
            params.addValue("rn", ruleName);
            if (delegateBean != null) {
                sql.append("  AND a.delegate_bean = :deleg ");
                params.addValue("deleg", delegateBean);
            }
            if (loopedOnly) {
                // The rule may contain BOTH a singleton call (e.g.
                // Get_OrderID) and a looped per-leg call. We want
                // only the looped one. Whether the loop is BPMN
                // multi-instance (loopCounter set) or FEEL-based
                // (loopCounter null), the looped call's activity_id
                // appears more times than any single-shot call's id —
                // so filter to "events whose activity_id has the
                // highest occurrence count for this rule + delegate".
                sql.append("  AND a.activity_id = ("
                        + "    SELECT a2.activity_id "
                        + "    FROM agent.rule_activity_events a2 "
                        + "    JOIN agent.rule_executions e2 ON e2.id = a2.execution_id "
                        + "    JOIN agent.rule_domains d2 ON d2.id = e2.domain_id "
                        + "    WHERE " + OV_ID_EXPR.replace("e.", "e2.") + " = :ov "
                        + "      AND COALESCE(d2.rule_name, d2.intent_label) = :rn "
                        + "      AND a2.delegate_bean = :deleg "
                        + "    GROUP BY a2.activity_id "
                        + "    ORDER BY COUNT(*) DESC, MIN(a2.start_ts) ASC "
                        + "    LIMIT 1) ");
            }
            sql.append("ORDER BY COALESCE(a.iteration_index, 0) ASC, a.start_ts ASC");
        } else {
            sql.append("WHERE ").append(OV_ID_EXPR).append(" = :ov ")
               .append("  AND a.activity_id = :act ")
               .append("ORDER BY a.start_ts ASC");
            params.addValue("act", exactActivityId);
        }

        return jdbc.query(sql.toString(), params,
                (rs, i) -> new ActivityInvocation(
                        rs.getString("id"),
                        rs.getInt("iteration_index"),
                        rs.getString("error_code") != null ? "failed" : "completed",
                        rs.getLong("start_ts"),
                        (Long) rs.getObject("end_ts"),
                        null,
                        parseJsonAsObject(rs.getString("input_json")),
                        parseJsonAsObject(rs.getString("output_json")),
                        rs.getString("error_message")));
    }

    /** Resolved (rule_name, delegate_bean, loopedOnly) for a UI concept. */
    private record ConceptMatch(String ruleName, String delegateBean, boolean loopedOnly) {}

    /**
     * Translate a UI concept identifier to a narrowed query.
     * The delegate filter pins us to the right kind of step; the
     * multi-instance filter excludes shared start-of-rule calls
     * (e.g. {@code Get_OrderID}) so per-leg/per-line iteration counts
     * are accurate:
     * <ul>
     *   <li>{@code evaluateLegSequence}  → leg-sequence rule, {@code decisionTableDelegate}, single
     *   <li>{@code callServiceability}    → serviceability rule, {@code toolCallDelegate}, multi-instance only
     *   <li>{@code callContainerAvailability} → container rule, {@code toolCallDelegate}, multi-instance only
     * </ul>
     */
    private static ConceptMatch resolveConcept(String defId) {
        if (defId == null) return null;
        String d = defId.toLowerCase();
        boolean isCall = d.startsWith("call") || d.contains("invoke");
        boolean isEvaluate = d.startsWith("evaluate") || d.contains("decision");

        if (d.contains("legsequence") || d.contains("leg-sequence") || d.contains("leg_sequence")) {
            return new ConceptMatch(RULE_LEG_SEQUENCE,
                    isEvaluate ? "decisionTableDelegate" : null,
                    false);
        }
        if (d.contains("serviceability") || d.contains("service_ability")) {
            return new ConceptMatch(RULE_SERVICEABILITY,
                    isCall ? "toolCallDelegate" : null,
                    isCall);
        }
        if (d.contains("container")) {
            return new ConceptMatch(RULE_CONTAINER,
                    isCall ? "toolCallDelegate" : null,
                    isCall);
        }
        return null;
    }

    /** Build an OrderValidationRun stub from raw rule_executions rows for the run-detail screen. */
    private OrderValidationRun synthesizeRun(String instId, List<Map<String, Object>> execs) {
        long started = Long.MAX_VALUE;
        long ended = 0;
        boolean anyFail = false;
        boolean allOk = true;
        String firstError = null;
        String orderId = null;
        for (Map<String, Object> e : execs) {
            long t = num(e.get("created_at"));
            if (t < started) started = t;
            if (t > ended) ended = t;
            Object successObj = e.get("success");
            boolean success = successObj instanceof Boolean b ? b : true;
            if (!success) anyFail = true;
            else allOk &= true;
            if (!success && firstError == null) firstError = stringOf(e.get("error_message"));
            if (orderId == null) {
                String inputs = stringOf(e.get("inputs_json"));
                if (inputs != null) {
                    try {
                        JsonNode n = objectMapper.readTree(inputs);
                        JsonNode oid = n.path("orderId");
                        if (oid.isTextual()) orderId = oid.asText();
                    } catch (Exception ignored) { /* not JSON */ }
                }
            }
        }
        String overall = anyFail ? "failed" : (allOk ? "clear" : "review");
        return OrderValidationRun.builder()
                .id(instId)
                .orderId(orderId != null ? orderId : "ad-hoc-" + instId.substring(0, Math.min(8, instId.length())))
                .state("COMPLETED")
                .overallStatus(overall)
                .startedAt(started)
                .endedAt(ended)
                .durationMs((int) Math.max(ended - started, 0))
                .errorMessage(firstError)
                .build();
    }

    // ── Per-check summary pages (leg-sequence / serviceability / container) ──

    public CheckResults.LegSequenceSummary legSequenceSummary(String workflowId, Long fromTs, Long toTs,
                                                              String search, int limit, int offset) {
        long since = fromTs != null ? fromTs : 0L;
        long until = toTs != null ? toTs : Long.MAX_VALUE;

        Map<String, Object> agg = jdbc.queryForMap(OV_RUNS_CTE
                + "SELECT COUNT(*) AS total, "
                + "       COUNT(*) FILTER (WHERE leg_sequence_status='pass') AS passed, "
                + "       COUNT(*) FILTER (WHERE leg_sequence_status='fail') AS failed "
                + "FROM ov_runs "
                + "WHERE started_at >= :fr AND started_at <= :to",
                new MapSqlParameterSource()
                        .addValue("workflow_id", workflowId)
                        .addValue("fr", since)
                        .addValue("to", until));

        long total = num(agg.get("total"));
        long passed = num(agg.get("passed"));
        long failed = num(agg.get("failed"));
        double passRate = total == 0 ? 0.0 : (passed * 100.0 / total);

        List<CheckResults.LegSequenceResult> recent = recentChecks(
                workflowId, since, until, RULE_LEG_SEQUENCE, search, limit, offset,
                this::parseLegSequence);

        return new CheckResults.LegSequenceSummary(
                total, passRate, failed,
                null, Collections.emptyList(), recent, total);
    }

    public CheckResults.ServiceabilitySummary serviceabilitySummary(String workflowId, Long fromTs, Long toTs,
                                                                    String search, int limit, int offset) {
        long since = fromTs != null ? fromTs : 0L;
        long until = toTs != null ? toTs : Long.MAX_VALUE;

        Map<String, Object> agg = jdbc.queryForMap(OV_RUNS_CTE
                + "SELECT COUNT(*) AS total, "
                + "       COUNT(*) FILTER (WHERE serviceability_status='pass') AS passed, "
                + "       COUNT(*) FILTER (WHERE serviceability_status='fail') AS failed, "
                + "       COUNT(*) FILTER (WHERE serviceability_status IS NULL) AS skipped "
                + "FROM ov_runs "
                + "WHERE started_at >= :fr AND started_at <= :to",
                new MapSqlParameterSource()
                        .addValue("workflow_id", workflowId)
                        .addValue("fr", since)
                        .addValue("to", until));

        long total = num(agg.get("total"));
        long passed = num(agg.get("passed"));
        long failed = num(agg.get("failed"));
        long skipped = num(agg.get("skipped"));
        double rate = total == 0 ? 0.0 : (passed * 100.0 / total);

        List<CheckResults.ServiceabilityResult> recent = flattenRuns(
                workflowId, since, until, RULE_SERVICEABILITY, search, limit, offset,
                this::parseServiceability);

        return new CheckResults.ServiceabilitySummary(
                total, rate, failed, skipped, Collections.emptyList(), recent, recent.size());
    }

    public CheckResults.ContainerAvailabilitySummary containerAvailabilitySummary(String workflowId,
                                                                                  Long fromTs, Long toTs,
                                                                                  String search, int limit, int offset) {
        long since = fromTs != null ? fromTs : 0L;
        long until = toTs != null ? toTs : Long.MAX_VALUE;

        Map<String, Object> agg = jdbc.queryForMap(OV_RUNS_CTE
                + "SELECT COUNT(*) AS total, "
                + "       COUNT(*) FILTER (WHERE container_status='pass') AS passed, "
                + "       COUNT(*) FILTER (WHERE container_status IS NULL) AS skipped "
                + "FROM ov_runs "
                + "WHERE started_at >= :fr AND started_at <= :to",
                new MapSqlParameterSource()
                        .addValue("workflow_id", workflowId)
                        .addValue("fr", since)
                        .addValue("to", until));

        long total = num(agg.get("total"));
        long passed = num(agg.get("passed"));
        long skipped = num(agg.get("skipped"));
        double rate = total == 0 ? 0.0 : (passed * 100.0 / total);

        List<CheckResults.ContainerAvailabilityResult> recent = flattenRuns(
                workflowId, since, until, RULE_CONTAINER, search, limit, offset,
                this::parseContainerAvailability);

        return new CheckResults.ContainerAvailabilitySummary(
                total, rate, skipped, total - passed - skipped,
                Collections.emptyList(), recent, recent.size());
    }

    // ── Parsers (defensive: fall back to nulls when shape doesn't match) ──

    private CheckResults.LegSequenceResult parseLegSequence(OrderValidationRun run, JsonNode outputs) {
        if (outputs == null) return new CheckResults.LegSequenceResult(
                run.getId(), run.getOrderId(), run.getJourneyType(),
                Collections.emptyList(), null, null, null);
        JsonNode legSeqNode = outputs.path("legSequence").isMissingNode() ? outputs : outputs.path("legSequence");
        List<String> seq = new ArrayList<>();
        JsonNode arr = legSeqNode.path("actualSequence");
        if (!arr.isArray()) arr = legSeqNode.path("actual_sequence");
        if (!arr.isArray()) arr = legSeqNode.path("sequence");
        if (arr.isArray()) for (JsonNode n : arr) seq.add(n.asText());
        Boolean valid = firstBool(legSeqNode, "valid", "matched", "isValid", "is_valid");
        return new CheckResults.LegSequenceResult(
                run.getId(), run.getOrderId(), run.getJourneyType(),
                seq,
                firstText(legSeqNode, "matchedRule", "matched_rule", "rule"),
                valid,
                firstText(legSeqNode, "message", "msg", "reason"));
    }

    private List<CheckResults.ServiceabilityResult> parseServiceability(OrderValidationRun run, JsonNode outputs) {
        if (outputs == null) return Collections.emptyList();
        JsonNode arr = outputs.path("serviceability").isArray()
                ? outputs.path("serviceability")
                : outputs;
        if (!arr.isArray()) return Collections.emptyList();
        List<CheckResults.ServiceabilityResult> out = new ArrayList<>();
        for (JsonNode row : arr) {
            out.add(new CheckResults.ServiceabilityResult(
                    run.getId(),
                    run.getOrderId(),
                    firstText(row, "lineId", "LineId", "LineIdentity", "line_id"),
                    firstText(row, "itemCode", "ItemCode", "item_code"),
                    firstText(row, "originZip", "origin", "originAddress",
                            "originZipCode", "origin_zip", "from", "fromZip", "OriginZip"),
                    firstText(row, "destinationZip", "destination", "destinationAddress",
                            "destinationZipCode", "destination_zip", "to", "toZip", "DestinationZip"),
                    firstBool(row, "isServiceable", "serviceable", "IsServiceable"),
                    firstText(row, "exceptionType", "exception", "exception_type"),
                    firstText(row, "status", "Status", "result")));
        }
        return out;
    }

    /** Try each key in order; return the first textual / numeric value found. */
    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isTextual()) {
                String s = v.asText();
                if (!s.isEmpty()) return s;
            } else if (v.isNumber()) {
                return v.asString();
            } else if (v.isBoolean()) {
                return v.asString();
            }
        }
        return null;
    }

    /** Try each key in order; return the first boolean value found, or null. */
    private static Boolean firstBool(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isBoolean()) return v.asBoolean();
            if (v.isTextual()) {
                String s = v.asText().toLowerCase();
                if ("true".equals(s)) return true;
                if ("false".equals(s)) return false;
            }
        }
        return null;
    }

    private List<CheckResults.ContainerAvailabilityResult> parseContainerAvailability(OrderValidationRun run, JsonNode outputs) {
        if (outputs == null) return Collections.emptyList();
        JsonNode arr = outputs.path("containerAvailability").isArray()
                ? outputs.path("containerAvailability")
                : outputs;
        if (!arr.isArray()) return Collections.emptyList();
        List<CheckResults.ContainerAvailabilityResult> out = new ArrayList<>();
        for (JsonNode row : arr) {
            List<String> dates = new ArrayList<>();
            JsonNode datesArr = row.path("availableDates");
            if (!datesArr.isArray()) datesArr = row.path("available_dates");
            if (!datesArr.isArray()) datesArr = row.path("dates");
            if (datesArr.isArray()) for (JsonNode d : datesArr) dates.add(d.asText());
            Boolean checked = firstBool(row, "checked", "isChecked", "is_checked");
            out.add(new CheckResults.ContainerAvailabilityResult(
                    run.getId(),
                    run.getOrderId(),
                    firstText(row, "lineId", "LineId", "LineIdentity", "line_id"),
                    firstText(row, "itemCode", "ItemCode", "item_code"),
                    checked != null && checked,
                    dates,
                    firstText(row, "skipReason", "skip_reason", "reason")));
        }
        return out;
    }

    // ── Enrichment: pull missing fields from the per-iteration toolCall input ──

    /**
     * Merges per-leg / per-line toolCall request payloads into an
     * existing parsed-result list. The compiled rule's postTransform
     * usually drops the original request (origin/destination/itemCode),
     * so we read it back from {@code rule_activity_events.input_json}
     * and match by lineId.
     */
    private <T> void enrichFromToolCallInputs(String instId, String ruleName,
                                              List<T> rows, FieldMerger<T> merger) {
        if (rows == null || rows.isEmpty()) return;
        List<Map<String, Object>> events;
        try {
            events = jdbc.queryForList(
                    "SELECT a.iteration_index, a.input_json "
                    + "FROM agent.rule_activity_events a "
                    + "JOIN agent.rule_executions e ON e.id = a.execution_id "
                    + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                    + "WHERE " + OV_ID_EXPR + " = :ov "
                    + "  AND COALESCE(d.rule_name, d.intent_label) = :rn "
                    + "  AND a.delegate_bean = 'toolCallDelegate' "
                    + "  AND a.iteration_index IS NOT NULL "
                    + "ORDER BY a.iteration_index ASC",
                    new MapSqlParameterSource("ov", instId).addValue("rn", ruleName));
        } catch (Exception ex) {
            log.warn("[OV] enrichment query failed for rule {}: {}", ruleName, ex.getMessage());
            return;
        }
        if (events.isEmpty()) return;

        // Build a lineId → input JsonNode map. lineId comes from the
        // toolCall input as a number or string — coerce to a stable key.
        Map<String, JsonNode> byLineId = new java.util.HashMap<>();
        List<JsonNode> byIndex = new ArrayList<>();
        for (Map<String, Object> ev : events) {
            JsonNode inputs = parseJson(stringOf(ev.get("input_json")));
            if (inputs == null) continue;
            byIndex.add(inputs);
            String lid = firstText(inputs, "lineId", "LineId", "LineIdentity", "line_id");
            if (lid != null) byLineId.put(lid, inputs);
        }

        for (int i = 0; i < rows.size(); i++) {
            T row = rows.get(i);
            String key = merger.lineIdOf(row);
            JsonNode inputs = key != null ? byLineId.get(key) : null;
            // Fallback: positional match by iteration order — works when
            // lineId formatting differs between input/output (e.g. scientific
            // notation vs raw digits).
            if (inputs == null && i < byIndex.size()) inputs = byIndex.get(i);
            if (inputs == null) continue;
            rows.set(i, merger.merge(row, inputs));
        }
    }

    @FunctionalInterface
    private interface FieldMerger<T> {
        T merge(T row, JsonNode toolCallInput);
        default String lineIdOf(T row) { return null; }
    }

    private static final class ServiceabilityFieldMerger
            implements FieldMerger<CheckResults.ServiceabilityResult> {
        static final ServiceabilityFieldMerger INSTANCE = new ServiceabilityFieldMerger();

        @Override
        public String lineIdOf(CheckResults.ServiceabilityResult r) { return r.lineId(); }

        @Override
        public CheckResults.ServiceabilityResult merge(CheckResults.ServiceabilityResult r, JsonNode in) {
            return new CheckResults.ServiceabilityResult(
                    r.instId(),
                    r.orderId(),
                    r.lineId(),
                    coalesce(r.itemCode(), firstText(in, "itemCode", "ItemCode", "item_code")),
                    coalesce(r.originZip(), firstText(in, "originZip", "origin", "OriginZip",
                            "originZipCode", "fromZip", "originAddress")),
                    coalesce(r.destinationZip(), firstText(in, "destinationZip", "destination",
                            "DestinationZip", "destinationZipCode", "toZip", "destinationAddress")),
                    r.isServiceable(),
                    r.exceptionType(),
                    r.status());
        }
    }

    private static final class ContainerFieldMerger
            implements FieldMerger<CheckResults.ContainerAvailabilityResult> {
        static final ContainerFieldMerger INSTANCE = new ContainerFieldMerger();

        @Override
        public String lineIdOf(CheckResults.ContainerAvailabilityResult r) { return r.lineId(); }

        @Override
        public CheckResults.ContainerAvailabilityResult merge(CheckResults.ContainerAvailabilityResult r, JsonNode in) {
            return new CheckResults.ContainerAvailabilityResult(
                    r.instId(),
                    r.orderId(),
                    r.lineId(),
                    coalesce(r.itemCode(), firstText(in, "itemCode", "ItemCode", "item_code")),
                    r.checked(),
                    r.availableDates(),
                    r.skipReason());
        }
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface SingleParser<T> {
        T parse(OrderValidationRun run, JsonNode outputs);
    }

    @FunctionalInterface
    private interface ManyParser<T> {
        List<T> parse(OrderValidationRun run, JsonNode outputs);
    }

    private <T> List<T> recentChecks(String workflowId, long from, long to, String ruleName, String search,
                                     int limit, int offset, SingleParser<T> parser) {
        StringBuilder where = new StringBuilder(
                "WHERE r.started_at >= :fr AND r.started_at <= :to "
                + "AND COALESCE(d.rule_name, d.intent_label) = :rn ");
        var p = new MapSqlParameterSource()
                .addValue("workflow_id", workflowId)
                .addValue("fr", from)
                .addValue("to", to)
                .addValue("rn", ruleName);
        if (search != null && !search.isBlank()) {
            where.append("AND (r.order_id ILIKE :s OR COALESCE(r.journey_type,'') ILIKE :s) ");
            p.addValue("s", "%" + search + "%");
        }
        p.addValue("lim", Math.min(Math.max(limit, 1), 200));
        p.addValue("off", Math.max(offset, 0));

        return jdbc.query(OV_RUNS_CTE
                + "SELECT r.id, r.order_id, r.journey_type, e.outputs_json "
                + "FROM ov_runs r "
                + "JOIN agent.rule_executions e ON " + OV_ID_EXPR + " = r.id "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                + where
                + "ORDER BY r.started_at DESC LIMIT :lim OFFSET :off",
                p, (rs, i) -> {
                    OrderValidationRun run = OrderValidationRun.builder()
                            .id(rs.getString("id"))
                            .orderId(rs.getString("order_id"))
                            .journeyType(rs.getString("journey_type"))
                            .build();
                    JsonNode outputs = parseJson(rs.getString("outputs_json"));
                    return parser.parse(run, outputs);
                });
    }

    private <T> List<T> flattenRuns(String workflowId, long from, long to, String ruleName, String search,
                                    int limit, int offset, ManyParser<T> parser) {
        StringBuilder where = new StringBuilder(
                "WHERE r.started_at >= :fr AND r.started_at <= :to "
                + "AND COALESCE(d.rule_name, d.intent_label) = :rn ");
        var p = new MapSqlParameterSource()
                .addValue("workflow_id", workflowId)
                .addValue("fr", from)
                .addValue("to", to)
                .addValue("rn", ruleName);
        if (search != null && !search.isBlank()) {
            where.append("AND (r.order_id ILIKE :s OR COALESCE(r.journey_type,'') ILIKE :s) ");
            p.addValue("s", "%" + search + "%");
        }
        p.addValue("lim", Math.min(Math.max(limit, 1), 200));
        p.addValue("off", Math.max(offset, 0));

        List<List<T>> nested = jdbc.query(OV_RUNS_CTE
                + "SELECT r.id, r.order_id, r.journey_type, e.outputs_json "
                + "FROM ov_runs r "
                + "JOIN agent.rule_executions e ON " + OV_ID_EXPR + " = r.id "
                + "JOIN agent.rule_domains d ON d.id = e.domain_id "
                + where
                + "ORDER BY r.started_at DESC LIMIT :lim OFFSET :off",
                p, (rs, i) -> {
                    OrderValidationRun run = OrderValidationRun.builder()
                            .id(rs.getString("id"))
                            .orderId(rs.getString("order_id"))
                            .journeyType(rs.getString("journey_type"))
                            .build();
                    JsonNode outputs = parseJson(rs.getString("outputs_json"));
                    return parser.parse(run, outputs);
                });
        List<T> out = new ArrayList<>();
        for (List<T> chunk : nested) out.addAll(chunk);
        return out;
    }

    private JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return objectMapper.readTree(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private Object parseJsonAsObject(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception ex) {
            return s;
        }
    }

    private static long num(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }

    private static String stringOf(Object v) {
        return v == null ? null : v.toString();
    }

    private static String nz(String s) {
        return s == null ? "unknown" : s;
    }
}
