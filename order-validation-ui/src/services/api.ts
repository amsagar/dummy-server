import type {
  ActivityInvocation,
  ContainerAvailabilitySummary,
  DashboardMetrics,
  LegSequenceSummary,
  OrderQueueResponse,
  RunDetail,
  RunSummary,
  ServiceabilitySummary,
  WorkflowSummary,
} from "@/types/orderValidation";
import type {
  DecisionTableDetail,
  DecisionTableSummary,
  DecisionTableUpsertRequest,
  EvaluateDecisionTableRequest,
  EvaluateDecisionTableResponse,
} from "@/types/decisionTable";

// Vite's dev server proxies /api → http://localhost:8080 (see vite.config.ts).
// In production the app should be served from the same origin as the backend
// or behind a reverse proxy that does the same — no absolute base URL needed.
const BASE = "/api/v1/order-validation";

async function get<T>(path: string, params: object = {}, base = BASE): Promise<T> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== "") qs.set(k, String(v));
  }
  const url = qs.toString() ? `${base}${path}?${qs.toString()}` : `${base}${path}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API ${res.status}: ${text || res.statusText}`);
  }
  return res.json() as Promise<T>;
}

async function send<T>(
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body: unknown,
  base: string,
  params: object = {},
): Promise<T> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== "") qs.set(k, String(v));
  }
  const url = qs.toString() ? `${base}${path}?${qs.toString()}` : `${base}${path}`;
  const res = await fetch(url, {
    method,
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: body == null ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API ${res.status}: ${text || res.statusText}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export interface AnalyticsParams {
  workflowId: string;
  fromTs?: number;
  toTs?: number;
}

export interface PageParams {
  search?: string;
  limit?: number;
  offset?: number;
}

export const orderValidationApi = {
  listWorkflows(): Promise<WorkflowSummary[]> {
    return get<WorkflowSummary[]>("/workflows");
  },
  dashboard(p: AnalyticsParams): Promise<DashboardMetrics> {
    return get<DashboardMetrics>("/dashboard", p);
  },
  orderQueue(p: AnalyticsParams & { status?: string; limit?: number; offset?: number; search?: string }): Promise<OrderQueueResponse> {
    return get<OrderQueueResponse>("/orders", p);
  },
  legSequence(p: AnalyticsParams & PageParams): Promise<LegSequenceSummary> {
    return get<LegSequenceSummary>("/leg-sequence", p);
  },
  serviceability(p: AnalyticsParams & PageParams): Promise<ServiceabilitySummary> {
    return get<ServiceabilitySummary>("/serviceability", p);
  },
  containerAvailability(p: AnalyticsParams & PageParams): Promise<ContainerAvailabilitySummary> {
    return get<ContainerAvailabilitySummary>("/container-availability", p);
  },
  runDetail(instId: string): Promise<RunDetail> {
    return get<RunDetail>(`/runs/${encodeURIComponent(instId)}`);
  },
  runActivities(instId: string, defId: string): Promise<ActivityInvocation[]> {
    return get<ActivityInvocation[]>(
      `/runs/${encodeURIComponent(instId)}/activities/${encodeURIComponent(defId)}`,
    );
  },
  /**
   * Raw order JSON captured by the first non-looped tool call of this
   * run (typically {@code Get_OrderID}). Returns
   * {@code { orderId, activityId, payload }} where {@code payload}
   * is the full response body.
   */
  runOrderPayload(instId: string): Promise<{
    orderId: string | null;
    activityId: string | null;
    payload: unknown;
  }> {
    return get(`/runs/${encodeURIComponent(instId)}/order-payload`);
  },
};

export interface OrderValidationUiSettings {
  chatModelRef: string | null;
  responseMode: "basic" | "detailed";
  workflowId: string | null;
  // Allow-lists for the scoped AI assistant. `null` means unrestricted;
  // `[]` means deny-all; populated array means only these are exposed.
  allowedSkillIds: string[] | null;
  allowedRuleDomainIds: string[] | null;
  allowedDecisionTables: string[] | null;
}

export const orderValidationSettingsApi = {
  get(): Promise<OrderValidationUiSettings> {
    return get<OrderValidationUiSettings>("/settings");
  },
  update(s: OrderValidationUiSettings): Promise<OrderValidationUiSettings> {
    return send<OrderValidationUiSettings>("PUT", "/settings", s, BASE);
  },
};

export interface ScopeSkillRow {
  id: string;
  name: string;
  description?: string | null;
}
export interface ScopeDecisionTableRow {
  name: string;
  description?: string | null;
}

// The workflow's skill + every rule_domain under it are auto-allowed
// server-side. The UI exposes two optional, additive pickers: "extra"
// skills (other skills outside the workflow) and decision tables.
export const orderValidationScopeApi = {
  listSkills(): Promise<ScopeSkillRow[]> {
    return get<ScopeSkillRow[]>("/scope/skills");
  },
  listDecisionTables(): Promise<ScopeDecisionTableRow[]> {
    return get<ScopeDecisionTableRow[]>("/scope/decision-tables");
  },
};

const WORKFLOW_BASE = "/api/v1/workflow";

export const workflowRunsApi = {
  start(req: { processDefId: string; initialVariables: Record<string, unknown>; requesterId?: string }): Promise<RunSummary> {
    return send<RunSummary>("POST", "/runs", req, WORKFLOW_BASE, { async: true });
  },
};

const DECISION_TABLES_BASE = "/api/v1/decision-tables";

export const decisionTablesApi = {
  list(): Promise<DecisionTableSummary[]> {
    return get<DecisionTableSummary[]>("", {}, DECISION_TABLES_BASE);
  },
  detail(name: string): Promise<DecisionTableDetail> {
    return get<DecisionTableDetail>(`/${encodeURIComponent(name)}`, {}, DECISION_TABLES_BASE);
  },
  create(req: DecisionTableUpsertRequest): Promise<DecisionTableDetail> {
    return send<DecisionTableDetail>("POST", "", req, DECISION_TABLES_BASE);
  },
  update(name: string, req: DecisionTableUpsertRequest): Promise<DecisionTableDetail> {
    return send<DecisionTableDetail>("PUT", `/${encodeURIComponent(name)}`, req, DECISION_TABLES_BASE);
  },
  remove(name: string): Promise<void> {
    return send<void>("DELETE", `/${encodeURIComponent(name)}`, null, DECISION_TABLES_BASE);
  },
  evaluate(name: string, req: EvaluateDecisionTableRequest): Promise<EvaluateDecisionTableResponse> {
    return send<EvaluateDecisionTableResponse>(
      "POST",
      `/${encodeURIComponent(name)}/evaluate`,
      req,
      DECISION_TABLES_BASE,
    );
  },
};
