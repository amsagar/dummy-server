// ── Model / Provider types ────────────────────────────────────────────────────

/** Compound model reference: providerID + modelID */
export interface ModelRef {
  providerID: string;
  modelID: string;
}

/** A model entry returned by GET /api/v1/models — merged from models.dev + DB overrides */
export interface ModelConfig {
  providerID: string;
  modelID: string;
  providerName?: string;
  displayName: string;
  contextWindow?: number;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsStreaming: boolean;
  supportsReasoning: boolean;
  costInput?: number;
  costOutput?: number;
  enabled: boolean;
  source: 'catalog' | 'db';
  hasKey: boolean;
  baseUrl?: string;
}

/** A provider entry returned by GET /api/v1/providers */
export interface ProviderEntry {
  id: string;
  name: string;
  models: ModelEntry[];
}

export interface ModelEntry {
  id: string;
  name: string;
  providerID: string;
  providerName: string;
  context?: number;
  costInput?: number;
  costOutput?: number;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsStreaming: boolean;
  supportsReasoning: boolean;
  supportsTemperature: boolean;
}

// ── Chat / Session types ──────────────────────────────────────────────────────

export interface ChatMessage {
  id?: string;
  sessionId?: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt?: number;
}

export interface ChatSessionSummary {
  sessionId: string;
  createdAt: number;
  lastActive: number;
  timezone?: string;
  title?: string;
  archivedAt?: number | null;
}

export interface ToolDomain {
  id: string;
  name: string;
  description?: string;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface AgentTool {
  id: string;
  domainId: string;
  name: string;
  description?: string;
  sourceType: 'manual' | 'openapi_import' | 'curl_import' | 'framework_default';
  executionKind?: 'http_proxy' | 'filesystem' | 'shell' | 'web' | 'workflow' | 'memory' | 'integration';
  permissionScope?: string;
  requiresApproval?: boolean;
  modelGate?: string;
  providerGate?: string;
  experimental?: boolean;
  inputSchemaVersion?: number;
  method?: string;
  host?: string;
  endpoint?: string;
  requestSchema?: string;
  responseSchema?: string;
  sampleRequest?: string;
  sampleResponse?: string;
  authProfileId?: string;
  authOverrideEnabled?: boolean;
  authType?: string;
  authConfig?: string | Record<string, unknown>;
  clientId?: string;
  tokenUrl?: string;
  authorizationUrl?: string;
  redirectUri?: string;
  scopes?: string;
  tokenExpiresAt?: number;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface ToolAuthProfile {
  id: string;
  domainId: string;
  name: string;
  description?: string;
  authType: string;
  authConfig?: Record<string, unknown> | string;
  clientId?: string;
  tokenUrl?: string;
  authorizationUrl?: string;
  redirectUri?: string;
  scopes?: string;
  tokenExpiresAt?: number | null;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface Skill {
  id: string;
  name: string;
  description?: string;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface SkillFile {
  id: string;
  skillId: string;
  filePath: string;
  blobPath: string;
  mimeType?: string;
  contentSha256?: string;
  sizeBytes: number;
  createdAt: number;
  updatedAt: number;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Serialize a ModelRef to a localStorage-safe string key */
export function modelRefKey(ref: ModelRef): string {
  return `${ref.providerID}/${ref.modelID}`;
}

/** Parse a "providerID/modelID" string back to a ModelRef, or null */
export function parseModelRefKey(key: string): ModelRef | null {
  if (!key) return null;
  const slash = key.indexOf('/');
  if (slash < 1) return null;
  return { providerID: key.slice(0, slash), modelID: key.slice(slash + 1) };
}
