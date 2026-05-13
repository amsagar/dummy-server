const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN_KEY = 'pods_auth_token';

export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

function getHeaders(): Record<string, string> {
  const token = getAuthToken();
  return {
    'Content-Type': 'application/json',
    // X-OV-Client tags requests so the Spring backend (when there's no JWT)
    // partitions chat sessions per UI. Without this, the vendor-rationalization
    // sidebar would surface order-validation-ui chats and vice versa, because
    // both UIs would share the same default user id.
    'X-OV-Client': 'vendor-rationalization-ui',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function handleResponse(response: Response) {
  if (!response.ok) {
    let msg = `HTTP ${response.status}`;
    try {
      const e = await response.json();
      msg = e.message || e.error || msg;
    } catch {}
    throw new Error(msg);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function get(path: string) {
  return fetch(`${BASE_URL}${path}`, { headers: getHeaders() }).then(handleResponse);
}

function post(path: string, body?: unknown) {
  return fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  }).then(handleResponse);
}

function put(path: string, body: unknown) {
  return fetch(`${BASE_URL}${path}`, {
    method: 'PUT',
    headers: getHeaders(),
    body: JSON.stringify(body),
  }).then(handleResponse);
}

// ── Vendor Rationalization ────────────────────────────────────────────────────

export const vrApi = {
  dashboard: () => get('/vendor-rationalization/dashboard'),
  vendors: (params?: {
    search?: string;
    category?: string;
    topGroup?: string;
    limit?: number;
    offset?: number;
  }) => {
    const q = new URLSearchParams();
    if (params?.search) q.set('search', params.search);
    if (params?.category && params.category !== 'all') q.set('category', params.category);
    if (params?.topGroup && params.topGroup !== 'all') q.set('topGroup', params.topGroup);
    if (params?.limit != null) q.set('limit', String(params.limit));
    if (params?.offset != null) q.set('offset', String(params.offset));
    const qs = q.toString();
    return get(`/vendor-rationalization/vendors${qs ? '?' + qs : ''}`);
  },
  categories: () => get('/vendor-rationalization/categories'),
  categoryList: () => get('/vendor-rationalization/categories/list'),
  pareto: (limit = 200) => get(`/vendor-rationalization/pareto?limit=${limit}`),
  savings: () => get('/vendor-rationalization/savings'),
  reload: () => post('/vendor-rationalization/reload'),
  strategicLevers: () => get('/vendor-rationalization/strategic-levers'),
  getConfig: () => get('/vendor-rationalization/config'),
  putConfig: (cfg: unknown) => put('/vendor-rationalization/config', cfg),
  insights: (
    surface: 'dashboard' | 'category' | 'vendor' | 'savings' | 'contracts' = 'dashboard',
    opts: { scope?: string; refresh?: boolean } = {},
  ) => {
    const q = new URLSearchParams({ surface });
    if (opts.scope) q.set('scope', opts.scope);
    if (opts.refresh) q.set('refresh', 'true');
    return get(`/vendor-rationalization/insights?${q.toString()}`);
  },
};

// ── Chat / Sessions (reuse same backend) ─────────────────────────────────────

export const chatApi = {
  streamUrl: () => `${BASE_URL}/chat`,
  sessions: (limit = 30, offset = 0) => get(`/sessions?limit=${limit}&offset=${offset}`),
  history: (sessionId: string, limit = 50, offset = 0) =>
    get(`/chat/history/${sessionId}?limit=${limit}&offset=${offset}`),
  events: (sessionId: string) => get(`/chat/events/${sessionId}`),
  pending: (sessionId: string) => get(`/chat/pending/${sessionId}`),
  renameSession: (sessionId: string, title: string) =>
    fetch(`${BASE_URL}/sessions/${sessionId}`, {
      method: 'PATCH',
      headers: getHeaders(),
      body: JSON.stringify({ title }),
    }).then(handleResponse),
  archiveSession: (sessionId: string, restore = false) =>
    post(`/sessions/${sessionId}/archive`, { restore }),
  deleteSession: (sessionId: string) =>
    fetch(`${BASE_URL}/sessions/${sessionId}`, {
      method: 'DELETE',
      headers: getHeaders(),
    }).then(handleResponse),
  reply: (requestId: string, message: string) =>
    post('/chat/reply', { requestId, message }),
  models: () => get('/models/enabled'),
};
