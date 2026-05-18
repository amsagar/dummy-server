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