import type { Dispatch, FC, SetStateAction } from "react";
import { ChevronRight } from "lucide-react";
import SystemEventCard from "./SystemEventCard";
import type { ChatMessage, PendingInteraction, InteractionAction } from "./types";

type Props = {
  messages: ChatMessage[];
  pendingInteractions?: PendingInteraction[];
  interactionDrafts?: Record<string, string>;
  interactionSelections?: Record<string, string[]>;
  setInteractionDrafts?: Dispatch<SetStateAction<Record<string, string>>>;
  setInteractionSelections?: Dispatch<SetStateAction<Record<string, string[]>>>;
  answerPending?: (requestId: string, action: InteractionAction, selected?: string[]) => void;
  className?: string;
};

const SystemEventGroup: FC<Props> = ({
  messages,
  pendingInteractions = [],
  interactionDrafts,
  interactionSelections,
  setInteractionDrafts,
  setInteractionSelections,
  answerPending,
  className,
}) => {
  if (messages.length === 0) return null;

  const toolNames = messages
    .filter((msg) => msg.eventType === "tool.call")
    .map((msg) => msg.eventPayload?.toolName)
    .filter(Boolean) as string[];
  const questionCount = messages.filter((msg) => msg.eventType === "question").length;
  const approvalCount = messages.filter((msg) => msg.eventType === "approval_required").length;
  const hasPending = messages.some(
    (msg) => msg.requestId && pendingInteractions.some((p) => p.requestId === msg.requestId)
  );

  const parts: string[] = [];
  if (toolNames.length === 1) parts.push(`Called ${toolNames[0]}`);
  else if (toolNames.length === 2) parts.push(`Called ${toolNames[0]}, ${toolNames[1]}`);
  else if (toolNames.length > 2) parts.push(`Called ${toolNames[0]} +${toolNames.length - 1} more`);
  if (questionCount > 0) parts.push(`${questionCount} question${questionCount > 1 ? "s" : ""}`);
  if (approvalCount > 0) parts.push(`${approvalCount} approval${approvalCount > 1 ? "s" : ""}`);
  const groupSummary = parts.join(" · ") || `${messages.length} event${messages.length > 1 ? "s" : ""}`;

  return (
    <details
      open={hasPending}
      className={
        className ?? "group/outer mx-2 rounded-xl border border-slate-200 bg-slate-50 text-xs"
      }
    >
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 [&::-webkit-details-marker]:hidden">
        <ChevronRight size={10} className="shrink-0 text-slate-400 transition-transform group-open/outer:rotate-90" />
        <span className="flex-1 text-slate-600">{groupSummary}</span>
        <span className="text-[10px] text-slate-400">
          {messages.length} event{messages.length > 1 ? "s" : ""}
        </span>
      </summary>
      <div className="space-y-1 border-t border-slate-200 px-2 pb-2 pt-1.5">
        {messages.map((msg) => (
          <SystemEventCard
            key={msg.id}
            message={msg}
            pendingInteractions={pendingInteractions}
            interactionDrafts={interactionDrafts}
            interactionSelections={interactionSelections}
            setInteractionDrafts={setInteractionDrafts}
            setInteractionSelections={setInteractionSelections}
            answerPending={answerPending}
          />
        ))}
      </div>
    </details>
  );
};

export default SystemEventGroup;
