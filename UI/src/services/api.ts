const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN_KEY = 'pods_auth_token';
const USER_KEY = 'pods_auth_user';

export type AuthUser = {
  id: string;
  email: string;
};

export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setAuthToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function getAuthUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthUser;
  } catch {
    return null;
  }
}

export function setAuthUser(user: AuthUser) {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearAuthUser() {
  localStorage.removeItem(USER_KEY);
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY);
  clearAuthUser();
}

export function isAuthenticated() {
  return !!getAuthToken();
}

function getHeaders() {
  const token = getAuthToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function handleResponse(response: Response) {
  if (!response.ok) {
    if (response.status === 401) {
      clearAuthToken();
    }
    let msg = `HTTP ${response.status}`;
    try { const e = await response.json(); msg = e.message || e.error || msg; } catch {}
    throw new Error(msg);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  get: (path: string) => fetch(`${BASE_URL}${path}`, { headers: getHeaders() }).then(handleResponse),
  post: (path: string, body?: any) => fetch(`${BASE_URL}${path}`, { method: 'POST', headers: getHeaders(), body: body ? JSON.stringify(body) : undefined }).then(handleResponse),
  put: (path: string, body?: any) => fetch(`${BASE_URL}${path}`, { method: 'PUT', headers: getHeaders(), body: body ? JSON.stringify(body) : undefined }).then(handleResponse),
  patch: (path: string, body?: any) => fetch(`${BASE_URL}${path}`, { method: 'PATCH', headers: getHeaders(), body: body ? JSON.stringify(body) : undefined }).then(handleResponse),
  delete: (path: string) => fetch(`${BASE_URL}${path}`, { method: 'DELETE', headers: getHeaders() }).then(handleResponse),
  upload: (path: string, file: File, name?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (name) form.append('name', name);
    const token = getAuthToken();
    return fetch(`${BASE_URL}${path}`, { method: 'POST', body: form, headers: token ? { Authorization: `Bearer ${token}` } : undefined }).then(handleResponse);
  },
  getChatStreamUrl: () => `${BASE_URL}/chat`,
  embeddingModels: {
    list: () => fetch(`${BASE_URL}/embedding-models`, { headers: getHeaders() }).then(handleResponse),
    upsert: (payload: {
      providerID: string;
      modelID: string;
      displayName?: string;
      apiKey?: string;
      baseUrl?: string;
      dimensions?: number;
      enabled: boolean;
    }) => fetch(`${BASE_URL}/embedding-models`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify(payload),
    }).then(handleResponse),
    setDefault: (providerID: string, modelID: string) => fetch(
      `${BASE_URL}/embedding-models/${encodeURIComponent(providerID)}/${encodeURIComponent(modelID)}/default`,
      { method: 'POST', headers: getHeaders() }
    ).then(handleResponse),
    setEnabled: (providerID: string, modelID: string, enabled: boolean) => fetch(
      `${BASE_URL}/embedding-models/${encodeURIComponent(providerID)}/${encodeURIComponent(modelID)}/${enabled ? 'enable' : 'disable'}`,
      { method: 'POST', headers: getHeaders() }
    ).then(handleResponse),
    delete: (providerID: string, modelID: string) => fetch(
      `${BASE_URL}/embedding-models/${encodeURIComponent(providerID)}/${encodeURIComponent(modelID)}`,
      { method: 'DELETE', headers: getHeaders() }
    ).then(handleResponse),
  },
  tools: {
    reindex: () => fetch(`${BASE_URL}/admin/tools/reindex`, {
      method: 'POST',
      headers: getHeaders(),
    }).then(handleResponse),
  },
  auth: {
    signup: (email: string, password: string) => fetch(`${BASE_URL}/auth/signup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }).then(handleResponse),
    login: (email: string, password: string) => fetch(`${BASE_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }).then(handleResponse),
  },
  toolchains: {
    list: () => fetch(`${BASE_URL}/toolchains`, { headers: getHeaders() }).then(handleResponse),
    create: (payload: any) => fetch(`${BASE_URL}/toolchains`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    update: (id: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}`, { method: 'PATCH', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    remove: (id: string) => fetch(`${BASE_URL}/toolchains/${id}`, { method: 'DELETE', headers: getHeaders() }).then(handleResponse),
    versions: (id: string) => fetch(`${BASE_URL}/toolchains/${id}/versions`, { headers: getHeaders() }).then(handleResponse),
    createVersion: (id: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}/versions`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    publishVersion: (id: string, version: number) => fetch(`${BASE_URL}/toolchains/${id}/versions/${version}/publish`, { method: 'POST', headers: getHeaders() }).then(handleResponse),
    generateDraft: (id: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}/generate-draft`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    execute: (id: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}/execute`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    runs: (id: string, limit = 50, offset = 0) => fetch(`${BASE_URL}/toolchains/${id}/runs?limit=${limit}&offset=${offset}`, { headers: getHeaders() }).then(handleResponse),
    run: (runId: string) => fetch(`${BASE_URL}/runs/${runId}`, { headers: getHeaders() }).then(handleResponse),
    runEvents: (runId: string) => fetch(`${BASE_URL}/runs/${runId}/events`, { headers: getHeaders() }).then(handleResponse),
    runStatus: (runId: string) => fetch(`${BASE_URL}/runs/${runId}/status`, { headers: getHeaders() }).then(handleResponse),
    rerun: (runId: string) => fetch(`${BASE_URL}/runs/${runId}/rerun`, { method: 'POST', headers: getHeaders() }).then(handleResponse),
    approvals: () => fetch(`${BASE_URL}/toolchains/approvals`, { headers: getHeaders() }).then(handleResponse),
    runApprovals: (runId: string) => fetch(`${BASE_URL}/runs/${runId}/approvals`, { headers: getHeaders() }).then(handleResponse),
    approveStep: (runId: string, nodeId: string, payload?: any) => fetch(`${BASE_URL}/runs/${runId}/steps/${encodeURIComponent(nodeId)}/approve`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload || {}) }).then(handleResponse),
    rejectStep: (runId: string, nodeId: string, payload?: any) => fetch(`${BASE_URL}/runs/${runId}/steps/${encodeURIComponent(nodeId)}/reject`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload || {}) }).then(handleResponse),
    analytics: () => fetch(`${BASE_URL}/toolchains/analytics`, { headers: getHeaders() }).then(handleResponse),
    configSessions: (id: string) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions`, { headers: getHeaders() }).then(handleResponse),
    configSessionsGlobal: () => fetch(`${BASE_URL}/toolchains/config-sessions`, { headers: getHeaders() }).then(handleResponse),
    configSessionDetail: (id: string, sessionId: string) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}`, { headers: getHeaders() }).then(handleResponse),
    configChat: (id: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}/config-chat`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    configChatStreamUrl: (id: string) => `${BASE_URL}/toolchains/${id}/config-chat/stream`,
    cancelConfigStream: (id: string, sessionId: string) =>
      fetch(`${BASE_URL}/toolchains/${id}/config-chat/stream/${sessionId}/stop`, { method: 'POST', headers: getHeaders() }).then(handleResponse),
    configSessionReply: (id: string, sessionId: string, payload: any) =>
      fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}/reply`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload || {}) }).then(handleResponse),
    configChatGlobal: (payload: any) => fetch(`${BASE_URL}/toolchains/config-chat`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    configChatGlobalStreamUrl: () => `${BASE_URL}/toolchains/config-chat/stream`,
    configSessionReplyGlobal: (sessionId: string, payload: any) =>
      fetch(`${BASE_URL}/toolchains/config-sessions/${sessionId}/reply`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload || {}) }).then(handleResponse),
    truncateConfigSession: (id: string, sessionId: string, messageId: string) =>
      fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}/truncate`, {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ messageId }),
      }).then(handleResponse),
    compileSession: (id: string, sessionId: string) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}/compile`, { method: 'POST', headers: getHeaders() }).then(handleResponse),
    publishSession: (id: string, sessionId: string) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}/publish`, { method: 'POST', headers: getHeaders() }).then(handleResponse),
    updateConfigSession: (id: string, sessionId: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}`, { method: 'PATCH', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    configSessionLayout: (id: string, sessionId: string) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}/layout`, { headers: getHeaders() }).then(handleResponse),
    saveConfigSessionLayout: (id: string, sessionId: string, payload: any) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}/layout`, { method: 'PATCH', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    deleteConfigSession: (id: string, sessionId: string) => fetch(`${BASE_URL}/toolchains/${id}/config-sessions/${sessionId}`, { method: 'DELETE', headers: getHeaders() }).then(handleResponse),
  },
};
