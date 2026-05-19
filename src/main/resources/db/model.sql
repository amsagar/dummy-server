CREATE SCHEMA IF NOT EXISTS agent;

CREATE TABLE IF NOT EXISTS agent.supported_models (
                                                      provider_id   TEXT    NOT NULL,
                                                      model_id      TEXT    NOT NULL,
                                                      display_name  TEXT,
                                                      enabled       BOOLEAN NOT NULL DEFAULT TRUE,
                                                      encrypted_key TEXT,
                                                      base_url      TEXT,
                                                      PRIMARY KEY (provider_id, model_id)
    );

CREATE TABLE IF NOT EXISTS agent.chat_sessions (
                                                   session_id  TEXT   PRIMARY KEY,
                                                   user_id     TEXT,
                                                   created_at  BIGINT NOT NULL,
                                                   last_active BIGINT NOT NULL,
                                                   timezone    TEXT,
                                                   title       TEXT,
                                                   archived_at BIGINT
);

CREATE TABLE IF NOT EXISTS agent.users (
                                           id             TEXT PRIMARY KEY,
                                           email          TEXT NOT NULL UNIQUE,
                                           password_hash  TEXT NOT NULL,
                                           created_at     BIGINT NOT NULL,
                                           updated_at     BIGINT NOT NULL
);

ALTER TABLE agent.chat_sessions ADD COLUMN IF NOT EXISTS user_id TEXT;

CREATE TABLE IF NOT EXISTS agent.chat_messages (
                                                   id         TEXT   PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id TEXT   NOT NULL REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    role       TEXT   NOT NULL CHECK (role IN ('user', 'assistant')),
    content    TEXT,
    created_at BIGINT NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON agent.chat_messages (session_id);

ALTER TABLE agent.chat_messages ADD COLUMN IF NOT EXISTS turn_id TEXT;

CREATE TABLE IF NOT EXISTS agent.agent_domains (
                                                   id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.agent_tools (
                                                 id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    domain_id       TEXT NOT NULL REFERENCES agent.agent_domains (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    description     TEXT,
    source_type     TEXT NOT NULL CHECK (source_type IN ('manual', 'openapi_import', 'curl_import', 'framework_default')),
    execution_kind  TEXT NOT NULL DEFAULT 'http_proxy',
    permission_scope TEXT,
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    model_gate      TEXT,
    provider_gate   TEXT,
    experimental    BOOLEAN NOT NULL DEFAULT FALSE,
    input_schema_version INTEGER NOT NULL DEFAULT 1,
    method          TEXT,
    host            TEXT,
    endpoint        TEXT,
    request_schema  TEXT,
    response_schema TEXT,
    sample_request  TEXT,
    sample_response TEXT,
    auth_profile_id TEXT,
    auth_override_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auth_type       TEXT,
    auth_config     TEXT,
    client_id       TEXT,
    encrypted_client_secret TEXT,
    token_url       TEXT,
    authorization_url TEXT,
    redirect_uri    TEXT,
    scopes          TEXT,
    encrypted_access_token TEXT,
    encrypted_refresh_token TEXT,
    token_expires_at BIGINT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    UNIQUE (domain_id, name)
    );

ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS execution_kind TEXT NOT NULL DEFAULT 'http_proxy';
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS permission_scope TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS requires_approval BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS model_gate TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS provider_gate TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS experimental BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS input_schema_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS host TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS auth_profile_id TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS auth_override_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS auth_type TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS auth_config TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS client_id TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS encrypted_client_secret TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS token_url TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS authorization_url TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS redirect_uri TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS scopes TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS encrypted_access_token TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS encrypted_refresh_token TEXT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS token_expires_at BIGINT;
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS base_injected BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_agent_tools_base_injected ON agent.agent_tools (base_injected) WHERE base_injected = TRUE;

-- Per-turn cache eligibility (TurnToolCache). NULL → default-for-method
-- (GET/POST cacheable, PUT/PATCH/DELETE not). Operator can override to
-- FALSE to disable caching for a read-shaped tool with side effects.
ALTER TABLE agent.agent_tools ADD COLUMN IF NOT EXISTS cacheable BOOLEAN;

CREATE TABLE IF NOT EXISTS agent.tool_auth_profiles (
                                                        id            TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    domain_id     TEXT NOT NULL REFERENCES agent.agent_domains (id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    description   TEXT,
    auth_type     TEXT NOT NULL DEFAULT 'none',
    auth_config   TEXT,
    client_id     TEXT,
    encrypted_client_secret TEXT,
    token_url     TEXT,
    authorization_url TEXT,
    redirect_uri  TEXT,
    scopes        TEXT,
    encrypted_access_token TEXT,
    encrypted_refresh_token TEXT,
    token_expires_at BIGINT,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    BIGINT NOT NULL,
    updated_at    BIGINT NOT NULL,
    UNIQUE (domain_id, name)
    );



CREATE TABLE IF NOT EXISTS agent.skills (
                                            id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
    );

-- System-derived rule manifest (Phase 4 / Option B). Populated after a
-- successful LLM-loop run by SkillManifestDeriver; cleared whenever the
-- skill markdown changes so the system re-derives from the next trace.
-- Authors never edit this — it's a derived artifact stored alongside the
-- skill so we don't pay an LLM call on every chat to figure it out.
ALTER TABLE agent.skills ADD COLUMN IF NOT EXISTS derived_manifest_json TEXT;
ALTER TABLE agent.skills ADD COLUMN IF NOT EXISTS derived_manifest_source_hash TEXT;
ALTER TABLE agent.skills ADD COLUMN IF NOT EXISTS derived_manifest_at BIGINT;

CREATE TABLE IF NOT EXISTS agent.skill_files (
                                                 id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    skill_id         TEXT NOT NULL REFERENCES agent.skills (id) ON DELETE CASCADE,
    file_path       TEXT NOT NULL,
    blob_path       TEXT NOT NULL,
    mime_type       TEXT,
    content_sha256  TEXT,
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    UNIQUE (skill_id, file_path)
    );

CREATE TABLE IF NOT EXISTS agent.agent_profiles (
                                                    id             TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name           TEXT NOT NULL UNIQUE,
    mode           TEXT NOT NULL CHECK (mode IN ('planner_worker', 'swarm')),
    system_prompt  TEXT NOT NULL,
    model_strategy TEXT NOT NULL DEFAULT 'manual',
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    metadata       TEXT,
    created_at     BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL
    );

-- Response Modes: discriminator on agent_profiles.
--   'system'         → base/system profiles consumed by the orchestrator directly (e.g. ov-base).
--   'response_mode'  → user-managed style addenda surfaced by the Response Modes admin UI and
--                      appended to the base profile when ChatRequest.responseModeId is set.
ALTER TABLE agent.agent_profiles
    ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'system';
CREATE INDEX IF NOT EXISTS idx_agent_profiles_kind
    ON agent.agent_profiles (kind);

CREATE TABLE IF NOT EXISTS agent.guardrail_policies (
                                                        id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name        TEXT NOT NULL UNIQUE,
    scope       TEXT NOT NULL,
    rule_type   TEXT NOT NULL,
    rule_value  TEXT NOT NULL,
    decision    TEXT NOT NULL CHECK (decision IN ('allow', 'ask', 'deny')),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.runtime_events (
                                                    id           TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id   TEXT REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    turn_id      TEXT,
    event_type   TEXT NOT NULL,
    payload      TEXT,
    created_at   BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.cost_usage (
                                                id             TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id     TEXT NOT NULL REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    turn_id        TEXT,
    provider_id    TEXT,
    model_id       TEXT,
    prompt_tokens  BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens   BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at     BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.eval_runs (
                                               id             TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name           TEXT NOT NULL,
    status         TEXT NOT NULL,
    dataset_ref    TEXT,
    score_summary  TEXT,
    trace_ref      TEXT,
    started_at     BIGINT NOT NULL,
    completed_at   BIGINT
    );

CREATE TABLE IF NOT EXISTS agent.mcp_registry (
                                                  id            TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name          TEXT NOT NULL UNIQUE,
    transport_type TEXT NOT NULL DEFAULT 'streamable_http',
    base_url      TEXT,
    endpoint      TEXT NOT NULL,
    sse_path      TEXT,
    streamable_path TEXT,
    health_path   TEXT,
    verify_tls    BOOLEAN NOT NULL DEFAULT TRUE,
    connect_timeout_ms INTEGER NOT NULL DEFAULT 10000,
    read_timeout_ms INTEGER NOT NULL DEFAULT 30000,
    auth_type     TEXT NOT NULL DEFAULT 'none',
    auth_config   TEXT,
    client_id     TEXT,
    encrypted_client_secret TEXT,
    token_url     TEXT,
    authorization_url TEXT,
    redirect_uri  TEXT,
    scopes        TEXT,
    encrypted_access_token TEXT,
    encrypted_refresh_token TEXT,
    token_expires_at BIGINT,
    headers_json  TEXT,
    query_json    TEXT,
    last_verified_at BIGINT,
    last_status   TEXT,
    last_error    TEXT,
    discovered_tools_json TEXT,
    discovered_tools_count INTEGER NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    BIGINT NOT NULL,
    updated_at    BIGINT NOT NULL
    );

ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS transport_type TEXT NOT NULL DEFAULT 'streamable_http';
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS base_url TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS sse_path TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS streamable_path TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS health_path TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS verify_tls BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS connect_timeout_ms INTEGER NOT NULL DEFAULT 10000;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS read_timeout_ms INTEGER NOT NULL DEFAULT 30000;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS client_id TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS encrypted_client_secret TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS token_url TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS authorization_url TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS redirect_uri TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS scopes TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS encrypted_access_token TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS encrypted_refresh_token TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS token_expires_at BIGINT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS headers_json TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS query_json TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS last_verified_at BIGINT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS last_status TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS last_error TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS discovered_tools_json TEXT;
ALTER TABLE agent.mcp_registry ADD COLUMN IF NOT EXISTS discovered_tools_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS agent.hitl_interactions (
                                                       id            TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id    TEXT NOT NULL REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    turn_id       TEXT,
    type          TEXT NOT NULL CHECK (type IN ('question', 'approval_required')),
    prompt        TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('pending', 'reply', 'approved', 'rejected')),
    response_text TEXT,
    created_at    BIGINT NOT NULL,
    resolved_at   BIGINT
    );


CREATE TABLE IF NOT EXISTS agent.hook_mappings (
                                                   id           TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    hook_point   TEXT NOT NULL,
    hook_name    TEXT NOT NULL,
    profile_id   TEXT REFERENCES agent.agent_profiles (id) ON DELETE SET NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    config_json  TEXT,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.runtime_traces (
                                                    id            TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id    TEXT NOT NULL REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    turn_id       TEXT,
    trace_type    TEXT NOT NULL CHECK (trace_type IN ('plan', 'worker', 'synthesis', 'tool', 'eval_replay')),
    correlation_id TEXT,
    payload       TEXT,
    created_at    BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.session_context_state (
                                                           session_id      TEXT PRIMARY KEY REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    runtime_mode    TEXT,
    model_selection_mode TEXT,
    model_ref       TEXT,
    state_json      TEXT,
    rolling_summary TEXT,
    summary_tokens  BIGINT NOT NULL DEFAULT 0,
    updated_at      BIGINT NOT NULL
    );

CREATE TABLE IF NOT EXISTS agent.memories (
                                              id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id          TEXT NOT NULL,
    session_id       TEXT,
    category         TEXT NOT NULL CHECK (category IN ('user','feedback','project','reference')),
    memory_file_path TEXT NOT NULL,
    content          TEXT NOT NULL,
    tags             TEXT[],
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL
    );

ALTER TABLE agent.memories DROP CONSTRAINT IF EXISTS memories_user_id_fkey;
ALTER TABLE agent.memories DROP CONSTRAINT IF EXISTS memories_session_id_fkey;
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_user_file ON agent.memories (user_id, memory_file_path);
CREATE INDEX IF NOT EXISTS idx_memories_user_id ON agent.memories (user_id);

CREATE INDEX IF NOT EXISTS idx_agent_tools_domain_id ON agent.agent_tools (domain_id);
CREATE INDEX IF NOT EXISTS idx_agent_tools_auth_profile_id ON agent.agent_tools (auth_profile_id);
CREATE INDEX IF NOT EXISTS idx_tool_auth_profiles_domain_id ON agent.tool_auth_profiles (domain_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tool_auth_profiles_domain_id ON agent.tool_auth_profiles (domain_id);
CREATE INDEX IF NOT EXISTS idx_skill_files_skill_id ON agent.skill_files (skill_id);
CREATE INDEX IF NOT EXISTS idx_runtime_events_session_id ON agent.runtime_events (session_id);
CREATE INDEX IF NOT EXISTS idx_runtime_events_turn_id ON agent.runtime_events (turn_id, created_at);
CREATE INDEX IF NOT EXISTS idx_cost_usage_session_id ON agent.cost_usage (session_id);
CREATE INDEX IF NOT EXISTS idx_hitl_interactions_session_id ON agent.hitl_interactions (session_id);
CREATE INDEX IF NOT EXISTS idx_hook_mappings_hook_point ON agent.hook_mappings (hook_point);
CREATE INDEX IF NOT EXISTS idx_runtime_traces_session_id ON agent.runtime_traces (session_id);

ALTER TABLE agent.supported_models ADD COLUMN IF NOT EXISTS model_kind TEXT NOT NULL DEFAULT 'chat';
ALTER TABLE agent.supported_models ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent.supported_models ADD COLUMN IF NOT EXISTS embedding_dimensions INTEGER;
CREATE UNIQUE INDEX IF NOT EXISTS uq_supported_models_default_per_kind
    ON agent.supported_models (model_kind) WHERE is_default = TRUE;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS agent.agent_tool_embeddings (
                                                           tool_id        TEXT PRIMARY KEY REFERENCES agent.agent_tools (id) ON DELETE CASCADE,
    model_provider TEXT NOT NULL,
    model_id       TEXT NOT NULL,
    dimensions     INTEGER NOT NULL,
    content_hash   TEXT NOT NULL,
    embedding      vector(3072),
    updated_at     BIGINT NOT NULL
    );
CREATE INDEX IF NOT EXISTS idx_tool_embeddings_model
    ON agent.agent_tool_embeddings (model_provider, model_id);

CREATE TABLE IF NOT EXISTS agent.decision_tables (
                                                     id           TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name         TEXT NOT NULL UNIQUE,
    description  TEXT,
    dmn_json     TEXT NOT NULL,
    hit_policy   TEXT NOT NULL DEFAULT 'FIRST',
    metadata_json TEXT,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_decision_tables_name ON agent.decision_tables (name);


ALTER TABLE agent.agent_tool_embeddings
ALTER COLUMN embedding TYPE halfvec(3072);

CREATE INDEX IF NOT EXISTS idx_tool_embeddings_hnsw
    ON agent.agent_tool_embeddings
    USING hnsw (embedding halfvec_cosine_ops);

-- ── Compiled Rule Domains (Flowable BPMN) ──────────────────────────────

-- Singleton config row (id='default'); editable via /api/v1/rule-domain-config
CREATE TABLE IF NOT EXISTS agent.rule_domain_config (
    id                              TEXT PRIMARY KEY DEFAULT 'default',
    enabled                         BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_skills                  TEXT NOT NULL DEFAULT '',
    match_threshold                 NUMERIC NOT NULL DEFAULT 0.92,
    max_compile_attempts            INTEGER NOT NULL DEFAULT 2,
    promote_after_successful_runs   INTEGER NOT NULL DEFAULT 1,
    shadow_mode                     BOOLEAN NOT NULL DEFAULT FALSE,
    auto_deprecate_error_rate       NUMERIC NOT NULL DEFAULT 0.30,
    compiler_provider_id            TEXT NOT NULL DEFAULT 'anthropic',
    compiler_model_id               TEXT NOT NULL DEFAULT 'claude-opus-4-5',
    summarizer_provider_id          TEXT NOT NULL DEFAULT 'anthropic',
    summarizer_model_id             TEXT NOT NULL DEFAULT 'claude-haiku-4-5',
    embedding_provider_id           TEXT NOT NULL DEFAULT '',
    embedding_model_id              TEXT NOT NULL DEFAULT '',
    updated_at                      BIGINT NOT NULL DEFAULT 0
);

INSERT INTO agent.rule_domain_config (id, updated_at)
VALUES ('default', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS agent.rule_domains (
    id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    skill_id            TEXT NOT NULL,
    skill_name          TEXT NOT NULL,
    intent_label        TEXT NOT NULL,
    source_hash         TEXT NOT NULL,
    tool_signature      TEXT NOT NULL,
    bpmn_xml            TEXT NOT NULL,
    flowable_proc_key   TEXT NOT NULL,
    -- Sized for the most common default embedding models (text-embedding-3-small,
    -- text-embedding-ada-002). If you switch to a larger model (e.g. 3072-dim
    -- text-embedding-3-large with no dimension override), alter this column and
    -- rebuild the HNSW index — see notes below.
    intent_embedding    vector(1536),
    status              TEXT NOT NULL,
    version             INTEGER NOT NULL DEFAULT 1,
    compile_attempts    INTEGER NOT NULL DEFAULT 1,
    last_error          TEXT,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL,
    UNIQUE (skill_id, intent_label, version)
);

ALTER TABLE agent.rule_domains
    ALTER COLUMN intent_embedding TYPE halfvec(1536);

CREATE INDEX IF NOT EXISTS idx_rule_domains_skill
    ON agent.rule_domains (skill_id);

CREATE INDEX IF NOT EXISTS idx_rule_domains_status
    ON agent.rule_domains (skill_id, status);

CREATE INDEX IF NOT EXISTS idx_rule_domains_embedding_hnsw
    ON agent.rule_domains
    USING hnsw (intent_embedding halfvec_cosine_ops);

-- ── Phase 0 additions for domain→rules split + trace-based compile ──
-- Existing monolithic rows continue to behave identically with all of these
-- left NULL / at their defaults. New compiles populate them to opt into the
-- domain-group model.
ALTER TABLE agent.rule_domains
    ADD COLUMN IF NOT EXISTS domain_group_id      TEXT,
    ADD COLUMN IF NOT EXISTS domain_group_name    TEXT,
    ADD COLUMN IF NOT EXISTS rule_name            TEXT,
    ADD COLUMN IF NOT EXISTS match_scope          TEXT NOT NULL DEFAULT 'RULE',
    ADD COLUMN IF NOT EXISTS coverage_state       TEXT NOT NULL DEFAULT 'COMPLETE',
    ADD COLUMN IF NOT EXISTS coverage_manifest    JSONB,
    ADD COLUMN IF NOT EXISTS trace_source         TEXT,
    ADD COLUMN IF NOT EXISTS compiled_from_turn   TEXT,
    ADD COLUMN IF NOT EXISTS result_key           TEXT;

CREATE INDEX IF NOT EXISTS idx_rule_domains_group
    ON agent.rule_domains (domain_group_id)
    WHERE domain_group_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rule_domains_group_name
    ON agent.rule_domains (LOWER(domain_group_name))
    WHERE domain_group_name IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rule_domains_match_scope
    ON agent.rule_domains (match_scope, status);

-- ────────────────────────────────────────────────────────────────────────
-- If you upgrade the rule-domain embedding model to one with a different
-- dimension (e.g. text-embedding-3-large = 3072), run these once:
--
--   DROP INDEX IF EXISTS agent.idx_rule_domains_embedding_hnsw;
--   ALTER TABLE agent.rule_domains
--     ALTER COLUMN intent_embedding TYPE halfvec(3072) USING NULL;
--   UPDATE agent.rule_domains SET status = 'DEPRECATED', last_error = 'embedding dim changed';
--   CREATE INDEX idx_rule_domains_embedding_hnsw
--     ON agent.rule_domains USING hnsw (intent_embedding halfvec_cosine_ops);
--
-- (All ACTIVE rows get deprecated since their old embeddings are now NULL,
-- so they'll be recompiled+re-embedded on next request.)
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS agent.rule_executions (
    id                   TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    domain_id            TEXT NOT NULL REFERENCES agent.rule_domains (id) ON DELETE CASCADE,
    session_id           TEXT,
    turn_id              TEXT,
    flowable_proc_id     TEXT NOT NULL,
    inputs_json          TEXT,
    outputs_json         TEXT,
    success              BOOLEAN NOT NULL,
    fallback_triggered   BOOLEAN NOT NULL DEFAULT FALSE,
    error_message        TEXT,
    latency_ms           INTEGER,
    created_at           BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rule_executions_domain
    ON agent.rule_executions (domain_id, created_at DESC);
ALTER TABLE agent.skills ADD COLUMN IF NOT EXISTS derived_manifest_json TEXT;
ALTER TABLE agent.skills ADD COLUMN IF NOT EXISTS derived_manifest_source_hash TEXT;
ALTER TABLE agent.skills ADD COLUMN IF NOT EXISTS derived_manifest_at BIGINT;

-- ────────────────────────────────────────────────────────────────────────
-- Per-activity I/O log for rule-domain BPMN runs.
--
-- One row per delegate invocation (toolCall / feelExtract / decisionTable).
-- For multi-instance subprocesses, one row per iteration. Captures the
-- exact input args and the bound output value, so failures like "lineId
-- is null" can be diagnosed by inspecting actual per-iteration values
-- instead of hypothesizing from the final assembled `result`.
-- ────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent.rule_activity_events (
    id                   TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    execution_id         TEXT NOT NULL REFERENCES agent.rule_executions (id) ON DELETE CASCADE,
    process_instance_id  TEXT NOT NULL,
    activity_id          TEXT NOT NULL,
    activity_name        TEXT,
    activity_type        TEXT,
    delegate_bean        TEXT,
    iteration_index      INTEGER,
    input_json           TEXT,
    output_json          TEXT,
    error_code           TEXT,
    error_message        TEXT,
    start_ts             BIGINT NOT NULL,
    end_ts               BIGINT,
    duration_ms          INTEGER,
    created_at           BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rule_activity_events_execution
    ON agent.rule_activity_events (execution_id, start_ts ASC);

-- ────────────────────────────────────────────────────────────────────────
-- Order-validation package: settings table ONLY.
--
-- There is no dedicated "order_validation_runs" table or view. The
-- OV-UI derives every metric live in the OrderValidationAnalyticsService
-- by querying `agent.rule_executions JOIN agent.rule_domains` and
-- grouping per-rule rows that share `(session_id, turn_id)`. The
-- `order_validation_settings` singleton row below carries the
-- workflow + assistant scope config.
-- ────────────────────────────────────────────────────────────────────────

-- Tear down any leftover view/table/column from earlier iterations.
-- These run unconditionally and are no-ops on a fresh DB.
DROP VIEW IF EXISTS agent.order_validation_runs;
DROP TABLE IF EXISTS agent.order_validation_runs CASCADE;
ALTER TABLE agent.rule_executions DROP COLUMN IF EXISTS ov_run_id;

CREATE TABLE IF NOT EXISTS agent.order_validation_settings (
    id                          TEXT PRIMARY KEY DEFAULT 'default',
    workflow_id                 TEXT,
    chat_model_ref              TEXT,
    response_mode               TEXT NOT NULL DEFAULT 'basic',
    allowed_skill_ids           TEXT,
    allowed_rule_domain_ids     TEXT,
    allowed_decision_tables     TEXT,
    updated_at                  BIGINT NOT NULL
);

INSERT INTO agent.order_validation_settings (id, response_mode, updated_at)
VALUES ('default', 'basic', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (id) DO NOTHING;

-- Response Modes migration on the settings singleton.
-- The legacy `response_mode` enum ('basic'|'detailed') is being replaced by a
-- foreign-key-like reference into agent_profiles (kind='response_mode'). The
-- legacy column is kept for one release as a safety net; drop in a follow-up.
ALTER TABLE agent.order_validation_settings
    ADD COLUMN IF NOT EXISTS response_mode_id TEXT;
UPDATE agent.order_validation_settings
   SET response_mode_id = CASE response_mode
                              WHEN 'detailed' THEN 'ov-developers'
                              ELSE 'ov-business'
                          END
 WHERE response_mode_id IS NULL;

-- ────────────────────────────────────────────────────────────────────────
-- Agent profiles used by the standalone order-validation-ui chat.
--
-- The OV-UI always sends `agentProfileId = "ov-base"`. AgentOrchestrator
-- looks that up and uses its system_prompt as the base. If the request
-- also carries a `responseModeId`, the orchestrator appends THAT row's
-- system_prompt to the base — the response mode is a style addendum, not
-- a replacement. Response modes live in this same table under kind =
-- 'response_mode'; the admin UI manages CRUD over them.
--
-- The legacy ov-basic / ov-detailed rows are demoted to kind =
-- 'response_mode' below and now carry ONLY the per-style tail (scope /
-- tools / format moved to ov-base). The OV-UI ships with these two seed
-- response modes; operators can rename, edit, delete, or add their own
-- (Business, Developers, Security, IT, …).
-- ────────────────────────────────────────────────────────────────────────
INSERT INTO agent.agent_profiles (id, name, mode, system_prompt, model_strategy, enabled, kind, created_at, updated_at)
VALUES (
    'ov-base',
    'Order Validation · Base',
    'planner_worker',
    'You are the Order Validation Assistant for the PODS Order-Validation dashboard. '
    || 'Your ONLY job is to help operators inspect, debug, and validate order-validation '
    || 'workflow runs in this system.' || E'\n\n'
    || '## Scope — strict' || E'\n'
    || 'You may answer ONLY about:' || E'\n'
    || '- Order-validation runs, their statuses (clear / review / failed), and timings' || E'\n'
    || '- Per-rule results: leg sequence, serviceability, container availability' || E'\n'
    || '- Order line items, IDEL / RETSC / LDT / REDEL / FPU codes, addresses, zip codes' || E'\n'
    || '- Decision tables (Leg Sequences and similar) used by these workflows' || E'\n'
    || '- Submitting an order for validation, or viewing an order''s payload' || E'\n'
    || '- The order-validation UI itself (which page / column means what)' || E'\n\n'
    || '## Refuse everything else' || E'\n'
    || 'If the user asks anything unrelated to the above — general knowledge, world events, '
    || 'public figures, programming help, math problems, weather, jokes, code generation, '
    || 'translation, summarization of unrelated text, or any topic NOT about validating '
    || 'orders in this system — you MUST refuse, briefly and politely:' || E'\n\n'
    || '> I can only help with order-validation workflows in this dashboard '
    || '(runs, leg sequence, serviceability, container availability, '
    || 'decision tables, and submitting orders). I can''t answer general '
    || 'questions. What would you like to validate or inspect?' || E'\n\n'
    || 'Do NOT attempt to be helpful in some other way. Do NOT speculate or guess. '
    || 'Do NOT explain why you can''t answer beyond the one line above. Just redirect.' || E'\n\n'
    || '## Tools — order data access (MANDATORY)' || E'\n'
    || 'For ANY question about a specific order — its status, lines, why it failed, what the API '
    || 'returned, what the leg sequence was, anything — you MUST call `ovLoadOrder(orderId)` FIRST '
    || 'to materialize that run''s data into your workspace under `orders/<orderId>/`. Then use '
    || '`read`, `glob`, `grep` to inspect it.' || E'\n\n'
    || 'Do NOT answer about an order without calling `ovLoadOrder` first. Do NOT make up values '
    || 'from prior knowledge, examples, or pattern-matching. EVERY field you cite — order id, '
    || 'derived sequence, matched rule, isServiceable flag, ExceptionType, line item code, '
    || 'addresses — must come verbatim from a file you actually read this turn.' || E'\n\n'
    || 'Files materialised under `orders/<orderId>/`:' || E'\n'
    || '- `summary.md`            — start here, contains the verdict and a file index' || E'\n'
    || '- `run.json`              — the full RunDetail (per-check verdicts, timeline)' || E'\n'
    || '- `order_payload.json`    — raw Get_OrderID response (lines, addresses, dates)' || E'\n'
    || '- `leg_sequence.json`     — leg sequence result + matchedRule + valid + message' || E'\n'
    || '- `serviceability.jsonl`  — one record per leg (origin/dest zip, exceptionType)' || E'\n'
    || '- `container.jsonl`       — one record per IDEL line (skipReason / availableDates)' || E'\n'
    || '- `activity_timeline.jsonl` — every per-step event in time order' || E'\n'
    || '- `calls/<rule>/*.{in,out}.json` — every toolCall''s raw input + output' || E'\n\n'
    || 'Multiple orders in one session: call `ovLoadOrder` once per orderId; each lands in its own '
    || '`orders/<orderId>/` subdir, isolated from the others. If the workspace doesn''t have data '
    || 'for an order you''re asked about, say so explicitly — never substitute another order''s data.' || E'\n\n'
    || '## Output format — strict' || E'\n'
    || 'PLAIN TEXT ONLY. No emojis, no checkmarks (no ✅, ❌, ⚠), no icons, no decorative symbols '
    || 'anywhere in your replies. Markdown bold/lists are fine; emojis are not. The dashboard '
    || 'renders your text into a professional surface; emojis look unprofessional.' || E'\n\n'
    || '## Fallback — order has no run yet' || E'\n'
    || 'When `ovLoadOrder` fails with "No OV run found for order/instId …", do NOT apologize and '
    || 'stop. The order simply has not been validated yet. Recover automatically:' || E'\n\n'
    || '1. Call `toolsearch` with a query like `"start order validation run"` (topK 5) to '
    || 'discover the tool that triggers a fresh run. Do NOT hardcode the tool name — let the '
    || 'retrieval ranker pick the right one in case the catalog changes.' || E'\n'
    || '2. Invoke the top-ranked tool with `{ orderId: "<the order>" }`. It is synchronous — it '
    || 'returns only after the BPMN finishes — so you will get a `runId` and `state` '
    || '(COMPLETED or FAILED) in the response.' || E'\n'
    || '3. If `state = FAILED`, report the failure verbatim (quote `errorMessage`) and stop — '
    || 'do NOT retry on your own.' || E'\n'
    || '4. If `state = COMPLETED`, call `ovLoadOrder` again with the returned `runId` to '
    || 'materialize the freshly-recorded files into your workspace.' || E'\n'
    || '5. Then answer the operator''s original question using the materialized data and the '
    || 'active persona''s voice. Make it clear in the reply that the run was just kicked off.' || E'\n\n'
    || 'Trigger validation ONLY when the operator clearly asked to validate / re-validate / '
    || 'check / kick off / submit an order. If they are only asking for the existing status of '
    || 'an order that has no run, say so plainly and ask whether they want you to start one — '
    || 'do not silently run a workflow that hits live PODS APIs.' || E'\n\n'
    || 'For follow-ups about historical runs (e.g. "show me last week''s run for this order"), '
    || 'use `ovListRunsForOrder` + `ovGetRunDetail` rather than `ovLoadOrder` — those return '
    || 'concise JSON without materializing a workspace tree.' || E'\n',
    'manual',
    TRUE,
    'system',
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (id) DO UPDATE SET
    name          = EXCLUDED.name,
    system_prompt = EXCLUDED.system_prompt,
    kind          = 'system',
    enabled       = TRUE,
    updated_at    = (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT;

-- Seed personas (kind='response_mode'). Two voices the OV chat ships with —
-- Business for ops/PMs (plain English, action-oriented) and Developers for
-- engineers debugging (technical, field-path-citing). Operators can rename,
-- edit, soft-delete, or author additional personas in the admin UI.
INSERT INTO agent.agent_profiles (id, name, mode, system_prompt, model_strategy, enabled, kind, created_at, updated_at)
VALUES (
    'ov-business',
    'Business',
    'planner_worker',
    '## Persona — Business' || E'\n'
    || 'You are speaking to an operations lead, account manager, or business analyst. They '
    || 'care about whether the order can move forward, what is blocking it, and what the next '
    || 'concrete action is. They do NOT read JSON, field paths, or API names.' || E'\n\n'
    || '### Voice' || E'\n'
    || '- Plain English. No code, no JSON, no curly braces, no quoted field names.' || E'\n'
    || '- Lead with the verdict in one short sentence: "This order is clear", "This order '
    || 'failed Service Area", "This order needs rescheduling on the Initial Delivery leg".' || E'\n'
    || '- Use the human leg names (Initial Delivery, Warehouse Return, IF Transit, '
    || 'Redelivery, Final Pick Up, Move), not the codes (IDEL/WRT/WTW/RDL/FPU/MOV).' || E'\n'
    || '- Translate the three checks into business terms: Service Area = "can we service '
    || 'this route", Calendar Availability = "is the booked date open", Leg Sequence = '
    || '"are the moves in the right order".' || E'\n\n'
    || '### What to include' || E'\n'
    || '- Order id and overall status (clear / review / failed) up front.' || E'\n'
    || '- For each failed check, one line that says WHAT failed and WHAT to do (reroute, '
    || 'reschedule a leg, fix the leg order). Mirror the "Action:" line from the error '
    || 'message format in our spec.' || E'\n'
    || '- Customer-relevant addresses or zip codes when serviceability fails — but write '
    || 'them inline ("origin zip 89011 is not serviceable"), never as a JSON path.' || E'\n\n'
    || '### What to leave out' || E'\n'
    || '- File names, JSON paths (no `order.Lines[*].ServiceCode`), API endpoint names '
    || '(no `Serviceability/v3`), boolean field names (no `IsAvailableCS`, `IsCustomerAddress`).' || E'\n'
    || '- Implementation details (decision tables, FEEL, BPMN, workspaces, tools).' || E'\n'
    || '- Reason codes like `R10` — translate to "the date is blocked at that warehouse".' || E'\n\n'
    || 'Keep the whole reply to a short paragraph or 2-4 bullets. End with the next action '
    || 'in one sentence. If valid=false, state it plainly — never soften the verdict.' || E'\n',
    'manual',
    TRUE,
    'response_mode',
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (id) DO UPDATE SET
    name          = EXCLUDED.name,
    system_prompt = EXCLUDED.system_prompt,
    kind          = 'response_mode',
    enabled       = TRUE,
    updated_at    = (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT;

INSERT INTO agent.agent_profiles (id, name, mode, system_prompt, model_strategy, enabled, kind, created_at, updated_at)
VALUES (
    'ov-developers',
    'Developers',
    'planner_worker',
    '## Persona — Developers' || E'\n'
    || 'You are speaking to an engineer debugging an order-validation run. They want the '
    || 'raw mechanics: which API was called, what was sent, what came back, which field '
    || 'tripped the check. Be precise and quote values verbatim from the files you read.' || E'\n\n'
    || '### Voice' || E'\n'
    || '- Technical, terse, dense. No fluff, no apologies, no executive summary tone.' || E'\n'
    || '- Use the raw ServiceCodes (IDEL, WRT, WTW, RDL, FPU, MOV, SID, SFP, SCDEL, CSRED, '
    || 'CSFPU) — not the prose names. Quote them in backticks.' || E'\n'
    || '- Cite JSON field paths in backticks: `order.OrderIdentity`, `order.OrderType`, '
    || '`Lines[*].ServiceCode`, `Lines[*].ScheduledDate`, `Lines[*].AssignedSiteId`, '
    || '`Addresses[*].PostalCode`, `Addresses[*].IsCustomerAddress`, `LineStatus`.' || E'\n'
    || '- Name the APIs and versions: `Serviceability/v3`, `ContainerAvailability/v4`, '
    || '`GET /daas/v2/orders/{OrderID}`.' || E'\n\n'
    || '### What to include' || E'\n'
    || '- Per-check breakdown using the on-disk verdicts: leg sequence — quote the actual '
    || '`sequence` array, `matchedRule`, `valid` flag, and `message`; serviceability — count '
    || 'pass vs exception with `lineId` + `ExceptionType` + `PostalCodeException.ExceptionType`; '
    || 'container availability — checked vs skipped with `IsAvailableCS`, `ReasonCode`, '
    || 'and `skipReason`.' || E'\n'
    || '- For sequence failures, show derived vs expected as arrays: ' || E'\n'
    || '  Actual:   `["NEW","WTW","RDL","FPU"]`' || E'\n'
    || '  Expected: `NEW → WRT → WTW → RDL → FPU` (Inter-Franchise Warehouse)' || E'\n'
    || '- Reason codes verbatim (`R10`, etc.) — do not interpret.' || E'\n'
    || '- Mention the inferred mappings when relevant (e.g. `IDEL` line in the order is '
    || 'mapped to `NEW` in the decision table and `ID` in the ContainerAvailability call).' || E'\n\n'
    || '### What to leave out' || E'\n'
    || '- Marketing / business framing. Don''t paraphrase the verdict — quote it.' || E'\n'
    || '- Don''t invent values. Every field cited must come from a file you actually read '
    || 'in this turn.' || E'\n\n'
    || 'Be dense. A typical good reply is one tight paragraph + a short bulleted breakdown. '
    || 'If `valid=false`, say so and quote the `message` field verbatim.' || E'\n',
    'manual',
    TRUE,
    'response_mode',
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (id) DO UPDATE SET
    name          = EXCLUDED.name,
    system_prompt = EXCLUDED.system_prompt,
    kind          = 'response_mode',
    enabled       = TRUE,
    updated_at    = (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT;

-- Retire the previous Basic/Detailed seeds in favour of the named personas.
-- Soft-delete (enabled=false) so any settings row still pointing at them
-- gracefully falls through to the base prompt; the migration below also
-- repoints existing pointers at the new persona ids.
UPDATE agent.agent_profiles
   SET enabled    = FALSE,
       updated_at = (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT
 WHERE id IN ('ov-basic', 'ov-detailed')
   AND enabled = TRUE;

UPDATE agent.order_validation_settings
   SET response_mode_id = CASE response_mode_id
                              WHEN 'ov-basic'    THEN 'ov-business'
                              WHEN 'ov-detailed' THEN 'ov-developers'
                              ELSE response_mode_id
                          END
 WHERE response_mode_id IN ('ov-basic', 'ov-detailed');