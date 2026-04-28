import * as React from "react";
import { useState, useEffect, useRef } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import mermaid from "mermaid";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Send,
  Trash2,
  Plus,
  Bot,
  Loader2,
  MessageSquare,
  Pencil,
  Archive,
  ArchiveRestore,
  Copy,
  Check,
  RotateCcw,
  ChevronDown,
  ChevronRight,
  Brain,
  Paperclip,
  X,
  Maximize2,
  ZoomIn,
  ZoomOut,
  Code2,
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
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";

let mermaidInitialized = false;

function ensureMermaidInitialized() {
  if (mermaidInitialized) return;
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: "loose",
    theme: "default",
    suppressErrorRendering: true,
  });
  // Prevent Mermaid from injecting global error UI into the page.
  (mermaid as any).parseError = () => {};
  mermaidInitialized = true;
}

type MsgType = 'user' | 'assistant' | 'system';

interface Message {
  id: string;
  type: MsgType;
  content: string;
  reasoning?: string;
  isStreaming?: boolean;
  dbId?: string;
  createdAt?: number;
  turnId?: string;
  eventType?: string;
  requestId?: string;
  hitlStatus?: string;
  hitlResponse?: string;
  eventPayload?: any;
}

interface ChatAttachment {
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  contentBase64: string;
}

interface HitlQuestionOption {
  id: string;
  label: string;
}

interface HitlQuestionMetadata {
  responseMode?: "single_select" | "multi_select" | "text";
  options?: HitlQuestionOption[];
  allowCustomText?: boolean;
  minSelections?: number;
  maxSelections?: number;
}

function normalizeQuestionMetadata(raw: any): HitlQuestionMetadata | undefined {
  if (!raw) return undefined;
  const optionsRaw = Array.isArray(raw.options) ? raw.options : [];
  const options: HitlQuestionOption[] = optionsRaw
    .map((opt: any) => {
      if (typeof opt === "string") return { id: opt, label: opt };
      if (opt && typeof opt === "object") {
        const label = String(opt.label ?? opt.id ?? "").trim();
        const id = String(opt.id ?? label).trim();
        if (!id) return null;
        return { id, label: label || id };
      }
      return null;
    })
    .filter(Boolean) as HitlQuestionOption[];
  if (options.length > 0) {
    return {
      responseMode: raw.responseMode || "single_select",
      options,
      allowCustomText: raw.allowCustomText !== false,
      minSelections: raw.minSelections,
      maxSelections: raw.maxSelections,
    };
  }
  if (Array.isArray(raw.questions) && raw.questions.length > 0) {
    const first = raw.questions[0];
    const qOptions = Array.isArray(first?.options) ? first.options : [];
    const normalized = qOptions
      .map((opt: any) => {
        const label = String(opt?.label ?? opt?.id ?? "").trim();
        const id = String(opt?.id ?? label).trim();
        if (!id) return null;
        return { id, label: label || id };
      })
      .filter(Boolean) as HitlQuestionOption[];
    if (normalized.length > 0) {
      return {
        responseMode: "single_select",
        options: normalized,
        allowCustomText: true,
        minSelections: 1,
        maxSelections: 1,
      };
    }
  }
  if (raw.responseMode || raw.allowCustomText !== undefined) {
    return {
      responseMode: raw.responseMode || "text",
      options: [],
      allowCustomText: raw.allowCustomText !== false,
      minSelections: raw.minSelections,
      maxSelections: raw.maxSelections,
    };
  }
  return undefined;
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

function formatPayload(payload: any): string {
  if (payload === null || payload === undefined) return "";
  if (typeof payload === "string") return payload;
  try {
    return JSON.stringify(payload, null, 2);
  } catch {
    return String(payload);
  }
}

function formatHitlResponse(response?: string) {
  if (!response) return "";
  if (response.startsWith("options=")) {
    const parts = response.split(";").map((p) => p.trim());
    const optionsPart = parts.find((p) => p.startsWith("options="));
    const messagePart = parts.find((p) => p.startsWith("message="));
    const selected = optionsPart ? optionsPart.replace("options=", "").trim() : "";
    const text = messagePart ? messagePart.replace("message=", "").trim() : "";
    if (selected && text) return `Selected: ${selected} | Message: ${text}`;
    if (selected) return `Selected: ${selected}`;
    if (text) return text;
  }
  return response;
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

type MarkdownCodeProps = React.ComponentPropsWithoutRef<"code"> & {
  inline?: boolean;
};

function MermaidBlock({ chart }: { chart: string }) {
  const [svg, setSvg] = React.useState("");
  const [error, setError] = React.useState("");
  const [zoom, setZoom] = React.useState(1);
  const [expanded, setExpanded] = React.useState(false);
  const [showRawCode, setShowRawCode] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const [panX, setPanX] = React.useState(0);
  const [panY, setPanY] = React.useState(0);
  const [isDragging, setIsDragging] = React.useState(false);
  const dragStartRef = React.useRef<{ mouseX: number; mouseY: number; panX: number; panY: number } | null>(null);
  const renderId = React.useMemo(() => `mermaid-${Math.random().toString(36).slice(2)}`, []);

  React.useEffect(() => {
    let mounted = true;
    ensureMermaidInitialized();

    const render = async () => {
      try {
        const { svg } = await mermaid.render(renderId, chart);
        if (!mounted) return;
        setError("");
        setSvg(svg);
      } catch (err) {
        if (!mounted) return;
        const message = err instanceof Error ? err.message : "Invalid Mermaid chart";
        setSvg("");
        setError(message);
      }
    };

    void render();
    return () => {
      mounted = false;
    };
  }, [chart, renderId]);

  if (error) {
    return (
      <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
        Mermaid render error: {error}
      </div>
    );
  }

  if (!svg) {
    return (
      <div className="inline-flex items-center gap-2 rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
        <Loader2 size={12} className="animate-spin" />
        Rendering Mermaid chart...
      </div>
    );
  }

  const zoomIn = () => setZoom((z) => Math.min(3, +(z + 0.2).toFixed(2)));
  const zoomOut = () => setZoom((z) => Math.max(0.4, +(z - 0.2).toFixed(2)));
  const resetView = () => {
    setZoom(1);
    setPanX(0);
    setPanY(0);
  };
  const copyRawCode = async () => {
    try {
      await navigator.clipboard.writeText(chart);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      setCopied(false);
    }
  };
  const handleCanvasWheel = (event: React.WheelEvent<HTMLDivElement>) => {
    event.preventDefault();
    const delta = event.deltaY < 0 ? 0.1 : -0.1;
    setZoom((z) => Math.max(0.4, Math.min(3, +(z + delta).toFixed(2))));
  };
  const handleCanvasMouseDown = (event: React.MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    setIsDragging(true);
    dragStartRef.current = {
      mouseX: event.clientX,
      mouseY: event.clientY,
      panX,
      panY,
    };
  };
  const handleCanvasMouseMove = (event: React.MouseEvent<HTMLDivElement>) => {
    if (!isDragging || !dragStartRef.current) return;
    const dx = event.clientX - dragStartRef.current.mouseX;
    const dy = event.clientY - dragStartRef.current.mouseY;
    setPanX(dragStartRef.current.panX + dx);
    setPanY(dragStartRef.current.panY + dy);
  };
  const stopDragging = () => {
    setIsDragging(false);
    dragStartRef.current = null;
  };

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-1">
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50"
          onClick={() => setExpanded(true)}
        >
          <Maximize2 size={12} />
          Expand
        </button>
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50"
          onClick={() => setShowRawCode((v) => !v)}
        >
          <Code2 size={12} />
          {showRawCode ? "Hide Code" : "View Code"}
        </button>
      </div>
      {showRawCode ? (
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
          {chart}
        </pre>
      ) : null}
      <div
        className="overflow-auto rounded-md border border-slate-200 bg-white p-2"
        dangerouslySetInnerHTML={{ __html: svg }}
      />

      <Dialog open={expanded} onOpenChange={setExpanded}>
        <DialogContent className="h-[92vh] w-[96vw] max-w-[96vw] bg-white p-4 sm:max-w-[96vw]">
          <DialogHeader>
            <DialogTitle>Mermaid Diagram</DialogTitle>
          </DialogHeader>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={zoomOut}
            >
              <ZoomOut size={14} />
              Zoom Out
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={zoomIn}
            >
              <ZoomIn size={14} />
              Zoom In
            </button>
            <button
              type="button"
              className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={resetView}
            >
              Reset
            </button>
            <button
              type="button"
              className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={() => setShowRawCode((v) => !v)}
            >
              {showRawCode ? "Hide Code" : "Show Code"}
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={copyRawCode}
            >
              <Copy size={12} />
              {copied ? "Copied" : "Copy Code"}
            </button>
            <span className="text-xs text-slate-500">Zoom: {Math.round(zoom * 100)}%</span>
          </div>

          {showRawCode ? (
            <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
              {chart}
            </pre>
          ) : null}

          <div
            className="h-[78vh] overflow-hidden rounded-md border border-slate-200 bg-white p-3"
            onWheel={handleCanvasWheel}
            onMouseDown={handleCanvasMouseDown}
            onMouseMove={handleCanvasMouseMove}
            onMouseUp={stopDragging}
            onMouseLeave={stopDragging}
            onDoubleClick={resetView}
            style={{ cursor: isDragging ? "grabbing" : "grab" }}
          >
            <div
              style={{ transform: `translate(${panX}px, ${panY}px) scale(${zoom})`, transformOrigin: "top left" }}
              dangerouslySetInnerHTML={{ __html: svg }}
            />
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default function ChatPage() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [editingMsgIdx, setEditingMsgIdx] = useState<number | null>(null);
  const [editDraft, setEditDraft] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [spinnerVerbIndex, setSpinnerVerbIndex] = useState(0);
  const isStreamingRef = useRef(false);
  const assistantAddedRef = useRef(false);
  const skipNextHydrationRef = useRef(false);
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
  const markdownComponents = React.useMemo(
    () => ({
      code({ inline, className, children, ...props }: MarkdownCodeProps) {
        const match = /language-([\w-]+)/.exec(className || "");
        const language = match?.[1]?.toLowerCase();
        const code = String(children ?? "").replace(/\n$/, "");
        if (!inline && language === "mermaid") {
          return <MermaidBlock chart={code} />;
        }
        return (
          <code className={className} {...props}>
            {children}
          </code>
        );
      },
    }),
    []
  );

  useEffect(() => {
    if (!isStreaming) return;
    const intervalId = window.setInterval(() => {
      setSpinnerVerbIndex((prev) => (prev + 1) % SPINNER_VERBS.length);
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
      const token = getAuthToken();
      const response = await fetch('http://localhost:8080/api/v1/chat', {
        method: 'POST',
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
            break;
          case 'task.done':
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
            <Tooltip>
              <TooltipTrigger asChild>
                <select
                  value={modelSelectionMode}
                  onChange={(e) => setModelSelectionMode(e.target.value as any)}
                  className="h-7 rounded-md border border-gray-300 bg-white px-2 text-xs text-gray-700"
                  title="Choose model routing mode"
                >
                  <option value="manual">Manual Model</option>
                  <option value="auto">Auto Route</option>
                </select>
              </TooltipTrigger>
              <TooltipContent side="bottom"><p>Choose model routing mode</p></TooltipContent>
            </Tooltip>
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
              // Group consecutive system messages; user/assistant messages stay as individual items
              type RenderItem =
                | { kind: 'msg'; msg: Message; idx: number }
                | { kind: 'group'; msgs: Array<{ msg: Message; idx: number }> };

              // Keep live render order consistent with history hydration:
              // for each turn, show user -> system events -> assistant.
              const turnOrdered: Array<{ msg: Message; idx: number }> = [];
              let cursor = 0;
              while (cursor < messages.length) {
                const current = messages[cursor];
                if (current.type !== "user") {
                  turnOrdered.push({ msg: current, idx: cursor });
                  cursor++;
                  continue;
                }
                turnOrdered.push({ msg: current, idx: cursor });
                cursor++;
                const segment: Array<{ msg: Message; idx: number }> = [];
                while (cursor < messages.length && messages[cursor].type !== "user") {
                  segment.push({ msg: messages[cursor], idx: cursor });
                  cursor++;
                }
                const systems = segment.filter((entry) => entry.msg.type === "system");
                const nonSystems = segment.filter((entry) => entry.msg.type !== "system");
                turnOrdered.push(...systems, ...nonSystems);
              }

              const items: RenderItem[] = [];
              let i = 0;
              while (i < turnOrdered.length) {
                if (turnOrdered[i].msg.type !== 'system') {
                  items.push({ kind: 'msg', msg: turnOrdered[i].msg, idx: turnOrdered[i].idx });
                  i++;
                } else {
                  const group: Array<{ msg: Message; idx: number }> = [];
                  while (i < turnOrdered.length && turnOrdered[i].msg.type === 'system') {
                    group.push({ msg: turnOrdered[i].msg, idx: turnOrdered[i].idx });
                    i++;
                  }
                  items.push({ kind: 'group', msgs: group });
                }
              }

              // Render a single system event as a nested collapsible row
              const renderSystemEvent = (m: Message) => {
                const pending = m.requestId ? pendingInteractions.find((p) => p.requestId === m.requestId) : undefined;
                const draftValue = m.requestId ? (interactionDrafts[m.requestId] ?? "") : "";
                const selected = m.requestId ? (interactionSelections[m.requestId] ?? []) : [];
                const metadata = pending?.metadata || normalizeQuestionMetadata(m.eventPayload?.metadata || m.eventPayload);
                const mode = metadata?.responseMode || "text";
                const isToolEvent = m.eventType === "tool.call" || m.eventType === "tool.done" || m.eventType === "tool.match";
                if (isToolEvent) {
                  const payloadText = m.eventType === "tool.call"
                    ? formatPayload(m.eventPayload?.input)
                    : m.eventType === "tool.done"
                    ? formatPayload(m.eventPayload?.output)
                    : formatPayload({
                        reason: m.eventPayload?.reason,
                        needsClarification: m.eventPayload?.needsClarification,
                        selectedTool: m.eventPayload?.selectedTool,
                        score: m.eventPayload?.score,
                        candidates: m.eventPayload?.candidates,
                      });
                  return (
                    <details key={m.id} className="group/item rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs">
                      <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
                        <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
                        <span className="w-20 shrink-0 font-mono text-[10px] text-slate-400">{m.eventType}</span>
                        <span className="min-w-0 flex-1 truncate text-slate-600">{m.content}</span>
                      </summary>
                      {payloadText ? (
                        <pre className="mt-1.5 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded border border-slate-100 bg-slate-50 p-2 text-[10px] text-slate-700">
                          {payloadText}
                        </pre>
                      ) : null}
                    </details>
                  );
                }

                if (m.eventType === "question") {
                  const isResolved = m.hitlStatus && m.hitlStatus !== "pending";
                  return (
                    <details key={m.id} open={!isResolved} className="group/item rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs">
                      <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
                        <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
                        <span className="font-medium text-slate-600">Question</span>
                        <span className="min-w-0 flex-1 truncate text-slate-500">{m.content}</span>
                      </summary>
                      <div className="mt-2 space-y-2">
                        <div className="text-slate-700">{m.content}</div>
                        {isResolved ? (
                          <div className="flex items-start gap-2">
                            <span className="mt-0.5 shrink-0 text-[10px] font-medium uppercase text-slate-400">Answer</span>
                            <span className="text-slate-600">{formatHitlResponse(m.hitlResponse)}</span>
                          </div>
                        ) : pending?.type === "question" ? (
                          <div className="flex items-center gap-2">
                            {(mode === "single_select" || mode === "multi_select") && metadata?.options?.length ? (
                              <div className="w-full space-y-2">
                                <div className="flex flex-wrap gap-2">
                                  {metadata.options.map((opt) => {
                                    const active = selected.includes(opt.id);
                                    return (
                                      <button key={opt.id} type="button"
                                        onClick={() => {
                                          if (!m.requestId) return;
                                          setInteractionSelections((prev) => {
                                            const current = prev[m.requestId as string] || [];
                                            if (mode === "single_select") return { ...prev, [m.requestId as string]: [opt.id] };
                                            const next = current.includes(opt.id) ? current.filter((id) => id !== opt.id) : [...current, opt.id];
                                            return { ...prev, [m.requestId as string]: next };
                                          });
                                        }}
                                        className={`rounded border px-2 py-1 text-xs ${active ? "border-[#005CB9] bg-[#EFF6FF] text-[#123262]" : "border-gray-200 bg-white text-slate-700"}`}
                                      >{opt.label}</button>
                                    );
                                  })}
                                </div>
                                {metadata.allowCustomText && (
                                  <input value={draftValue}
                                    onChange={(e) => { if (!m.requestId) return; setInteractionDrafts((prev) => ({ ...prev, [m.requestId as string]: e.target.value })); }}
                                    placeholder="Optional clarification..."
                                    className="h-8 w-full rounded border border-gray-200 bg-white px-2 text-xs text-slate-700"
                                  />
                                )}
                              </div>
                            ) : (
                              <input value={draftValue}
                                onChange={(e) => { if (!m.requestId) return; setInteractionDrafts((prev) => ({ ...prev, [m.requestId as string]: e.target.value })); }}
                                placeholder="Type your answer..."
                                className="h-8 flex-1 rounded border border-gray-200 bg-white px-2 text-xs text-slate-700"
                              />
                            )}
                            <Button size="sm" variant="outline" onClick={() => answerPending(pending.requestId, "reply", selected)}>Continue</Button>
                          </div>
                        ) : null}
                      </div>
                    </details>
                  );
                }

                if (m.eventType === "approval_required") {
                  const isResolved = m.hitlStatus && m.hitlStatus !== "pending";
                  return (
                    <details key={m.id} open={!isResolved} className="group/item rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs">
                      <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
                        <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
                        <span className="font-medium text-slate-600">Approval request</span>
                        {isResolved && (
                          <span className={`ml-1 rounded px-1.5 py-0.5 text-[10px] font-medium ${m.hitlStatus === "approved" ? "bg-green-100 text-green-700" : "bg-red-100 text-red-700"}`}>
                            {m.hitlStatus}
                          </span>
                        )}
                        <span className="min-w-0 flex-1 truncate text-slate-500">{m.content}</span>
                      </summary>
                      <div className="mt-2 space-y-2">
                        <div className="text-slate-700">{m.content}</div>
                        {isResolved ? (
                          m.hitlResponse ? <div className="text-slate-500">{m.hitlResponse}</div> : null
                        ) : pending?.type && pending.type !== "question" ? (
                          <div className="space-y-2">
                            <input value={draftValue}
                              onChange={(e) => { if (!m.requestId) return; setInteractionDrafts((prev) => ({ ...prev, [m.requestId as string]: e.target.value })); }}
                              placeholder="Optional reason..."
                              className="h-8 w-full rounded border border-gray-200 bg-white px-2 text-xs text-slate-700"
                            />
                            <div className="flex gap-2">
                              <Button size="sm" variant="outline" onClick={() => answerPending(pending.requestId, "approve")}>Approve</Button>
                              <Button size="sm" variant="outline" onClick={() => answerPending(pending.requestId, "reject")}>Reject</Button>
                            </div>
                          </div>
                        ) : null}
                      </div>
                    </details>
                  );
                }

                // Fallthrough (approval_status, etc.)
                return (
                  <details key={m.id} className="group/item rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs">
                    <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
                      <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
                      <span className="font-mono text-[10px] text-slate-400">{m.eventType || "system"}</span>
                      <span className="min-w-0 flex-1 truncate text-slate-600">{m.content}</span>
                    </summary>
                  </details>
                );
              };

              const hasSystemGroup = items.some((entry) => entry.kind === "group");
              const deferredStreamingAssistant: Array<{ msg: Message; idx: number }> = [];

              const renderedItems = items.map((item) => {
                if (item.kind === 'msg') {
                  const { msg: m, idx } = item;
                  if (m.type === 'user') {
                    const isEditing = editingMsgIdx === idx;
                    return (
                      <div key={m.id} className="flex items-end justify-end gap-2"
                        onMouseEnter={() => setHoveredMsgIdx(idx)}
                        onMouseLeave={() => setHoveredMsgIdx(null)}
                      >
                        {/* Action buttons appear to the left of the bubble on hover */}
                        {hoveredMsgIdx === idx && !isEditing && (
                          <div className="flex shrink-0 items-center gap-1">
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <button onClick={() => { setEditDraft(m.content); setEditingMsgIdx(idx); }}
                                  className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50">
                                  <Pencil size={11} />
                                </button>
                              </TooltipTrigger>
                              <TooltipContent side="top"><p>Edit &amp; resend</p></TooltipContent>
                            </Tooltip>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <button onClick={() => copyMessage(m.content, idx)}
                                  className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50">
                                  {copiedId === String(idx) ? <Check size={11} className="text-green-500" /> : <Copy size={11} />}
                                </button>
                              </TooltipTrigger>
                              <TooltipContent side="top"><p>{copiedId === String(idx) ? "Copied!" : "Copy"}</p></TooltipContent>
                            </Tooltip>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <button onClick={() => handleResend(idx, m.content)} disabled={isStreaming}
                                  className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50 disabled:opacity-40">
                                  <RotateCcw size={11} />
                                </button>
                              </TooltipTrigger>
                              <TooltipContent side="top"><p>Resend</p></TooltipContent>
                            </Tooltip>
                          </div>
                        )}
                        {isEditing ? (
                          <div className="flex w-[82%] flex-col gap-2">
                            <textarea
                              value={editDraft}
                              onChange={(e) => setEditDraft(e.target.value)}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' && !e.shiftKey) {
                                  e.preventDefault();
                                  handleResend(idx, editDraft);
                                  setEditingMsgIdx(null);
                                }
                                if (e.key === 'Escape') setEditingMsgIdx(null);
                              }}
                              rows={Math.max(2, editDraft.split('\n').length)}
                              autoFocus
                              className="w-full resize-none rounded-xl border border-[#005CB9] px-4 py-2.5 text-sm text-gray-800 outline-none focus:ring-2 focus:ring-[#005CB9]/30"
                            />
                            <div className="flex justify-end gap-2">
                              <Button size="sm" variant="outline" onClick={() => setEditingMsgIdx(null)}>Cancel</Button>
                              <Button size="sm" className="text-white" style={{ background: "#005CB9" }}
                                onClick={() => { handleResend(idx, editDraft); setEditingMsgIdx(null); }}
                                disabled={isStreaming || !editDraft.trim()}
                              >Send</Button>
                            </div>
                          </div>
                        ) : (
                          <div className="max-w-[82%] rounded-2xl rounded-tr-sm px-4 py-2.5 text-sm text-white" style={{ background: "#005CB9" }}>
                            {m.content}
                          </div>
                        )}
                      </div>
                    );
                  }
                  if (m.type === 'assistant') {
                    if (m.isStreaming && hasSystemGroup) {
                      deferredStreamingAssistant.push({ msg: m, idx });
                      return null;
                    }
                    return (
                      <div key={m.id} className="group relative flex justify-start"
                        onMouseEnter={() => setHoveredMsgIdx(idx)}
                        onMouseLeave={() => setHoveredMsgIdx(null)}
                      >
                        <div className="max-w-[88%] rounded-2xl rounded-tl-sm border border-gray-200 bg-white px-4 py-3 text-sm text-gray-800 shadow-sm">
                          {m.reasoning && (
                            <details className="group/think mb-3 rounded-lg border border-indigo-100 bg-indigo-50/60 text-xs">
                              <summary className="flex cursor-pointer list-none items-center gap-1.5 px-3 py-1.5 text-indigo-500 [&::-webkit-details-marker]:hidden">
                                <Brain size={11} className="shrink-0" />
                                <span className="flex-1 font-medium">
                                  {m.isStreaming && !m.content ? 'Thinking…' : 'Reasoning'}
                                </span>
                                <ChevronRight size={10} className="shrink-0 text-indigo-400 transition-transform group-open/think:rotate-90" />
                              </summary>
                              <div className="border-t border-indigo-100 px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-500 whitespace-pre-wrap">
                                {m.reasoning}
                              </div>
                            </details>
                          )}
                          <div className="prose prose-sm max-w-none prose-slate prose-p:my-1 prose-li:my-0.5 prose-headings:my-2 prose-pre:bg-slate-100 prose-pre:text-xs prose-code:text-xs prose-code:bg-slate-100 prose-code:px-1 prose-code:rounded">
                            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                              {m.content}
                            </ReactMarkdown>
                          </div>
                          {m.isStreaming && (
                            <div className="mt-2 inline-flex items-center gap-1.5 text-xs text-slate-500">
                              <Loader2 size={12} className="animate-spin" />
                              <span>{currentSpinnerVerb}...</span>
                              <span className="inline-block h-4 w-1.5 animate-pulse rounded-sm bg-slate-400" />
                            </div>
                          )}
                        </div>
                        {hoveredMsgIdx === idx && !m.isStreaming && (
                          <div className="absolute -bottom-6 left-0 flex items-center gap-1">
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <button onClick={() => copyMessage(m.content, idx)} className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50">
                                  {copiedId === String(idx) ? <Check size={11} className="text-green-500" /> : <Copy size={11} />}
                                </button>
                              </TooltipTrigger>
                              <TooltipContent side="bottom"><p>{copiedId === String(idx) ? "Copied!" : "Copy"}</p></TooltipContent>
                            </Tooltip>
                          </div>
                        )}
                      </div>
                    );
                  }
                  return null;
                }

                // Render a group of consecutive system events as a 2-level collapsible
                const { msgs } = item;
                const groupKey = msgs[0].msg.id;

                // Build group summary label
                const toolNames = msgs
                  .filter(({ msg }) => msg.eventType === 'tool.call')
                  .map(({ msg }) => msg.eventPayload?.toolName)
                  .filter(Boolean) as string[];
                const questionCount = msgs.filter(({ msg }) => msg.eventType === 'question').length;
                const approvalCount = msgs.filter(({ msg }) => msg.eventType === 'approval_required').length;
                const hasPending = msgs.some(({ msg }) =>
                  msg.requestId && pendingInteractions.some((p) => p.requestId === msg.requestId)
                );

                const parts: string[] = [];
                if (toolNames.length === 1) parts.push(`Called ${toolNames[0]}`);
                else if (toolNames.length === 2) parts.push(`Called ${toolNames[0]}, ${toolNames[1]}`);
                else if (toolNames.length > 2) parts.push(`Called ${toolNames[0]} +${toolNames.length - 1} more`);
                if (questionCount > 0) parts.push(`${questionCount} question${questionCount > 1 ? 's' : ''}`);
                if (approvalCount > 0) parts.push(`${approvalCount} approval${approvalCount > 1 ? 's' : ''}`);
                const groupSummary = parts.join(' · ') || `${msgs.length} event${msgs.length > 1 ? 's' : ''}`;

                return (
                  <details key={groupKey} open={hasPending} className="group/outer mx-2 rounded-xl border border-slate-200 bg-slate-50 text-xs">
                    <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 [&::-webkit-details-marker]:hidden">
                      <ChevronRight size={10} className="shrink-0 text-slate-400 transition-transform group-open/outer:rotate-90" />
                      <span className="flex-1 text-slate-600">{groupSummary}</span>
                      <span className="text-[10px] text-slate-400">{msgs.length} event{msgs.length > 1 ? 's' : ''}</span>
                    </summary>
                    <div className="space-y-1 border-t border-slate-200 px-2 pb-2 pt-1.5">
                      {msgs.map(({ msg }) => renderSystemEvent(msg))}
                    </div>
                  </details>
                );
              });

              const deferredItems = deferredStreamingAssistant.map(({ msg: m, idx }) => (
                <div key={m.id} className="group relative flex justify-start"
                  onMouseEnter={() => setHoveredMsgIdx(idx)}
                  onMouseLeave={() => setHoveredMsgIdx(null)}
                >
                  <div className="max-w-[88%] rounded-2xl rounded-tl-sm border border-gray-200 bg-white px-4 py-3 text-sm text-gray-800 shadow-sm">
                    {m.reasoning && (
                      <details className="group/think mb-3 rounded-lg border border-indigo-100 bg-indigo-50/60 text-xs">
                        <summary className="flex cursor-pointer list-none items-center gap-1.5 px-3 py-1.5 text-indigo-500 [&::-webkit-details-marker]:hidden">
                          <Brain size={11} className="shrink-0" />
                          <span className="flex-1 font-medium">
                            {m.isStreaming && !m.content ? 'Thinking…' : 'Reasoning'}
                          </span>
                          <ChevronRight size={10} className="shrink-0 text-indigo-400 transition-transform group-open/think:rotate-90" />
                        </summary>
                        <div className="border-t border-indigo-100 px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-500 whitespace-pre-wrap">
                          {m.reasoning}
                        </div>
                      </details>
                    )}
                    <div className="prose prose-sm max-w-none prose-slate prose-p:my-1 prose-li:my-0.5 prose-headings:my-2 prose-pre:bg-slate-100 prose-pre:text-xs prose-code:text-xs prose-code:bg-slate-100 prose-code:px-1 prose-code:rounded">
                      <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                        {m.content}
                      </ReactMarkdown>
                    </div>
                    {m.isStreaming && (
                      <div className="mt-2 inline-flex items-center gap-1.5 text-xs text-slate-500">
                        <Loader2 size={12} className="animate-spin" />
                        <span>{currentSpinnerVerb}...</span>
                        <span className="inline-block h-4 w-1.5 animate-pulse rounded-sm bg-slate-400" />
                      </div>
                    )}
                  </div>
                </div>
              ));

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
              <button
                onClick={() => handleSend()}
                disabled={isStreaming || (!input.trim() && attachments.length === 0)}
                className="absolute bottom-2 right-2 flex h-7 w-7 items-center justify-center rounded-md border border-[#00529f] text-white shadow-sm transition hover:brightness-110 disabled:opacity-40"
                style={{ background: "#005CB9" }}
                title={isStreaming ? "Generating response..." : "Send message"}
                aria-label={isStreaming ? "Generating response" : "Send message"}
              >
                {isStreaming ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
              </button>
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
