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
CREATE INDEX IF NOT EXISTS idx_hook_mappings_hook_point ON agent.hook_mappings (hook_point);
CREATE INDEX IF NOT EXISTS idx_runtime_traces_session_id ON agent.runtime_traces (session_id);

CREATE TABLE IF NOT EXISTS agent.system_toolchain_proposals (
                                                          id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id        TEXT NOT NULL REFERENCES agent.chat_sessions (session_id) ON DELETE CASCADE,
    turn_id           TEXT NOT NULL,
    user_id           TEXT,
    status            TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'materialized', 'failed')),
    reason            TEXT,
    confidence        TEXT,
    trace_path        TEXT,
    durable_trace_json JSONB,
    user_prompt       TEXT,
    assistant_response TEXT,
    model_provider_id TEXT,
    model_id          TEXT,
    decision_comment  TEXT,
    decided_by        TEXT,
    decided_at        BIGINT,
    tool_chain_id     TEXT REFERENCES agent.tool_chains (id) ON DELETE SET NULL,
    error_message     TEXT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    UNIQUE (session_id, turn_id)
    );
ALTER TABLE agent.system_toolchain_proposals ADD COLUMN IF NOT EXISTS durable_trace_json JSONB;

CREATE INDEX IF NOT EXISTS idx_system_toolchain_proposals_user_status
    ON agent.system_toolchain_proposals (user_id, status, created_at DESC);

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

CREATE TABLE IF NOT EXISTS agent.tool_chains (
                                                 id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    status          TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published', 'disabled')),
    current_version INTEGER,
    origin          TEXT NOT NULL DEFAULT 'user' CHECK (origin IN ('user', 'system_suggested')),
    approval_status TEXT NOT NULL DEFAULT 'approved' CHECK (approval_status IN ('pending', 'approved', 'rejected')),
    approved_by     TEXT,
    approved_at     BIGINT,
    intent_signature TEXT,
    structure_signature TEXT,
    metadata_json   TEXT,
    created_by      TEXT,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL
    );
ALTER TABLE agent.tool_chains ADD COLUMN IF NOT EXISTS origin TEXT NOT NULL DEFAULT 'user';
ALTER TABLE agent.tool_chains ADD COLUMN IF NOT EXISTS approval_status TEXT NOT NULL DEFAULT 'approved';
ALTER TABLE agent.tool_chains ADD COLUMN IF NOT EXISTS approved_by TEXT;
ALTER TABLE agent.tool_chains ADD COLUMN IF NOT EXISTS approved_at BIGINT;
ALTER TABLE agent.tool_chains ADD COLUMN IF NOT EXISTS intent_signature TEXT;
ALTER TABLE agent.tool_chains ADD COLUMN IF NOT EXISTS structure_signature TEXT;
UPDATE agent.tool_chains SET origin = 'user' WHERE origin IS NULL;
UPDATE agent.tool_chains SET approval_status = 'approved' WHERE approval_status IS NULL;

CREATE TABLE IF NOT EXISTS agent.tool_chain_versions (
                                                         id                 TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    tool_chain_id      TEXT NOT NULL REFERENCES agent.tool_chains (id) ON DELETE CASCADE,
    version            INTEGER NOT NULL,
    graph_json         TEXT NOT NULL,
    input_schema       TEXT,
    output_schema      TEXT,
    response_mode      TEXT NOT NULL DEFAULT 'hybrid',
    synthesis_prompt   TEXT,
    intents_json       TEXT,
    intent_signature   TEXT,
    structure_signature TEXT,
    rag_config_json    TEXT,
    variables_json     TEXT,
    is_published       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by         TEXT,
    created_at         BIGINT NOT NULL,
    UNIQUE (tool_chain_id, version)
    );
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS intents_json TEXT;
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS intent_signature TEXT;
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS structure_signature TEXT;
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS rag_config_json TEXT;
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS variables_json TEXT;

CREATE TABLE IF NOT EXISTS agent.tool_chain_runs (
                                                     id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    tool_chain_id    TEXT NOT NULL REFERENCES agent.tool_chains (id) ON DELETE CASCADE,
    tool_chain_version_id TEXT NOT NULL REFERENCES agent.tool_chain_versions (id) ON DELETE CASCADE,
    version          INTEGER NOT NULL,
    trigger_source   TEXT NOT NULL CHECK (trigger_source IN ('chat', 'api', 'manual', 'rerun')),
    initiated_by     TEXT,
    status           TEXT NOT NULL CHECK (status IN ('queued', 'running', 'waiting_for_approval', 'success', 'failed', 'rejected', 'cancelled')),
    started_at       BIGINT NOT NULL,
    ended_at         BIGINT,
    duration_ms      BIGINT,
    input_snapshot   TEXT,
    output_snapshot  TEXT,
    error_message    TEXT
    );

CREATE TABLE IF NOT EXISTS agent.tool_chain_run_steps (
                                                          id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    run_id           TEXT NOT NULL REFERENCES agent.tool_chain_runs (id) ON DELETE CASCADE,
    node_id          TEXT NOT NULL,
    node_type        TEXT NOT NULL,
    tool_ref         TEXT,
    branch_path      TEXT,
    status           TEXT NOT NULL CHECK (status IN ('queued', 'running', 'waiting_for_approval', 'success', 'failed', 'skipped', 'rejected')),
    retry_count      INTEGER NOT NULL DEFAULT 0,
    input_payload    TEXT,
    output_payload   TEXT,
    error_message    TEXT,
    started_at       BIGINT NOT NULL,
    ended_at         BIGINT
    );

CREATE TABLE IF NOT EXISTS agent.tool_chain_approvals (
                                                          id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    run_id           TEXT NOT NULL REFERENCES agent.tool_chain_runs (id) ON DELETE CASCADE,
    step_id          TEXT NOT NULL REFERENCES agent.tool_chain_run_steps (id) ON DELETE CASCADE,
    node_id          TEXT NOT NULL,
    request_id       TEXT NOT NULL UNIQUE,
    approval_group   TEXT,
    prompt           TEXT NOT NULL,
    status           TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'timeout')),
    decision_by      TEXT,
    decision_comment TEXT,
    created_at       BIGINT NOT NULL,
    decided_at       BIGINT
    );

CREATE INDEX IF NOT EXISTS idx_tool_chain_versions_chain ON agent.tool_chain_versions (tool_chain_id, version DESC);
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS intent_signature TEXT;
ALTER TABLE agent.tool_chain_versions ADD COLUMN IF NOT EXISTS structure_signature TEXT;
CREATE INDEX IF NOT EXISTS idx_tool_chain_versions_signatures ON agent.tool_chain_versions (intent_signature, structure_signature);
CREATE INDEX IF NOT EXISTS idx_tool_chains_origin_approval ON agent.tool_chains (origin, approval_status, updated_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tool_chain_system_signatures
    ON agent.tool_chains (intent_signature, structure_signature)
    WHERE origin = 'system_suggested' AND intent_signature IS NOT NULL AND structure_signature IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tool_chain_runs_chain ON agent.tool_chain_runs (tool_chain_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_chain_runs_status ON agent.tool_chain_runs (status, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_chain_steps_run ON agent.tool_chain_run_steps (run_id, started_at ASC);
CREATE INDEX IF NOT EXISTS idx_tool_chain_approvals_pending ON agent.tool_chain_approvals (status, created_at DESC);

CREATE TABLE IF NOT EXISTS agent.tool_chain_config_sessions (
                                                                id                  TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    tool_chain_id       TEXT NOT NULL REFERENCES agent.tool_chains (id) ON DELETE CASCADE,
    title               TEXT,
    status              TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'clarification_required', 'ready_to_compile', 'compiled')),
    latest_artifact_json TEXT,
    pending_question_json TEXT,
    created_by          TEXT,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL,
    archived_at         BIGINT
    );
ALTER TABLE agent.tool_chain_config_sessions ADD COLUMN IF NOT EXISTS archived_at BIGINT;

CREATE TABLE IF NOT EXISTS agent.tool_chain_config_messages (
                                                                id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id       TEXT NOT NULL REFERENCES agent.tool_chain_config_sessions (id) ON DELETE CASCADE,
    role             TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content          TEXT NOT NULL,
    metadata_json    TEXT,
    created_at       BIGINT NOT NULL
    );
-- Allow the timeline to also persist system events (tool calls, questions,
-- approvals) so reload restores the full conversation including HITL cards.
ALTER TABLE agent.tool_chain_config_messages DROP CONSTRAINT IF EXISTS tool_chain_config_messages_role_check;
ALTER TABLE agent.tool_chain_config_messages ADD CONSTRAINT tool_chain_config_messages_role_check CHECK (role IN ('user', 'assistant', 'system'));
ALTER TABLE agent.tool_chain_config_messages ADD COLUMN IF NOT EXISTS event_type TEXT;
ALTER TABLE agent.tool_chain_config_messages ADD COLUMN IF NOT EXISTS event_payload TEXT;
ALTER TABLE agent.tool_chain_config_messages ADD COLUMN IF NOT EXISTS request_id TEXT;
ALTER TABLE agent.tool_chain_config_messages ADD COLUMN IF NOT EXISTS hitl_status TEXT;
ALTER TABLE agent.tool_chain_config_messages ADD COLUMN IF NOT EXISTS hitl_response TEXT;

CREATE INDEX IF NOT EXISTS idx_tool_chain_config_sessions_chain ON agent.tool_chain_config_sessions (tool_chain_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_chain_config_messages_session ON agent.tool_chain_config_messages (session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_tool_chain_config_messages_request ON agent.tool_chain_config_messages (request_id) WHERE request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent.tool_chain_config_layouts (
                                                               id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    tool_chain_id    TEXT NOT NULL REFERENCES agent.tool_chains (id) ON DELETE CASCADE,
    session_id       TEXT NOT NULL REFERENCES agent.tool_chain_config_sessions (id) ON DELETE CASCADE,
    user_id          TEXT NOT NULL,
    positions_json   TEXT,
    viewport_json    TEXT,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL,
    UNIQUE (tool_chain_id, session_id, user_id)
    );

CREATE INDEX IF NOT EXISTS idx_tool_chain_config_layouts_scope ON agent.tool_chain_config_layouts (tool_chain_id, session_id, user_id);

CREATE TABLE IF NOT EXISTS agent.tool_chain_user_layouts (
                                                             id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
    tool_chain_id    TEXT NOT NULL REFERENCES agent.tool_chains (id) ON DELETE CASCADE,
    user_id          TEXT NOT NULL,
    positions_json   TEXT,
    viewport_json    TEXT,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL,
    UNIQUE (tool_chain_id, user_id)
    );

CREATE INDEX IF NOT EXISTS idx_tool_chain_user_layouts_scope ON agent.tool_chain_user_layouts (tool_chain_id, user_id);

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
