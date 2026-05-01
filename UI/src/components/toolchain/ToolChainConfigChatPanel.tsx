import { type ChangeEvent, useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Bot, History, Paperclip, Plus, SendHorizontal, Square, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { SPINNER_VERBS } from "@/lib/spinnerVerbs";
import {
  AssistantBubble,
  UserBubble,
  SystemEventGroup,
  type ChatMessage,
  type InteractionAction,
  type PendingInteraction,
  buildRenderItems,
} from "@/components/chat";
import type { Dispatch, SetStateAction } from "react";

type PendingQuestion = {
  id?: string;
  question: string;
  options?: { id: string; label: string }[];
};

type ChatAttachment = {
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  contentBase64: string;
};

type Props = {
  messages: ChatMessage[];
  pendingQuestion: PendingQuestion | null;
  pendingInteractions?: PendingInteraction[];
  interactionDrafts?: Record<string, string>;
  interactionSelections?: Record<string, string[]>;
  setInteractionDrafts?: Dispatch<SetStateAction<Record<string, string>>>;
  setInteractionSelections?: Dispatch<SetStateAction<Record<string, string[]>>>;
  answerPending?: (requestId: string, action: InteractionAction, selected?: string[]) => void;
  onSend: (payload: {
    message?: string;
    answerText?: string;
    selectedOptionId?: string;
    selectedOptionLabel?: string;
    attachments?: ChatAttachment[];
  }) => void;
  onNewSession: () => void;
  /** Toggle the session-history popover (rendered by the parent so the popover
   *  can be anchored relative to the chat panel layout). */
  onShowHistory?: () => void;
  /** Cancel an in-flight stream. When provided AND isSending is true, the Send
   *  button slot in the composer renders a Stop button instead. */
  onCancelStream?: () => void;
  className?: string;
  isSending?: boolean;
  onResendMessage?: (content: string, messageId: string) => void;
};

const ALLOWED_EXTENSIONS = new Set([
  "txt", "md", "csv", "json", "xml", "yaml", "yml", "log",
  "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
  "png", "jpg", "jpeg", "webp",
]);
const MAX_ATTACHMENT_SIZE_BYTES = 5 * 1024 * 1024;
const MAX_ATTACHMENTS = 5;

export default function ToolChainConfigChatPanel({
  messages,
  pendingQuestion,
  pendingInteractions = [],
  interactionDrafts = {},
  interactionSelections = {},
  setInteractionDrafts,
  setInteractionSelections,
  answerPending,
  onSend,
  onNewSession,
  onShowHistory,
  onCancelStream,
  className,
  isSending,
  onResendMessage,
}: Props) {
  const [input, setInput] = useState("");
  const [hoveredMsgId, setHoveredMsgId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState("");
  const [attachments, setAttachments] = useState<ChatAttachment[]>([]);
  const [spinnerVerbIndex, setSpinnerVerbIndex] = useState(() =>
    Math.floor(Math.random() * SPINNER_VERBS.length)
  );
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  // Auto-grow the textarea up to a 7-line cap. Beyond that, content scrolls
  // inside the textarea instead of pushing the composer / Send button down.
  // The cap (~140px) plays nicely with the chat-panel layout.
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 140);
    el.style.height = `${next}px`;
  }, [input]);
  const currentSpinnerVerb = SPINNER_VERBS[spinnerVerbIndex % SPINNER_VERBS.length] || "Thinking";

  useEffect(() => {
    if (!isSending) return;
    const intervalId = window.setInterval(() => {
      setSpinnerVerbIndex((prev) => {
        if (SPINNER_VERBS.length <= 1) return 0;
        // Pick a random index that's NOT the current one so the verb visibly changes
        // instead of cycling sequentially through the list.
        let next = Math.floor(Math.random() * SPINNER_VERBS.length);
        if (next === prev) next = (next + 1) % SPINNER_VERBS.length;
        return next;
      });
    }, 1250);
    return () => window.clearInterval(intervalId);
  }, [isSending]);

  const hasPendingInteraction = pendingInteractions.length > 0 || Boolean(pendingQuestion);
  // Show the cycling spinner whenever a turn is in flight, regardless of past
  // assistant messages. Prior failed/successful turns shouldn't suppress the
  // current turn's "thinking" indicator (e.g. during a long read/apply_patch
  // window where no new assistant text has streamed yet).
  const showThinkingHint = Boolean(isSending) && !hasPendingInteraction;

  const renderItems = useMemo(() => buildRenderItems(messages), [messages]);
  const isStreamingAny = messages.some((m) => m.isStreaming);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isSending, pendingQuestion]);

  const submitMessage = () => {
    if (!input.trim() && attachments.length === 0) return;
    onSend({ message: input.trim(), attachments });
    setInput("");
    setAttachments([]);
  };

  const copyMessage = async (messageId: string, content: string) => {
    try {
      await navigator.clipboard.writeText(content);
      setCopiedId(messageId);
      window.setTimeout(() => setCopiedId(null), 1200);
    } catch {
      setCopiedId(null);
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

  const onSelectAttachments = async (event: ChangeEvent<HTMLInputElement>) => {
    const files: File[] = event.target.files ? Array.from(event.target.files as FileList) : [];
    if (files.length === 0) return;
    const existing = new Set(attachments.map((a) => a.fileName.toLowerCase()));
    const next: ChatAttachment[] = [];
    for (const file of files) {
      if (attachments.length + next.length >= MAX_ATTACHMENTS) break;
      const ext = file.name.includes(".") ? file.name.split(".").pop()!.toLowerCase() : "";
      if (!ALLOWED_EXTENSIONS.has(ext)) continue;
      if (file.size > MAX_ATTACHMENT_SIZE_BYTES) continue;
      if (existing.has(file.name.toLowerCase())) continue;
      const contentBase64 = await fileToBase64(file);
      next.push({
        fileName: file.name,
        mimeType: file.type || "application/octet-stream",
        sizeBytes: file.size,
        contentBase64,
      });
    }
    if (next.length > 0) setAttachments((prev) => [...prev, ...next]);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const renderUser = (m: ChatMessage) => {
    const isEditing = editingMessageId === m.id;
    return (
      <UserBubble
        key={m.id}
        content={m.content}
        hovered={hoveredMsgId === m.id}
        copied={copiedId === m.id}
        isEditing={isEditing}
        editDraft={editDraft}
        isStreaming={Boolean(isSending)}
        onMouseEnter={() => setHoveredMsgId(m.id)}
        onMouseLeave={() => setHoveredMsgId(null)}
        onCopy={() => copyMessage(m.id, m.content)}
        onResend={() => onResendMessage?.(m.content, m.id)}
        onStartEdit={() => {
          setEditingMessageId(m.id);
          setEditDraft(m.content);
        }}
        onCancelEdit={() => setEditingMessageId(null)}
        onChangeEditDraft={setEditDraft}
        onSubmitEdit={() => {
          if (!editDraft.trim()) return;
          onResendMessage?.(editDraft.trim(), m.id);
          setEditingMessageId(null);
        }}
      />
    );
  };

  const renderAssistant = (m: ChatMessage) => (
    <AssistantBubble
      key={m.id}
      message={m}
      spinnerVerb={currentSpinnerVerb}
      hovered={hoveredMsgId === m.id}
      copied={copiedId === m.id}
      onMouseEnter={() => setHoveredMsgId(m.id)}
      onMouseLeave={() => setHoveredMsgId(null)}
      onCopy={() => copyMessage(m.id, m.content)}
    />
  );

  const hasSystemGroup = renderItems.some((entry) => entry.kind === "group");
  const deferredStreamingAssistants: ChatMessage[] = [];

  const renderItem = (item: ReturnType<typeof buildRenderItems>[number]) => {
    if (item.kind === "msg") {
      const m = item.msg;
      if (m.type === "user") return renderUser(m);
      if (m.type === "assistant") {
        // Mirror ChatPage: defer the still-streaming assistant to the bottom
        // so it doesn't get hidden inside/above the system event group.
        if (m.isStreaming && hasSystemGroup) {
          deferredStreamingAssistants.push(m);
          return null;
        }
        return renderAssistant(m);
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
  };

  return (
    <div className={cn("flex h-full flex-col overflow-hidden rounded-lg border border-slate-200 bg-white", className)}>
      <div className="flex items-center justify-between gap-2 border-b border-slate-200 px-3 py-2">
        <div className="flex min-w-0 items-center gap-2">
          <Bot size={14} className="text-[#123262]" />
          <h3 className="truncate text-sm font-semibold text-[#123262]">AI Configuration Chat</h3>
        </div>
        <div className="flex items-center gap-1">
          {onShowHistory ? (
            <button
              type="button"
              aria-label="Session history"
              title="Session history"
              className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
              onClick={onShowHistory}
            >
              <History size={14} />
            </button>
          ) : null}
          <button
            type="button"
            aria-label="New session"
            title="New session"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
            onClick={onNewSession}
          >
            <Plus size={14} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto bg-[#F8FAFC] p-3">
        <div className="space-y-3">
          {renderItems.map((item) => renderItem(item))}
          {deferredStreamingAssistants.map((m) => renderAssistant(m))}
          {messages.length === 0 ? (
            <p className="py-12 text-center text-xs text-slate-500">Start a chat to configure this ToolChain.</p>
          ) : null}
          {showThinkingHint ? (
            <div className="text-left">
              <div className="inline-flex items-center gap-1.5 rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-3 py-1.5 text-[11px] text-slate-500">
                <span>{currentSpinnerVerb}...</span>
                <span className="inline-block h-3 w-1 animate-pulse rounded-sm bg-slate-400" />
              </div>
            </div>
          ) : null}
          <div ref={messagesEndRef} />
        </div>
      </div>

      <div className="space-y-2 border-t border-slate-200 bg-white p-3">
        {attachments.length > 0 ? (
          <div className="flex flex-wrap gap-2">
            {attachments.map((a) => (
              <span key={a.fileName} className="inline-flex items-center gap-1 rounded border bg-slate-100 px-2 py-1 text-[11px] text-slate-700">
                <Paperclip size={11} />
                {a.fileName}
                <button onClick={() => setAttachments((prev) => prev.filter((x) => x.fileName !== a.fileName))}>
                  <X size={11} />
                </button>
              </span>
            ))}
          </div>
        ) : null}
        <div className="flex items-end gap-2">
          <input ref={fileInputRef} type="file" multiple className="hidden" onChange={onSelectAttachments} />
          <button
            type="button"
            aria-label="Attach files"
            title="Attach files"
            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
            onClick={() => fileInputRef.current?.click()}
          >
            <Paperclip size={14} />
          </button>
          <textarea
            ref={textareaRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask AI to build or refine this ToolChain..."
            rows={1}
            className="min-w-0 flex-1 resize-none overflow-y-auto rounded-md border border-slate-200 bg-transparent px-3 py-1.5 text-xs leading-5 text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-1 focus:ring-slate-300"
            style={{ minHeight: "32px", maxHeight: "140px" }}
            onKeyDown={(e) => {
              // Enter sends; Shift+Enter inserts a newline.
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                submitMessage();
              }
            }}
          />
          {onCancelStream && isSending ? (
            <button
              type="button"
              aria-label="Stop streaming"
              title="Stop streaming"
              className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-red-300 bg-red-50 text-red-600 hover:bg-red-100"
              onClick={onCancelStream}
            >
              <Square size={12} fill="currentColor" />
            </button>
          ) : (
            <button
              type="button"
              aria-label="Send"
              title="Send"
              className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-[#7FADE2] text-white hover:bg-[#6A9BD7] disabled:opacity-50"
              onClick={submitMessage}
              disabled={isSending || isStreamingAny || (!input.trim() && attachments.length === 0)}
            >
              <SendHorizontal size={14} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
