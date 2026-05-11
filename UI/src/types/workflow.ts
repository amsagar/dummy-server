// TypeScript types for the new Joget-style workflow API.
//
// Mirrors com.pods.agent.workflow.api.dto.ProcessDefDto and the engine's
// activity-type taxonomy (Joget WorkflowActivity TYPE_* constants).
//
// The React Flow board emits a Workflow.ProcessDef on save. The backend
// validates it via ProcessDefinition.build, persists xpdl_json to
// agent.process_def, and runs it via WorkflowManager.

export type ActivityType =
  | "normal"
  | "tool"
  | "route"
  | "subflow"
  | "foreach"
  | "while"
  | "batch"
  | "ai_reasoning";

export type ErrorClass =
  | "EXPRESSION"
  | "VALIDATION"
  | "TIMEOUT"
  | "TOOL"
  | "SUBFLOW"
  | "UNCAUGHT";

export type ProcessState =
  | "open.running"
  | "open.not_running.suspended"
  | "closed.completed"
  | "closed.terminated"
  | "closed.aborted";

export type ActivityState =
  | "ready"
  | "running"
  | "suspended"
  | "completed"
  | "failed"
  | "deadline_breached"
  | "cancelled";

export interface VariableSpec {
  name: string;
  javaClass?: string | null;
  defaultExpression?: string | null;
  required?: boolean;
}

export interface Activity {
  id: string;
  name?: string;
  type: ActivityType;
  /** Spring bean simple-name for the plugin (e.g. SkillToolPlugin, McpToolPlugin, AiChatPlugin, HttpRequestPlugin, CodeExecPlugin). */
  pluginName?: string | null;
  /** Plugin properties; values may be SecureSpel expressions like "#{#input}". */
  properties?: Record<string, unknown>;
  /** JSON-schema-like contract for resolved activity input. */
  inputSchema?: Record<string, unknown>;
  /** JSON-schema-like contract for emitted output/variable writes. */
  outputSchema?: Record<string, unknown>;
  /** ISO-8601 duration, e.g. "PT30S". */
  deadlineExpression?: string | null;
  isStart?: boolean;
  isEnd?: boolean;
  /** For type=subflow. */
  subflowDefId?: string | null;
  subflowInputs?: Record<string, string>;
  subflowOutputs?: Record<string, string>;
  /** Optional declared output variables; first one receives the activity's output. */
  outputVariables?: VariableSpec[];
  /**
   * Synchronizing AND-join: when true, this activity executes only after
   * EVERY incoming transition has fired. Default false = OR-join (fire on
   * first arrival).
   */
  andJoin?: boolean;
  errorPolicy?: ErrorPolicy;
}

export type TransitionTrigger =
  | "ON_SUCCESS"
  | "ON_NO_MATCH"
  | "ON_ERROR"
  | "ON_TIMEOUT"
  | "ON_VALIDATION_ERROR";

export interface Transition {
  id: string;
  fromActivityId: string;
  toActivityId: string;
  /** SecureSpel boolean expression; null means unconditional. */
  condition?: string | null;
  isErrorEdge?: boolean;
  matchesErrorClass?: ErrorClass | null;
  trigger?: TransitionTrigger | null;
  priority?: number | null;
  isDefault?: boolean;
}

export interface ErrorPolicy {
  retryCount?: number;
  backoffMs?: number;
  timeoutMs?: number | null;
  failFast?: boolean;
  continueOnError?: boolean;
}

export interface ProcessDef {
  id?: string;
  name: string;
  version: string;
  packageId?: string | null;
  description?: string | null;
  variables?: VariableSpec[];
  activities: Activity[];
  transitions: Transition[];
}

/**
 * Phase C: rolling aggregates surfaced by /api/v1/workflow/processes/{id}/metadata.
 * Mirrors com.pods.agent.workflow.api.dto.ProcessDefMetadataDto. Read-only;
 * the editor never POSTs this back.
 */
export interface ProcessDefAllTimeMetrics {
  totalRuns: number;
  totalSuccesses: number;
  totalLatencyMs: number;
  successRate?: number | null;
  avgLatencyMs?: number | null;
  lastRunAt?: number | null;
}

export interface ProcessDefRecentWindow {
  /** Number of runs in the rolling window (capped at 50 by WorkflowMetadataService). */
  runs: number;
  successes: number;
  successRate?: number | null;
  avgLatencyMs: number;
  lastRunAt?: number | null;
}

export interface ProcessDefAiNode {
  activityDefId: string;
  /** Number of times this ai_reasoning activity actually fired (skipped runs excluded). */
  count: number;
  [extra: string]: unknown;
}

export interface ProcessDefMetadata {
  defId: string;
  allTime: ProcessDefAllTimeMetrics;
  recentWindow: ProcessDefRecentWindow;
  aiNodes: ProcessDefAiNode[];
  /** True once the Phase D embedding column is populated for this def. */
  hasEmbedding: boolean;
}

export interface RunSummary {
  instanceId: string;
  defId?: string | null;
  state?: ProcessState | null;
  startedAt?: number | null;
  endedAt?: number | null;
  requesterId?: string | null;
  errorClass?: string | null;
  errorMessage?: string | null;
  /**
   * The JSON-decoded value of the closing activity's `properties.result`
   * SecureSpel expression. Present on closed.completed runs whose workflow
   * declares an end-result; absent otherwise (server omits the field).
   */
  result?: unknown;
}

// Field names mirror the Java record components in ActivityInstRow /
// AuditTrailRow — Jackson 3 emits them camelCase, matching every other
// DTO in this codebase.
export interface ActivityInst {
  id: string;
  instId: string;
  activityDefId: string;
  type: ActivityType;
  state: ActivityState;
  startedAt: number | null;
  endedAt: number | null;
  dueAt: number | null;
  assignee: string | null;
  attempt: number;
  pluginName: string | null;
  inputSnapshot: string | null;
  outputSnapshot: string | null;
  errorClass: string | null;
  errorMessage: string | null;
}

export interface AuditTrailEntry {
  id: string;
  instId: string;
  activityInstId: string | null;
  action: string;
  actor: string | null;
  ts: number;
  payloadJson: string | null;
}

export interface StartRunRequest {
  processDefId: string;
  initialVariables?: Record<string, unknown>;
  requesterId?: string | null;
}

// ── Plugin descriptor types (mirror backend records under
//    com.pods.agent.workflow.plugin.descriptor.*) ──────────────────────────
//
// Returned by GET /api/v1/workflow/plugins. Drives the n8n-style node library
// (left rail) and the descriptor-driven form renderer (right inspector).

export type PluginPropertyType =
  | "STRING"
  | "NUMBER"
  | "BOOLEAN"
  | "OPTIONS"
  | "MULTI_OPTIONS"
  | "COLLECTION"
  | "FIXED_COLLECTION"
  | "JSON"
  | "CODE"
  | "DATETIME"
  | "CREDENTIALS"
  | "NOTICE";

export interface PluginPropertyOption {
  value: string;
  label: string;
}

/**
 * show/hide rules: each entry maps a sibling field name to the values that
 * make this field visible (or hidden). All show entries must match (any-of
 * within an entry); any matching hide entry hides the field.
 */
export interface PluginPropertyDisplayOption {
  show?: Record<string, string[]> | null;
  hide?: Record<string, string[]> | null;
}

export interface PluginPropertyDescriptor {
  name: string;
  label?: string | null;
  description?: string | null;
  type: PluginPropertyType;
  required?: boolean;
  defaultValue?: unknown;
  /** When true, the field shows a fixed/expression toggle in the form. */
  expressionAllowed?: boolean;
  options?: PluginPropertyOption[] | null;
  /** Backend loader id (e.g. "agent-tools", "skills") for dynamic options. */
  optionsLoader?: string | null;
  children?: PluginPropertyDescriptor[] | null;
  displayOptions?: PluginPropertyDisplayOption | null;
  placeholder?: string | null;
}

export interface PluginDescriptor {
  name: string;
  label: string;
  description?: string | null;
  /** Lucide icon name (e.g. "globe", "wrench", "sparkles"). */
  icon?: string | null;
  /** Grouping shown in the node library (e.g. "Tool", "AI", "Code"). */
  category?: string | null;
  properties: PluginPropertyDescriptor[];
}

export interface PluginPreviewResponse {
  success: boolean;
  output: unknown;
  error: string | null;
  durationMs: number;
}

// ── Insights (Phase 5) ─────────────────────────────────────────────────────

export type InsightsPeriod = "24h" | "7d" | "14d" | "30d" | "90d" | "6mo" | "1y";

export interface InsightsSummary {
  total: number;
  failed: number;
  completed: number;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
}

export interface InsightsByDayRow {
  day: string;       // yyyy-MM-dd
  total: number;
  failed: number;
  completed: number;
  p50Ms: number | null;
  p95Ms: number | null;
}

export interface InsightsByWorkflowRow {
  defId: string | null;
  name: string | null;
  total: number;
  failed: number;
  completed: number;
  p50Ms: number | null;
  p95Ms: number | null;
  timeSavedSecondsPerRun: number;
  timeSavedSeconds: number;
}

export interface InsightsHotspotRow {
  activityDefId: string;
  pluginName: string;
  failures: number;
  lastErrorClass: string;
  lastErrorMessage: string;
}

export interface InsightsResponse {
  period: InsightsPeriod;
  fromTs: number;
  toTs: number;
  summary: InsightsSummary;
  byDay: InsightsByDayRow[];
  byWorkflow: InsightsByWorkflowRow[];
  hotspots: InsightsHotspotRow[];
  totalTimeSavedSeconds: number;
}

export interface PendingApproval {
  id: string;
  instId: string;
  activityInstId: string | null;
  activityDefId: string;
  requestedBy: string | null;
  requestedAt: number;
  reason: string | null;
  decidedBy: string | null;
  decidedAt: number | null;
  decision: string | null;
  comment: string | null;
  defId: string | null;
  workflowName: string | null;
}

export interface WorkflowProposal {
  id: string;
  sessionId: string;
  turnId: string;
  status: "pending" | "approved" | "rejected" | "materialized" | "failed";
  reason?: string | null;
  confidence?: number | null;
  intentSignature?: string | null;
  traceRef?: string | null;
  userPrompt?: string | null;
  decisionComment?: string | null;
  materializedDefId?: string | null;
  errorMessage?: string | null;
  createdAt?: number;
  updatedAt?: number;
}
