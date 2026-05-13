import type { FC } from "react";
import { ChevronRight } from "lucide-react";
import { formatPayload } from "./types";
import type { ChatMessage } from "./types";

type Props = { messages: Array<{ msg: ChatMessage; idx: number }> };

const SystemEventGroup: FC<Props> = ({ messages }) => {
  if (messages.length === 0) return null;
  const toolNames = messages
    .filter(e => e.msg.eventType === "tool.call")
    .map(e => e.msg.eventPayload?.toolName).filter(Boolean) as string[];
  const summary = toolNames.length > 0
    ? toolNames.length === 1 ? `Called ${toolNames[0]}` : `Called ${toolNames[0]} +${toolNames.length - 1} more`
    : `${messages.length} event${messages.length > 1 ? "s" : ""}`;

  return (
    <details className="mx-2 rounded-xl border border-slate-200 bg-slate-50 text-xs">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 [&::-webkit-details-marker]:hidden">
        <ChevronRight size={10} className="shrink-0 text-slate-400 transition-transform group-open:rotate-90" />
        <span className="flex-1 text-slate-600">{summary}</span>
        <span className="text-[10px] text-slate-400">{messages.length} event{messages.length > 1 ? "s" : ""}</span>
      </summary>
      <div className="space-y-1 border-t border-slate-200 px-2 pb-2 pt-1.5">
        {messages.map(({ msg }) => {
          const payload = msg.eventType === "tool.call"
            ? formatPayload(msg.eventPayload?.input)
            : msg.eventType === "tool.done" || msg.eventType === "tool.result"
            ? formatPayload(msg.eventPayload?.output)
            : "";
          return (
            <details key={msg.id} className="rounded-lg border border-slate-200 bg-white px-3 py-1.5">
              <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
                <ChevronRight size={9} className="shrink-0 text-slate-400" />
                <span className="w-20 shrink-0 font-mono text-[10px] text-slate-500">{msg.eventType}</span>
                <span className="min-w-0 flex-1 truncate text-slate-700">{msg.content}</span>
              </summary>
              {payload && (
                <pre className="mt-1.5 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded border border-slate-200 bg-slate-50 p-2 text-xs text-slate-800">
                  {payload}
                </pre>
              )}
            </details>
          );
        })}
      </div>
    </details>
  );
};

export default SystemEventGroup;
