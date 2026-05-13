import * as React from "react";
import { useState, useEffect, useRef, useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Send, Plus, Bot, MessageSquare, Pencil, Archive, ArchiveRestore,
  Trash2, ChevronLeft, ChevronRight, Loader2, Square, Check, Copy,
} from "lucide-react";
import { formatDistanceToNow } from "date-fns";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { chatApi, getAuthToken, vrApi } from "@/services/api";
import type { VendorRationalizationConfig } from "@/types/vendorRationalization";
import { SPINNER_VERBS } from "@/lib/spinnerVerbs";
import AssistantBubble from "@/components/chat/AssistantBubble";
import UserBubble from "@/components/chat/UserBubble";
import SystemEventGroup from "@/components/chat/SystemEventGroup";
import { buildRenderItems } from "@/components/chat/chatTimeline";
import { normalizeQuestionMetadata, formatPayload } from "@/components/chat/types";
import type { ChatMessage } from "@/components/chat/types";

const BASE_URL = "http://localhost:8080/api/v1";
const SESSION_PAGE = 30;
const HISTORY_PAGE = 50;

function genId() { return Math.random().toString(36).slice(2); }

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);
  const [spinnerIdx, setSpinnerIdx] = useState(0);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [sidebarSearch, setSidebarSearch] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [hoveredSession, setHoveredSession] = useState<string | null>(null);
  const [hoveredMsgIdx, setHoveredMsgIdx] = useState<number | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [historyMessages, setHistoryMessages] = useState<any[]>([]);
  const [historyOffset, setHistoryOffset] = useState(0);
  const [historyTotal, setHistoryTotal] = useState(0);
  // Chat model is the server-side selection picked in Settings; this UI
  // no longer offers a per-tab dropdown. If the admin hasn't picked one,
  // the input is disabled with a clear prompt to go set it.
  const { data: vrConfig } = useQuery<VendorRationalizationConfig>({
    queryKey: ["vr-config"],
    queryFn: () => vrApi.getConfig(),
    staleTime: 60_000,
  });
  const selectedModel = vrConfig?.chatModelRef ?? "";

  const isStreamingRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const queryClient = useQueryClient();

  // Spinner cycling
  useEffect(() => {
    if (!isStreaming) return;
    const id = setInterval(() => setSpinnerIdx(i => (i + 1) % SPINNER_VERBS.length), 1250);
    return () => clearInterval(id);
  }, [isStreaming]);

  const { data: sessionsData, refetch: refetchSessions } = useQuery({
    queryKey: ["vr-sessions"],
    queryFn: () => chatApi.sessions(SESSION_PAGE, 0),
  });

  const { data: eventsData } = useQuery({
    queryKey: ["vr-events", sessionId],
    queryFn: () => chatApi.events(sessionId!),
    enabled: !!sessionId,
  });

  // Load history when session changes
  const loadHistory = useCallback(async (sid: string) => {
    try {
      const meta = await chatApi.history(sid, 1, 0);
      const total = Number(meta?.total || 0);
      const initOffset = Math.max(0, total - HISTORY_PAGE);
      const page = await chatApi.history(sid, HISTORY_PAGE, initOffset);
      setHistoryMessages(page?.messages || []);
      setHistoryOffset(initOffset);
      setHistoryTotal(Number(page?.total || total));
    } catch { setHistoryMessages([]); }
  }, []);

  useEffect(() => {
    if (!sessionId) { setHistoryMessages([]); setHistoryOffset(0); setHistoryTotal(0); return; }
    loadHistory(sessionId);
  }, [sessionId, loadHistory]);

  // Hydrate messages from history
  useEffect(() => {
    if (!historyMessages.length || isStreamingRef.current) return;
    const reasoningMap = new Map<string, string>();
    (eventsData?.events || []).forEach((e: any) => {
      if (e.eventType === "reasoning" && e.turnId) {
        try { const p = JSON.parse(e.payload || "{}"); if (p.content) reasoningMap.set(e.turnId, p.content); } catch {}
      }
    });
    const chatMsgs: ChatMessage[] = historyMessages.map((m: any) => ({
      id: genId(), type: m.role === "user" ? "user" : "assistant",
      content: m.content || "", reasoning: m.role === "assistant" && m.turnId ? reasoningMap.get(m.turnId) : undefined,
      dbId: m.id, turnId: m.turnId, createdAt: m.createdAt,
    }));
    const sysMsgs: ChatMessage[] = (eventsData?.events || []).map((e: any) => {
      let payload: any = {};
      try { payload = JSON.parse(e.payload || "{}"); } catch {}
      let content = "";
      if (e.eventType === "tool.call") { content = `Calling tool: ${payload.toolName || ""}`; payload = { ...payload, toolName: payload.toolName, input: payload.input ?? payload.arguments }; }
      else if (e.eventType === "tool.done" || e.eventType === "tool.result") { content = `Tool completed: ${payload.toolName || ""}`; payload = { ...payload, output: payload.output ?? payload.result }; }
      else if (e.eventType === "question") { content = payload.question || payload.prompt || ""; }
      else return null as any;
      return { id: genId(), type: "system" as const, turnId: e.turnId, eventType: e.eventType, content, createdAt: e.createdAt, requestId: payload.requestId, eventPayload: payload };
    }).filter(Boolean);
    const merged = [...chatMsgs, ...sysMsgs].sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
    setMessages(merged);
  }, [historyMessages, eventsData]);

  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages]);

  const allSessions: any[] = sessionsData?.sessions || sessionsData || [];
  const activeSessions = allSessions.filter(s => !s.archivedAt && (!sidebarSearch || s.title?.toLowerCase().includes(sidebarSearch.toLowerCase())));
  const archivedSessions = allSessions.filter(s => s.archivedAt && (!sidebarSearch || s.title?.toLowerCase().includes(sidebarSearch.toLowerCase())));

  const handleNewChat = () => {
    setSessionId(null); setMessages([]); setHistoryMessages([]);
    setHistoryOffset(0); setHistoryTotal(0); setInput("");
  };

  const handleSelectSession = (id: string) => {
    if (id === sessionId) return;
    setSessionId(id); setMessages([]); setHistoryMessages([]);
    setHistoryOffset(0); setHistoryTotal(0);
  };

  const commitRename = async (sid: string) => {
    if (!renameValue.trim()) { setRenamingId(null); return; }
    try {
      await chatApi.renameSession(sid, renameValue.trim());
      queryClient.invalidateQueries({ queryKey: ["vr-sessions"] });
    } catch { toast.error("Failed to rename"); }
    setRenamingId(null);
  };

  const archiveSession = async (sid: string) => {
    try {
      await chatApi.archiveSession(sid);
      if (sessionId === sid) handleNewChat();
      queryClient.invalidateQueries({ queryKey: ["vr-sessions"] });
    } catch { toast.error("Failed to archive"); }
  };

  const restoreSession = async (sid: string) => {
    try {
      await chatApi.archiveSession(sid, true);
      queryClient.invalidateQueries({ queryKey: ["vr-sessions"] });
    } catch { toast.error("Failed to restore"); }
  };

  const deleteSession = async (sid: string) => {
    try {
      await chatApi.deleteSession(sid);
      if (sessionId === sid) handleNewChat();
      queryClient.invalidateQueries({ queryKey: ["vr-sessions"] });
    } catch { toast.error("Failed to delete"); }
  };

  const handleSend = async () => {
    const text = input.trim();
    if (!text || isStreaming) return;
    setInput("");

    const userMsg: ChatMessage = { id: genId(), type: "user", content: text };
    const assistantId = genId();
    const assistantMsg: ChatMessage = { id: assistantId, type: "assistant", content: "", isStreaming: true };
    setMessages(prev => [...prev, userMsg, assistantMsg]);
    setIsStreaming(true);
    isStreamingRef.current = true;
    setSpinnerIdx(Math.floor(Math.random() * SPINNER_VERBS.length));

    try {
      const abort = new AbortController();
      abortRef.current = abort;
      const token = getAuthToken();
      const [providerID, modelID] = selectedModel.split("/");
      const resp = await fetch(`${BASE_URL}/chat`, {
        method: "POST", signal: abort.signal,
        headers: {
          "Content-Type": "application/json",
          "X-OV-Client": "vendor-rationalization-ui",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({
          message: text, sessionId: sessionId || undefined,
          model: selectedModel ? { providerID, modelID } : undefined,
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          agentProfileId: "vr-assistant",
        }),
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

      const reader = resp.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      const handleEvent = (ev: any) => {
        if (ev.type === "connected" || ev.type === "session") {
          if (ev.sessionId) {
            setSessionId(ev.sessionId);
            queryClient.setQueryData(["vr-sessions"], (old: any) => {
              const sessions = Array.isArray(old?.sessions) ? [...old.sessions] : [];
              const idx = sessions.findIndex((s: any) => s.sessionId === ev.sessionId);
              if (idx < 0) sessions.unshift({ sessionId: ev.sessionId, title: ev.title || "New Chat", createdAt: Date.now(), lastActive: Date.now(), archivedAt: null });
              else sessions[idx] = { ...sessions[idx], lastActive: Date.now(), ...(ev.title ? { title: ev.title } : {}) };
              return { ...(old || {}), sessions };
            });
          }
        } else if (ev.type === "text.delta") {
          setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, content: m.content + (ev.content || "") } : m));
        } else if (ev.type === "tool.call" || ev.type === "tool.done" || ev.type === "tool.result") {
          const toolName = ev.toolName || ev.tool || "";
          const content = ev.type === "tool.call" ? `Calling tool: ${toolName}` : `Tool completed: ${toolName}`;
          setMessages(prev => [...prev, { id: genId(), type: "system", eventType: ev.type, content, eventPayload: ev }]);
        } else if (ev.type === "done") {
          setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, isStreaming: false } : m));
          setIsStreaming(false); isStreamingRef.current = false;
          refetchSessions();
          if (sessionId) { queryClient.invalidateQueries({ queryKey: ["vr-events", sessionId] }); loadHistory(sessionId); }
        } else if (ev.type === "error") {
          setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, content: m.content || `Error: ${ev.message}`, isStreaming: false } : m));
          setIsStreaming(false); isStreamingRef.current = false;
        }
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";
        for (const line of lines) {
          if (line.startsWith("data: ")) {
            const json = line.slice(6);
            if (json === "[DONE]") break;
            try { handleEvent(JSON.parse(json)); } catch {}
          }
        }
      }
    } catch (err: any) {
      if (err.name !== "AbortError") {
        setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, content: m.content || "Connection error.", isStreaming: false } : m));
        toast.error("Chat error: " + (err.message || "Unknown"));
      }
    } finally {
      setIsStreaming(false); isStreamingRef.current = false;
      setMessages(prev => prev.map(m => m.id === assistantId ? { ...m, isStreaming: false } : m));
    }
  };

  const stopStreaming = () => { abortRef.current?.abort(); setIsStreaming(false); isStreamingRef.current = false; setMessages(prev => prev.map(m => m.isStreaming ? { ...m, isStreaming: false } : m)); };

  const renderItems = buildRenderItems(messages);

  return (
    <div className="flex h-full overflow-hidden">
      {/* Session sidebar */}
      <div className={cn("flex flex-col border-r border-gray-200 bg-white transition-all duration-300 flex-shrink-0", sidebarCollapsed ? "w-0 overflow-hidden" : "w-64")}>
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
          <span className="font-semibold text-sm text-[#123262]">Conversations</span>
          <button onClick={handleNewChat} className="p-1.5 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors" title="New chat">
            <Plus size={16} />
          </button>
        </div>
        <div className="px-3 py-2">
          <input value={sidebarSearch} onChange={e => setSidebarSearch(e.target.value)} placeholder="Search..." className="w-full h-7 px-2.5 rounded-lg border border-gray-200 text-xs bg-gray-50 focus:outline-none focus:border-[#005CB9]" />
        </div>
        <div className="flex-1 overflow-y-auto">
          {activeSessions.map((s: any) => (
            <SessionItem key={s.sessionId} session={s} isActive={s.sessionId === sessionId} isHovered={hoveredSession === s.sessionId} isRenaming={renamingId === s.sessionId} renameValue={renameValue} onSelect={() => handleSelectSession(s.sessionId)} onHover={v => setHoveredSession(v ? s.sessionId : null)} onStartRename={() => { setRenamingId(s.sessionId); setRenameValue(s.title || ""); }} onRenameChange={setRenameValue} onRenameCommit={() => commitRename(s.sessionId)} onRenameCancel={() => setRenamingId(null)} onArchive={() => archiveSession(s.sessionId)} onDelete={() => deleteSession(s.sessionId)} />
          ))}
          {archivedSessions.length > 0 && (
            <>
              <button onClick={() => setShowArchived(v => !v)} className="w-full flex items-center gap-2 px-4 py-2 text-xs text-gray-400 hover:text-gray-600">
                {showArchived ? <ChevronLeft size={12} /> : <ChevronRight size={12} />}
                Archived ({archivedSessions.length})
              </button>
              {showArchived && archivedSessions.map((s: any) => (
                <SessionItem key={s.sessionId} session={s} isActive={false} isHovered={hoveredSession === s.sessionId} isRenaming={false} renameValue="" onSelect={() => handleSelectSession(s.sessionId)} onHover={v => setHoveredSession(v ? s.sessionId : null)} onStartRename={() => {}} onRenameChange={() => {}} onRenameCommit={() => {}} onRenameCancel={() => {}} onArchive={() => restoreSession(s.sessionId)} onDelete={() => deleteSession(s.sessionId)} isArchived />
              ))}
            </>
          )}
        </div>
      </div>

      {/* Main chat area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <div className="pods-header">
          <div className="flex items-center gap-3">
            <button onClick={() => setSidebarCollapsed(v => !v)} className="p-1.5 rounded-lg hover:bg-gray-100 text-gray-500">
              {sidebarCollapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
            </button>
            <div>
              <div className="page-title">AI Assistant</div>
              <div className="page-subtitle">Vendor Spend Optimization — ask about vendors, categories, savings</div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {selectedModel ? (
              <span
                className="text-xs px-2 py-1 rounded-md bg-blue-50 text-[#1d4ed8] border border-blue-100"
                title="Set in Settings → AI Chat Model"
              >
                Model: {selectedModel}
              </span>
            ) : (
              <a
                href="/config"
                className="text-xs px-2 py-1 rounded-md bg-amber-50 text-amber-700 border border-amber-200 hover:bg-amber-100"
                title="No chat model configured"
              >
                Configure model →
              </a>
            )}
          </div>
        </div>

        {/* Messages */}
        <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
          {messages.length === 0 && (
            <div className="flex flex-col items-center justify-center h-full text-center py-16">
              <div className="w-14 h-14 rounded-2xl bg-blue-50 flex items-center justify-center mb-4">
                <Bot size={28} className="text-[#005CB9]" />
              </div>
              <h3 className="font-semibold text-[#123262] mb-2">Vendor Spend AI Assistant</h3>
              <p className="text-sm text-gray-500 max-w-sm mb-6">Ask me about vendor spending, consolidation opportunities, category insights, or savings potential.</p>
              <div className="grid grid-cols-1 gap-2 w-full max-w-sm">
                {["What are our top 10 vendors by spend?", "Which categories have the most fragmentation?", "Where are our biggest savings opportunities?", "Give me an executive summary of our vendor spend"].map(q => (
                  <button key={q} onClick={() => { setInput(q); }} className="text-left px-4 py-2.5 rounded-xl border border-gray-200 text-sm text-gray-700 hover:bg-blue-50 hover:border-[#005CB9] transition-colors">
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}
          {renderItems.map((item, i) => {
            if (item.kind === "group") {
              return <SystemEventGroup key={i} messages={item.msgs} />;
            }
            const { msg, idx } = item;
            if (msg.type === "user") return (
              <div key={msg.id} onMouseEnter={() => setHoveredMsgIdx(idx)} onMouseLeave={() => setHoveredMsgIdx(null)}>
                <UserBubble content={msg.content} />
              </div>
            );
            if (msg.type === "assistant") return (
              <AssistantBubble key={msg.id} message={msg} spinnerVerb={SPINNER_VERBS[spinnerIdx % SPINNER_VERBS.length]} hovered={hoveredMsgIdx === idx} copied={copiedId === String(idx)} onMouseEnter={() => setHoveredMsgIdx(idx)} onMouseLeave={() => setHoveredMsgIdx(null)} onCopy={() => { navigator.clipboard.writeText(msg.content).catch(() => {}); setCopiedId(String(idx)); setTimeout(() => setCopiedId(null), 2000); }} />
            );
            return null;
          })}
          <div ref={messagesEndRef} />
        </div>

        {/* Input */}
        <div className="border-t border-gray-200 bg-white px-6 py-4">
          <div className="flex items-end gap-3 max-w-4xl mx-auto">
            <div className="flex-1 relative">
              <textarea
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); } }}
                placeholder="Ask about vendors, categories, savings opportunities..."
                rows={1}
                className="w-full resize-none rounded-xl border border-gray-200 px-4 py-3 text-sm text-gray-800 focus:outline-none focus:border-[#005CB9] focus:ring-2 focus:ring-[#005CB9]/20 transition-colors"
                style={{ minHeight: "44px", maxHeight: "120px" }}
              />
            </div>
            {isStreaming ? (
              <button onClick={stopStreaming} className="flex-shrink-0 w-10 h-10 rounded-xl bg-red-500 hover:bg-red-600 text-white flex items-center justify-center transition-colors">
                <Square size={16} />
              </button>
            ) : (
              <button onClick={handleSend} disabled={!input.trim()} className="flex-shrink-0 w-10 h-10 rounded-xl bg-[#005CB9] hover:bg-[#00458C] text-white flex items-center justify-center transition-colors disabled:opacity-40 disabled:cursor-not-allowed">
                <Send size={16} />
              </button>
            )}
          </div>
          <p className="text-center text-[10px] text-gray-400 mt-2">AI can make mistakes. Verify important figures against source data.</p>
        </div>
      </div>
    </div>
  );
}

function SessionItem({ session, isActive, isHovered, isRenaming, renameValue, onSelect, onHover, onStartRename, onRenameChange, onRenameCommit, onRenameCancel, onArchive, onDelete, isArchived }: {
  session: any; isActive: boolean; isHovered: boolean; isRenaming: boolean; renameValue: string;
  onSelect: () => void; onHover: (v: boolean) => void; onStartRename: () => void;
  onRenameChange: (v: string) => void; onRenameCommit: () => void; onRenameCancel: () => void;
  onArchive: () => void; onDelete: () => void; isArchived?: boolean;
}) {
  return (
    <div
      className={cn("group relative flex items-center gap-2 px-3 py-2.5 mx-2 rounded-lg cursor-pointer transition-colors", isActive ? "bg-blue-50 text-[#005CB9]" : "hover:bg-gray-50 text-gray-700")}
      onClick={onSelect} onMouseEnter={() => onHover(true)} onMouseLeave={() => onHover(false)}
    >
      <MessageSquare size={14} className="flex-shrink-0 opacity-60" />
      {isRenaming ? (
        <input autoFocus value={renameValue} onChange={e => onRenameChange(e.target.value)}
          onKeyDown={e => { if (e.key === "Enter") onRenameCommit(); if (e.key === "Escape") onRenameCancel(); }}
          onBlur={onRenameCommit} onClick={e => e.stopPropagation()}
          className="flex-1 min-w-0 text-xs bg-white border border-[#005CB9] rounded px-1.5 py-0.5 focus:outline-none" />
      ) : (
        <div className="flex-1 min-w-0">
          <div className="text-xs font-medium truncate">{session.title || "New Chat"}</div>
          <div className="text-[10px] text-gray-400">{session.lastActive ? formatDistanceToNow(new Date(session.lastActive), { addSuffix: true }) : ""}</div>
        </div>
      )}
      {isHovered && !isRenaming && (
        <div className="flex items-center gap-0.5 flex-shrink-0" onClick={e => e.stopPropagation()}>
          {!isArchived && <button onClick={onStartRename} className="p-1 rounded hover:bg-gray-200 text-gray-500" title="Rename"><Pencil size={11} /></button>}
          <button onClick={onArchive} className="p-1 rounded hover:bg-gray-200 text-gray-500" title={isArchived ? "Restore" : "Archive"}>
            {isArchived ? <ArchiveRestore size={11} /> : <Archive size={11} />}
          </button>
          <button onClick={onDelete} className="p-1 rounded hover:bg-red-100 text-red-500" title="Delete"><Trash2 size={11} /></button>
        </div>
      )}
    </div>
  );
}
