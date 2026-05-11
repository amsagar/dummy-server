package com.pods.agent.workflow.engine;

import com.pods.agent.repository.SqlQueryLoader;
import com.pods.agent.workflow.persistence.ActivityInstRepository;
import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.AuditTrailRepository;
import com.pods.agent.workflow.persistence.AuditTrailRow;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRepository;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Spring-wired {@link EnginePersistence} backed by the workflow repositories.
 * Active by default; can be disabled with
 * {@code agent.workflow.persistence.enabled=false} which causes the
 * {@link NoopEnginePersistence} fallback to win.
 */
@Component
@ConditionalOnProperty(prefix = "agent.workflow.persistence", name = "enabled", matchIfMissing = true)
@Slf4j
public class JdbcEnginePersistence implements EnginePersistence {

    private final ProcessInstRepository processInstRepo;
    private final ActivityInstRepository activityInstRepo;
    private final WorkflowVariableRepository variableRepo;
    private final AuditTrailRepository auditRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public JdbcEnginePersistence(ProcessInstRepository processInstRepo,
                                 ActivityInstRepository activityInstRepo,
                                 WorkflowVariableRepository variableRepo,
                                 AuditTrailRepository auditRepo,
                                 NamedParameterJdbcTemplate jdbc,
                                 SqlQueryLoader sql) {
        this.processInstRepo = processInstRepo;
        this.activityInstRepo = activityInstRepo;
        this.variableRepo = variableRepo;
        this.auditRepo = auditRepo;
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public void persistAudit(AuditTrailRow row) {
        try {
            auditRepo.insert(row);
        } catch (RuntimeException e) {
            log.warn("[JdbcEnginePersistence] audit insert failed: {}", e.toString());
        }
    }

    @Override
    public void persistProcessStart(ProcessInstRow row) {
        processInstRepo.insert(row);
    }

    @Override
    public void persistProcessEnd(String id, String state, Long endedAt,
                                  String errorClass, String errorMessage) {
        processInstRepo.updateState(id, state, endedAt, errorClass, errorMessage);
    }

    @Override
    public void persistActivityStart(ActivityInstRow row) {
        activityInstRepo.insert(row);
    }

    @Override
    public void persistActivityEnd(String id, String state, Long endedAt,
                                   String outputSnapshot, String errorClass, String errorMessage) {
        activityInstRepo.updateState(id, state, endedAt, outputSnapshot, errorClass, errorMessage);
    }

    @Override
    public void persistVariable(WorkflowVariableRow row) {
        variableRepo.upsert(row);
    }

    @Override
    public void persistCheckpoint(String instanceId,
                                  String worklistJson,
                                  String joinStateJson,
                                  Long checkpointAt) {
        jdbc.update(sql.getQuery("WORKFLOW_PROCESS_INST.UPDATE_CHECKPOINT"),
                new MapSqlParameterSource()
                        .addValue("id", instanceId)
                        .addValue("worklistJson", worklistJson)
                        .addValue("joinStateJson", joinStateJson)
                        .addValue("checkpointAt", checkpointAt));
    }

    @Override
    public Checkpoint loadCheckpoint(String instanceId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(sql.getQuery("WORKFLOW_PROCESS_INST.LOAD_CHECKPOINT"),
                    new MapSqlParameterSource("id", instanceId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
        Object wl = row.get("worklist_json");
        Object js = row.get("join_state_json");
        Object ts = row.get("checkpoint_at");
        return new Checkpoint(
                wl == null ? null : wl.toString(),
                js == null ? null : js.toString(),
                ts == null ? null : ((Number) ts).longValue());
    }
}
