import { type ChangeEvent, useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Bot, Paperclip, X } from "lucide-react";
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
  className?: string;
  isSending?: boolean;
  onCompile?: () => void;
  canCompile?: boolean;
  isCompiling?: boolean;
  compileLabel?: string;
  onResendMessage?: (content: string) => void;
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
  className,
  isSending,
  onCompile,
  canCompile,
  isCompiling,
  compileLabel,
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
  const currentSpinnerVerb = SPINNER_VERBS[spinnerVerbIndex % SPINNER_VERBS.length] || "Thinking";

  useEffect(() => {
    if (!isSending) return;
    const intervalId = window.setInterval(() => {
      setSpinnerVerbIndex((prev) => (prev + 1) % SPINNER_VERBS.length);
    }, 1250);
    return () => window.clearInterval(intervalId);
  }, [isSending]);

  const hasAnyAssistantContent = useMemo(
    () =>
      messages.some(
        (m) => m.type === "assistant" && (Boolean(String(m.content || "").trim()) || Boolean(m.isStreaming))
      ),
    [messages]
  );
  const hasPendingInteraction = pendingInteractions.length > 0 || Boolean(pendingQuestion);
  const showThinkingHint = Boolean(isSending) && !hasPendingInteraction && !hasAnyAssistantContent;

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
        onResend={() => onResendMessage?.(m.content)}
        onStartEdit={() => {
          setEditingMessageId(m.id);
          setEditDraft(m.content);
        }}
        onCancelEdit={() => setEditingMessageId(null)}
        onChangeEditDraft={setEditDraft}
        onSubmitEdit={() => {
          if (!editDraft.trim()) return;
          onResendMessage?.(editDraft.trim());
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
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" className="h-8 px-3 text-xs" onClick={onNewSession}>
            New Session
          </Button>
          <Button
            size="sm"
            className="h-8 bg-[#7FADE2] px-3 text-xs text-white hover:bg-[#6A9BD7]"
            onClick={onCompile}
            disabled={!canCompile || isCompiling}
          >
            {isCompiling ? "Compiling..." : (compileLabel || "Compile Version")}
          </Button>
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
        <div className="flex items-center gap-2">
          <input ref={fileInputRef} type="file" multiple className="hidden" onChange={onSelectAttachments} />
          <Button size="sm" variant="outline" className="h-8 px-2.5 text-xs" onClick={() => fileInputRef.current?.click()}>
            <Paperclip size={12} className="mr-1" />
            Attach
          </Button>
          <Input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask AI to build or refine this ToolChain..."
            className="h-8 text-xs"
            onKeyDown={(e) => {
              if (e.key === "Enter") submitMessage();
            }}
          />
          <Button
            className="bg-[#7FADE2] px-5 text-white hover:bg-[#6A9BD7]"
            onClick={submitMessage}
            disabled={isSending || isStreamingAny || (!input.trim() && attachments.length === 0)}
          >
            Send
          </Button>
        </div>
      </div>
    </div>
  );
}
