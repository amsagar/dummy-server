import type { Dispatch, FC, SetStateAction } from "react";
import { ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  type ChatMessage,
  type PendingInteraction,
  type InteractionAction,
  formatPayload,
  formatHitlResponse,
  normalizeQuestionMetadata,
} from "./types";

type Props = {
  message: ChatMessage;
  pendingInteractions?: PendingInteraction[];
  interactionDrafts?: Record<string, string>;
  interactionSelections?: Record<string, string[]>;
  setInteractionDrafts?: Dispatch<SetStateAction<Record<string, string>>>;
  setInteractionSelections?: Dispatch<SetStateAction<Record<string, string[]>>>;
  answerPending?: (requestId: string, action: InteractionAction, selected?: string[]) => void;
};

const SystemEventCard: FC<Props> = ({
  message: m,
  pendingInteractions = [],
  interactionDrafts = {},
  interactionSelections = {},
  setInteractionDrafts,
  setInteractionSelections,
  answerPending,
}) => {
  const pending = m.requestId ? pendingInteractions.find((p) => p.requestId === m.requestId) : undefined;
  const draftValue = m.requestId ? (interactionDrafts[m.requestId] ?? "") : "";
  const selected = m.requestId ? (interactionSelections[m.requestId] ?? []) : [];
  const metadata =
    pending?.metadata || normalizeQuestionMetadata(m.eventPayload?.metadata || m.eventPayload);
  const mode = metadata?.responseMode || "text";

  if (m.eventType === "toolchain.run.bound") {
    const runId = String(m.eventPayload?.runId || "");
    const version = m.eventPayload?.version;
    const status = m.eventPayload?.status;
    const href = runId ? `/toolchains/runs/${runId}` : null;
    return (
      <div className="rounded-lg border border-blue-300 bg-blue-50 px-3 py-1.5 text-xs">
        <div className="flex items-center gap-2">
          <span className="font-mono text-[10px] text-blue-700">▶ ToolChain run</span>
          <span className="min-w-0 flex-1 truncate text-slate-700">
            #{runId.slice(0, 8)}
            {version ? ` (v${version})` : ""}
            {status ? ` — ${status}` : ""}
          </span>
          {href ? (
            <a
              href={href}
              className="shrink-0 rounded px-2 py-0.5 text-[11px] font-medium text-blue-700 hover:bg-blue-100"
            >
              Open run
            </a>
          ) : null}
        </div>
      </div>
    );
  }

  if (m.eventType === "task.started" || m.eventType === "task.done") {
    const taskName = m.eventPayload?.taskName || m.eventPayload?.taskId || "node";
    const status = m.eventPayload?.status || m.eventPayload?.result;
    const isStarted = m.eventType === "task.started";
    const detailsPayload = isStarted
      ? {
          taskId: m.eventPayload?.taskId,
          type: m.eventPayload?.type,
          toolRef: m.eventPayload?.toolRef,
          input: m.eventPayload?.input,
        }
      : {
          taskId: m.eventPayload?.taskId,
          status,
          output: m.eventPayload?.output,
          error: m.eventPayload?.error,
        };
    const payloadText = formatPayload(detailsPayload);
    return (
      <details className="group/item rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs">
        <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
          <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
          <span className="font-mono text-[10px] text-slate-500">{isStarted ? "▶ node" : "✓ node"}</span>
          <span className="min-w-0 flex-1 truncate text-slate-700">
            {taskName}
            {status ? ` — ${status}` : ""}
          </span>
        </summary>
        {payloadText ? (
          <pre className="mt-1.5 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded border border-slate-200 bg-white p-2 text-[10px] text-slate-800">
            {payloadText}
          </pre>
        ) : null}
      </details>
    );
  }

  const isToolEvent =
    m.eventType === "tool.call" ||
    m.eventType === "tool.done" ||
    m.eventType === "tool.result" ||
    m.eventType === "tool.match";

  if (isToolEvent) {
    const payloadText =
      m.eventType === "tool.call"
        ? formatPayload(m.eventPayload?.input)
        : m.eventType === "tool.done" || m.eventType === "tool.result"
        ? formatPayload(m.eventPayload?.output)
        : formatPayload({
            reason: m.eventPayload?.reason,
            needsClarification: m.eventPayload?.needsClarification,
            selectedTool: m.eventPayload?.selectedTool,
            score: m.eventPayload?.score,
            candidates: m.eventPayload?.candidates,
          });
    return (
      <details className="group/item rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-xs">
        <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
          <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
          <span className="w-20 shrink-0 font-mono text-[10px] text-slate-500">{m.eventType}</span>
          <span className="min-w-0 flex-1 truncate text-slate-700">{m.content}</span>
        </summary>
        {payloadText ? (
          <pre className="mt-1.5 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded border border-slate-200 bg-white p-2 text-[10px] text-slate-800">
            {payloadText}
          </pre>
        ) : null}
      </details>
    );
  }

  if (m.eventType === "question") {
    const isResolved = m.hitlStatus && m.hitlStatus !== "pending";
    return (
      <details open={!isResolved} className="group/item rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-xs">
        <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
          <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
          <span className="font-medium text-slate-700">Question</span>
          <span className="min-w-0 flex-1 truncate text-slate-600">{m.content}</span>
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
                        <button
                          key={opt.id}
                          type="button"
                          onClick={() => {
                            if (!m.requestId || !setInteractionSelections) return;
                            const requestId = m.requestId;
                            setInteractionSelections((prev) => {
                              const current = prev[requestId] || [];
                              if (mode === "single_select") return { ...prev, [requestId]: [opt.id] };
                              const next = current.includes(opt.id)
                                ? current.filter((id) => id !== opt.id)
                                : [...current, opt.id];
                              return { ...prev, [requestId]: next };
                            });
                          }}
                          className={`rounded border px-2 py-1 text-xs ${
                            active
                              ? "border-[#005CB9] bg-[#EFF6FF] text-[#123262]"
                              : "border-gray-200 bg-white text-slate-700"
                          }`}
                        >
                          {opt.label}
                        </button>
                      );
                    })}
                  </div>
                  {metadata.allowCustomText && (
                    <input
                      value={draftValue}
                      onChange={(e) => {
                        if (!m.requestId || !setInteractionDrafts) return;
                        const requestId = m.requestId;
                        setInteractionDrafts((prev) => ({ ...prev, [requestId]: e.target.value }));
                      }}
                      placeholder="Optional clarification..."
                      className="h-8 w-full rounded border border-gray-200 bg-white px-2 text-xs text-slate-700"
                    />
                  )}
                </div>
              ) : (
                <input
                  value={draftValue}
                  onChange={(e) => {
                    if (!m.requestId || !setInteractionDrafts) return;
                    const requestId = m.requestId;
                    setInteractionDrafts((prev) => ({ ...prev, [requestId]: e.target.value }));
                  }}
                  placeholder="Type your answer..."
                  className="h-8 flex-1 rounded border border-gray-200 bg-white px-2 text-xs text-slate-700"
                />
              )}
              <Button
                size="sm"
                variant="outline"
                onClick={() => answerPending?.(pending.requestId, "reply", selected)}
              >
                Continue
              </Button>
            </div>
          ) : null}
        </div>
      </details>
    );
  }

  if (m.eventType === "approval_required") {
    const isResolved = m.hitlStatus && m.hitlStatus !== "pending";
    return (
      <details open={!isResolved} className="group/item rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-xs">
        <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
          <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
          <span className="font-medium text-slate-700">Approval request</span>
          {isResolved && (
            <span
              className={`ml-1 rounded px-1.5 py-0.5 text-[10px] font-medium ${
                m.hitlStatus === "approved" ? "bg-green-100 text-green-700" : "bg-red-100 text-red-700"
              }`}
            >
              {m.hitlStatus}
            </span>
          )}
          <span className="min-w-0 flex-1 truncate text-slate-600">{m.content}</span>
        </summary>
        <div className="mt-2 space-y-2">
          <div className="text-slate-700">{m.content}</div>
          {isResolved ? (
            m.hitlResponse ? <div className="text-slate-500">{m.hitlResponse}</div> : null
          ) : pending?.type && pending.type !== "question" ? (
            <div className="space-y-2">
              <input
                value={draftValue}
                onChange={(e) => {
                  if (!m.requestId || !setInteractionDrafts) return;
                  const requestId = m.requestId;
                  setInteractionDrafts((prev) => ({ ...prev, [requestId]: e.target.value }));
                }}
                placeholder="Optional reason..."
                className="h-8 w-full rounded border border-gray-200 bg-white px-2 text-xs text-slate-700"
              />
              <div className="flex gap-2">
                <Button size="sm" variant="outline" onClick={() => answerPending?.(pending.requestId, "approve")}>
                  Approve
                </Button>
                <Button size="sm" variant="outline" onClick={() => answerPending?.(pending.requestId, "reject")}>
                  Reject
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </details>
    );
  }

  return (
    <details className="group/item rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-xs">
      <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
        <ChevronRight size={9} className="shrink-0 text-slate-400 transition-transform group-open/item:rotate-90" />
        <span className="font-mono text-[10px] text-slate-500">{m.eventType || "system"}</span>
        <span className="min-w-0 flex-1 truncate text-slate-700">{m.content}</span>
      </summary>
    </details>
  );
};

export default SystemEventCard;
