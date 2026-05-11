-- Joget-style workflow engine schema for the agent.
--
-- Apply manually alongside model.sql, e.g.
--     psql $DATABASE_URL -f src/main/resources/db/joget_workflow.sql
--
-- Conventions (match model.sql):
--   * schema name:                       agent
--   * uuid primary keys:                 TEXT DEFAULT gen_random_uuid()::text
--   * timestamps:                        BIGINT (epoch millis)
--   * idempotent DDL:                    CREATE TABLE / INDEX IF NOT EXISTS
--
-- Design follows Joget's Shark/XPDL state model. See THIRD_PARTY_NOTICES.md
-- for the upstream design references (WorkflowDODSPersistentManager,
-- ProcessDO, ActivityDO, ProcessStateDO).

CREATE SCHEMA IF NOT EXISTS agent;

-- ---------------------------------------------------------------------------
-- process_def: a versioned workflow definition, as authored in the React Flow
-- board. xpdl_json holds the canonical engine-readable representation
-- (activities + transitions + variable specs + plugin configs).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.process_def (
    id                TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name              TEXT    NOT NULL,
    version           TEXT    NOT NULL,
    package_id        TEXT,
    description       TEXT,
    xpdl_json         JSONB   NOT NULL,
    created_at        BIGINT  NOT NULL,
    updated_at        BIGINT  NOT NULL,
    -- Rolling aggregates maintained by WorkflowMetadataService after every
    -- process completion. Raw counters never reset; success_rate / avg
    -- columns are derived per write.
    total_runs        INTEGER NOT NULL DEFAULT 0,
    total_successes   INTEGER NOT NULL DEFAULT 0,
    total_latency_ms  BIGINT  NOT NULL DEFAULT 0,
    success_rate      REAL,
    avg_latency_ms    BIGINT,
    ai_nodes_json     JSONB,
    last_run_at       BIGINT,
    UNIQUE (name, version)
);

CREATE INDEX IF NOT EXISTS idx_process_def_name ON agent.process_def (name);

-- pgvector + embedding column for Phase D intent matching. Optional; the
-- WorkflowSchemaMigrator gracefully degrades when the extension is missing.
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE agent.process_def ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- Rolling window (last 50 runs per def) for time-windowed metrics, in
-- addition to the all-time counters on process_def itself.
CREATE TABLE IF NOT EXISTS agent.process_def_runs_recent (
    def_id        TEXT    NOT NULL REFERENCES agent.process_def (id) ON DELETE CASCADE,
    run_id        TEXT    NOT NULL PRIMARY KEY,
    succeeded     BOOLEAN NOT NULL,
    latency_ms    BIGINT  NOT NULL,
    completed_at  BIGINT  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pdrr_def_completed
    ON agent.process_def_runs_recent (def_id, completed_at DESC);

-- ---------------------------------------------------------------------------
-- process_inst: one running or finished instance of a process_def.
-- state values (Joget convention):
--     open.running, open.not_running.suspended,
--     closed.completed, closed.terminated, closed.aborted
-- parent_inst_id is set when this instance was started as a sub-flow.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.process_inst (
    id              TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    def_id          TEXT    NOT NULL REFERENCES agent.process_def (id) ON DELETE RESTRICT,
    state           TEXT    NOT NULL DEFAULT 'open.running',
    started_at      BIGINT  NOT NULL,
    ended_at        BIGINT,
    requester_id    TEXT,
    parent_inst_id  TEXT    REFERENCES agent.process_inst (id) ON DELETE SET NULL,
    due_at          BIGINT,
    error_class     TEXT,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_process_inst_def_id ON agent.process_inst (def_id);
CREATE INDEX IF NOT EXISTS idx_process_inst_state  ON agent.process_inst (state);
CREATE INDEX IF NOT EXISTS idx_process_inst_parent ON agent.process_inst (parent_inst_id);

-- Mid-flow checkpoint columns (added by the resume pass).
-- worklist_json is the list of activityDefIds the engine has yet to run;
-- join_state_json captures JoinCoordinator state. Both are NULL while the
-- run is in flight (we only checkpoint after each activity completes) and
-- become non-NULL only at quiescent points.
ALTER TABLE agent.process_inst ADD COLUMN IF NOT EXISTS worklist_json    JSONB;
ALTER TABLE agent.process_inst ADD COLUMN IF NOT EXISTS join_state_json  JSONB;
ALTER TABLE agent.process_inst ADD COLUMN IF NOT EXISTS checkpoint_at    BIGINT;

-- ---------------------------------------------------------------------------
-- activity_inst: per-activity execution record. type is one of the Joget
-- WorkflowActivity.TYPE_* constants (normal, tool, route, subflow). state is
-- one of: ready, running, completed, failed, deadline_breached, cancelled.
-- attempt is incremented on retry. error_class is a typed error category
-- (see ErrorClass enum in the engine).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.activity_inst (
    id                TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    inst_id           TEXT    NOT NULL REFERENCES agent.process_inst (id) ON DELETE CASCADE,
    activity_def_id   TEXT    NOT NULL,
    type              TEXT    NOT NULL CHECK (type IN ('normal', 'tool', 'route', 'subflow', 'foreach', 'while', 'batch', 'ai_reasoning')),
    state             TEXT    NOT NULL DEFAULT 'ready',
    started_at        BIGINT,
    ended_at          BIGINT,
    due_at            BIGINT,
    assignee          TEXT,
    attempt           INT     NOT NULL DEFAULT 0,
    plugin_name       TEXT,
    input_snapshot    JSONB,
    output_snapshot   JSONB,
    error_class       TEXT,
    error_message     TEXT
);

CREATE INDEX IF NOT EXISTS idx_activity_inst_inst_id ON agent.activity_inst (inst_id);
CREATE INDEX IF NOT EXISTS idx_activity_inst_state   ON agent.activity_inst (state);
CREATE INDEX IF NOT EXISTS idx_activity_inst_due_at  ON agent.activity_inst (due_at) WHERE due_at IS NOT NULL;

-- Migration: the CHECK constraint above only takes effect when the table is
-- created fresh. Already-deployed databases were created when the engine only
-- supported normal/tool/route/subflow. Drop and recreate the constraint so
-- foreach/while/batch activities can persist on existing installs.
ALTER TABLE agent.activity_inst DROP CONSTRAINT IF EXISTS activity_inst_type_check;
ALTER TABLE agent.activity_inst
    ADD CONSTRAINT activity_inst_type_check
    CHECK (type IN ('normal', 'tool', 'route', 'subflow', 'foreach', 'while', 'batch', 'ai_reasoning'));

-- ---------------------------------------------------------------------------
-- variable: process- and sub-flow-scoped typed variables. java_class is a
-- nullable type hint (e.g. java.lang.String, java.lang.Long). scope is the
-- process_inst.id at which this variable lives (parent vs sub-flow), so a
-- sub-flow can have isolated variables that don't pollute the parent.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.workflow_variable (
    id          TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    inst_id     TEXT    NOT NULL REFERENCES agent.process_inst (id) ON DELETE CASCADE,
    scope       TEXT    NOT NULL,
    name        TEXT    NOT NULL,
    java_class  TEXT,
    value_json  JSONB,
    updated_at  BIGINT  NOT NULL,
    UNIQUE (inst_id, scope, name)
);

CREATE INDEX IF NOT EXISTS idx_workflow_variable_inst_scope ON agent.workflow_variable (inst_id, scope);

-- ---------------------------------------------------------------------------
-- audit_trail: per-activity-event audit row. action is e.g. activity.started,
-- activity.completed, activity.failed, deadline.fired, decision.routed,
-- variable.updated. payload_json holds the per-event details. actor is the
-- user / system that triggered the event.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.audit_trail (
    id                  TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    inst_id             TEXT    NOT NULL REFERENCES agent.process_inst (id) ON DELETE CASCADE,
    activity_inst_id    TEXT    REFERENCES agent.activity_inst (id) ON DELETE SET NULL,
    action              TEXT    NOT NULL,
    actor               TEXT,
    ts                  BIGINT  NOT NULL,
    payload_json        JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_trail_inst_id ON agent.audit_trail (inst_id);
CREATE INDEX IF NOT EXISTS idx_audit_trail_ts      ON agent.audit_trail (ts);

-- ---------------------------------------------------------------------------
-- process_link: parent <-> child sub-flow relationship, mirrors Joget's
-- WorkflowProcessLink.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.process_link (
    process_id          TEXT    PRIMARY KEY REFERENCES agent.process_inst (id) ON DELETE CASCADE,
    parent_process_id   TEXT    REFERENCES agent.process_inst (id) ON DELETE SET NULL,
    origin_process_id   TEXT
);

CREATE INDEX IF NOT EXISTS idx_process_link_parent ON agent.process_link (parent_process_id);
CREATE INDEX IF NOT EXISTS idx_process_link_origin ON agent.process_link (origin_process_id);

-- ---------------------------------------------------------------------------
-- activity_pin: deterministic test fixtures. Pins an output for a given
-- (process_def, activity_def_id) so the engine can replay downstream
-- activities without re-executing this one. Used by the n8n-style
-- "re-run from this node" / "pin output" features in the run inspector.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.activity_pin (
    id                  TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    def_id              TEXT    NOT NULL REFERENCES agent.process_def (id) ON DELETE CASCADE,
    activity_def_id     TEXT    NOT NULL,
    pinned_output       JSONB   NOT NULL,
    created_at          BIGINT  NOT NULL,
    updated_at          BIGINT  NOT NULL,
    created_by          TEXT,
    UNIQUE (def_id, activity_def_id)
);

CREATE INDEX IF NOT EXISTS idx_activity_pin_def_id ON agent.activity_pin (def_id);

-- ---------------------------------------------------------------------------
-- Insights: per-workflow ROI knob. When set, the Insights dashboard's
-- "time saved" metric multiplies completed runs by this value.
-- Null disables the contribution for this workflow.
-- ---------------------------------------------------------------------------
ALTER TABLE agent.process_def
    ADD COLUMN IF NOT EXISTS time_saved_seconds_per_run INT;

-- ---------------------------------------------------------------------------
-- pending_approval: one row per suspended manual activity awaiting human
-- approval. The dispatcher checks `properties.requireApproval == true` on
-- a `normal` activity; if no `approvalDecision` variable is in scope, the
-- executor inserts a row here, marks the run open.not_running.suspended,
-- and exits. The /workflow/approvals/{id}/approve|reject endpoint writes
-- the decision back into the run's variable scope, marks the row decided,
-- and triggers WorkflowManager.resumeProcess.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.pending_approval (
    id                  TEXT    PRIMARY KEY DEFAULT gen_random_uuid()::text,
    inst_id             TEXT    NOT NULL REFERENCES agent.process_inst (id) ON DELETE CASCADE,
    activity_inst_id    TEXT    REFERENCES agent.activity_inst (id) ON DELETE CASCADE,
    activity_def_id     TEXT    NOT NULL,
    requested_by        TEXT,
    requested_at        BIGINT  NOT NULL,
    reason              TEXT,
    decided_by          TEXT,
    decided_at          BIGINT,
    decision            TEXT,    -- 'approve' | 'reject' | NULL while pending
    comment             TEXT
);

CREATE INDEX IF NOT EXISTS idx_pending_approval_inst    ON agent.pending_approval (inst_id);
CREATE INDEX IF NOT EXISTS idx_pending_approval_pending ON agent.pending_approval (decided_at) WHERE decided_at IS NULL;
