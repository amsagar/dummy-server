package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.AuditTrailRow;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link EnginePersistence} that drops everything on the floor.
 * Active when {@link JdbcEnginePersistence} is disabled or absent (e.g. unit
 * tests of the engine that don't need a database).
 */
@Component
@ConditionalOnMissingBean(JdbcEnginePersistence.class)
public class NoopEnginePersistence implements EnginePersistence {

    @Override public void persistAudit(AuditTrailRow row) {}
    @Override public void persistProcessStart(ProcessInstRow row) {}
    @Override public void persistProcessEnd(String id, String state, Long endedAt,
                                            String errorClass, String errorMessage) {}
    @Override public void persistActivityStart(ActivityInstRow row) {}
    @Override public void persistActivityEnd(String id, String state, Long endedAt,
                                             String outputSnapshot, String errorClass, String errorMessage) {}
    @Override public void persistVariable(WorkflowVariableRow row) {}
    @Override public void persistCheckpoint(String instanceId, String worklistJson,
                                            String joinStateJson, Long checkpointAt) {}
    @Override public Checkpoint loadCheckpoint(String instanceId) { return null; }
}
