// Frontend service for the new Joget-style workflow API.
//
// The endpoints live at /api/v1/workflow/* on the agent backend; this module
// reuses the auth header / response handling from services/api.ts via the
// shared `api` helper so requests carry the same Bearer token as the rest of
// the app.

import { api } from "./api";
import type {
  ActivityInst,
  AuditTrailEntry,
  InsightsPeriod,
  InsightsResponse,
  PendingApproval,
  PluginDescriptor,
  PluginPreviewResponse,
  PluginPropertyOption,
  ProcessDef,
  ProcessDefMetadata,
  RunSummary,
  StartRunRequest,
  WorkflowProposal,
} from "../types/workflow";

const PROCESSES = "/workflow/processes";
const RUNS = "/workflow/runs";
const API_KEYS = "/workflow/api-keys";

export interface ApiKeySummary {
  id: string;
  name: string;
  keyPrefix: string;
  processDefIds: string[];
  createdAt: number;
  lastUsedAt: number | null;
  revokedAt: number | null;
}

export interface ApiKeyCreateResponse extends ApiKeySummary {
  /** Plaintext key — shown to the user exactly once. */
  key: string;
}
const PLUGINS = "/workflow/plugins";

export interface ExecutionsFilters {
  state?: string | null;
  stateGroup?: "open" | "closed" | "suspended" | null;
  defId?: string | null;
  requester?: string | null;
  from?: number | null;
  to?: number | null;
  limit?: number;
  offset?: number;
}

export const workflowApi = {
  processes: {
    list: (): Promise<ProcessDef[]> => api.get(PROCESSES),
    get: (id: string): Promise<ProcessDef> => api.get(`${PROCESSES}/${id}`),
    create: (def: ProcessDef): Promise<ProcessDef> => api.post(PROCESSES, def),
    update: (id: string, def: ProcessDef): Promise<ProcessDef> =>
      api.put(`${PROCESSES}/${id}`, def),
    remove: (id: string, opts?: { force?: boolean }): Promise<{ deleted: boolean; id: string; force?: boolean }> =>
      api.delete(`${PROCESSES}/${id}${opts?.force ? "?force=true" : ""}`),
    versions: (id: string): Promise<ProcessDef[]> => api.get(`${PROCESSES}/${id}/versions`),
    templates: (): Promise<{ templates: any[] }> => api.get(`${PROCESSES}/templates`),
    createFromTemplate: (templateId: string): Promise<ProcessDef> =>
      api.post(`${PROCESSES}/from-template`, { templateId }),
    proposals: (): Promise<{ proposals: WorkflowProposal[] }> =>
      api.get(`${PROCESSES}/proposals`),
    approveProposal: (id: string, comment?: string): Promise<{ proposal: WorkflowProposal }> =>
      api.post(`${PROCESSES}/proposals/${id}/approve`, { comment: comment || "" }),
    rejectProposal: (id: string, comment?: string): Promise<{ proposal: WorkflowProposal }> =>
      api.post(`${PROCESSES}/proposals/${id}/reject`, { comment: comment || "" }),

    // Phase C: rolling aggregates surfaced by the metadata endpoints.
    // The single-id form is what detail pages call; the batch form is for
    // the workflows index so it can show success-rate / AI-nodes badges
    // without one round-trip per row.
    metadata: (id: string): Promise<ProcessDefMetadata> =>
      api.get(`${PROCESSES}/${id}/metadata`),
    metadataBatch: (ids?: string[]): Promise<{ metadata: Record<string, ProcessDefMetadata> }> => {
      const qs = ids && ids.length > 0 ? `?ids=${encodeURIComponent(ids.join(","))}` : "";
      return api.get(`${PROCESSES}/metadata${qs}`);
    },
  },

  runs: {
    start: (req: StartRunRequest, opts?: { async?: boolean }): Promise<RunSummary> => {
      const query = opts?.async ? "?async=true" : "";
      return api.post(`${RUNS}${query}`, req);
    },
    get: (instanceId: string): Promise<RunSummary> => api.get(`${RUNS}/${instanceId}`),
    activities: (instanceId: string): Promise<ActivityInst[]> =>
      api.get(`${RUNS}/${instanceId}/activities`),
    audit: (instanceId: string): Promise<AuditTrailEntry[]> =>
      api.get(`${RUNS}/${instanceId}/audit`),
    status: (instanceId: string): Promise<any> => api.get(`${RUNS}/${instanceId}/status`),
    events: (instanceId: string): Promise<any> => api.get(`${RUNS}/${instanceId}/events`),
    byProcess: (processDefId: string, limit = 50, offset = 0): Promise<any> =>
      api.get(`${RUNS}/by-process/${processDefId}?limit=${limit}&offset=${offset}`),
    list: (filters: ExecutionsFilters = {}): Promise<{
      limit: number;
      offset: number;
      total: number;
      runs: Array<{
        id: string;
        defId: string | null;
        state: string;
        startedAt: number | null;
        endedAt: number | null;
        requesterId: string | null;
        errorClass: string | null;
        errorMessage: string | null;
      }>;
    }> => {
      const qs = new URLSearchParams();
      if (filters.state) qs.set("state", filters.state);
      if (filters.stateGroup) qs.set("stateGroup", filters.stateGroup);
      if (filters.defId) qs.set("defId", filters.defId);
      if (filters.requester) qs.set("requester", filters.requester);
      if (filters.from != null) qs.set("from", String(filters.from));
      if (filters.to != null) qs.set("to", String(filters.to));
      qs.set("limit", String(filters.limit ?? 50));
      qs.set("offset", String(filters.offset ?? 0));
      return api.get(`${RUNS}?${qs.toString()}`);
    },
    replay: (instanceId: string, requesterId?: string | null): Promise<RunSummary> =>
      api.post(`${RUNS}/${instanceId}/replay${requesterId ? `?requesterId=${encodeURIComponent(requesterId)}` : ""}`),
    rerunFrom: (instanceId: string, activityDefId: string, requesterId?: string | null): Promise<RunSummary> =>
      api.post(`${RUNS}/${instanceId}/rerun-from/${encodeURIComponent(activityDefId)}${requesterId ? `?requesterId=${encodeURIComponent(requesterId)}` : ""}`),
    subflows: (instanceId: string): Promise<{
      instanceId: string;
      children: Array<{
        id: string;
        defId: string;
        state: string;
        startedAt: number | null;
        endedAt: number | null;
        parentInstId: string | null;
      }>;
    }> => api.get(`${RUNS}/${instanceId}/subflows`),
    resume: (instanceId: string, requesterId?: string | null): Promise<RunSummary> =>
      api.post(`${RUNS}/${instanceId}/resume${requesterId ? `?requesterId=${encodeURIComponent(requesterId)}` : ""}`),
    analytics: (limit = 50, offset = 0): Promise<any> =>
      api.get(`${RUNS}/analytics?limit=${limit}&offset=${offset}`),
    approvals: (): Promise<{ pending: any[] }> => api.get(`${RUNS}/approvals`),
    approve: (instanceId: string): Promise<any> => api.post(`/workflow/runs/${instanceId}/approve`, {}),
    reject: (instanceId: string): Promise<any> => api.post(`/workflow/runs/${instanceId}/reject`, {}),
  },

  approvals: {
    list: (limit = 50): Promise<{ total: number; pending: PendingApproval[] }> =>
      api.get(`/workflow/approvals?limit=${limit}`),
    get: (id: string): Promise<PendingApproval> => api.get(`/workflow/approvals/${id}`),
    approve: (id: string, decidedBy?: string | null, comment?: string | null): Promise<{
      ok: boolean; id: string; decision: string; instanceId: string; state: string; error: string | null;
    }> => api.post(`/workflow/approvals/${id}/approve`, { decidedBy: decidedBy ?? null, comment: comment ?? null }),
    reject: (id: string, decidedBy?: string | null, comment?: string | null): Promise<{
      ok: boolean; id: string; decision: string; instanceId: string; state: string; error: string | null;
    }> => api.post(`/workflow/approvals/${id}/reject`, { decidedBy: decidedBy ?? null, comment: comment ?? null }),
  },

  insights: {
    get: (period: InsightsPeriod = "7d", limit = 20): Promise<InsightsResponse> =>
      api.get(`/workflow/insights?period=${period}&limit=${limit}`),
  },

  plugins: {
    list: (): Promise<{ plugins: PluginDescriptor[] }> => api.get(PLUGINS),
    get: (name: string): Promise<PluginDescriptor> =>
      api.get(`${PLUGINS}/${encodeURIComponent(name)}`),
    options: (loaderId: string): Promise<{ loaderId: string; options: PluginPropertyOption[] }> =>
      api.get(`${PLUGINS}/options/${encodeURIComponent(loaderId)}`),
    preview: (
      name: string,
      properties: Record<string, unknown>,
    ): Promise<PluginPreviewResponse> =>
      api.post(`${PLUGINS}/${encodeURIComponent(name)}/preview`, { properties }),
  },

  tools: {
    validateExpression: (expression: string, bindings?: Record<string, unknown>): Promise<any> =>
      api.post(`/workflow/expressions/validate`, { expression, bindings: bindings || {} }),
    testMapping: (expression: string, bindings?: Record<string, unknown>): Promise<any> =>
      api.post(`/workflow/mappings/test`, { expression, bindings: bindings || {} }),
    previewCode: (payload: {
      language: string;
      code: string;
      input?: unknown;
      timeoutMs?: number;
      memoryLimitMb?: number;
    }): Promise<any> => api.post(`/workflow/code/preview`, payload),
  },

  apiKeys: {
    list: (): Promise<ApiKeySummary[]> => api.get(API_KEYS),
    create: (name: string, processDefIds: string[]): Promise<ApiKeyCreateResponse> =>
      api.post(API_KEYS, { name, processDefIds }),
    update: (id: string, name: string, processDefIds: string[]): Promise<void> =>
      api.patch(`${API_KEYS}/${id}`, { name, processDefIds }),
    regenerate: (id: string): Promise<ApiKeyCreateResponse> =>
      api.post(`${API_KEYS}/${id}/regenerate`),
    revoke: (id: string): Promise<void> => api.delete(`${API_KEYS}/${id}`),
    /** Hard-delete an already-revoked key. The server enforces revoked-only. */
    purge: (id: string): Promise<void> => api.delete(`${API_KEYS}/${id}/purge`),
  },
};
