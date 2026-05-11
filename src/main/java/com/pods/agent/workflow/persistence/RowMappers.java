package com.pods.agent.workflow.persistence;

import org.springframework.jdbc.core.RowMapper;

/**
 * Hand-written {@link RowMapper}s for the workflow tables. We don't use
 * {@code DataClassRowMapper} so the column-name conversions are explicit and
 * obvious — there's no auto-magic snake_case-to-camelCase bridge.
 */
final class RowMappers {

    private RowMappers() {}

    static final RowMapper<ProcessInstRow> PROCESS_INST = (rs, n) -> new ProcessInstRow(
            rs.getString("id"),
            rs.getString("def_id"),
            rs.getString("state"),
            (Long) rs.getObject("started_at"),
            (Long) rs.getObject("ended_at"),
            rs.getString("requester_id"),
            rs.getString("parent_inst_id"),
            (Long) rs.getObject("due_at"),
            rs.getString("error_class"),
            rs.getString("error_message"),
            rs.getString("result_json"));

    static final RowMapper<ActivityInstRow> ACTIVITY_INST = (rs, n) -> new ActivityInstRow(
            rs.getString("id"),
            rs.getString("inst_id"),
            rs.getString("activity_def_id"),
            rs.getString("type"),
            rs.getString("state"),
            (Long) rs.getObject("started_at"),
            (Long) rs.getObject("ended_at"),
            (Long) rs.getObject("due_at"),
            rs.getString("assignee"),
            (Integer) rs.getObject("attempt"),
            rs.getString("plugin_name"),
            rs.getString("input_snapshot"),
            rs.getString("output_snapshot"),
            rs.getString("error_class"),
            rs.getString("error_message"));

    static final RowMapper<WorkflowVariableRow> VARIABLE = (rs, n) -> new WorkflowVariableRow(
            rs.getString("id"),
            rs.getString("inst_id"),
            rs.getString("scope"),
            rs.getString("name"),
            rs.getString("java_class"),
            rs.getString("value_json"),
            (Long) rs.getObject("updated_at"));

    static final RowMapper<AuditTrailRow> AUDIT = (rs, n) -> new AuditTrailRow(
            rs.getString("id"),
            rs.getString("inst_id"),
            rs.getString("activity_inst_id"),
            rs.getString("action"),
            rs.getString("actor"),
            (Long) rs.getObject("ts"),
            rs.getString("payload_json"));

    static final RowMapper<ProcessDefRow> PROCESS_DEF = (rs, n) -> new ProcessDefRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("package_id"),
            rs.getString("description"),
            rs.getString("xpdl_json"),
            (Long) rs.getObject("created_at"),
            (Long) rs.getObject("updated_at"));
}
