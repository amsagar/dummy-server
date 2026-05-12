import type {
  ChatHistoryResponse,
  ChatSession,
  ChatStreamRequest,
  ModelConfig,
  PendingInteraction,
  SseEnvelope,
} from "@/types/chat";

const BASE = "/api/v1";

async function jget<T>(path: string, params: Record<string, unknown> = {}): Promise<T> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== "") qs.set(k, String(v));
  }
  const url = qs.toString() ? `${BASE}${path}?${qs.toString()}` : `${BASE}${path}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`API ${res.status}: ${(await res.text().catch(() => "")) || res.statusText}`);
  return res.json() as Promise<T>;
}

async function jsend<T>(method: "POST" | "PUT" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: body == null ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`API ${res.status}: ${(await res.text().catch(() => "")) || res.statusText}`);
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const chatApi = {
  listModels(): Promise<ModelConfig[]> {
    return jget<ModelConfig[]>("/models/enabled");
  },
  listSessions(
    limit = 30,
    offset = 0,
    includeArchived = false,
  ): Promise<{ sessions: ChatSession[]; total: number }> {
    return jget("/sessions", { limit, offset, archived: includeArchived });
  },
  archiveSession(sessionId: string, restore = false): Promise<ChatSession> {
    return jsend("POST", `/sessions/${encodeURIComponent(sessionId)}/archive`, { restore });
  },
  getHistory(sessionId: string, limit = 50, offset = 0): Promise<ChatHistoryResponse> {
    return jget(`/chat/history/${encodeURIComponent(sessionId)}`, { limit, offset });
  },
  getEvents(sessionId: string): Promise<{ events: SseEnvelope[] }> {
    return jget(`/chat/events/${encodeURIComponent(sessionId)}`);
  },
  getPending(sessionId: string): Promise<{ pending: PendingInteraction[] }> {
    return jget(`/chat/pending/${encodeURIComponent(sessionId)}`);
  },
  renameSession(sessionId: string, title: string): Promise<ChatSession> {
    return jsend("PATCH", `/sessions/${encodeURIComponent(sessionId)}`, { title });
  },
  deleteSession(sessionId: string): Promise<void> {
    return jsend("DELETE", `/sessions/${encodeURIComponent(sessionId)}`);
  },
  cancelTurn(sessionId: string): Promise<void> {
    return jsend("POST", `/chat/sessions/${encodeURIComponent(sessionId)}/cancel`, {});
  },
  replyToQuestion(payload: { requestId: string; message: string }): Promise<unknown> {
    return jsend("POST", "/chat/reply", payload);
  },
  approve(payload: { requestId: string; message?: string }): Promise<unknown> {
    return jsend("POST", "/chat/approve", payload);
  },
  reject(payload: { requestId: string; message?: string }): Promise<unknown> {
    return jsend("POST", "/chat/reject", payload);
  },
  /**
   * Open a streaming SSE connection to POST /chat. Calls `onEvent` for each
   * parsed JSON event; resolves when the stream closes naturally.
   */
  async streamChat(
    req: ChatStreamRequest,
    onEvent: (event: SseEnvelope) => void,
    signal?: AbortSignal,
  ): Promise<void> {
    const res = await fetch(`${BASE}/chat`, {
      method: "POST",
      headers: { Accept: "text/event-stream", "Content-Type": "application/json" },
      body: JSON.stringify(req),
      signal,
    });
    if (!res.ok || !res.body) {
      throw new Error(`Chat stream failed: ${res.status} ${(await res.text().catch(() => "")) || res.statusText}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE frames are separated by blank lines; parse each frame as a JSON event.
      let idx;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        for (const raw of frame.split("\n")) {
          const line = raw.trim();
          if (!line.startsWith("data:")) continue;
          const payload = line.slice(5).trim();
          if (!payload) continue;
          try {
            onEvent(JSON.parse(payload) as SseEnvelope);
          } catch {
            // ignore malformed frames
          }
        }
      }
    }
  },
};
