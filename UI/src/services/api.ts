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
  }
};
