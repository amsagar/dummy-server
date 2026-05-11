package com.pods.agent.workflow.migration;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One-shot migration helper that backfills workflow tables from legacy
 * ToolChain tables.
 */
@Service
public class ToolChainWorkflowMigrationService {

    private final NamedParameterJdbcTemplate jdbc;

    public ToolChainWorkflowMigrationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> dryRun() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolChains", scalar("SELECT COUNT(*) FROM agent.tool_chains"));
        out.put("toolChainVersions", scalar("SELECT COUNT(*) FROM agent.tool_chain_versions"));
        out.put("toolChainRuns", scalar("SELECT COUNT(*) FROM agent.tool_chain_runs"));
        out.put("toolChainRunSteps", scalar("SELECT COUNT(*) FROM agent.tool_chain_run_steps"));
        out.put("toolChainApprovals", scalar("SELECT COUNT(*) FROM agent.tool_chain_approvals"));
        out.put("workflowDefs", scalar("SELECT COUNT(*) FROM agent.process_def"));
        out.put("workflowRuns", scalar("SELECT COUNT(*) FROM agent.process_inst"));
        out.put("workflowActivities", scalar("SELECT COUNT(*) FROM agent.activity_inst"));
        out.put("workflowAudit", scalar("SELECT COUNT(*) FROM agent.audit_trail"));
        return out;
    }

    public Map<String, Object> migrateAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processDefInserted", migrateProcessDefs());
        result.put("processInstInserted", migrateProcessInst());
        result.put("activityInstInserted", migrateActivityInst());
        result.put("auditInserted", migrateAuditRows());
        result.put("postMigrationCounts", dryRun());
        return result;
    }

    private int migrateProcessDefs() {
        String sql = """
                INSERT INTO agent.process_def (id, name, version, package_id, description, xpdl_json, created_at, updated_at)
                SELECT
                    'wf-' || tcv.id AS id,
                    tc.name AS name,
                    tcv.version::text AS version,
                    'toolchain.migration' AS package_id,
                    COALESCE(tc.description, '') || ' [migrated from toolchain v' || tcv.version::text || ']' AS description,
                    jsonb_build_object(
                        'id', 'wf-' || tcv.id,
                        'name', tc.name,
                        'version', tcv.version::text,
                        'description', COALESCE(tc.description, ''),
                        'variables', COALESCE(CAST(tcv.variables_json AS jsonb), '[]'::jsonb),
                        'activities', jsonb_build_array(
                            jsonb_build_object('id', 'start', 'name', 'Start', 'type', 'route', 'isStart', true),
                            jsonb_build_object(
                                'id', 'legacy_toolchain',
                                'name', 'Legacy ToolChain Graph',
                                'type', 'tool',
                                'pluginName', 'CodeExecPlugin',
                                'properties', jsonb_build_object('legacyGraphJson', COALESCE(tcv.graph_json, '{}'))
                            ),
                            jsonb_build_object('id', 'endNode', 'name', 'End', 'type', 'route', 'isEnd', true)
                        ),
                        'transitions', jsonb_build_array(
                            jsonb_build_object('id', 't1', 'fromActivityId', 'start', 'toActivityId', 'legacy_toolchain'),
                            jsonb_build_object('id', 't2', 'fromActivityId', 'legacy_toolchain', 'toActivityId', 'endNode')
                        )
                    ) AS xpdl_json,
                    COALESCE(tcv.created_at, EXTRACT(EPOCH FROM NOW())::bigint * 1000) AS created_at,
                    COALESCE(tcv.created_at, EXTRACT(EPOCH FROM NOW())::bigint * 1000) AS updated_at
                FROM agent.tool_chain_versions tcv
                JOIN agent.tool_chains tc ON tc.id = tcv.tool_chain_id
                ON CONFLICT (id) DO NOTHING
                """;
        return jdbc.update(sql, new MapSqlParameterSource());
    }

    private int migrateProcessInst() {
        String sql = """
                INSERT INTO agent.process_inst (id, def_id, state, started_at, ended_at, requester_id, parent_inst_id, due_at, error_class, error_message)
                SELECT
                    r.id AS id,
                    'wf-' || r.tool_chain_version_id AS def_id,
                    CASE
                        WHEN r.status = 'success' THEN 'closed.completed'
                        WHEN r.status IN ('failed', 'rejected') THEN 'closed.terminated'
                        WHEN r.status = 'cancelled' THEN 'closed.aborted'
                        WHEN r.status = 'waiting_for_approval' THEN 'open.not_running.suspended'
                        ELSE 'open.running'
                    END AS state,
                    r.started_at,
                    r.ended_at,
                    r.initiated_by AS requester_id,
                    NULL AS parent_inst_id,
                    NULL AS due_at,
                    CASE WHEN r.status IN ('failed', 'rejected') THEN 'UNCAUGHT' ELSE NULL END AS error_class,
                    r.error_message AS error_message
                FROM agent.tool_chain_runs r
                ON CONFLICT (id) DO NOTHING
                """;
        return jdbc.update(sql, new MapSqlParameterSource());
    }

    private int migrateActivityInst() {
        String sql = """
                INSERT INTO agent.activity_inst (id, inst_id, activity_def_id, type, state, started_at, ended_at, due_at, assignee, attempt, plugin_name, input_snapshot, output_snapshot, error_class, error_message)
                SELECT
                    s.id AS id,
                    s.run_id AS inst_id,
                    s.node_id AS activity_def_id,
                    CASE
                        WHEN s.node_type IN ('normal', 'tool', 'route', 'subflow') THEN s.node_type
                        ELSE 'tool'
                    END AS type,
                    CASE
                        WHEN s.status IN ('success', 'skipped') THEN 'completed'
                        WHEN s.status = 'failed' THEN 'failed'
                        WHEN s.status = 'rejected' THEN 'cancelled'
                        WHEN s.status = 'waiting_for_approval' THEN 'ready'
                        WHEN s.status = 'running' THEN 'running'
                        ELSE 'ready'
                    END AS state,
                    s.started_at,
                    s.ended_at,
                    NULL AS due_at,
                    NULL AS assignee,
                    COALESCE(s.retry_count, 0) AS attempt,
                    s.tool_ref AS plugin_name,
                    CAST(s.input_payload AS jsonb) AS input_snapshot,
                    CAST(s.output_payload AS jsonb) AS output_snapshot,
                    CASE WHEN s.status = 'failed' THEN 'UNCAUGHT' ELSE NULL END AS error_class,
                    s.error_message
                FROM agent.tool_chain_run_steps s
                ON CONFLICT (id) DO NOTHING
                """;
        return jdbc.update(sql, new MapSqlParameterSource());
    }

    private int migrateAuditRows() {
        String sql = """
                INSERT INTO agent.audit_trail (id, inst_id, activity_inst_id, action, actor, ts, payload_json)
                SELECT
                    'audit-' || s.id AS id,
                    s.run_id AS inst_id,
                    s.id AS activity_inst_id,
                    CASE
                        WHEN s.status = 'failed' THEN 'activity.failed'
                        WHEN s.status = 'running' THEN 'activity.started'
                        ELSE 'activity.completed'
                    END AS action,
                    NULL AS actor,
                    COALESCE(s.ended_at, s.started_at, EXTRACT(EPOCH FROM NOW())::bigint * 1000) AS ts,
                    jsonb_build_object(
                        'nodeId', s.node_id,
                        'nodeType', s.node_type,
                        'status', s.status,
                        'errorMessage', s.error_message
                    ) AS payload_json
                FROM agent.tool_chain_run_steps s
                ON CONFLICT (id) DO NOTHING
                """;
        return jdbc.update(sql, new MapSqlParameterSource());
    }

    private long scalar(String sql) {
        Long out = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return out == null ? 0L : out;
    }
}
