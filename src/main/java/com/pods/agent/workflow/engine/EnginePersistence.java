package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.AuditTrailRow;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;

/**
 * Persistence façade for the workflow engine. The engine talks only to this
 * interface so unit tests can drop in a no-op or recording fake without
 * requiring a real Postgres.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link JdbcEnginePersistence} — Spring bean wired to the workflow
 *       repositories; active when the agent runs against Postgres.</li>
 *   <li>{@link NoopEnginePersistence} — fallback bean used when the agent
 *       runs in-memory (e.g. embedded tests).</li>
 * </ul>
 */
public interface EnginePersistence {

    void persistAudit(AuditTrailRow row);

    void persistProcessStart(ProcessInstRow row);

    void persistProcessEnd(String id,
                           String state,
                           Long endedAt,
                           String errorClass,
                           String errorMessage);

    /**
     * Mark the run as awaiting external input (manual approval, etc.). The
     * row's {@code ended_at} stays null so the duration counters keep ticking
     * until a real terminal write.
     */
    default void persistProcessSuspend(String id) {
        persistProcessEnd(id, "open.not_running.suspended", null, null, null);
    }

    void persistActivityStart(ActivityInstRow row);

    void persistActivityEnd(String id,
                            String state,
                            Long endedAt,
                            String outputSnapshot,
                            String errorClass,
                            String errorMessage);

    void persistVariable(WorkflowVariableRow row);

    /**
     * Mid-flow checkpoint: stores the executor's worklist + join coordinator
     * state on the {@code process_inst} row. Called after every activity
     * transition while the run is in flight; nulled out on terminal state.
     *
     * @param worklistJson  JSON-serialized list of activityDefIds queued to
     *                      run next. Null clears the checkpoint.
     * @param joinStateJson JSON-serialized {@link JoinCoordinator.Snapshot}.
     *                      Null clears.
     * @param checkpointAt  epoch millis of this checkpoint; null clears.
     */
    void persistCheckpoint(String instanceId,
                           String worklistJson,
                           String joinStateJson,
                           Long checkpointAt);

    /** Read back the latest checkpoint for an instance, or {@code null}. */
    Checkpoint loadCheckpoint(String instanceId);

    record Checkpoint(String worklistJson, String joinStateJson, Long checkpointAt) {}
}
