# AI Agent Platform — Full Technical Documentation

> **Last updated:** 2026-04-28  
> **Stack:** Spring Boot 3 · Java 21 · Spring AI · React 18 · TypeScript · PostgreSQL + pgvector · Azure Blob Storage

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [API Reference](#3-api-reference)
   - [Chat](#31-chat--apiv1chat)
   - [Sessions](#32-sessions--apiv1sessions)
   - [Tools & Domains](#33-tools--domains--apiv1tool-domains)
   - [Tool Auth Profiles](#34-tool-auth-profiles--apiv1tool-auth)
   - [Skills](#35-skills--apiv1skills)
   - [Models](#36-models--apiv1models--apiv1providers)
   - [MCP Registry](#37-mcp-registry--apiv1mcp-registry)
   - [Platform Admin](#38-platform-admin--apiv1platform)
   - [Auth](#39-auth--apiv1auth)
4. [Domain Models](#4-domain-models)
5. [Database Schema](#5-database-schema)
6. [Configuration Reference](#6-configuration-reference)
7. [Core Services](#7-core-services)
8. [Agent & Streaming Internals](#8-agent--streaming-internals)
9. [Tool Execution Flow](#9-tool-execution-flow-end-to-end)
10. [Memory System](#10-memory-system)
11. [Skills System](#11-skills-system)
12. [MCP Integration](#12-mcp-model-context-protocol-integration)
13. [Embedding & Semantic Tool Routing](#13-embedding--semantic-tool-routing)
14. [Human-in-the-Loop (HITL)](#14-human-in-the-loop-hitl)
15. [Security](#15-security)
16. [Frontend Pages](#16-frontend-pages)
17. [SSE Event Reference](#17-sse-event-reference)

---

## 1. Overview

This is an **enterprise AI agent platform** — a full-stack system that connects large language models to real-world APIs and business logic via a managed tool-calling loop. It is not a chatbot wrapper; it is an orchestration engine with:

- **Multi-model routing** — OpenAI, Azure OpenAI, Anthropic Claude, Ollama, Google Vertex
- **Tool registry** — import tools from OpenAPI specs, cURL commands, or manual definition
- **Semantic tool selection** — pgvector embeddings rank the most relevant tools per query
- **Skills engine** — domain rules and prompting logic encoded as Markdown files
- **Persistent memory** — user-scoped memory stored in Azure Blob, injected into context
- **Human-in-the-loop (HITL)** — approval gates and interactive questions mid-turn
- **Streaming chat** — SSE-based real-time output with live tool-call visibility
- **Full audit trail** — every tool call, decision, and token persisted to PostgreSQL

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    React UI (TypeScript)                  │
│   ChatPage · ToolsPage · SkillsPage · ModelsPage · Admin │
└──────────────────────────┬───────────────────────────────┘
                           │  HTTP REST + SSE
┌──────────────────────────┴───────────────────────────────┐
│              Spring Boot API Layer (Java 21)              │
│  ChatController · ToolController · SkillController · ...  │
└──────────────────────────┬───────────────────────────────┘
                           │
┌──────────────────────────┴───────────────────────────────┐
│                    Service Layer                          │
│  ChatService → AgentRuntimeService → AgentOrchestrator   │
│  ToolRegistryService · SkillRegistryService              │
│  MemoryService · McpClientService · ToolExecutionService │
└────────────┬──────────────────────────┬──────────────────┘
             │                          │
┌────────────┴──────────┐  ┌───────────┴──────────────────┐
│   PostgreSQL (agent.) │  │  Azure Blob Storage           │
│   21 tables + pgvector│  │  skills/ · memories/          │
└───────────────────────┘  └──────────────────────────────┘
```

**Request path for a single chat turn:**

```
POST /api/v1/chat
  → ChatService.handleChatAsync()
    → AgentRuntimeService.runTurn()
      → tool selection (embedding + memory signals)
      → AgentOrchestrator.streamTurn()
        → Spring AI ChatClient → LLM provider
          → tool call → AgentToolCallback.call()
            → ToolExecutionService.execute()
              → HTTP / MCP / filesystem / shell
          → SSE: text.delta, tool.call, tool.result, done
```

---

## 3. API Reference

All endpoints are under `/api/v1`. Authentication is via JWT Bearer token in the `Authorization` header.

---

### 3.1 Chat — `/api/v1/chat`

#### `POST /api/v1/chat`
Start a streaming chat turn. Returns an SSE stream.

**Request body (`ChatRequest`):**

| Field | Type | Required | Description |
|---|---|---|---|
| `message` | string | no* | User message text |
| `sessionId` | string | no | Resume existing session (creates new if absent) |
| `timezone` | string | no | User timezone (e.g. `America/New_York`) |
| `runtimeMode` | string | no | `aicore_loop` (default) |
| `modelSelectionMode` | string | no | `auto` or `manual` |
| `model` | object | no | `{providerID, modelID}` for manual selection |
| `embeddingModel` | object | no | `{providerID, modelID}` override |
| `attachments` | array | no | Max 5, max 5 MB each. Allowed: txt, md, csv, json, xml, yaml, log, pdf, doc, docx, xls, xlsx, ppt, pptx, png, jpg, jpeg, webp |

*Either `message` or `attachments` must be present.

**Response:** SSE stream — see [Section 17: SSE Event Reference](#17-sse-event-reference)

---

#### `POST /api/v1/chat/reply`
Reply to a `question` interaction mid-turn.

**Request body:**

| Field | Type | Description |
|---|---|---|
| `requestId` | string | ID from the `question` SSE event |
| `message` | string | Free-text reply |
| `selectedOptionIds` | string[] | Selected option IDs (for multi-choice) |

**Response:** `{ ok: true, requestId }`

---

#### `POST /api/v1/chat/approve`
Approve a pending HITL action.

**Request body:** same as `/reply`  
**Response:** `{ ok: true, requestId, action: "approve" }`

---

#### `POST /api/v1/chat/reject`
Reject a pending HITL action.

**Request body:** same as `/reply`  
**Response:** `{ ok: true, requestId, action: "reject" }`

---

#### `POST /api/v1/chat/sessions/{sessionId}/cancel`
Cancel the active streaming turn for a session.

**Response:** `{ ok: true, sessionId, cancelled: true }`

---

#### `POST /api/v1/chat/sessions/{sessionId}/truncate`
Delete all messages from a given message ID onwards (inclusive).

**Request body:** `{ messageId: string }`  
**Response:** `{ ok: true, sessionId, fromMessageId, deletedCount }`

---

#### `DELETE /api/v1/chat/messages/{messageId}`
Delete a single message by ID.

**Response:** `{ deleted: true, messageId }`

---

#### `GET /api/v1/chat/history/{sessionId}?limit=100&offset=0`
Retrieve paginated message history.

**Response:**
```json
{
  "sessionId": "...",
  "limit": 100,
  "offset": 0,
  "total": 42,
  "hasMore": false,
  "messageCount": 42,
  "messages": [
    { "id": "...", "role": "user|assistant", "content": "...", "turnId": "...", "createdAt": 1234567890 }
  ]
}
```

---

#### `GET /api/v1/chat/cost/{sessionId}`
Get session token usage and estimated cost.

**Response:** `{ sessionId, summary: { totalTokens, estimatedCostUsd, ... }, turns: [...] }`

---

#### `GET /api/v1/chat/pending/{sessionId}`
List outstanding HITL interactions waiting for user action.

**Response:** `{ sessionId, pending: [ HitlInteraction... ] }`

---

#### `GET /api/v1/chat/events/{sessionId}`
Get the full event trace for a session (tool calls, questions, approvals, reasoning).

**Response:**
```json
{
  "sessionId": "...",
  "events": [
    {
      "id": "...",
      "turnId": "...",
      "eventType": "tool.call | tool.done | question | approval_required | reasoning",
      "payload": "{ ... JSON string ... }",
      "createdAt": 1234567890,
      "hitlStatus": "pending | reply | approved | rejected",
      "hitlResponse": "..."
    }
  ]
}
```

---

### 3.2 Sessions — `/api/v1/sessions`

#### `GET /api/v1/sessions?limit=100&offset=0&archived=false`
List chat sessions for the authenticated user.

**Query params:**
- `archived` — `true` for archived only, `false` for active only, omit for all

**Response:** `{ sessions: [...], limit, offset, count, total, hasMore }`

---

#### `GET /api/v1/sessions/{sessionId}`
Get session metadata.

**Response:** `{ sessionId, userId, createdAt, lastActive, timezone, title }`

---

#### `PATCH /api/v1/sessions/{sessionId}`
Rename session title.

**Request body:** `{ title: string }`  
**Response:** `{ ok: true, sessionId, title }`

---

#### `POST /api/v1/sessions/{sessionId}/archive`
Archive or restore a session.

**Request body:** `{ restore: true }` to unarchive (optional)  
**Response:** `{ ok: true, sessionId, archived: true|false }`

---

#### `GET /api/v1/sessions/{sessionId}/messages?limit=100&offset=0`
Get messages for a session.

**Response:** `{ sessionId, messages: [...], limit, offset, total, hasMore }`

---

#### `DELETE /api/v1/sessions/{sessionId}`
Delete session and all associated messages.

**Response:** `{ deleted: true, sessionId }`

---

### 3.3 Tools & Domains — `/api/v1/tool-domains`

#### `GET /api/v1/tool-domains`
List all user-visible tool domains. Hides framework and MCP system domains.

**Response:** `[ { id, name, description, enabled, createdAt, updatedAt } ]`

---

#### `POST /api/v1/tool-domains`
Create a new tool domain.

**Request body:**

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |
| `description` | string | no |
| `enabled` | boolean | no (default true) |

---

#### `PATCH /api/v1/tool-domains/{domainId}`
Update domain name, description, or enabled state.

---

#### `DELETE /api/v1/tool-domains/{domainId}`
Delete a domain and all its tools (cascades).

---

#### `GET /api/v1/tool-domains/{domainId}/tools`
List all tools in a domain.

---

#### `POST /api/v1/tool-domains/{domainId}/tools`
Create a tool manually.

**Request body (`ToolRequest`):**

| Field | Type | Description |
|---|---|---|
| `name` | string | Tool name (used as function name by LLM) |
| `description` | string | What the tool does |
| `sourceType` | string | `manual` \| `openapi_import` \| `curl_import` \| `framework_default` |
| `executionKind` | string | `http_proxy` \| `filesystem` \| `shell` \| `mcp` \| `skill` |
| `permissionScope` | string | `filesystem` \| `shell` \| `web` \| `workflow` \| `memory` \| `integration` \| `http_proxy` |
| `requiresApproval` | boolean | Require HITL approval before every execution |
| `modelGate` | string | Restrict to specific model ID |
| `providerGate` | string | Restrict to specific provider |
| `experimental` | boolean | Gated by `enable-experimental-tools` config |
| `method` | string | HTTP method: `GET` \| `POST` \| `PUT` \| `PATCH` \| `DELETE` |
| `host` | string | Base URL (e.g. `https://api.example.com`) |
| `endpoint` | string | Path (e.g. `/orders/{orderId}`) — supports `{param}` placeholders |
| `requestSchema` | string | JSON Schema for input |
| `responseSchema` | string | JSON Schema for output |
| `sampleRequest` | string | Example request JSON |
| `sampleResponse` | string | Example response JSON |
| `authProfileId` | string | Link to shared auth profile |
| `authOverrideEnabled` | boolean | Use tool-level auth override |
| `authType` | string | `none` \| `api_key` \| `oauth2` \| `basic` |
| `authConfig` | string | Auth configuration JSON |
| `enabled` | boolean | Whether tool is active |

---

#### `PATCH /api/v1/tool-domains/{domainId}/tools/{toolId}`
Update a tool.

---

#### `POST /api/v1/tool-domains/{domainId}/tools/{toolId}/enable`
Enable a tool. **Response:** `{ enabled: true, toolId }`

---

#### `POST /api/v1/tool-domains/{domainId}/tools/{toolId}/disable`
Disable a tool. **Response:** `{ enabled: false, toolId }`

---

#### `DELETE /api/v1/tool-domains/{domainId}/tools/{toolId}`
Delete a tool.

---

#### `POST /api/v1/tool-domains/import/openapi`
Import tools from an OpenAPI 3.0 specification.

**Request body:**

| Field | Type | Description |
|---|---|---|
| `domainId` | string | Target domain |
| `spec` | string | Raw OpenAPI YAML or JSON |
| `specUrl` | string | URL to fetch spec from (alternative to `spec`) |
| `enabled` | boolean | Enable imported tools |

**Response:** `{ importedCount: N, tools: [...] }`

---

#### `POST /api/v1/tool-domains/import/curl`
Import a tool from a cURL command.

**Request body:**

| Field | Type | Description |
|---|---|---|
| `domainId` | string | Target domain |
| `curlCommand` | string | Full cURL command string |
| `toolName` | string | Name to assign |
| `description` | string | Tool description |
| `responseSample` | string | Example response (improves schema generation) |
| `enabled` | boolean | Enable imported tool |

---

#### `POST /api/v1/tool-domains/install/framework-defaults`
Install or update built-in framework tools (memory, filesystem, shell, web, workflow).

**Response:** `{ created: N, updated: N, total: N }`

---

### 3.4 Tool Auth Profiles — `/api/v1/tool-auth`

Auth profiles are domain-level reusable credential sets that multiple tools can share.

#### `GET /api/v1/tool-auth/domains/{domainId}/profiles`
List auth profiles for a domain.

---

#### `POST /api/v1/tool-auth/domains/{domainId}/profiles`
Create an auth profile.

**Request body:**

| Field | Type | Description |
|---|---|---|
| `name` | string | Profile display name |
| `authType` | string | `none` \| `api_key` \| `oauth2` \| `basic` |
| `authConfig` | string | JSON config (headers, param names, etc.) |
| `clientId` | string | OAuth2 client ID |
| `tokenUrl` | string | OAuth2 token endpoint |
| `authorizationUrl` | string | OAuth2 authorization endpoint |
| `redirectUri` | string | OAuth2 redirect URI |
| `scopes` | string | Space-separated OAuth2 scopes |

---

#### `PATCH /api/v1/tool-auth/domains/{domainId}/profiles/{profileId}`
Update an auth profile.

---

#### `DELETE /api/v1/tool-auth/domains/{domainId}/profiles/{profileId}`
Delete an auth profile. Tools that referenced it fall back to `none`.

---

#### `PATCH /api/v1/tool-auth/domains/{domainId}/tools/{toolId}/binding`
Bind a tool to an auth profile.

**Request body:** `{ authProfileId: string, authOverrideEnabled: boolean }`

---

### 3.5 Skills — `/api/v1/skills`

#### `GET /api/v1/skills`
List all skills (DB records only).

---

#### `GET /api/v1/skills/registry`
List all active skills — includes DB skills **and** built-in defaults from classpath.

**Response:** `[ { id, name, description, isDefault, files: [...] } ]`

---

#### `POST /api/v1/skills`
Create a skill (empty shell — add files separately).

**Request body:** `{ name, description, enabled }`

---

#### `PATCH /api/v1/skills/{skillId}` / `POST .../enable` / `POST .../disable`
Update skill metadata or toggle enabled state.

---

#### `DELETE /api/v1/skills/{skillId}`
Delete a skill and all its files.

---

#### `GET /api/v1/skills/{skillId}/files`
List all files for a skill.

---

#### `POST /api/v1/skills/{skillId}/files`
Create or update (upsert by `filePath`) a skill file.

**Request body:** `{ filePath, content, mimeType }`

---

#### `GET /api/v1/skills/{skillId}/files/{fileId}`
Download file content.

**Response:** `{ id, filePath, mimeType, content }`

---

#### `PATCH /api/v1/skills/{skillId}/files/{fileId}`
Update file content.

---

#### `DELETE /api/v1/skills/{skillId}/files/{fileId}`
Delete a file. `SKILL.md` (root file) cannot be deleted.

---

#### `GET /api/v1/skills/{skillId}/tree`
Get directory tree for a skill.

**Response:** `{ skillId, files: [ { id, path, name, sizeBytes } ] }`

---

#### `POST /api/v1/skills/upload` (multipart)
Upload a skill package.

**Form fields:**
- `file` — `.md`, `.zip`, or `.skill` file
- `name` — optional display name override

ZIP files are unpacked and the common root directory is stripped. Each extracted file becomes a `SkillFile` record.

---

### 3.6 Models — `/api/v1/models` & `/api/v1/providers`

#### `GET /api/v1/providers`
List all AI providers (from models.dev catalog + configured providers).

**Response:** `[ { providerId, providerName, models: [...] } ]`

---

#### `GET /api/v1/models`
List all models (catalog + DB overrides merged).

---

#### `GET /api/v1/models/enabled`
List enabled models only.

---

#### `GET /api/v1/models/{providerID}/{modelID}`
Get a specific model config.

---

#### `POST /api/v1/models/{providerID}/{modelID}/enable`
Enable a model.

---

#### `POST /api/v1/models/{providerID}/{modelID}/disable`
Disable a model.

---

#### `DELETE /api/v1/models/{providerID}/{modelID}`
Remove a DB override (model reverts to catalog defaults).

---

### 3.7 MCP Registry — `/api/v1/mcp-registry`

#### `GET /api/v1/mcp-registry`
List all registered MCP servers.

---

#### `POST /api/v1/mcp-registry`
Register a new MCP server.

**Request body:**

| Field | Type | Description |
|---|---|---|
| `name` | string | Unique display name |
| `transportType` | string | `streamable_http` (default) |
| `baseUrl` | string | Base URL (e.g. `https://mcp.example.com`) |
| `endpoint` | string | Tool execution path (e.g. `/mcp/`) |
| `ssePath` | string | SSE path for streaming transport |
| `streamablePath` | string | Streamable HTTP path |
| `healthPath` | string | Health check path |
| `verifyTls` | boolean | Verify TLS cert (default `true`) |
| `connectTimeoutMs` | int | Connection timeout (default 10000) |
| `readTimeoutMs` | int | Read timeout (default 30000) |
| `authType` | string | `none` \| `oauth2` \| `api_key` \| `basic` |
| `authConfig` | string | Auth JSON config |
| `headersJson` | string | Extra request headers JSON |
| `queryJson` | string | Extra query parameters JSON |

**Response includes:** `nextAction` hint (e.g. `oauth2_needed`, `ready`)

---

#### `PATCH /api/v1/mcp-registry/{id}`
Update an MCP server registration.

---

#### `DELETE /api/v1/mcp-registry/{id}`
Delete an MCP server and its synced tools and domain.

---

### 3.8 Platform Admin — `/api/v1/platform`

#### Guardrail Policies

`GET /api/v1/platform/policies` — list all policies  
`POST /api/v1/platform/policies` — create policy  
`DELETE /api/v1/platform/policies/{id}` — delete policy

**Policy fields:**

| Field | Type | Description |
|---|---|---|
| `name` | string | Unique name |
| `scope` | string | What this policy applies to |
| `ruleType` | string | Type of rule |
| `ruleValue` | string | Rule value or pattern |
| `decision` | string | `allow` \| `ask` (HITL) \| `deny` |
| `enabled` | boolean | Whether policy is active |

---

#### Agent Profiles

`GET /api/v1/platform/agent-profiles` — list all profiles  
`POST /api/v1/platform/agent-profiles` — create profile  
`PATCH /api/v1/platform/agent-profiles/{id}` — update profile  
`DELETE /api/v1/platform/agent-profiles/{id}` — delete profile

**Profile fields:**

| Field | Type | Description |
|---|---|---|
| `name` | string | Unique name |
| `mode` | string | `planner_worker` \| `swarm` |
| `systemPrompt` | string | Custom system prompt override |
| `modelStrategy` | string | `manual` \| `auto` \| `balanced` |
| `enabled` | boolean | Whether profile is active |
| `metadata` | string | JSON metadata |

---

### 3.9 Auth — `/api/v1/auth`

#### `POST /api/v1/auth/signup`
Register a new user.

**Request body:** `{ email, password }`  
**Response:** `{ userId, token, expiresIn }`

---

#### `POST /api/v1/auth/login`
Authenticate an existing user.

**Request body:** `{ email, password }`  
**Response:** `{ userId, token, expiresIn }`

---

## 4. Domain Models

All entities in `src/main/java/com/pods/agent/domain/`.

### User
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `email` | String | Unique |
| `passwordHash` | String | BCrypt |
| `createdAt` | long | Epoch ms |
| `updatedAt` | long | Epoch ms |

### ChatSession
| Field | Type | Notes |
|---|---|---|
| `sessionId` | String (UUID) | PK |
| `userId` | String | FK → users |
| `createdAt` | long | |
| `lastActive` | long | |
| `timezone` | String | nullable |
| `title` | String | LLM-generated, nullable |
| `archivedAt` | Long | null = active |

### ChatMessage
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `sessionId` | String | FK → chat_sessions |
| `role` | String | `user` \| `assistant` |
| `content` | String | nullable |
| `turnId` | String | nullable, groups turn messages |
| `createdAt` | long | |

### AgentDomain
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `name` | String | Unique |
| `description` | String | nullable |
| `enabled` | boolean | |
| `createdAt` | long | |
| `updatedAt` | long | |

### AgentTool
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `domainId` | String | FK → agent_domains |
| `name` | String | Used as LLM function name |
| `description` | String | |
| `sourceType` | String | `manual` \| `openapi_import` \| `curl_import` \| `framework_default` |
| `executionKind` | String | `http_proxy` \| `filesystem` \| `shell` \| `mcp` \| `skill` |
| `permissionScope` | String | |
| `requiresApproval` | boolean | |
| `modelGate` | String | nullable |
| `providerGate` | String | nullable |
| `experimental` | boolean | |
| `method` | String | HTTP verb |
| `host` | String | |
| `endpoint` | String | |
| `requestSchema` | String | JSON Schema |
| `responseSchema` | String | JSON Schema |
| `sampleRequest` | String | |
| `sampleResponse` | String | |
| `authProfileId` | String | nullable |
| `authType` | String | |
| `authConfig` | String | JSON |
| `clientId` | String | OAuth2 |
| `encryptedClientSecret` | String | AES-256 encrypted |
| `tokenUrl` | String | |
| `authorizationUrl` | String | |
| `scopes` | String | Space-separated |
| `encryptedAccessToken` | String | AES-256 encrypted |
| `encryptedRefreshToken` | String | AES-256 encrypted |
| `tokenExpiresAt` | Long | nullable |
| `enabled` | boolean | |
| `baseInjected` | boolean | Always included in context |
| `createdAt` | long | |
| `updatedAt` | long | |

### Skill
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `name` | String | Unique |
| `description` | String | |
| `enabled` | boolean | |
| `createdAt` | long | |
| `updatedAt` | long | |

### SkillFile
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `skillId` | String | FK → skills |
| `filePath` | String | Relative path, unique per skill |
| `blobPath` | String | Azure Blob path |
| `mimeType` | String | |
| `contentSha256` | String | SHA-256 hex |
| `sizeBytes` | long | |
| `createdAt` | long | |
| `updatedAt` | long | |

### Memory
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `userId` | String | FK → users |
| `sessionId` | String | nullable |
| `category` | String | `user` \| `feedback` \| `project` \| `reference` |
| `memoryFilePath` | String | Logical path, unique per user |
| `content` | String | Markdown content |
| `tags` | List\<String\> | |
| `createdAt` | long | |
| `updatedAt` | long | |

### RuntimeEvent
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `sessionId` | String | FK |
| `turnId` | String | nullable |
| `eventType` | String | See event types below |
| `payload` | String | JSON string |
| `createdAt` | long | |

**Event types:** `tool.match`, `tool.call`, `tool.done`, `tool.result`, `question`, `approval_required`, `reasoning`, `plan.created`, `task.started`, `task.done`, `state.updated`

### HitlInteraction
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `sessionId` | String | FK |
| `turnId` | String | nullable |
| `type` | String | `question` \| `approval_required` |
| `prompt` | String | Displayed to user |
| `status` | String | `pending` \| `reply` \| `approved` \| `rejected` |
| `responseText` | String | nullable |
| `createdAt` | long | |
| `resolvedAt` | Long | nullable |

### CostUsage
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `sessionId` | String | FK |
| `turnId` | String | nullable |
| `providerId` | String | |
| `modelId` | String | |
| `promptTokens` | long | |
| `completionTokens` | long | |
| `totalTokens` | long | |
| `estimatedCostUsd` | double | |
| `createdAt` | long | |

### McpRegistryEntry
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `name` | String | Unique |
| `transportType` | String | `streamable_http` |
| `baseUrl` | String | |
| `endpoint` | String | |
| `authType` | String | |
| `discoveredToolsJson` | String | JSON array of discovered tools |
| `discoveredToolsCount` | Integer | |
| `lastVerifiedAt` | Long | |
| `lastStatus` | String | |
| `lastError` | String | nullable |
| `enabled` | boolean | |
| `createdAt` | long | |
| `updatedAt` | long | |

### ModelConfig
| Field | Type | Notes |
|---|---|---|
| `providerId` | String | |
| `modelId` | String | |
| `providerName` | String | |
| `displayName` | String | |
| `contextWindow` | Long | Token limit |
| `supportsTools` | boolean | |
| `supportsVision` | boolean | |
| `supportsStreaming` | boolean | |
| `supportsReasoning` | boolean | Extended thinking |
| `costInput` | Double | Per 1M tokens |
| `costOutput` | Double | Per 1M tokens |
| `enabled` | boolean | |
| `source` | String | `catalog` \| `db` |
| `hasKey` | boolean | API key configured |
| `modelKind` | String | `chat` \| `embedding` |
| `defaultModel` | boolean | |
| `embeddingDimensions` | Integer | nullable |

### GuardrailPolicy
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `name` | String | Unique |
| `scope` | String | |
| `ruleType` | String | |
| `ruleValue` | String | |
| `decision` | String | `allow` \| `ask` \| `deny` |
| `enabled` | boolean | |

### AgentProfile
| Field | Type | Notes |
|---|---|---|
| `id` | String (UUID) | PK |
| `name` | String | Unique |
| `mode` | String | `planner_worker` \| `swarm` |
| `systemPrompt` | String | |
| `modelStrategy` | String | `manual` \| `auto` \| `balanced` |
| `enabled` | boolean | |
| `metadata` | String | JSON |

---

## 5. Database Schema

**Database:** PostgreSQL  
**Schema:** `agent`  
**Extension required:** `pgvector`

### Tables

| Table | Description |
|---|---|
| `users` | User accounts |
| `chat_sessions` | Chat sessions per user |
| `chat_messages` | Individual messages per session |
| `agent_domains` | Tool domain groupings |
| `agent_tools` | Tool definitions |
| `tool_auth_profiles` | Shared auth configs per domain |
| `tool_versions` | Tool schema versioning |
| `skills` | Skill definitions |
| `skill_files` | Skill file metadata |
| `agent_profiles` | Agent mode/prompt configs |
| `guardrail_policies` | Access control rules |
| `runtime_events` | Execution event log |
| `runtime_traces` | Detailed execution traces |
| `cost_usage` | Token/cost tracking per turn |
| `eval_runs` | Evaluation harness results |
| `mcp_registry` | Registered MCP servers |
| `hitl_interactions` | HITL request/response records |
| `hook_mappings` | Runtime hook registrations |
| `session_context_state` | Per-session agent state |
| `memories` | User long-term memory records |
| `agent_tool_embeddings` | pgvector embeddings for tools (max 3072 dims) |
| `supported_models` | DB overrides for AI model catalog |

### Key FK Relationships

```
users
  ↓ (userId)
chat_sessions
  ↓ (sessionId)
chat_messages, runtime_events, cost_usage, hitl_interactions, session_context_state, memories

agent_domains
  ↓ (domainId)
agent_tools
  ↓ (toolId)
agent_tool_embeddings   [ON DELETE CASCADE]
tool_versions
tool_auth_profiles (domainId)

skills
  ↓ (skillId)
skill_files

mcp_registry → agent_domains → agent_tools → agent_tool_embeddings
```

### Embedding Index

Table: `agent_tool_embeddings`

| Column | Type | Notes |
|---|---|---|
| `tool_id` | varchar | FK → agent_tools (CASCADE) |
| `model_provider` | varchar | |
| `model_id` | varchar | |
| `dimensions` | int | Actual embedding dimensions |
| `content_hash` | varchar | SHA-256 of embedded text |
| `embedding` | vector(3072) | Padded/truncated to 3072 |
| `updated_at` | bigint | |

---

## 6. Configuration Reference

File: `src/main/resources/application.yaml`

### Database
```yaml
spring.datasource.url: jdbc:postgresql://localhost:5432/pods_ai_agent
spring.datasource.username: postgres
spring.datasource.password: <password>
spring.datasource.driver-class-name: org.postgresql.Driver
```

### AI Provider API Keys
```yaml
spring.ai.openai.api-key: <key>
spring.ai.azure.openai.endpoint: https://<name>.openai.azure.com/
spring.ai.azure.openai.api-key: <key>
spring.ai.anthropic.api-key: <key>
spring.ai.ollama.base-url: http://localhost:11434
```

### JWT Authentication
```yaml
pods.auth.jwt-secret: <32+ char secret>
pods.auth.jwt-expiration-ms: 86400000   # 24 hours
```

### Credential Encryption
```yaml
pods.encryption.key: <64-char hex key>   # AES-256
```

### Azure Blob Storage (Skills & Memory)
```yaml
pods.skills.blob.connection-string: DefaultEndpointsProtocol=https;AccountName=...
pods.skills.blob.container: ai-skills
pods.skills.blob.allow-in-memory-fallback: false
```

### Runtime Tuning
```yaml
pods.runtime:
  # Context & token management
  max-output-tokens: 8192
  summary-token-threshold: 6000           # tokens before context summarization
  summary-retain-recent-messages: 16      # messages preserved after summarization
  context-window-utilization-percent: 85  # trigger threshold
  min-history-messages-to-keep: 12

  # Tool exposure
  dynamic-tool-exposure-enabled: true
  tool-shortlist-default-size: 24
  tool-shortlist-expanded-size: 48
  tool-memory-bias-weight: 0.2            # usage history influence on ranking
  tool-shortlist-fallback-to-all-on-miss: true
  max-tool-callbacks-per-turn: 120

  # Skill exposure
  dynamic-skill-exposure-enabled: true
  skill-shortlist-default-size: 6
  skill-memory-bias-weight: 0.2

  # Scope enforcement
  strict-scope-only: true
  strict-scope.pre-flight-enabled: true
  strict-scope.min-top-tool-cosine: 0.30  # minimum similarity to pass scope gate

  # Model routing
  auto-routing-low-threshold: 400         # chars
  auto-routing-medium-threshold: 1400     # chars

  # HITL
  hitl-reply-timeout-ms: 300000           # 5 minutes

  # Experimental tools
  enable-experimental-tools: true
  enabled-experimental-tool-names: []     # allowlist; empty = none

  # Cost tracking
  anthropic-chars-per-token: 3.5
  open-ai-chars-per-token: 4.0
  ollama-chars-per-token: 4.2

  # Budget hints
  enable-budget-hints: true
  budget-hint-warn-percent: 80
  budget-hint-elevated-percent: 70
  budget-hint-critical-percent: 85

  # Anthropic-specific
  enable-anthropic-thinking: true
  enable-anthropic-prompt-caching-hints: false
```

### CORS
```yaml
pods.cors:
  allowed-origins:
    - http://localhost:3000
    - http://localhost:5173
  allowed-methods: [GET, POST, PATCH, PUT, DELETE, OPTIONS]
  allowed-headers: ["*"]
  allow-credentials: false
  path-pattern: /api/**
```

---

## 7. Core Services

| Service | Package | Responsibility |
|---|---|---|
| `ChatService` | service | End-to-end turn handling: session, persistence, attachments, title generation |
| `AgentRuntimeService` | service | Multi-turn runtime: tool selection, skill loading, guardrails |
| `AgentOrchestrator` | agent | Spring AI streaming, system prompt assembly, token windowing |
| `ToolRegistryService` | service | In-memory tool cache, refresh, base-injected tools |
| `SkillRegistryService` | service | Skill catalog (DB + classpath defaults) |
| `MemoryService` | service | User memory CRUD + blob sync |
| `ToolExecutionService` | service | Dispatches tool calls: HTTP, MCP, filesystem, shell, skill |
| `McpClientService` | service | MCP server lifecycle, tool discovery, orphan cleanup |
| `ToolEmbeddingIndexService` | service/tool | pgvector upsert/search, batch embedding, reindex |
| `EmbeddingAutoRouterService` | service | Picks embedding model for semantic retrieval |
| `ModelAutoRouterService` | service | Selects LLM based on message context |
| `ModelProviderRouter` | config | Maps `ModelRef` → Spring AI `ChatClient` instance |
| `ContextSummarizationService` | service | Summarizes history when tokens approach threshold |
| `GuardrailPolicyEngine` | service | Evaluates allow/ask/deny policies per tool call |
| `PendingInteractionService` | service | HITL create/await/resolve lifecycle |
| `HttpToolAuthService` | service | Injects auth (OAuth2, API key, Basic) into HTTP tool calls |
| `EncryptionService` | service | AES-256 encrypt/decrypt for stored credentials |
| `FrameworkToolPackService` | service | Installs built-in framework tools |
| `ToolImportService` | service | OpenAPI and cURL → AgentTool conversion |
| `RuntimeHookRegistryService` | service | Fires hook events at pre-prompt, post-response, etc. |
| `SecurityContextService` | service | Extracts authenticated userId from JWT context |

---

## 8. Agent & Streaming Internals

### AgentSession
Holds the in-memory state of a single conversation:
- `messages` — Spring AI `Message` list (user + assistant turns)
- `activeEmitter` — the current `SseEmitter` for streaming
- `cancelled` — volatile boolean; set by `cancel()` to stop the reactive stream
- `chatState` — restored from `SessionContextState` on reconnect

### AgentSessionManager
A `ConcurrentHashMap<sessionId, AgentSession>` holding all active in-memory sessions. Sessions are created on first turn and evicted when the SSE connection closes.

### Streaming Pipeline

```
ChatClient.prompt()...stream().chatResponse()
  .doOnNext(chatResponse → {
      if (session.isCancelled()) throw CancellationException
      String delta = chatResponse.result.output.text
      SseEventSender.sendTextDelta(delta)
  })
  .blockLast()
```

Tool callbacks are dispatched by Spring AI on pooled reactor threads. `AgentToolCallback` captures `userId` at construction time (while inside `UserContextHolder.withUser()`) and re-injects it before `ToolExecutionService.execute()` runs.

### System Prompt Assembly (per turn)

1. `base-system-prompt.md` — scope enforcement, tool selection priority, output format rules
2. Memory injection — user memories matching the current query (up to 2000 chars)
3. Skill catalog — names and descriptions of enabled skills
4. Full skill content — if `include-full-skill-files: true` (default: false)
5. Budget hints — context window utilization warnings

---

## 9. Tool Execution Flow (End-to-End)

```
1. POST /api/v1/chat
2. ChatService.handleChatAsync()
   - resolve/create session
   - persist user message
3. AgentRuntimeService.runTurn()
   - model selection (auto or manual)
   - tool selection:
       a. getBaseInjectedTools() — always-on tools
       b. semantic retrieval (embedding cosine) — top-K tools
       c. apply memory bias signals
       d. merge → final tool list
   - AgentToolCallbackFactory.buildForTurn() — wrap as ToolCallback[]
4. AgentOrchestrator.streamTurn()
   - build system prompt
   - apply prompt window guard (summarize if needed)
   - ChatClient.prompt().stream()
5. LLM generates tool call → Spring AI invokes AgentToolCallback.call()
   a. check SkillExecutionGate (skill-first enforcement)
   b. check GuardrailPolicyEngine → allow / ask / deny
   c. if ask: create HitlInteraction, suspend, wait for user reply
   d. emit tool.call SSE event
   e. UserContextHolder.withUser(userId) → ToolExecutionService.execute()
      - http_proxy → HttpClient + auth injection
      - mcp → McpRuntimeAdapter
      - filesystem → file operations
      - shell → bash execution
      - skill → load SKILL.md + execute
   f. emit tool.result SSE event
   g. persist RuntimeEvent (tool.call + tool.done)
6. LLM generates final text → stream text.delta events
7. Persist assistant ChatMessage
8. Track CostUsage
9. Update SessionContextState
10. Emit done event
```

---

## 10. Memory System

Memory is **user-scoped** — one user's memories are never accessible to another.

### Storage Layers

| Layer | Storage | Scope |
|---|---|---|
| Long-term memory | Azure Blob (`memories/{userId}/{filePath}`) + `memories` table | Per user, cross-session |
| Session state | `session_context_state` table | Per session |
| Selection signals | Blob (`.system/selection-signals.json`) | Per user |

### Memory Categories

| Category | Purpose |
|---|---|
| `user` | User preferences, background, identity |
| `feedback` | Behavioral corrections and confirmations |
| `project` | Project-specific decisions and context |
| `reference` | Pointers to external resources |

### Memory Tools (callable by the LLM)

| Tool | Description |
|---|---|
| `memoryview` | View the memory index or a specific file |
| `memorycreate` | Create a new memory file |
| `memorystrreplace` | Replace text within a memory file |
| `memoryinsert` | Insert text at a specific line |
| `memorydelete` | Delete a memory file |
| `memoryrename` | Rename a memory file |

All memory tools call `MemoryTools.requireUserId()` — execution fails if the user context is not set. The `userId` is propagated from `AgentToolCallback` (captured at construction time via `UserContextHolder`).

### Memory Injection

At the start of each turn, `buildInjectionPrompt(userId, query, maxChars=2000)` searches user memories by semantic relevance to the current query and prepends matching content to the system prompt.

---

## 11. Skills System

Skills are **domain knowledge packages** — Markdown files that encode rules, prompting logic, and structured instructions that the LLM loads and follows.

### File Structure

```
skill-name/
  SKILL.md          ← required; root behavior and instructions
  rules.md          ← optional supporting files
  templates/
    report.md
  ...
```

### Frontmatter (SKILL.md)
```yaml
---
name: "Skill Name"
description: "What this skill enables"
---
```

### Sources

| Source | Location | Always Available |
|---|---|---|
| Built-in | `src/main/resources/default-skills/` | Yes |
| User-defined | Azure Blob `skills/{skillId}/` | When enabled |

### Upload Formats
- `.md` — single-file skill
- `.zip` — multi-file; common root directory is stripped
- `.skill` — packaged bundle

### Availability in Context

By default (`include-full-skill-files: false`), only skill names and descriptions appear in the system prompt. When the LLM calls the `skill` tool with a skill name, the full `SKILL.md` content is loaded and injected into the conversation.

If `include-full-skill-files: true`, skill content is preloaded (up to `max-skill-content-chars: 12000` and `max-skill-files-per-skill: 6`).

---

## 12. MCP (Model Context Protocol) Integration

MCP servers expose tools, resources, and prompts that the agent can call at runtime.

### Registration Flow

```
1. POST /api/v1/mcp-registry — register server URL and auth
2. McpClientService auto-detects auth (OAuth2, API key, none)
3. Health check via healthPath
4. Tool discovery: POST /tools/list
5. Sync discovered tools → agent_domains ("MCP {name}") → agent_tools
6. Runtime invocation via McpRuntimeAdapter
```

### Transport

| Type | Description |
|---|---|
| `streamable_http` | Long-polling HTTP (primary) |
| `sse` | Server-Sent Events |

### Auth Types

| Type | How |
|---|---|
| `none` | No auth |
| `oauth2` | Authorization code flow; tokens encrypted in DB |
| `api_key` | Bearer token in Authorization header |
| `basic` | HTTP Basic Auth |

### Orphan Cleanup

On startup, `McpClientService.cleanupOrphanedMcpDomains()` deletes any `agent_domains` named `"MCP {X}"` where `X` is not in the MCP registry. This handles stale data from previously deleted MCP servers.

When a server is deleted via `DELETE /api/v1/mcp-registry/{id}`, `deleteServer()` cascades: domain → tools → embeddings, then refreshes the tool cache.

---

## 13. Embedding & Semantic Tool Routing

Each enabled tool is vectorized and stored in `agent_tool_embeddings` using pgvector.

### Embedded Text Format

```
name: <tool name>
description: <tool description>
category: <domainId> (<sourceType>)
operation: <method> <endpoint>
parameters: <request schema summary (max 600 chars)>
returns: <response schema summary (max 600 chars)>
```

### Selection Algorithm (per turn)

```
1. Embed user message using configured embedding model
2. Cosine similarity search against agent_tool_embeddings
3. Apply memory signals: score += memoryWeight × tanh(usageFrequency)
4. Apply host affinity boost: +0.25 for tools used in prior turns
5. Filter by scoreFloor (default 0.30)
6. Return top-K (default 24, expanded 48)
7. Merge with baseInjected tools (always included)
```

### Embedding Models

Configured via the Embedding Models page (`/api/v1/models` with `modelKind: "embedding"`). Supported providers:
- OpenAI (`text-embedding-3-large`, etc.)
- Azure OpenAI
- Custom HTTP endpoint

Max vector dimensions: **3072** (padded or truncated). Hash-based change detection prevents redundant re-embedding.

---

## 14. Human-in-the-Loop (HITL)

HITL pauses execution and waits for a human response before proceeding.

### Triggers

| Trigger | Source |
|---|---|
| `requiresApproval: true` on a tool | Tool definition |
| Guardrail policy decision = `ask` | Policy engine |
| LLM calls the `question` tool | Agent decision |

### Lifecycle

```
1. HitlInteraction created (status: pending)
2. approval_required or question SSE event sent to client
3. Execution suspended (blocking wait up to hitlReplyTimeoutMs)
4. User calls /chat/approve or /chat/reject or /chat/reply
5. HitlInteraction updated (status: approved/rejected/reply)
6. Execution resumes or returns denial message
7. Timeout → returns "Approval timed out."
```

### Question Tool Parameters

| Parameter | Type | Description |
|---|---|---|
| `question` | string | Question text to present |
| `options` | string[] | Optional multiple-choice options |
| `allowFreeText` | boolean | Allow free-text reply in addition to options |

---

## 15. Security

### Authentication
- **JWT Bearer tokens** — all `/api/v1/**` endpoints (except `/api/v1/auth/**`)
- Token expiration: configurable (default 24h)
- Extracted by `SecurityContextService.currentUserIdOrThrow()`

### Credential Storage
- Client secrets, access tokens, refresh tokens stored **AES-256 encrypted** via `EncryptionService`
- Encryption key in `pods.encryption.key` (64-char hex)

### Permission Scopes
Tools are assigned a `permissionScope`. The runtime enforces the allowlist:

```yaml
pods.runtime.allowed-permission-scopes:
  - filesystem
  - shell
  - web
  - workflow
  - memory
  - integration
  - http_proxy
```

Tools with scopes outside this allowlist are rejected.

### Guardrail Policies
Policies can `allow`, require HITL (`ask`), or `deny` any tool execution. Evaluated by `GuardrailPolicyEngine` before every tool call.

### Scope Enforcement
`strict-scope-only: true` (default) causes the agent to refuse requests that do not match any registered tool or skill by cosine similarity (`min-top-tool-cosine: 0.30`).

### User Data Isolation
- All queries are scoped to `userId` (from JWT)
- Memory, sessions, messages, and interactions are user-partitioned
- No cross-user data access paths exist

---

## 16. Frontend Pages

All pages are in `UI/src/pages/` — React 18 + TypeScript, Vite bundler.

| Page | Route | Description |
|---|---|---|
| `LoginPage` | `/login` | JWT login form |
| `SignupPage` | `/signup` | User registration |
| `ChatPage` | `/chat` or `/` | Main chat: SSE streaming, message history, tool events, HITL interactions, attachments, stop button |
| `ToolsPage` | `/tools` | Tool domain list, OpenAPI/cURL import |
| `ToolDomainPage` | `/tools/:domainId` | Tools within a domain, auth profile binding |
| `SkillsPage` | `/skills` | Skill CRUD, file upload, enable/disable |
| `ModelsPage` | `/models` | LLM model catalog, enable/disable, default selection |
| `EmbeddingModelsPage` | `/embedding-models` | Embedding model config for semantic retrieval |
| `McpRegistryPage` | `/mcp` | Register/manage MCP servers |
| `McpServerToolsPage` | `/mcp/:serverId/tools` | Tools discovered from an MCP server |
| `AdminPage` | `/admin` | Guardrail policies, agent profiles, hooks |

### Chat Page Features
- Real-time SSE streaming with `AbortController` cancellation
- Stop button (red square) → calls `/chat/sessions/{id}/cancel` + aborts fetch
- Tool event cards: `tool.call`, `tool.result`, `tool.done`, `tool.match`
- HITL: inline approval/rejection UI when `approval_required` or `question` events arrive
- Attachment upload (drag & drop or file picker)
- Message actions: copy, delete, resend, branch (truncate from message)
- Markdown rendering with Mermaid diagram support
- Session sidebar with search, archive, rename

---

## 17. SSE Event Reference

All events are sent on the SSE stream opened by `POST /api/v1/chat`.

| Event Name | When | Payload |
|---|---|---|
| `connected` | Stream established | `{ sessionId }` |
| `text.delta` | LLM text token | `{ delta: "..." }` |
| `tool.match` | Tools selected for turn | `{ tools: [...] }` |
| `tool.call` | LLM invoked a tool | `{ callId, toolName, input }` |
| `tool.result` | Tool returned during stream | `{ callId, toolName, output, status }` |
| `tool.done` | Tool call complete (persisted) | `{ callId, toolName, status, output }` |
| `question` | LLM asked a question | `{ requestId, question, options }` |
| `approval_required` | Tool needs approval | `{ requestId, reason }` |
| `reasoning` | Extended thinking delta | `{ delta: "..." }` |
| `session.updated` | Session metadata changed | `{ sessionId, title }` |
| `state.updated` | Runtime state changed | `{ state, plannerState }` |
| `cost.updated` | Token usage updated | `{ sessionId, summary }` |
| `done` | Turn complete | `{ ok: true, turnId, response }` |
| `error` | Unrecoverable error | `{ message }` |

---

*End of documentation.*
