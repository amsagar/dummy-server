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
  chat: {
    approveInteraction: (requestId: string, message?: string) =>
      fetch(`${BASE_URL}/chat/approve`, {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ requestId, message: message || "" }),
      }).then(handleResponse),
    rejectInteraction: (requestId: string, message?: string) =>
      fetch(`${BASE_URL}/chat/reject`, {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ requestId, message: message || "" }),
      }).then(handleResponse),
  },
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
  models: {
    list: () => fetch(`${BASE_URL}/models`, { headers: getHeaders() }).then(handleResponse),
    upsert: (payload: {
      providerID: string;
      modelID: string;
      displayName?: string;
      apiKey?: string;
      baseUrl?: string;
      enabled: boolean;
    }) => fetch(`${BASE_URL}/models`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify(payload),
    }).then(handleResponse),
    setEnabled: (providerID: string, modelID: string, enabled: boolean) => fetch(
      `${BASE_URL}/models/${encodeURIComponent(providerID)}/${encodeURIComponent(modelID)}/${enabled ? 'enable' : 'disable'}`,
      { method: 'POST', headers: getHeaders() }
    ).then(handleResponse),
    delete: (providerID: string, modelID: string) => fetch(
      `${BASE_URL}/models/${encodeURIComponent(providerID)}/${encodeURIComponent(modelID)}`,
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
  decisionTables: {
    list: () => fetch(`${BASE_URL}/decision-tables`, { headers: getHeaders() }).then(handleResponse),
    get: (name: string) => fetch(`${BASE_URL}/decision-tables/${encodeURIComponent(name)}`, { headers: getHeaders() }).then(handleResponse),
    create: (payload: any) => fetch(`${BASE_URL}/decision-tables`, { method: 'POST', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    upsert: (name: string, payload: any) => fetch(`${BASE_URL}/decision-tables/${encodeURIComponent(name)}`, { method: 'PUT', headers: getHeaders(), body: JSON.stringify(payload) }).then(handleResponse),
    delete: (name: string) => fetch(`${BASE_URL}/decision-tables/${encodeURIComponent(name)}`, { method: 'DELETE', headers: getHeaders() }).then(handleResponse),
    evaluate: (name: string, inputs: Record<string, any>) =>
      fetch(`${BASE_URL}/decision-tables/${encodeURIComponent(name)}/evaluate`, {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ inputs }),
      }).then(handleResponse),
  },
};
