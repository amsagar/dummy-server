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
ALTER TABLE agent.agent_tools DROP COLUMN IF EXISTS risk_level;
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

CREATE TABLE IF NOT EXISTS agent.tool_versions (
                                                   id           TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    tool_id       TEXT NOT NULL REFERENCES agent.agent_tools (id) ON DELETE CASCADE,
    version       INTEGER NOT NULL,
    description   TEXT,
    request_schema  TEXT,
    response_schema TEXT,
    created_at    BIGINT NOT NULL,
    UNIQUE (tool_id, version)
    );

CREATE TABLE IF NOT EXISTS agent.skills (
                                            id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
    );

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

CREATE TABLE IF NOT EXISTS agent.workflow_proposals (
                                                        id                       TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id               TEXT NOT NULL REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    turn_id                  TEXT NOT NULL,
    user_id                  TEXT NOT NULL,
    status                   TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'building', 'rejected', 'materialized', 'failed')),
    reason                   TEXT,
    confidence               DOUBLE PRECISION,
    intent_signature         TEXT NOT NULL,
    trace_ref                TEXT,
    user_prompt              TEXT,
    model_provider_id        TEXT,
    model_id                 TEXT,
    proposed_workflow_json   JSONB,
    matched_tool_names_json  JSONB,
    suggested_name           TEXT,
    skill_names_json         JSONB,
    build_attempts           INTEGER NOT NULL DEFAULT 0,
    decision_comment         TEXT,
    decided_by               TEXT,
    decided_at               BIGINT,
    materialized_def_id      TEXT,
    error_message            TEXT,
    created_at               BIGINT NOT NULL,
    updated_at               BIGINT NOT NULL
    );

-- Backfill / migration block for installations created before the two-phase
-- (classifier + builder) split. Each statement is idempotent so running the
-- file repeatedly is safe.
ALTER TABLE agent.workflow_proposals
    ALTER COLUMN proposed_workflow_json DROP NOT NULL;
ALTER TABLE agent.workflow_proposals
    ADD COLUMN IF NOT EXISTS suggested_name TEXT;
ALTER TABLE agent.workflow_proposals
    ADD COLUMN IF NOT EXISTS skill_names_json JSONB;
ALTER TABLE agent.workflow_proposals
    ADD COLUMN IF NOT EXISTS build_attempts INTEGER NOT NULL DEFAULT 0;
DO $$
BEGIN
    -- Replace the legacy CHECK constraint to add the 'building' transient state.
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'workflow_proposals_status_check'
    ) THEN
        ALTER TABLE agent.workflow_proposals DROP CONSTRAINT workflow_proposals_status_check;
    END IF;
    ALTER TABLE agent.workflow_proposals
        ADD CONSTRAINT workflow_proposals_status_check
        CHECK (status IN ('pending', 'approved', 'building', 'rejected', 'materialized', 'failed'));
END$$;

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
    user_id          TEXT NOT NULL REFERENCES agent.users (id) ON DELETE CASCADE,
    session_id       TEXT REFERENCES agent.chat_sessions (session_id) ON DELETE SET NULL,
    category         TEXT NOT NULL CHECK (category IN ('user','feedback','project','reference')),
    memory_file_path TEXT NOT NULL,
    content          TEXT NOT NULL,
    tags             TEXT[],
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL
    );

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
CREATE INDEX IF NOT EXISTS idx_workflow_proposals_user_status ON agent.workflow_proposals (user_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_proposals_intent ON agent.workflow_proposals (user_id, intent_signature, status);
CREATE INDEX IF NOT EXISTS idx_hook_mappings_hook_point ON agent.hook_mappings (hook_point);
CREATE INDEX IF NOT EXISTS idx_runtime_traces_session_id ON agent.runtime_traces (session_id);

-- ── Embedding model registrations + tool vector index ─────────────────────────
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

-- ── Workflow API keys ─────────────────────────────────────────────────────────
-- Scoped credentials for triggering workflow runs from outside the app
-- (curl, Postman, CI, etc.) without leaking a user's JWT. Each key is
-- restricted to a fixed allowlist of process_def_ids: it can only start runs
-- of workflows in `process_def_ids`. We store key_hash (sha-256) for
-- verification and key_prefix (the first 12 chars of the plaintext) for
-- O(1) lookup at auth time; the plaintext key is shown to the user exactly
-- once at creation.
CREATE TABLE IF NOT EXISTS agent.workflow_api_key (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name            TEXT NOT NULL,
    key_prefix      TEXT NOT NULL,
    key_hash        TEXT NOT NULL,
    owner_id        TEXT NOT NULL,
    process_def_ids TEXT NOT NULL,
    created_at      BIGINT NOT NULL,
    last_used_at    BIGINT,
    revoked_at      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_workflow_api_key_prefix
    ON agent.workflow_api_key (key_prefix);
CREATE INDEX IF NOT EXISTS idx_workflow_api_key_owner
    ON agent.workflow_api_key (owner_id, revoked_at);

