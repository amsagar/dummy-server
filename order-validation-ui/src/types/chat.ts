// Chat / session DTOs that mirror the Spring backend at /api/v1/chat
// and /api/v1/sessions. Field names match the wire shape — see
// ChatController + SessionController for source-of-truth.

export interface ChatSession {
  sessionId: string;
  createdAt: number;
  lastActive: number;
  timezone?: string;
  title?: string | null;
  archivedAt?: number | null;
}

export interface ChatMessage {
  id?: string;
  sessionId?: string;
  role: "user" | "assistant";
  content: string;
  createdAt?: number;
  turnId?: string | null;
}

export interface ChatHistoryResponse {
  messages: ChatMessage[];
  limit: number;
  offset: number;
  total: number;
}

export interface ModelConfig {
  providerID: string;
  modelID: string;
  displayName?: string;
  modelKind?: string;
}

export interface PendingInteraction {
  interactionId: string;
  type: "question" | "approval_required";
  question?: string;
  prompt?: string;
  status: string;
  createdAt: number;
  messageId?: string;
}

export interface SseEnvelope {
  type: string;
  schemaVersion?: string;
  eventId?: string;
  emittedAt?: number;
  [k: string]: unknown;
}

// System event captured for rendering inside the chat timeline.
export interface SystemEvent {
  id: string;
  type:
    | "tool.call"
    | "tool.done"
    | "tool.result"
    | "tool.match"
    | "task.started"
    | "task.done"
    | "step.started"
    | "step.finished"
    | "question"
    | "approval_required"
    | "workflow.run.bound"
    | "info";
  createdAt: number;
  turnId?: string | null;
  name?: string;
  callId?: string;
  input?: unknown;
  output?: unknown;
  message?: string;
  runId?: string;
  resolved?: boolean;
  answeredText?: string;
  payload?: SseEnvelope;
}

export interface ChatStreamRequest {
  message: string;
  sessionId?: string;
  model?: { providerID: string; modelID: string };
  timezone?: string;
  modelSelectionMode?: "manual" | "auto";
  agentProfileId?: string;
  /**
   * Optional Response Mode id (agent_profiles row, kind='response_mode').
   * The orchestrator appends the row's system_prompt to the base profile as a style addendum.
   */
  responseModeId?: string;
}
