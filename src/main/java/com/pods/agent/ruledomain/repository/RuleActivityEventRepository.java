package com.pods.agent.ruledomain.repository;

import com.pods.agent.ruledomain.model.RuleActivityEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RuleActivityEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RuleActivityEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RuleActivityEvent> ROW = (rs, i) -> RuleActivityEvent.builder()
            .id(rs.getString("id"))
            .executionId(rs.getString("execution_id"))
            .processInstanceId(rs.getString("process_instance_id"))
            .activityId(rs.getString("activity_id"))
            .activityName(rs.getString("activity_name"))
            .activityType(rs.getString("activity_type"))
            .delegateBean(rs.getString("delegate_bean"))
            .iterationIndex((Integer) rs.getObject("iteration_index"))
            .inputJson(rs.getString("input_json"))
            .outputJson(rs.getString("output_json"))
            .errorCode(rs.getString("error_code"))
            .errorMessage(rs.getString("error_message"))
            .startTs(rs.getLong("start_ts"))
            .endTs((Long) rs.getObject("end_ts"))
            .durationMs((Integer) rs.getObject("duration_ms"))
            .createdAt(rs.getLong("created_at"))
            .build();

    public RuleActivityEvent save(RuleActivityEvent e) {
        if (e.getId() == null) e.setId(UUID.randomUUID().toString());
        if (e.getCreatedAt() == 0) e.setCreatedAt(System.currentTimeMillis());
        var params = new MapSqlParameterSource()
                .addValue("id", e.getId())
                .addValue("execution_id", e.getExecutionId())
                .addValue("process_instance_id", e.getProcessInstanceId())
                .addValue("activity_id", e.getActivityId())
                .addValue("activity_name", e.getActivityName())
                .addValue("activity_type", e.getActivityType())
                .addValue("delegate_bean", e.getDelegateBean())
                .addValue("iteration_index", e.getIterationIndex())
                .addValue("input_json", e.getInputJson())
                .addValue("output_json", e.getOutputJson())
                .addValue("error_code", e.getErrorCode())
                .addValue("error_message", e.getErrorMessage())
                .addValue("start_ts", e.getStartTs())
                .addValue("end_ts", e.getEndTs())
                .addValue("duration_ms", e.getDurationMs())
                .addValue("created_at", e.getCreatedAt());

        jdbc.update("""
                INSERT INTO agent.rule_activity_events
                  (id, execution_id, process_instance_id, activity_id, activity_name,
                   activity_type, delegate_bean, iteration_index, input_json, output_json,
                   error_code, error_message, start_ts, end_ts, duration_ms, created_at)
                VALUES
                  (:id, :execution_id, :process_instance_id, :activity_id, :activity_name,
                   :activity_type, :delegate_bean, :iteration_index, :input_json, :output_json,
                   :error_code, :error_message, :start_ts, :end_ts, :duration_ms, :created_at)
                """, params);
        return e;
    }

    /** Insert many at once. Used by {@code BpmnRuntime} to flush the
     *  thread-local buffer after the parent {@code rule_executions} row
     *  is committed (so the FK constraint is satisfied). */
    public void saveAll(List<RuleActivityEvent> events) {
        for (RuleActivityEvent e : events) save(e);
    }

    public List<RuleActivityEvent> listForExecution(String executionId) {
        return jdbc.query("""
                SELECT * FROM agent.rule_activity_events
                WHERE execution_id = :eid
                ORDER BY start_ts ASC
                """,
                new MapSqlParameterSource("eid", executionId),
                ROW);
    }
}
