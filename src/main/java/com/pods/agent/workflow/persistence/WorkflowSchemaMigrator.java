package com.pods.agent.workflow.persistence;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent in-process migrations for the workflow engine schema.
 *
 * <p>The DDL files in {@code src/main/resources/db/} are applied manually
 * (psql) and protected by {@code CREATE TABLE IF NOT EXISTS}, so changes to
 * existing tables (especially CHECK constraints) never reach a database that
 * was created from an earlier version of the schema. This component patches
 * the constraints we know we ship at startup, so already-deployed installs do
 * not need a manual SQL run after each engine upgrade.
 *
 * <p>Each migration here is rewritten so it is safe to run on every boot:
 * <ul>
 *   <li>{@code DROP CONSTRAINT IF EXISTS} so re-runs are no-ops.</li>
 *   <li>{@code ADD CONSTRAINT} with the canonical predicate.</li>
 * </ul>
 *
 * <p>Failures are logged at WARN and swallowed — we never want a startup
 * migration to refuse to boot the application. Symptoms (e.g. constraint
 * violations) will surface clearly at runtime if a migration didn't take.
 */
@Component
@Slf4j
public class WorkflowSchemaMigrator {

    private final JdbcTemplate jdbc;

    public WorkflowSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        ensureActivityInstTypeCheck();
        ensurePgVectorExtension();
        ensureProcessDefMetadataColumns();
        ensureProcessDefRunsRecentTable();
    }

    /**
     * The {@code activity_inst.type} CHECK constraint was originally
     * {@code ('normal','tool','route','subflow')}. Loop activities
     * ({@code foreach}, {@code while}, {@code batch}) and the explicit AI
     * reasoning node ({@code ai_reasoning}) were added later. Older installs
     * still carry an outdated constraint — this widens it to the current
     * canonical set.
     */
    private void ensureActivityInstTypeCheck() {
        try {
            jdbc.execute("ALTER TABLE agent.activity_inst "
                    + "DROP CONSTRAINT IF EXISTS activity_inst_type_check");
            jdbc.execute("ALTER TABLE agent.activity_inst "
                    + "ADD CONSTRAINT activity_inst_type_check "
                    + "CHECK (type IN ('normal','tool','route','subflow','foreach','while','batch','ai_reasoning'))");
            log.info("[WorkflowSchemaMigrator] activity_inst_type_check synced "
                    + "(loop + ai_reasoning types enabled)");
        } catch (DataAccessException e) {
            log.warn("[WorkflowSchemaMigrator] failed to sync activity_inst_type_check: {}",
                    e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * pgvector enables the {@code embedding} column on {@code process_def}
     * (added by {@link #ensureProcessDefMetadataColumns}). Failure to install
     * the extension is non-fatal: the embedding column simply won't be added
     * and the intent matcher (Phase D) will fall back to its non-vector path.
     * Tracked separately via {@link #pgvectorAvailable} so dependent steps can
     * skip cleanly.
     */
    private boolean pgvectorAvailable;

    private void ensurePgVectorExtension() {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            pgvectorAvailable = true;
            log.info("[WorkflowSchemaMigrator] pgvector extension available");
        } catch (DataAccessException e) {
            pgvectorAvailable = false;
            log.warn("[WorkflowSchemaMigrator] pgvector extension unavailable; embedding-based intent matching will be disabled. Cause: {}",
                    e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * Adds the rolling-aggregate columns the {@code WorkflowMetadataService}
     * keeps in sync after every process completion, plus the {@code embedding}
     * column for Phase D intent matching. All ALTERs are idempotent so the
     * migration is safe to re-run on every boot.
     *
     * <ul>
     *   <li>{@code total_runs}, {@code total_successes}, {@code total_latency_ms}
     *       — raw counters; never reset.</li>
     *   <li>{@code success_rate} (REAL), {@code avg_latency_ms} (BIGINT) —
     *       derived columns; recomputed by the aggregator after each run.</li>
     *   <li>{@code ai_nodes_json} (JSONB) — list of
     *       {@code [{activityDefId, count}, ...]} for every {@code ai_reasoning}
     *       activity that has actually fired (i.e. wasn't skipped via
     *       {@code invokeWhen}). Recomputed per completion.</li>
     *   <li>{@code last_run_at} (BIGINT) — epoch ms of the most recent
     *       completion; lets the UI show staleness.</li>
     *   <li>{@code embedding} (vector(1536)) — cosine-similarity target for
     *       intent retrieval. Only added when pgvector is available.</li>
     * </ul>
     */
    private void ensureProcessDefMetadataColumns() {
        try {
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS total_runs INTEGER NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS total_successes INTEGER NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS total_latency_ms BIGINT NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS success_rate REAL");
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS avg_latency_ms BIGINT");
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS ai_nodes_json JSONB");
            jdbc.execute("ALTER TABLE agent.process_def "
                    + "ADD COLUMN IF NOT EXISTS last_run_at BIGINT");
            log.info("[WorkflowSchemaMigrator] process_def metadata columns synced "
                    + "(total_runs/successes/latency, success_rate, avg_latency_ms, ai_nodes_json, last_run_at)");
        } catch (DataAccessException e) {
            log.warn("[WorkflowSchemaMigrator] failed to add process_def metadata columns: {}",
                    e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
            return;
        }

        if (pgvectorAvailable) {
            try {
                jdbc.execute("ALTER TABLE agent.process_def "
                        + "ADD COLUMN IF NOT EXISTS embedding vector(1536)");
                log.info("[WorkflowSchemaMigrator] process_def.embedding column synced (vector(1536))");
            } catch (DataAccessException e) {
                log.warn("[WorkflowSchemaMigrator] failed to add process_def.embedding column: {}",
                        e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
            }
        }
    }

    /**
     * Sibling table that captures the last 50 runs per definition for rolling
     * window metrics (in addition to the all-time counters on
     * {@code process_def}). The aggregator inserts one row per completion and
     * trims back to 50.
     */
    private void ensureProcessDefRunsRecentTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS agent.process_def_runs_recent ("
                    + "  def_id        TEXT    NOT NULL REFERENCES agent.process_def (id) ON DELETE CASCADE,"
                    + "  run_id        TEXT    NOT NULL PRIMARY KEY,"
                    + "  succeeded     BOOLEAN NOT NULL,"
                    + "  latency_ms    BIGINT  NOT NULL,"
                    + "  completed_at  BIGINT  NOT NULL"
                    + ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_pdrr_def_completed "
                    + "ON agent.process_def_runs_recent (def_id, completed_at DESC)");
            log.info("[WorkflowSchemaMigrator] process_def_runs_recent table + index synced");
        } catch (DataAccessException e) {
            log.warn("[WorkflowSchemaMigrator] failed to provision process_def_runs_recent: {}",
                    e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
        }
    }

    /** Test/inspection accessor for downstream services that need to know whether pgvector is wired. */
    public boolean isPgvectorAvailable() {
        return pgvectorAvailable;
    }
}
