import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Archive,
  ArchiveRestore,
  Check,
  CircleStop,
  Loader2,
  MessageSquarePlus,
  Pencil,
  Send,
  Trash2,
  X,
} from "lucide-react";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Dialog } from "@/components/ui/dialog";
import { AssistantBubble } from "@/components/chat/AssistantBubble";
import { UserBubble } from "@/components/chat/UserBubble";
import { SystemEventGroup } from "@/components/chat/SystemEventGroup";
import { chatApi } from "@/services/chatApi";
import { orderValidationSettingsApi } from "@/services/api";
import { cn } from "@/lib/utils";
import { SPINNER_VERBS } from "@/lib/spinnerVerbs";
import type { ChatSession, SystemEvent } from "@/types/chat";

interface UiMessage {
  id: string;
  role: "user" | "assistant" | "system";
  content?: string;
  events?: SystemEvent[];
  streaming?: boolean;
  // Persisted message id (from chat_messages). Used for truncate-then-resend.
  dbId?: string | null;
}

function newId(): string {
  return `local-${Math.random().toString(36).slice(2, 10)}`;
}

export function AiChatPage() {
  const qc = useQueryClient();
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<UiMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [spinnerVerbIndex, setSpinnerVerbIndex] = useState(() =>
    Math.floor(Math.random() * SPINNER_VERBS.length),
  );
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  // Rotate the streaming-placeholder verb every ~1.25s while a turn is in
  // flight. Picks a different verb each tick so the user sees motion rather
  // than a frozen "Thinking…". Mirrors the UI/ project's spinner.
  useEffect(() => {
    if (!streaming) return;
    const interval = window.setInterval(() => {
      setSpinnerVerbIndex((prev) => {
        if (SPINNER_VERBS.length <= 1) return 0;
        let next = Math.floor(Math.random() * SPINNER_VERBS.length);
        if (next === prev) next = (next + 1) % SPINNER_VERBS.length;
        return next;
      });
    }, 1250);
    return () => window.clearInterval(interval);
  }, [streaming]);

  const currentVerb = SPINNER_VERBS[spinnerVerbIndex % SPINNER_VERBS.length] ?? "Thinking";

  const { data: sessionsData, isLoading: sessionsLoading } = useQuery({
    queryKey: ["chat-sessions", showArchived],
    queryFn: () => chatApi.listSessions(50, 0, showArchived),
  });

  const { data: settings } = useQuery({
    queryKey: ["ov-settings"],
    queryFn: () => orderValidationSettingsApi.get(),
  });

  const profileId = settings?.responseMode === "detailed" ? "ov-detailed" : "ov-basic";
  const modelRef = parseModelRef(settings?.chatModelRef);

  const sessions = sessionsData?.sessions ?? [];

  const loadHistory = useCallback(async (sid: string) => {
    try {
      const [history, eventsResp] = await Promise.all([
        chatApi.getHistory(sid, 100, 0),
        chatApi.getEvents(sid).catch(() => ({ events: [] as unknown[] })),
      ]);
      const events = ((eventsResp.events ?? []) as Array<Record<string, unknown>>)
        .map(toSystemEvent)
        .filter((e): e is SystemEvent => e != null);

      // Sort everything by timestamp.
      events.sort((a, b) => a.createdAt - b.createdAt);
      const sortedMessages = history.messages
        .slice()
        .sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0));

      // Render order per turn: user → system events → assistant.
      // Strategy: walk messages; after each USER message, drain every event
      // that belongs to that turn (by turnId match, or by timestamp falling
      // in the gap before the next user message) into a single system
      // bubble that lands BEFORE the assistant reply. The assistant message
      // never gets its own events block.
      const remaining = events.slice();
      const msgs: UiMessage[] = [];

      for (let i = 0; i < sortedMessages.length; i++) {
        const m = sortedMessages[i];
        msgs.push({
          id: m.id ?? newId(),
          role: m.role,
          content: m.content,
          dbId: m.id ?? null,
        });

        if (m.role !== "user") continue;

        // Find the next user message to bound the time window for this turn.
        let nextUserStart = Number.POSITIVE_INFINITY;
        for (let j = i + 1; j < sortedMessages.length; j++) {
          if (sortedMessages[j].role === "user") {
            nextUserStart = sortedMessages[j].createdAt ?? Number.POSITIVE_INFINITY;
            break;
          }
        }
        const turnStart = m.createdAt ?? 0;
        const matched: SystemEvent[] = [];
        for (let k = remaining.length - 1; k >= 0; k--) {
          const ev = remaining[k];
          const byTurnId = m.turnId && ev.turnId && ev.turnId === m.turnId;
          const byTime = ev.createdAt >= turnStart && ev.createdAt < nextUserStart;
          if (byTurnId || byTime) {
            matched.unshift(ev);
            remaining.splice(k, 1);
          }
        }
        if (matched.length) {
          // Collapse tool.call + tool.done/tool.result pairs sharing the
          // same callId into one entry — matches live-stream rendering.
          msgs.push({ id: newId(), role: "system", events: collapseSystemEvents(matched) });
        }
      }

      // Anything left over (e.g. events with no matching user message) lands
      // at the end so nothing is silently dropped.
      if (remaining.length) {
        msgs.push({ id: newId(), role: "system", events: collapseSystemEvents(remaining) });
      }

      // Move any system bubble that ended up AFTER its assistant sibling
      // back to sit BEFORE it. Belt-and-braces — shouldn't happen with the
      // logic above, but the screenshot showed it was happening, so we
      // normalize defensively.
      const normalized: UiMessage[] = [];
      for (let i = 0; i < msgs.length; i++) {
        const cur = msgs[i];
        if (
          cur.role === "system" &&
          normalized.length > 0 &&
          normalized[normalized.length - 1].role === "assistant"
        ) {
          // Insert the system block right before the trailing assistant.
          const assistant = normalized.pop()!;
          normalized.push(cur, assistant);
          continue;
        }
        normalized.push(cur);
      }

      setMessages(normalized);
    } catch (e) {
      console.error("Failed to load history", e);
    }
  }, []);

  // Auto-scroll to bottom on new message.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [messages]);

  const startNewSession = () => {
    abortRef.current?.abort();
    setSessionId(null);
    setMessages([]);
    setDraft("");
    setStreaming(false);
  };

  const pickSession = async (sid: string) => {
    if (sid === sessionId) return;
    abortRef.current?.abort();
    setStreaming(false);
    setSessionId(sid);
    setMessages([]);
    await loadHistory(sid);
  };

  const deleteSession = async (sid: string) => {
    await chatApi.deleteSession(sid);
    qc.invalidateQueries({ queryKey: ["chat-sessions"] });
    if (sid === sessionId) startNewSession();
    setConfirmDelete(null);
  };

  const runTurn = useCallback(
    async (text: string) => {
      if (!text.trim() || streaming) return;

      const userMsg: UiMessage = { id: newId(), role: "user", content: text };
      const assistantMsg: UiMessage = {
        id: newId(),
        role: "assistant",
        content: "",
        streaming: true,
      };
      const systemMsg: UiMessage = { id: newId(), role: "system", events: [] };
      setMessages((prev) => [...prev, userMsg, systemMsg, assistantMsg]);
      setStreaming(true);
      // Re-seed the spinner verb so two consecutive turns don't start with
      // the same word.
      setSpinnerVerbIndex(Math.floor(Math.random() * SPINNER_VERBS.length));

      const abort = new AbortController();
      abortRef.current = abort;

      try {
        await chatApi.streamChat(
          {
            message: text,
            sessionId: sessionId ?? undefined,
            model: modelRef ?? undefined,
            modelSelectionMode: "manual",
            agentProfileId: profileId,
            timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          },
          (event) => handleSseEvent(event, assistantMsg.id, systemMsg.id),
          abort.signal,
        );
      } catch (e) {
        if ((e as Error).name !== "AbortError") {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsg.id
                ? { ...m, content: `_Error: ${(e as Error).message}_`, streaming: false }
                : m,
            ),
          );
        }
      } finally {
        setStreaming(false);
        abortRef.current = null;
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantMsg.id ? { ...m, streaming: false } : m)),
        );
        qc.invalidateQueries({ queryKey: ["chat-sessions"] });
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [streaming, sessionId, modelRef, profileId],
  );

  const send = async () => {
    const text = draft.trim();
    if (!text || streaming) return;
    setDraft("");
    await runTurn(text);
  };

  const resendFromIndex = useCallback(
    async (index: number, content: string) => {
      if (streaming) return;
      const userMessage = messages[index];
      // Drop everything from this user message onward (we'll resend it).
      setMessages((prev) => prev.slice(0, index));

      // For an in-progress (no sessionId yet) chat there's nothing to
      // truncate on the server; just resend client-side.
      if (sessionId && userMessage?.dbId) {
        try {
          await fetch(
            `/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/truncate`,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ messageId: userMessage.dbId }),
            },
          );
        } catch {
          // best-effort; the next turn will overwrite the visible thread anyway
        }
      } else if (sessionId) {
        // Local message wasn't persisted yet — fall back to history lookup.
        try {
          const hist = await chatApi.getHistory(sessionId, 100, 0);
          const dbId = hist.messages
            .filter((m) => m.role === "user")
            [Math.floor(index / 3)]?.id;
          if (dbId) {
            await fetch(
              `/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/truncate`,
              {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ messageId: dbId }),
              },
            );
          }
        } catch {
          // ignore
        }
      }
      await runTurn(content);
    },
    [streaming, messages, sessionId, runTurn],
  );

  const handleSseEvent = (event: Record<string, unknown>, assistantId: string, systemId: string) => {
    const type = String(event.type ?? "");
    if (type === "session" || type === "session.updated") {
      const sid = (event.sessionId ?? event.id) as string | undefined;
      if (sid && sid !== sessionId) setSessionId(sid);
      // Backend often auto-generates a session title shortly after creation.
      // Refresh the sidebar so the new title replaces "Untitled chat" without
      // a full page reload.
      qc.invalidateQueries({ queryKey: ["chat-sessions"] });
      return;
    }
    if (type === "text.delta") {
      const delta = String(event.content ?? "");
      if (!delta) return;
      setMessages((prev) =>
        prev.map((m) => (m.id === assistantId ? { ...m, content: (m.content ?? "") + delta } : m)),
      );
      return;
    }
    if (type === "done" || type === "error") {
      if (type === "error") {
        const msg = String(event.message ?? "Stream error");
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantId ? { ...m, content: `_Error: ${msg}_` } : m)),
        );
      }
      return;
    }
    const sysEvent = toSystemEvent(event);
    if (!sysEvent) return;
    setMessages((prev) =>
      prev.map((m) =>
        m.id === systemId ? { ...m, events: mergeEvent(m.events ?? [], sysEvent) } : m,
      ),
    );
  };

  const stop = async () => {
    abortRef.current?.abort();
    if (sessionId) {
      try {
        await chatApi.cancelTurn(sessionId);
      } catch {
        /* best-effort */
      }
    }
  };

  const markResolved = (requestId: string, answeredText?: string) => {
    setMessages((prev) =>
      prev.map((m) =>
        m.events
          ? {
              ...m,
              events: m.events.map((e) =>
                e.callId === requestId
                  ? { ...e, resolved: true, answeredText: answeredText ?? e.answeredText }
                  : e,
              ),
            }
          : m,
      ),
    );
  };

  const onAnswer = async (requestId: string, answer: string) => {
    if (!sessionId) return;
    try {
      await chatApi.replyToQuestion({ requestId, message: answer });
      // Persist the user's answer locally so the card flips from waiting →
      // answered + shows the reply immediately, without waiting for a
      // session reload to pull `hitlResponse` back from the server.
      markResolved(requestId, answer);
    } catch (e) {
      console.error("reply failed", e);
    }
  };

  const onApprove = async (requestId: string) => {
    if (!sessionId) return;
    await chatApi.approve({ requestId });
    markResolved(requestId, "Approved");
  };

  const onReject = async (requestId: string) => {
    if (!sessionId) return;
    await chatApi.reject({ requestId });
    markResolved(requestId, "Rejected");
  };

  const emptyState = useMemo(
    () => (
      <div className="text-center py-20 space-y-3">
        <div className="text-base font-medium text-foreground">Order Validation Assistant</div>
        <div className="text-sm text-muted-foreground max-w-md mx-auto">
          Ask me about a specific order ID, or get a summary of recent validations.
          I can also kick off a new validation run when no prior run exists.
        </div>
        <div className="flex flex-wrap justify-center gap-2 pt-3">
          {[
            "Validate order 600030510",
            "Show me failed validations in the last 7 days",
            "What's the pass rate today?",
          ].map((s) => (
            <button
              key={s}
              onClick={() => setDraft(s)}
              className="px-3 py-1.5 rounded-md border border-border bg-muted text-xs hover:bg-accent transition-colors"
            >
              {s}
            </button>
          ))}
        </div>
      </div>
    ),
    [],
  );

  return (
    <>
      <TopBar
        title="AI Assistant"
        subtitle={settings ? `Mode: ${settings.responseMode === "detailed" ? "Detailed" : "Basic"}` : undefined}
      />
      <main className="flex-1 flex overflow-hidden">
        <aside className="w-[260px] shrink-0 border-r border-border bg-card flex flex-col">
          <div className="p-3 border-b border-border space-y-2">
            <Button onClick={startNewSession} className="w-full btn-primary-text">
              <MessageSquarePlus className="size-4" /> New chat
            </Button>
            <button
              onClick={() => setShowArchived((v) => !v)}
              className="w-full text-[11px] text-muted-foreground hover:text-foreground transition-colors text-left"
            >
              {showArchived ? "← Back to active" : "Show archived"}
            </button>
          </div>
          <div className="flex-1 overflow-auto p-2 space-y-0.5">
            {sessionsLoading ? (
              <div className="p-2">
                <Skeleton className="h-9 w-full" />
              </div>
            ) : sessions.length === 0 ? (
              <div className="text-xs text-muted-foreground p-3 text-center">
                {showArchived ? "No archived chats." : "No conversations yet."}
              </div>
            ) : (
              sessions.map((s: ChatSession) => {
                const isActive = s.sessionId === sessionId;
                const isRenaming = renamingId === s.sessionId;
                return (
                  <div
                    key={s.sessionId}
                    className={cn(
                      "group flex items-center gap-1 px-2 py-2 rounded-md text-sm transition-colors",
                      isActive
                        ? "bg-accent text-foreground"
                        : "text-foreground/80 hover:bg-accent/40",
                      !isRenaming && "cursor-pointer",
                    )}
                    onClick={() => !isRenaming && pickSession(s.sessionId)}
                  >
                    {isRenaming ? (
                      <form
                        className="flex-1 flex items-center gap-1"
                        onClick={(e) => e.stopPropagation()}
                        onSubmit={async (e) => {
                          e.preventDefault();
                          const title = renameDraft.trim();
                          if (!title) return;
                          try {
                            await chatApi.renameSession(s.sessionId, title);
                            qc.invalidateQueries({ queryKey: ["chat-sessions"] });
                          } finally {
                            setRenamingId(null);
                          }
                        }}
                      >
                        <Input
                          value={renameDraft}
                          onChange={(e) => setRenameDraft(e.target.value)}
                          className="h-7 text-xs"
                          autoFocus
                        />
                        <button
                          type="submit"
                          className="size-6 inline-flex items-center justify-center text-status-pass hover:bg-accent rounded-sm"
                        >
                          <Check className="size-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() => setRenamingId(null)}
                          className="size-6 inline-flex items-center justify-center text-muted-foreground hover:bg-accent rounded-sm"
                        >
                          <X className="size-3.5" />
                        </button>
                      </form>
                    ) : (
                      <>
                        <span className="flex-1 min-w-0 truncate">
                          {s.title?.trim() || "Untitled chat"}
                        </span>
                        <div className="opacity-0 group-hover:opacity-100 flex items-center gap-0.5 transition-opacity">
                          <button
                            title="Rename"
                            onClick={(e) => {
                              e.stopPropagation();
                              setRenamingId(s.sessionId);
                              setRenameDraft(s.title ?? "");
                            }}
                            className="size-6 inline-flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent rounded-sm"
                          >
                            <Pencil className="size-3" />
                          </button>
                          <button
                            title={showArchived ? "Restore" : "Archive"}
                            onClick={async (e) => {
                              e.stopPropagation();
                              await chatApi.archiveSession(s.sessionId, showArchived);
                              qc.invalidateQueries({ queryKey: ["chat-sessions"] });
                              if (s.sessionId === sessionId) startNewSession();
                            }}
                            className="size-6 inline-flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-accent rounded-sm"
                          >
                            {showArchived ? (
                              <ArchiveRestore className="size-3" />
                            ) : (
                              <Archive className="size-3" />
                            )}
                          </button>
                          <button
                            title="Delete"
                            onClick={(e) => {
                              e.stopPropagation();
                              setConfirmDelete(s.sessionId);
                            }}
                            className="size-6 inline-flex items-center justify-center text-muted-foreground hover:text-status-fail hover:bg-accent rounded-sm"
                          >
                            <Trash2 className="size-3" />
                          </button>
                        </div>
                      </>
                    )}
                  </div>
                );
              })
            )}
          </div>
        </aside>

        <div className="flex-1 flex flex-col min-w-0">
          <div ref={scrollRef} className="flex-1 overflow-auto p-6 space-y-4">
            {messages.length === 0 && !sessionId ? (
              emptyState
            ) : (
              messages.map((m, idx) => {
                if (m.role === "user") {
                  // Find the previous user message for "regenerate" support
                  // on the assistant bubble that follows.
                  return (
                    <UserBubble
                      key={m.id}
                      content={m.content ?? ""}
                      onEditResend={
                        streaming
                          ? undefined
                          : (newContent) => resendFromIndex(idx, newContent)
                      }
                      onResend={
                        streaming
                          ? undefined
                          : () => resendFromIndex(idx, m.content ?? "")
                      }
                    />
                  );
                }
                if (m.role === "assistant") {
                  // Find the most recent user message before this assistant
                  // bubble — regenerate replays it.
                  let prevUser: { index: number; content: string } | null = null;
                  for (let j = idx - 1; j >= 0; j--) {
                    if (messages[j].role === "user") {
                      prevUser = { index: j, content: messages[j].content ?? "" };
                      break;
                    }
                  }
                  return (
                    <AssistantBubble
                      key={m.id}
                      content={m.content ?? ""}
                      streaming={m.streaming}
                      streamingVerb={currentVerb}
                      onRegenerate={
                        prevUser && !streaming
                          ? () => resendFromIndex(prevUser!.index, prevUser!.content)
                          : undefined
                      }
                    />
                  );
                }
                if (m.role === "system" && m.events?.length) {
                  return (
                    <div key={m.id} className="ml-10 max-w-3xl">
                      <SystemEventGroup
                        events={m.events}
                        onAnswer={onAnswer}
                        onApprove={onApprove}
                        onReject={onReject}
                      />
                    </div>
                  );
                }
                return null;
              })
            )}
          </div>
          <div className="border-t border-border p-4 bg-card">
            <Card>
              <CardContent className="!p-3 flex items-end gap-2">
                <textarea
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.shiftKey) {
                      e.preventDefault();
                      send();
                    }
                  }}
                  placeholder="Ask about an order ID or recent validations…"
                  rows={2}
                  disabled={streaming}
                  className="flex-1 resize-none rounded-md border border-border bg-muted px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring/50 placeholder:text-muted-foreground"
                />
                {streaming ? (
                  <Button onClick={stop} variant="outline">
                    <CircleStop className="size-4" /> Stop
                  </Button>
                ) : (
                  <Button onClick={send} disabled={!draft.trim()} className="btn-primary-text">
                    {streaming ? (
                      <Loader2 className="size-4 animate-spin" />
                    ) : (
                      <>
                        <Send className="size-4" /> Send
                      </>
                    )}
                  </Button>
                )}
              </CardContent>
            </Card>
            <div className="mt-1.5 text-[11px] text-muted-foreground px-1">
              {modelRef ? `Model: ${modelRef.providerID}/${modelRef.modelID}` : "Model: not configured — set one under Settings."}
            </div>
          </div>
        </div>
      </main>

      <Dialog
        open={confirmDelete != null}
        onClose={() => setConfirmDelete(null)}
        title="Delete conversation"
        description="This permanently removes the session and its message history."
      >
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => setConfirmDelete(null)}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => confirmDelete && deleteSession(confirmDelete)}
          >
            Delete
          </Button>
        </div>
      </Dialog>
    </>
  );
}

function parseModelRef(ref: string | null | undefined): { providerID: string; modelID: string } | null {
  if (!ref) return null;
  const idx = ref.indexOf("/");
  if (idx <= 0) return null;
  return { providerID: ref.slice(0, idx), modelID: ref.slice(idx + 1) };
}

function toSystemEvent(raw: Record<string, unknown>): SystemEvent | null {
  // Persisted events come back as { eventType, payload: "<json>", turnId, createdAt }
  // while live SSE events arrive flat as { type, name, input, output, ... }.
  // Normalize both shapes here.
  let type = String(raw.type ?? raw.eventType ?? "");
  let merged: Record<string, unknown> = raw;
  if (raw.payload && typeof raw.payload === "string") {
    try {
      const parsed = JSON.parse(raw.payload as string) as Record<string, unknown>;
      merged = { ...raw, ...parsed };
      if (parsed.type) type = String(parsed.type);
    } catch {
      // leave merged as raw
    }
  } else if (raw.payload && typeof raw.payload === "object") {
    merged = { ...raw, ...(raw.payload as Record<string, unknown>) };
  }

  // task.* and step.* are runtime plumbing noise (planner phase, model-step
  // markers) — they don't help the user read the conversation, and the
  // backend already surfaces meaningful work through tool.* + question +
  // approval_required + workflow.run.bound. Drop them on the floor.
  const allowed = [
    "tool.call",
    "tool.done",
    "tool.result",
    "tool.match",
    "question",
    "approval_required",
    "workflow.run.bound",
  ];
  if (!allowed.includes(type)) return null;

  return {
    id: String(merged.eventId ?? merged.id ?? merged.callId ?? merged.interactionId ?? Math.random().toString(36).slice(2)),
    type: type as SystemEvent["type"],
    createdAt: Number(merged.emittedAt ?? merged.createdAt ?? Date.now()),
    turnId: (merged.turnId as string | null | undefined) ?? null,
    name: (merged.toolName as string | undefined) ?? (merged.name as string | undefined),
    callId:
      (merged.callId as string | undefined) ??
      (merged.interactionId as string | undefined) ??
      (merged.requestId as string | undefined) ??
      undefined,
    input: merged.input,
    output: merged.output ?? merged.result,
    message:
      (merged.message as string | undefined) ??
      (merged.question as string | undefined) ??
      (merged.prompt as string | undefined),
    runId: (merged.runId as string | undefined) ?? (merged.instanceId as string | undefined),
    // Backend stores the resolved status as the action verb: "reply" for
    // question answers, "approved" / "rejected" for approval gates. Treat
    // any non-pending value as resolved so on-reload questions show with
    // the user's answer instead of the input form again.
    resolved:
      typeof merged.hitlStatus === "string" &&
      merged.hitlStatus !== "pending" &&
      merged.hitlStatus !== ""
        ? true
        : undefined,
    answeredText: (merged.hitlResponse as string | undefined) ?? undefined,
    payload: merged as SystemEvent["payload"],
  };
}

function mergeEvent(events: SystemEvent[], incoming: SystemEvent): SystemEvent[] {
  // For tool.call → tool.done, prefer to update the matching callId entry
  // so the chip flips from "running" to "done" in place.
  if ((incoming.type === "tool.done" || incoming.type === "tool.result") && incoming.callId) {
    const idx = events.findIndex(
      (e) => e.callId === incoming.callId && (e.type === "tool.call" || e.type === "tool.result"),
    );
    if (idx >= 0) {
      const merged: SystemEvent = { ...events[idx], ...incoming, type: "tool.done" };
      const next = events.slice();
      next[idx] = merged;
      return next;
    }
  }
  return [...events, incoming];
}

/**
 * Walk a fresh batch of system events and apply the same merge rules the
 * live-stream handler uses, so persisted events on reload render exactly
 * like the original stream (one chip per tool call, flipping running→done
 * instead of two separate entries).
 */
function collapseSystemEvents(events: SystemEvent[]): SystemEvent[] {
  return events.reduce<SystemEvent[]>((acc, ev) => mergeEvent(acc, ev), []);
}
