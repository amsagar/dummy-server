import * as React from "react";
import { useState, useEffect, useRef } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Send,
  Trash2,
  Plus,
  Bot,
  MessageSquare,
  Pencil,
  Archive,
  ArchiveRestore,
  Copy,
  Check,
  ChevronDown,
  ChevronRight,
  Paperclip,
  X,
  Square,
} from "lucide-react";
import { formatDistanceToNow } from "date-fns";
import { api } from "@/services/api";
import { getAuthToken } from "@/services/api";
import { modelRefKey, parseModelRefKey } from "@/types";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { SearchableSelect } from "@/components/ui/searchable-select";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { SPINNER_VERBS } from "@/lib/spinnerVerbs";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import {
  AssistantBubble,
  UserBubble,
  SystemEventGroup,
  type ChatMessage,
  type HitlQuestionMetadata,
  buildRenderItems,
  formatPayload,
  normalizeQuestionMetadata,
} from "@/components/chat";

type Message = ChatMessage;
type MsgType = ChatMessage["type"];

interface ChatAttachment {
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  contentBase64: string;
}

const ALLOWED_EXTENSIONS = new Set([
  "txt", "md", "csv", "json", "xml", "yaml", "yml", "log",
  "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
  "png", "jpg", "jpeg", "webp",
]);
const MAX_ATTACHMENT_SIZE_BYTES = 5 * 1024 * 1024;
const MAX_ATTACHMENTS = 5;
const SESSION_PAGE_SIZE = 30;
const HISTORY_PAGE_SIZE = 50;

function genId() {
  return Math.random().toString(36).slice(2);
}

function moveAssistantAfterFollowingSystemEvents(messages: Message[], assistantId: string): Message[] {
  const assistantIdx = messages.findIndex((m) => m.id === assistantId);
  if (assistantIdx < 0) return messages;
  let targetIdx = assistantIdx;
  while (targetIdx + 1 < messages.length && messages[targetIdx + 1].type === "system") {
    targetIdx++;
  }
  if (targetIdx === assistantIdx) return messages;
  const next = [...messages];
  const [assistantMsg] = next.splice(assistantIdx, 1);
  next.splice(targetIdx, 0, assistantMsg);
  return next;
}


export default function ChatPage() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [editingMsgIdx, setEditingMsgIdx] = useState<number | null>(null);
  const [editDraft, setEditDraft] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [spinnerVerbIndex, setSpinnerVerbIndex] = useState(() =>
    Math.floor(Math.random() * SPINNER_VERBS.length)
  );
  const isStreamingRef = useRef(false);
  const assistantAddedRef = useRef(false);
  const skipNextHydrationRef = useRef(false);
  const abortControllerRef = useRef<AbortController | null>(null);
  const [isManualSessionSwitch, setIsManualSessionSwitch] = useState(false);
  const [modelSelectionMode, setModelSelectionMode] = useState<'manual' | 'auto'>('manual');
  const [pendingInteractions, setPendingInteractions] = useState<Array<{ requestId: string; type: string; prompt: string; metadata?: HitlQuestionMetadata }>>([]);
  const [interactionDrafts, setInteractionDrafts] = useState<Record<string, string>>({});
  const [interactionSelections, setInteractionSelections] = useState<Record<string, string[]>>({});
  const [attachments, setAttachments] = useState<ChatAttachment[]>([]);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const seenPendingIdsRef = useRef<Set<string>>(new Set());
  const resolvedRequestIdsRef = useRef<Set<string>>(new Set());

  // Session management state
  const [sidebarSearch, setSidebarSearch] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [hoveredSessionId, setHoveredSessionId] = useState<string | null>(null);

  // Message action state
  const [hoveredMsgIdx, setHoveredMsgIdx] = useState<number | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // stored as "providerID/modelID" string for localStorage compatibility
  const [selectedModel, setSelectedModel] = useState<string>(
    localStorage.getItem("lastModelId") || ""
  );
  // Per-request embedding model override. Empty string = "use system default".
  // TODO: wire embedding model selection — render a small picker in the composer
  // (label "Embed") sourced from api.embeddingModels.list({ enabledOnly: true }).
  const [selectedEmbeddingModel, setSelectedEmbeddingModel] = useState<string>(
    localStorage.getItem("lastEmbeddingModelId") || ""
  );
  // Suppress unused-setter warning until the picker UI is wired in.
  void setSelectedEmbeddingModel;
  // Provider-first selector: track selected provider separately
  const [selectedProvider, setSelectedProvider] = useState<string>(
    localStorage.getItem("lastModelId")?.split("/")[0] || ""
  );
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const sidebarScrollRootRef = useRef<HTMLDivElement | null>(null);
  const chatScrollRootRef = useRef<HTMLDivElement | null>(null);
  const queryClient = useQueryClient();
  const currentSpinnerVerb = SPINNER_VERBS[spinnerVerbIndex % SPINNER_VERBS.length] || "Thinking";
  const [historyMessages, setHistoryMessages] = useState<any[]>([]);
  const [historyOffset, setHistoryOffset] = useState(0);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [loadingOlderHistory, setLoadingOlderHistory] = useState(false);
  const [loadingMoreSessions, setLoadingMoreSessions] = useState(false);
  useEffect(() => {
    if (!isStreaming) return;
    const intervalId = window.setInterval(() => {
      setSpinnerVerbIndex((prev) => {
        if (SPINNER_VERBS.length <= 1) return 0;
        let next = Math.floor(Math.random() * SPINNER_VERBS.length);
        if (next === prev) next = (next + 1) % SPINNER_VERBS.length;
        return next;
      });
    }, 1250);
    return () => window.clearInterval(intervalId);
  }, [isStreaming]);

  const appendSystemMessage = (eventType: string, content: string, requestId?: string, eventPayload?: any) => {
    setMessages((prev) => {
      if (requestId && eventType === "approval_status") {
        const idx = [...prev]
          .map((m, i) => ({ m, i }))
          .reverse()
          .find(
            ({ m }) =>
              m.type === "system" &&
              m.requestId === requestId &&
              (m.eventType === "question" || m.eventType === "approval_required")
          )?.i;
        if (idx !== undefined) {
          return prev.map((m, i) =>
            i === idx ? { ...m, eventType: "approval_status", content } : m
          );
        }
      }
      const last = prev[prev.length - 1];
      const duplicateInWindow = prev
        .slice(-20)
        .some(
          (m) =>
            m.type === "system" &&
            m.eventType === eventType &&
            m.content === content &&
            (!requestId || m.requestId === requestId)
        );
      if ((last?.type === "system" && last.eventType === eventType && last.content === content && last.requestId === requestId) || duplicateInWindow) {
        return prev;
      }
      return [...prev, { id: genId(), type: "system", eventType, content, requestId, eventPayload }];
    });
  };

  // Check for resumeSessionId from Sessions page
  useEffect(() => {
    const resumeId = localStorage.getItem("resumeSessionId");
    if (resumeId) {
      setSessionId(resumeId);
      localStorage.removeItem("resumeSessionId");
    }
  }, []);

  const { data: modelsData } = useQuery({
    queryKey: ['models', 'enabled'],
    queryFn: () => api.get('/models/enabled'),
  });

  const { data: sessionsData, refetch: refetchSessions } = useQuery({
    queryKey: ['sessions'],
    queryFn: () => api.get(`/sessions?limit=${SESSION_PAGE_SIZE}&offset=0`),
  });

  const { data: pendingData, refetch: refetchPending } = useQuery({
    queryKey: ['chat-pending', sessionId],
    queryFn: () => api.get(`/chat/pending/${sessionId}`),
    enabled: !!sessionId,
  });
  const { data: eventsData } = useQuery({
    queryKey: ['chat-events', sessionId],
    queryFn: () => api.get(`/chat/events/${sessionId}`),
    enabled: !!sessionId,
  });

  const upsertSessionInCache = React.useCallback((patch: Record<string, any>) => {
    queryClient.setQueryData(['sessions'], (old: any) => {
      const fallback = {
        sessions: [],
        total: 0,
        hasMore: false,
        limit: SESSION_PAGE_SIZE,
        offset: 0,
      };
      const current = old?.sessions ? old : fallback;
      const sessions = Array.isArray(current.sessions) ? [...current.sessions] : [];
      const idx = sessions.findIndex((s: any) => s.sessionId === patch.sessionId);
      if (idx >= 0) {
        sessions[idx] = { ...sessions[idx], ...patch };
      } else {
        sessions.unshift({
          sessionId: patch.sessionId,
          title: patch.title || "New Chat",
          createdAt: patch.createdAt ?? Date.now(),
          lastActive: patch.lastActive ?? Date.now(),
          archivedAt: null,
          ...patch,
        });
      }
      sessions.sort((a: any, b: any) => (b.lastActive || 0) - (a.lastActive || 0));
      const nextTotal = Math.max(current.total || 0, sessions.length);
      return {
        ...current,
        sessions,
        total: nextTotal,
        count: sessions.length,
        hasMore: (current.hasMore === true) && sessions.length < nextTotal,
      };
    });
  }, [queryClient]);

  const loadInitialHistory = React.useCallback(async (sid: string) => {
    const meta = await api.get(`/chat/history/${sid}?limit=1&offset=0`);
    const total = Number(meta?.total || 0);
    const initialOffset = Math.max(0, total - HISTORY_PAGE_SIZE);
    const page = await api.get(`/chat/history/${sid}?limit=${HISTORY_PAGE_SIZE}&offset=${initialOffset}`);
    setHistoryMessages(page?.messages || []);
    setHistoryOffset(initialOffset);
    setHistoryTotal(Number(page?.total || total));
  }, []);

  const loadOlderHistory = React.useCallback(async () => {
    if (!sessionId || loadingOlderHistory || historyOffset <= 0) return;
    setLoadingOlderHistory(true);
    try {
      const nextOffset = Math.max(0, historyOffset - HISTORY_PAGE_SIZE);
      const nextLimit = historyOffset - nextOffset;
      const page = await api.get(`/chat/history/${sessionId}?limit=${nextLimit}&offset=${nextOffset}`);
      const older = page?.messages || [];
      setHistoryMessages((prev) => {
        const seen = new Set(prev.map((m: any) => m.id));
        const dedupedOlder = older.filter((m: any) => !seen.has(m.id));
        return [...dedupedOlder, ...prev];
      });
      setHistoryOffset(nextOffset);
      setHistoryTotal(Number(page?.total || historyTotal));
    } finally {
      setLoadingOlderHistory(false);
    }
  }, [historyOffset, historyTotal, loadingOlderHistory, sessionId]);

  const loadMoreSessions = React.useCallback(async () => {
    if (loadingMoreSessions) return;
    const currentSessions = sessionsData?.sessions || [];
    const hasMore = sessionsData?.hasMore === true;
    if (!hasMore) return;

    setLoadingMoreSessions(true);
    try {
      const nextOffset = currentSessions.length;
      const page = await api.get(`/sessions?limit=${SESSION_PAGE_SIZE}&offset=${nextOffset}`);
      const nextRows = page?.sessions || [];
      queryClient.setQueryData(['sessions'], (old: any) => {
        const existing = old?.sessions || [];
        const seen = new Set(existing.map((s: any) => s.sessionId));
        const merged = [...existing, ...nextRows.filter((s: any) => !seen.has(s.sessionId))];
        return {
          ...(old || {}),
          sessions: merged,
          total: Number(page?.total || old?.total || merged.length),
          hasMore: page?.hasMore === true,
          limit: SESSION_PAGE_SIZE,
          offset: 0,
          count: merged.length,
        };
      });
    } finally {
      setLoadingMoreSessions(false);
    }
  }, [loadingMoreSessions, queryClient, sessionsData]);

  useEffect(() => {
    if (!sessionId) {
      setHistoryMessages([]);
      setHistoryOffset(0);
      setHistoryTotal(0);
      return;
    }
    loadInitialHistory(sessionId).catch(() => {
      setHistoryMessages([]);
      setHistoryOffset(0);
      setHistoryTotal(0);
    });
  }, [loadInitialHistory, sessionId]);

  useEffect(() => {
    // Hydrate from persisted session history whenever history/events are available.
    // Skip hydration only while an active stream is in progress.
    if (historyMessages.length > 0 && !isStreamingRef.current) {
      // Skip one hydration cycle right after streaming completes so we wait for
      // eventsData to be refetched before overwriting the live-streamed messages.
      if (skipNextHydrationRef.current) {
        skipNextHydrationRef.current = false;
        return;
      }
      // Build a map of turnId → reasoning content from persisted reasoning events
      const reasoningByTurnId = new Map<string, string>();
      (eventsData?.events || []).forEach((e: any) => {
        if (e.eventType === 'reasoning' && e.turnId) {
          try {
            const p = JSON.parse(e.payload || '{}');
            if (p.content) reasoningByTurnId.set(e.turnId, p.content);
          } catch { /* ignore */ }
        }
      });

      const chatMsgs: Message[] = historyMessages.map((m: any) => ({
        id: genId(),
        type: m.role === 'user' ? 'user' : 'assistant',
        content: m.content || '',
        reasoning: m.role === 'assistant' && m.turnId ? reasoningByTurnId.get(m.turnId) : undefined,
        dbId: m.id,
        turnId: m.turnId,
        createdAt: m.createdAt,
      }));

      const sysMsgs: Message[] = (eventsData?.events || []).map((e: any) => {
        let payload: any = {};
        try { payload = JSON.parse(e.payload || '{}'); } catch { /* ignore */ }
        let content = '';
        if (e.eventType === 'tool.call') {
          const toolName = payload.toolName || payload.tool || "";
          payload = { ...payload, callId: payload.callId, toolName, input: payload.input ?? payload.arguments };
          content = `Calling tool: ${toolName}`;
        } else if (e.eventType === 'tool.done' || e.eventType === 'tool.result') {
          const toolName = payload.toolName || payload.tool || "";
          payload = { ...payload, callId: payload.callId, toolName, output: payload.output ?? payload.result };
          content = `Tool completed: ${toolName}`;
        } else if (e.eventType === 'step.started' || e.eventType === 'step.finished') {
          return null as any;
        } else if (e.eventType === 'tool.match') {
          const needsClarification = payload.needsClarification === true || payload.decision === "clarify";
          const candidates = Array.isArray(payload.candidates)
            ? payload.candidates
            : typeof payload.considered === "string"
            ? payload.considered.split(",").map((s: string) => s.trim()).filter(Boolean)
            : [];
          payload = {
            ...payload,
            needsClarification,
            candidates,
          };
          content = needsClarification
            ? `Tool clarification needed (${payload.reason || "ambiguous"})`
            : `Tool matched${payload.selectedTool ? `: ${payload.selectedTool}` : ""}`;
        } else if (e.eventType === 'question') {
          const metadata = normalizeQuestionMetadata(payload.metadata || payload);
          const questionFromNested = Array.isArray(payload.questions) && payload.questions.length > 0
            ? String(payload.questions[0]?.question || payload.questions[0]?.header || "")
            : "";
          payload = { ...payload, metadata };
          content = payload.question || payload.prompt || questionFromNested || '';
        } else if (e.eventType === 'approval_required') {
          content = payload.reason || '';
        } else if (e.eventType === 'toolchain.run.bound') {
          content = `ToolChain run #${String(payload.runId || '').slice(0, 8)}`;
        } else {
          return null as any;
        }
        return {
          id: genId(),
          type: 'system' as MsgType,
          turnId: e.turnId,
          eventType: e.eventType,
          content,
          createdAt: e.createdAt,
          requestId: payload.requestId,
          hitlStatus: e.hitlStatus,
          hitlResponse: e.hitlResponse,
          eventPayload: payload,
        };
      }).filter(Boolean);

      const toolMatchSeen = new Set<string>();
      const dedupedSysMsgs = sysMsgs.filter((m) => {
        if (m.eventType !== "tool.match") return true;
        const signature = `${m.turnId || "na"}|${m.content}|${formatPayload(m.eventPayload?.candidates || [])}`;
        if (toolMatchSeen.has(signature)) return false;
        toolMatchSeen.add(signature);
        return true;
      });
      dedupedSysMsgs
        .filter((m) => (m.eventType === "question" || m.eventType === "approval_required") && !!m.requestId)
        .forEach((m) => seenPendingIdsRef.current.add(String(m.requestId)));

      const merged = [...chatMsgs, ...dedupedSysMsgs].sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
      setMessages(merged);
      setIsManualSessionSwitch(false);
    }
  }, [historyMessages, eventsData]);

  useEffect(() => {
    if (pendingData?.pending) {
      const mappedPending = pendingData.pending.map((p: any) => ({
        requestId: p.requestId,
        type: p.type,
        prompt: p.prompt,
        metadata: normalizeQuestionMetadata(p.metadata || p),
      })).filter((p: any) => !resolvedRequestIdsRef.current.has(p.requestId));

      // Merge API results with locally-added items from SSE events.
      // The API response may arrive before the question is persisted (race condition),
      // so we must not overwrite SSE-driven items that aren't in the API result yet.
      setPendingInteractions(prev => {
        const apiIds = new Set(mappedPending.map((p: any) => p.requestId));
        const localOnlyItems = prev.filter(
          (p) => !apiIds.has(p.requestId) && !resolvedRequestIdsRef.current.has(p.requestId)
        );
        return [...mappedPending, ...localOnlyItems];
      });

      const unseenRows = mappedPending
        .filter((p: any) => !seenPendingIdsRef.current.has(p.requestId))
        .map((p: any) => ({
          id: genId(),
          type: "system" as const,
          content: p.prompt || (p.type === "question" ? "Clarification required." : "Approval required."),
          eventType: p.type === "question" ? "question" : "approval_required",
          requestId: p.requestId,
        }));
      unseenRows.forEach((row) => seenPendingIdsRef.current.add(String(row.requestId)));
      if (unseenRows.length > 0) {
        setMessages((prev) => [...prev, ...unseenRows]);
      }
    }
  }, [pendingData]);

  useEffect(() => {
    if (loadingOlderHistory) return;
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [loadingOlderHistory, messages]);

  useEffect(() => {
    const root = sidebarScrollRootRef.current;
    if (!root) return;
    const viewport = root.querySelector("[data-slot='scroll-area-viewport']") as HTMLElement | null;
    if (!viewport) return;
    const onScroll = () => {
      const remaining = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
      if (remaining < 240) {
        void loadMoreSessions();
      }
    };
    viewport.addEventListener("scroll", onScroll);
    return () => viewport.removeEventListener("scroll", onScroll);
  }, [loadMoreSessions, sessionsData?.hasMore, sessionsData?.sessions?.length]);

  useEffect(() => {
    const root = chatScrollRootRef.current;
    if (!root) return;
    const viewport = root.querySelector("[data-slot='scroll-area-viewport']") as HTMLElement | null;
    if (!viewport) return;
    const onScroll = () => {
      if (viewport.scrollTop < 80) {
        void loadOlderHistory();
      }
    };
    viewport.addEventListener("scroll", onScroll);
    return () => viewport.removeEventListener("scroll", onScroll);
  }, [loadOlderHistory, historyOffset, historyTotal]);

  // Build a provider→models map from enabled chat models (exclude embeddings)
  const modelsByProvider = React.useMemo(() => {
    const map = new Map<string, { providerName?: string; models: any[] }>();
    for (const m of (modelsData || [])) {
      if (m.modelKind === 'embedding') continue;
      const pid = m.providerID as string;
      if (!map.has(pid)) map.set(pid, { providerName: m.providerName, models: [] });
      map.get(pid)!.models.push(m);
    }
    return map;
  }, [modelsData]);

  const providerList = React.useMemo(() => Array.from(modelsByProvider.keys()), [modelsByProvider]);

  // Auto-select first provider+model when data loads
  useEffect(() => {
    if (modelsData?.length && !selectedModel) {
      const first = modelsData[0];
      const pid = first.providerID as string;
      const key = modelRefKey({ providerID: pid, modelID: first.modelID });
      setSelectedProvider(pid);
      setSelectedModel(key);
      localStorage.setItem("lastModelId", key);
    }
  }, [modelsData]);

  // ── session list helpers ────────────────────────────────────────────────────
  const allSessions: any[] = sessionsData?.sessions || sessionsData || [];
  const currentSession = allSessions.find((s: any) => s.sessionId === sessionId);

  const activeSessions = allSessions.filter(s =>
    !s.archivedAt &&
    (!sidebarSearch || s.title?.toLowerCase().includes(sidebarSearch.toLowerCase()))
  );
  const archivedSessions = allSessions.filter(s =>
    s.archivedAt &&
    (!sidebarSearch || s.title?.toLowerCase().includes(sidebarSearch.toLowerCase()))
  );

  // ── handlers ───────────────────────────────────────────────────────────────

  const handleProviderChange = (pid: string) => {
    setSelectedProvider(pid);
    const models = modelsByProvider.get(pid)?.models ?? [];
    if (models.length > 0) {
      const key = modelRefKey({ providerID: pid, modelID: models[0].modelID });
      setSelectedModel(key);
      localStorage.setItem("lastModelId", key);
    } else {
      setSelectedModel("");
    }
  };

  const handleModelChange = (val: string) => {
    setSelectedModel(val);
    localStorage.setItem("lastModelId", val);
  };

  const handleNewChat = () => {
    setSessionId(null);
    setMessages([]);
    setHistoryMessages([]);
    setHistoryOffset(0);
    setHistoryTotal(0);
    setInput("");
    setPendingInteractions([]);
    setInteractionDrafts({});
    setInteractionSelections({});
    seenPendingIdsRef.current = new Set();
    resolvedRequestIdsRef.current = new Set();
  };

  const handleSelectSession = (id: string) => {
    if (id === sessionId) return;
    setIsManualSessionSwitch(true);
    setSessionId(id);
    setMessages([]);
    setHistoryMessages([]);
    setHistoryOffset(0);
    setHistoryTotal(0);
    setPendingInteractions([]);
    setInteractionDrafts({});
    setInteractionSelections({});
    seenPendingIdsRef.current = new Set();
    resolvedRequestIdsRef.current = new Set();
  };

  const commitRename = async (sid: string) => {
    if (!renameValue.trim()) { setRenamingId(null); return; }
    try {
      await api.patch(`/sessions/${sid}`, { title: renameValue.trim() });
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
    } catch {
      toast.error("Failed to rename session");
    }
    setRenamingId(null);
  };

  const archiveSession = async (sid: string) => {
    try {
      await api.post(`/sessions/${sid}/archive`, {});
      if (sessionId === sid) handleNewChat();
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
    } catch {
      toast.error("Failed to archive session");
    }
  };

  const restoreSession = async (sid: string) => {
    try {
      await api.post(`/sessions/${sid}/archive`, { restore: true });
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
    } catch {
      toast.error("Failed to restore session");
    }
  };

  const copyMessage = (content: string, idx: number) => {
    navigator.clipboard.writeText(content).catch(() => {});
    setCopiedId(String(idx));
    setTimeout(() => setCopiedId(null), 2000);
  };

  const markRequestResolved = (requestId: string) => {
    resolvedRequestIdsRef.current.add(requestId);
    seenPendingIdsRef.current.add(requestId);
    setPendingInteractions((prev) => prev.filter((p) => p.requestId !== requestId));
    setInteractionDrafts((prev) => {
      const next = { ...prev };
      delete next[requestId];
      return next;
    });
    setInteractionSelections((prev) => {
      const next = { ...prev };
      delete next[requestId];
      return next;
    });
  };

  const handleSend = async (overrideText?: string, options?: { skipPendingHandling?: boolean }) => {
    const userMessage = overrideText ?? input.trim();
    const activeAttachments = overrideText ? [] : attachments;
    if ((!userMessage && activeAttachments.length === 0) || isStreaming) return;
    if (modelSelectionMode === "manual" && !selectedModel) {
      toast.error("Select a model before sending");
      return;
    }
    if (!overrideText) setInput("");

    const pendingQuestion = options?.skipPendingHandling
      ? null
      : pendingInteractions.find((p) => p.type === "question");
    const pendingMode = pendingQuestion?.metadata?.responseMode || "text";
    const questionCanUseComposer =
      !pendingQuestion
      || pendingMode === "text"
      || !!pendingQuestion.metadata?.allowCustomText;
    if (pendingQuestion && userMessage && questionCanUseComposer) {
      try {
        await api.post("/chat/reply", {
          requestId: pendingQuestion.requestId,
          message: userMessage,
          selectedOptionIds: interactionSelections[pendingQuestion.requestId] || [],
        });
        markRequestResolved(pendingQuestion.requestId);
        await refetchPending();
        appendSystemMessage("approval_status", "Reply submitted.", pendingQuestion.requestId);
        toast.success("Clarification submitted");
        return;
      } catch (e: any) {
        toast.error(e.message || "Failed to submit clarification");
        return;
      }
    }

    const attachmentLabel = activeAttachments.length > 0
      ? `\n\n[Attachments: ${activeAttachments.map(a => a.fileName).join(", ")}]`
      : "";
    const userMsg: Message = { id: genId(), type: 'user', content: `${userMessage}${attachmentLabel}`.trim() };
    setMessages(prev => [...prev, userMsg]);

    const assistantId = genId();
    setMessages(prev => [
      ...prev,
      {
        id: assistantId,
        type: 'assistant',
        content: '',
        isStreaming: true,
      },
    ]);

    setSpinnerVerbIndex(Math.floor(Math.random() * SPINNER_VERBS.length));
    setIsStreaming(true);
    isStreamingRef.current = true;
    assistantAddedRef.current = true;
    setIsManualSessionSwitch(false);

    try {
      const abort = new AbortController();
      abortControllerRef.current = abort;
      const token = getAuthToken();
      const response = await fetch('http://localhost:8080/api/v1/chat', {
        method: 'POST',
        signal: abort.signal,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify({
          message: userMessage,
          sessionId: sessionId || undefined,
          model: selectedModel ? parseModelRefKey(selectedModel) : undefined,
          embeddingModel: selectedEmbeddingModel ? parseModelRefKey(selectedEmbeddingModel) : null,
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          modelSelectionMode,
          attachments: activeAttachments.length > 0 ? activeAttachments : undefined,
        }),
      });

      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      const handleEvent = (ev: any) => {
        switch (ev.type) {
          case 'connected':
          case 'session':
            if (ev.sessionId) {
              setSessionId(ev.sessionId);
              upsertSessionInCache({
                sessionId: ev.sessionId,
                createdAt: Date.now(),
                lastActive: Date.now(),
                title: "New Chat",
                archivedAt: null,
              });
              refetchSessions();
            }
            break;

          case 'text.delta':
            setMessages(prev => prev.map(m =>
              m.id === assistantId
                ? { ...m, content: m.content + (ev.content || '') }
                : m
            ));
            break;

          case 'reasoning.delta':
            setMessages(prev => prev.map(m =>
              m.id === assistantId
                ? { ...m, reasoning: (m.reasoning || '') + (ev.content || '') }
                : m
            ));
            break;

          case 'session.updated':
            if (ev.sessionId && ev.title) {
              upsertSessionInCache({
                sessionId: ev.sessionId,
                title: ev.title,
                lastActive: Date.now(),
              });
            }
            break;
          case 'plan.created':
            break;
          case 'task.started':
            // ToolChain runtime emits one task.started per node. Render as a system
            // chip so the user sees the chain's nodes execute live in the chat.
            if (ev.taskName || ev.taskId) {
              appendSystemMessage('task.started', `Running node: ${ev.taskName || ev.taskId}`, undefined, {
                taskId: ev.taskId,
                taskName: ev.taskName,
              });
            }
            break;
          case 'task.done':
            if (ev.taskName || ev.taskId) {
              appendSystemMessage('task.done', `Node completed: ${ev.taskName || ev.taskId}`, undefined, {
                taskId: ev.taskId,
                taskName: ev.taskName,
                status: ev.result,
              });
            }
            break;
          case 'toolchain.run.bound':
            appendSystemMessage('toolchain.run.bound',
              `ToolChain run #${String(ev.runId || '').slice(0, 8)}`,
              undefined,
              {
                toolChainId: ev.toolChainId,
                runId: ev.runId,
                version: ev.version,
                status: ev.status,
              });
            break;
          case 'step.started':
          case 'step.finished':
            // suppressed — sequential tool execution no longer emits step boundaries
            break;
          case 'tool.call':
            {
              const toolName = ev.toolName || ev.tool || "";
              appendSystemMessage(ev.type, `Calling tool: ${toolName}`, undefined, { callId: ev.callId, toolName, input: ev.input ?? ev.arguments });
            }
            break;
          case 'tool.done':
          case 'tool.result':
            {
              const toolName = ev.toolName || ev.tool || "";
              appendSystemMessage(ev.type, `Tool completed: ${toolName}`, undefined, { callId: ev.callId, toolName, output: ev.output ?? ev.result, status: ev.status });
            }
            break;
          case 'tool.match':
            appendSystemMessage(
              ev.type,
              ev.needsClarification
                ? `Tool clarification needed (${ev.reason || "ambiguous"})`
                : `Tool matched${ev.selectedTool ? `: ${ev.selectedTool}` : ""}`,
              undefined,
              {
                selectedTool: ev.selectedTool,
                score: ev.score,
                needsClarification: ev.needsClarification,
                reason: ev.reason,
                candidates: Array.isArray(ev.candidates) ? ev.candidates : [],
              }
            );
            break;
          case 'question':
            if (resolvedRequestIdsRef.current.has(ev.requestId)) break;
            if (!seenPendingIdsRef.current.has(ev.requestId)) {
              const metadata = normalizeQuestionMetadata(ev.metadata || ev);
              const questionFromNested = Array.isArray(ev.questions) && ev.questions.length > 0
                ? String(ev.questions[0]?.question || ev.questions[0]?.header || "")
                : "";
              const questionText = ev.question || ev.prompt || questionFromNested || "Please provide clarification.";
              seenPendingIdsRef.current.add(ev.requestId);
              setPendingInteractions(prev => prev.some((p) => p.requestId === ev.requestId)
                ? prev
                : [...prev, { requestId: ev.requestId, type: 'question', prompt: questionText, metadata }]);
              setInteractionDrafts((prev) => ({ ...prev, [ev.requestId]: "" }));
              setInteractionSelections((prev) => ({ ...prev, [ev.requestId]: [] }));
              appendSystemMessage("question", questionText, ev.requestId, { metadata });
            }
            break;
          case 'approval_required':
            if (resolvedRequestIdsRef.current.has(ev.requestId)) break;
            if (!seenPendingIdsRef.current.has(ev.requestId)) {
              seenPendingIdsRef.current.add(ev.requestId);
              setPendingInteractions(prev => prev.some((p) => p.requestId === ev.requestId)
                ? prev
                : [...prev, { requestId: ev.requestId, type: 'approval', prompt: ev.reason || 'Approval required' }]);
              setInteractionDrafts((prev) => ({ ...prev, [ev.requestId]: "" }));
              appendSystemMessage("approval_required", ev.reason || "Approval required.", ev.requestId);
            }
            break;
          case 'state.updated':
            break;
          case 'workspace.ready':
          case 'skills.synced':
          case 'state.transition':
            // Internal runtime diagnostics: keep persisted for backend observability
            // but do not render as transcript rows in the main chat UI.
            break;
          case 'cost.updated':
            break;
          case 'summary.updated':
            break;

          case 'done':
            setIsStreaming(false);
            isStreamingRef.current = false;
            skipNextHydrationRef.current = true;
            if (ev.content !== undefined) {
              setMessages(prev => {
                const updated = prev.map(m =>
                  m.id === assistantId
                    ? { ...m, content: ev.content, isStreaming: false }
                    : m
                );
                return moveAssistantAfterFollowingSystemEvents(updated, assistantId);
              });
            } else {
              setMessages(prev => {
                const updated = prev
                  .map(m => (m.id === assistantId ? { ...m, isStreaming: false } : m))
                  .filter(m => !(m.id === assistantId && !m.content.trim()));
                return moveAssistantAfterFollowingSystemEvents(updated, assistantId);
              });
            }
            refetchSessions();
            refetchPending();
            queryClient.invalidateQueries({ queryKey: ['chat-events', sessionId] });
            if (ev.sessionId || sessionId) {
              const sid = ev.sessionId || sessionId;
              if (sid) {
                void loadInitialHistory(sid);
              }
              upsertSessionInCache({
                sessionId: sid,
                lastActive: Date.now(),
              });
            }
            break;

          case 'error':
            toast.error(ev.message || 'An error occurred');
            setIsStreaming(false);
            break;
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          // SSE format: "data: {...}" or "data:{...}" — handle both
          const dataLine = line.startsWith('data: ')
            ? line.slice(6)
            : line.startsWith('data:')
            ? line.slice(5)
            : null;
          if (dataLine) {
            try {
              const ev = JSON.parse(dataLine);
              handleEvent(ev);
            } catch {}
          }
        }
      }
    } catch (err: any) {
      toast.error(err.message || "Error communicating with AI");
      setIsStreaming(false);
    } finally {
      if (!overrideText) {
        setAttachments([]);
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
    }
  };

  const fileToBase64 = (file: File) =>
    new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = String(reader.result || "");
        const comma = result.indexOf(",");
        resolve(comma >= 0 ? result.slice(comma + 1) : result);
      };
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });

  const onSelectAttachments = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const fileList = event.target.files;
    const files: File[] = fileList ? Array.from(fileList) : [];
    if (files.length === 0) return;
    const existingNames = new Set(attachments.map(a => a.fileName.toLowerCase()));
    const next: ChatAttachment[] = [];
    for (const file of files) {
      if (attachments.length + next.length >= MAX_ATTACHMENTS) {
        toast.error(`Only ${MAX_ATTACHMENTS} attachments allowed`);
        break;
      }
      const ext = file.name.includes(".") ? file.name.split(".").pop()!.toLowerCase() : "";
      if (!ALLOWED_EXTENSIONS.has(ext)) {
        toast.error(`Unsupported file type: ${file.name}`);
        continue;
      }
      if (file.size > MAX_ATTACHMENT_SIZE_BYTES) {
        toast.error(`File too large: ${file.name} (max 5MB)`);
        continue;
      }
      if (existingNames.has(file.name.toLowerCase())) continue;
      const contentBase64 = await fileToBase64(file);
      next.push({
        fileName: file.name,
        mimeType: file.type || "application/octet-stream",
        sizeBytes: file.size,
        contentBase64,
      });
    }
    if (next.length > 0) setAttachments(prev => [...prev, ...next]);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const removeAttachment = (fileName: string) => {
    setAttachments(prev => prev.filter(a => a.fileName !== fileName));
  };

  const handleStop = async () => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    setIsStreaming(false);
    isStreamingRef.current = false;
    setMessages(prev => prev.map(m => m.isStreaming ? { ...m, isStreaming: false } : m));
    if (sessionId) {
      try { await api.post(`/chat/sessions/${sessionId}/cancel`, {}); } catch {}
    }
  };

  const answerPending = async (requestId: string, action: 'reply' | 'approve' | 'reject', selectedOptionIds?: string[]) => {
    const path = action === 'reply' ? '/chat/reply' : action === 'approve' ? '/chat/approve' : '/chat/reject';
    try {
      const customMessage = (interactionDrafts[requestId] || "").trim();
      const selected = selectedOptionIds || interactionSelections[requestId] || [];
      await api.post(path, { requestId, message: customMessage, selectedOptionIds: selected });
      markRequestResolved(requestId);
      refetchPending();
      appendSystemMessage(
        "approval_status",
        action === "approve"
          ? "Approved."
          : action === "reject"
          ? "Rejected."
          : "Reply submitted.",
        requestId
      );
      toast.success(`Action submitted: ${action}`);
    } catch (e: any) {
      toast.error(e.message || "Failed to submit action");
    }
  };

  const handleResend = async (messageIndex: number, content: string) => {
    if (isStreaming) return;
    if (!sessionId) {
      setMessages(prev => prev.slice(0, messageIndex));
      await handleSend(content);
      return;
    }

    let dbMessageId = messages[messageIndex]?.dbId;
    if (!dbMessageId) {
      const latestHistory = await api.get(`/chat/history/${sessionId}`);
      dbMessageId = latestHistory?.messages?.[messageIndex]?.id;
    }

    if (!dbMessageId) {
      toast.error("Could not locate message in session history");
      return;
    }

    await api.post(`/chat/sessions/${sessionId}/truncate`, { messageId: dbMessageId });
    setMessages(prev => prev.slice(0, messageIndex));
    await handleSend(content);
  };

  const deleteSession = useMutation({
    mutationFn: (id: string) => api.delete(`/sessions/${id}`),
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
      if (sessionId === id) handleNewChat();
      toast.success("Session deleted");
    },
    onError: () => toast.error("Failed to delete session"),
  });

  return (
    <div className="grid h-[calc(100vh-7rem)] min-h-[640px] grid-cols-1 overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm lg:grid-cols-[220px_minmax(0,1fr)]">
      {/* Session rail */}
      <div className="hidden border-r border-gray-200 bg-white lg:flex lg:flex-col">
        <div className="space-y-2 border-b border-gray-200 p-3">
          <Button
            className="h-9 w-full rounded-lg border-none text-sm font-semibold text-white shadow-none"
            style={{ background: "#005CB9" }}
            onClick={handleNewChat}
            title="Start a new chat session"
          >
            <Plus size={16} className="mr-2" /> New Chat
          </Button>
          <input
            value={sidebarSearch}
            onChange={e => setSidebarSearch(e.target.value)}
            placeholder="Search sessions..."
            className="w-full rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-700 placeholder:text-gray-400 focus:outline-none focus:ring-1 focus:ring-[#005CB9]/30"
          />
        </div>
        <div className="flex-1" ref={sidebarScrollRootRef}>
          <ScrollArea className="h-full">
            <div className="p-2 space-y-1">
            {activeSessions.length === 0 && archivedSessions.length === 0 && (
              <div className="py-8 text-center text-xs text-slate-400">
                <MessageSquare size={24} className="mx-auto mb-2 opacity-40" />
                No sessions yet
              </div>
            )}

            {/* Active sessions */}
            {activeSessions.map((s: any) => (
              <div
                key={s.sessionId}
                onMouseEnter={() => setHoveredSessionId(s.sessionId)}
                onMouseLeave={() => setHoveredSessionId(null)}
                className={cn(
                  "group flex cursor-pointer items-center justify-between rounded-lg p-2.5 transition-all",
                  sessionId === s.sessionId
                    ? "border border-[#BFDBFE] bg-[#EFF6FF]"
                    : "hover:bg-gray-50"
                )}
                onClick={() => renamingId !== s.sessionId && handleSelectSession(s.sessionId)}
              >
                <div className="min-w-0 flex-1">
                  {renamingId === s.sessionId ? (
                    <input
                      autoFocus
                      value={renameValue}
                      onChange={e => setRenameValue(e.target.value)}
                      onBlur={() => commitRename(s.sessionId)}
                      onKeyDown={e => {
                        if (e.key === 'Enter') commitRename(s.sessionId);
                        if (e.key === 'Escape') setRenamingId(null);
                      }}
                      className="w-full border-b border-slate-400 bg-transparent pb-0.5 text-xs text-slate-700 outline-none"
                      onClick={e => e.stopPropagation()}
                    />
                  ) : (
                    <>
                      <div className="truncate text-xs font-medium text-[#123262]" title={s.title || s.sessionId}>
                        {s.title || s.sessionId?.slice(0, 24) + "…"}
                      </div>
                      <div className="mt-0.5 text-[10px] text-gray-400">
                        {s.lastActive
                          ? formatDistanceToNow(new Date(s.lastActive)) + " ago"
                          : s.createdAt
                          ? formatDistanceToNow(new Date(s.createdAt)) + " ago"
                          : ""}
                      </div>
                    </>
                  )}
                </div>
                {hoveredSessionId === s.sessionId && renamingId !== s.sessionId && (
                  <div
                    className="ml-1 flex shrink-0 items-center gap-0.5"
                    onClick={e => e.stopPropagation()}
                  >
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <button
                          className="flex h-6 w-6 items-center justify-center rounded text-slate-400 hover:bg-slate-200 hover:text-slate-600"
                          onClick={() => { setRenamingId(s.sessionId); setRenameValue(s.title || ''); }}
                        >
                          <Pencil size={11} />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent side="top"><p>Rename</p></TooltipContent>
                    </Tooltip>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <button
                          className="flex h-6 w-6 items-center justify-center rounded text-slate-400 hover:bg-slate-200 hover:text-slate-600"
                          onClick={() => archiveSession(s.sessionId)}
                        >
                          <Archive size={11} />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent side="top"><p>Archive</p></TooltipContent>
                    </Tooltip>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <button
                          className="flex h-6 w-6 items-center justify-center rounded text-slate-400 hover:bg-red-100 hover:text-red-500"
                          onClick={() => deleteSession.mutate(s.sessionId)}
                        >
                          <Trash2 size={11} />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent side="top"><p>Delete</p></TooltipContent>
                    </Tooltip>
                  </div>
                )}
              </div>
            ))}

            {/* Archived sessions toggle */}
            {archivedSessions.length > 0 && (
              <div className="pt-1">
                <button
                  className="flex w-full items-center gap-1 px-2 py-1 text-[10px] font-semibold uppercase tracking-wider text-gray-400 transition-colors hover:text-gray-600"
                  onClick={() => setShowArchived(v => !v)}
                  title={showArchived ? "Hide archived sessions" : "Show archived sessions"}
                >
                  {showArchived ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                  Archived ({archivedSessions.length})
                </button>
                {showArchived && archivedSessions.map((s: any) => (
                  <div
                    key={s.sessionId}
                    className="flex cursor-pointer items-center gap-1 rounded px-2 py-1.5 text-xs text-slate-400 hover:bg-slate-100"
                    onClick={() => handleSelectSession(s.sessionId)}
                  >
                    <span className="flex-1 truncate" title={s.title || s.sessionId}>{s.title || s.sessionId?.slice(0, 24) + "…"}</span>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <button
                          className="flex h-5 w-5 shrink-0 items-center justify-center rounded hover:bg-slate-200 hover:text-slate-600"
                          onClick={e => { e.stopPropagation(); restoreSession(s.sessionId); }}
                        >
                          <ArchiveRestore size={11} />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent side="top"><p>Restore</p></TooltipContent>
                    </Tooltip>
                  </div>
                ))}
              </div>
            )}

            {loadingMoreSessions && (
              <div className="px-2 py-2 text-center text-[10px] text-gray-400">
                Loading more sessions...
              </div>
            )}
            </div>
          </ScrollArea>
        </div>
      </div>

      {/* Main chat lane */}
      <div className="flex min-w-0 flex-col overflow-hidden bg-white">
        <div className="flex h-12 shrink-0 items-center justify-between border-b border-gray-200 px-3">
          <div className="flex items-center gap-2">
            <span className="max-w-[280px] truncate text-sm font-semibold text-[#123262]" title={currentSession?.title || sessionId || "New Session"}>
              {currentSession?.title || (sessionId ? sessionId.slice(0, 16) + '…' : 'New Session')}
            </span>
            {sessionId ? <span className="text-xs text-slate-500">#{sessionId.slice(0, 8)}</span> : null}
          </div>
          <div className="flex items-center gap-1.5">
            <SearchableSelect
              options={[
                { value: "manual", label: "Manual Model", sublabel: "manual" },
                { value: "auto", label: "Auto Route", sublabel: "auto" },
              ]}
              value={modelSelectionMode}
              onValueChange={(value) => setModelSelectionMode(value as "manual" | "auto")}
              placeholder="Routing"
              searchPlaceholder="Search mode…"
              className="w-32"
              triggerTitle="Choose model routing mode"
              triggerAriaLabel="Choose model routing mode"
              tooltip="Choose model routing mode"
            />
            <SearchableSelect
              options={providerList.map(pid => ({
                value: pid,
                label: modelsByProvider.get(pid)?.providerName || pid,
                sublabel: pid,
              }))}
              value={selectedProvider}
              onValueChange={handleProviderChange}
              placeholder="Provider"
              searchPlaceholder="Search providers…"
              className="w-32"
              triggerTitle={selectedProvider ? `Provider: ${modelsByProvider.get(selectedProvider)?.providerName || selectedProvider}` : "Select provider"}
              triggerAriaLabel="Select provider"
              tooltip={selectedProvider ? `Provider: ${modelsByProvider.get(selectedProvider)?.providerName || selectedProvider}` : "Select provider"}
            />
            <SearchableSelect
              options={(modelsByProvider.get(selectedProvider)?.models ?? []).map((m: any) => ({
                value: modelRefKey({ providerID: m.providerID, modelID: m.modelID }),
                label: m.displayName,
                sublabel: m.modelID,
              }))}
              value={selectedModel}
              onValueChange={handleModelChange}
              placeholder="Select model"
              searchPlaceholder="Search models…"
              disabled={!selectedProvider || (modelsByProvider.get(selectedProvider)?.models.length ?? 0) === 0}
              className="w-40"
              triggerTitle={selectedModel ? `Model: ${(modelsByProvider.get(selectedProvider)?.models ?? []).find((m: any) => modelRefKey({ providerID: m.providerID, modelID: m.modelID }) === selectedModel)?.displayName || selectedModel}` : "Select model"}
              triggerAriaLabel="Select chat model"
              tooltip={selectedModel ? `Model: ${(modelsByProvider.get(selectedProvider)?.models ?? []).find((m: any) => modelRefKey({ providerID: m.providerID, modelID: m.modelID }) === selectedModel)?.displayName || selectedModel}` : "Select model"}
            />
          </div>
        </div>

        <div className="min-h-0 flex-1" ref={chatScrollRootRef}>
          <ScrollArea className="h-full bg-[#f8fafc] px-4 py-4">
            <div className="w-full space-y-4">
            {loadingOlderHistory && (
              <div className="text-center text-xs text-slate-400">Loading older messages...</div>
            )}
            {messages.length === 0 && (
              <div className="flex h-64 flex-col items-center justify-center py-16 text-center">
                <div
                  className="mb-4 flex items-center justify-center rounded-2xl"
                  style={{
                    background: "linear-gradient(135deg, #E31837 0%, #F47920 100%)",
                    width: 56,
                    height: 56
                  }}
                >
                  <Bot size={28} color="white" />
                </div>
                <h3 className="mb-1 text-xl font-bold text-[#123262]">PODS AI Agent</h3>
                <p className="max-w-sm text-sm text-gray-500">
                  Ask anything — your friendly PODS AI assistant is ready to help.
                </p>
              </div>
            )}

            {(() => {
              const items = buildRenderItems(messages);
              const hasSystemGroup = items.some((entry) => entry.kind === "group");
              const deferredStreamingAssistant: Array<{ msg: Message; idx: number }> = [];

              const renderAssistant = (m: Message, idx: number) => (
                <AssistantBubble
                  key={m.id}
                  message={m}
                  spinnerVerb={currentSpinnerVerb}
                  hovered={hoveredMsgIdx === idx}
                  copied={copiedId === String(idx)}
                  onMouseEnter={() => setHoveredMsgIdx(idx)}
                  onMouseLeave={() => setHoveredMsgIdx(null)}
                  onCopy={() => copyMessage(m.content, idx)}
                />
              );

              const renderedItems = items.map((item) => {
                if (item.kind === "msg") {
                  const { msg: m, idx } = item;
                  if (m.type === "user") {
                    const isEditing = editingMsgIdx === idx;
                    return (
                      <UserBubble
                        key={m.id}
                        content={m.content}
                        hovered={hoveredMsgIdx === idx}
                        copied={copiedId === String(idx)}
                        isEditing={isEditing}
                        editDraft={editDraft}
                        isStreaming={isStreaming}
                        onMouseEnter={() => setHoveredMsgIdx(idx)}
                        onMouseLeave={() => setHoveredMsgIdx(null)}
                        onCopy={() => copyMessage(m.content, idx)}
                        onResend={() => handleResend(idx, m.content)}
                        onStartEdit={() => {
                          setEditDraft(m.content);
                          setEditingMsgIdx(idx);
                        }}
                        onCancelEdit={() => setEditingMsgIdx(null)}
                        onChangeEditDraft={setEditDraft}
                        onSubmitEdit={() => {
                          handleResend(idx, editDraft);
                          setEditingMsgIdx(null);
                        }}
                      />
                    );
                  }
                  if (m.type === "assistant") {
                    if (m.isStreaming && hasSystemGroup) {
                      deferredStreamingAssistant.push({ msg: m, idx });
                      return null;
                    }
                    return renderAssistant(m, idx);
                  }
                  return null;
                }

                return (
                  <SystemEventGroup
                    key={item.msgs[0].msg.id}
                    messages={item.msgs.map((entry) => entry.msg)}
                    pendingInteractions={pendingInteractions}
                    interactionDrafts={interactionDrafts}
                    interactionSelections={interactionSelections}
                    setInteractionDrafts={setInteractionDrafts}
                    setInteractionSelections={setInteractionSelections}
                    answerPending={answerPending}
                  />
                );
              });

              const deferredItems = deferredStreamingAssistant.map(({ msg, idx }) => renderAssistant(msg, idx));

              return [...renderedItems, ...deferredItems];
            })()}
            <div ref={messagesEndRef} />
            </div>
          </ScrollArea>
        </div>

        <div className="shrink-0 border-t border-gray-200 bg-white p-3">
          <div className="flex w-full items-end gap-2">
            <div className="flex-1 relative">
              {attachments.length > 0 && (
                <div className="mb-2 flex flex-wrap gap-2">
                  {attachments.map((a) => (
                    <span key={a.fileName} className="inline-flex items-center gap-1 rounded border bg-slate-100 px-2 py-1 text-xs text-slate-700">
                      <Paperclip size={12} />
                      {a.fileName}
                      <button
                        type="button"
                        onClick={() => removeAttachment(a.fileName)}
                        className="text-slate-500 hover:text-slate-700"
                        title={`Remove ${a.fileName}`}
                        aria-label={`Remove attachment ${a.fileName}`}
                      >
                        <X size={12} />
                      </button>
                    </span>
                  ))}
                </div>
              )}
              <textarea
                placeholder="Ask anything…"
                className="w-full resize-none rounded-xl border border-gray-200 bg-white px-3 py-2.5 pr-12 text-sm text-gray-800 placeholder:text-gray-400 focus:border-[#005CB9] focus:outline-none focus:ring-2 focus:ring-[#005CB9]/20"
                style={{ minHeight: 40, maxHeight: 120 }}
                rows={1}
                value={input}
                title="Message input. Press Enter to send, Shift+Enter for a new line."
                aria-label="Chat message input"
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleSend();
                  }
                }}
              />
              {isStreaming ? (
                <button
                  onClick={handleStop}
                  className="absolute bottom-2 right-2 flex h-7 w-7 items-center justify-center rounded-md border border-red-500 text-white shadow-sm transition hover:brightness-110"
                  style={{ background: "#dc2626" }}
                  title="Stop generating"
                  aria-label="Stop generating"
                >
                  <Square size={12} fill="white" />
                </button>
              ) : (
                <button
                  onClick={() => handleSend()}
                  disabled={!input.trim() && attachments.length === 0}
                  className="absolute bottom-2 right-2 flex h-7 w-7 items-center justify-center rounded-md border border-[#00529f] text-white shadow-sm transition hover:brightness-110 disabled:opacity-40"
                  style={{ background: "#005CB9" }}
                  title="Send message"
                  aria-label="Send message"
                >
                  <Send size={14} />
                </button>
              )}
            </div>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={onSelectAttachments}
              accept=".txt,.md,.csv,.json,.xml,.yaml,.yml,.log,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.webp"
            />
            <Button
              type="button"
              variant="outline"
              className="h-10"
              onClick={() => fileInputRef.current?.click()}
              disabled={isStreaming || attachments.length >= MAX_ATTACHMENTS}
              title={attachments.length >= MAX_ATTACHMENTS ? `Maximum ${MAX_ATTACHMENTS} attachments reached` : "Attach files to your message"}
            >
              <Paperclip size={14} className="mr-1" />
              Attach
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
