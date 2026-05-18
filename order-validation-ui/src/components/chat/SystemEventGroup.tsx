import { ChevronRight } from "lucide-react";
import { SystemEventCard } from "./SystemEventCard";
import type { SystemEvent } from "@/types/chat";

interface Props {
  events: SystemEvent[];
  onAnswer?: (interactionId: string, answer: string) => Promise<void>;
  onApprove?: (interactionId: string) => Promise<void>;
  onReject?: (interactionId: string) => Promise<void>;
}

export function SystemEventGroup({ events, onAnswer, onApprove, onReject }: Props) {
  if (events.length === 0) return null;

  const toolNames = events
    .filter((e) => e.type === "tool.call" && e.name)
    .map((e) => e.name as string);
  const questionCount = events.filter((e) => e.type === "question").length;
  const approvalCount = events.filter((e) => e.type === "approval_required").length;
  const hasPending = events.some(
    (e) => (e.type === "question" || e.type === "approval_required") && !e.resolved,
  );

  const parts: string[] = [];
  if (toolNames.length === 1) parts.push(`Called ${toolNames[0]}`);
  else if (toolNames.length === 2) parts.push(`Called ${toolNames[0]}, ${toolNames[1]}`);
  else if (toolNames.length > 2)
    parts.push(`Called ${toolNames[0]} +${toolNames.length - 1} more`);
  if (questionCount > 0) parts.push(`${questionCount} question${questionCount > 1 ? "s" : ""}`);
  if (approvalCount > 0)
    parts.push(`${approvalCount} approval${approvalCount > 1 ? "s" : ""}`);
  const summary =
    parts.join(" · ") || `${events.length} event${events.length > 1 ? "s" : ""}`;

  return (
    <details
      open={hasPending}
      className="group/outer rounded-xl border border-border bg-muted/40 text-xs"
    >
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 [&::-webkit-details-marker]:hidden">
        <ChevronRight
          size={12}
          className="shrink-0 text-muted-foreground transition-transform group-open/outer:rotate-90"
        />
        <span className="flex-1 text-foreground/80 truncate">{summary}</span>
        <span className="text-[10px] text-muted-foreground">
          {events.length} event{events.length > 1 ? "s" : ""}
        </span>
      </summary>
      <div className="space-y-1 border-t border-border px-2 pb-2 pt-1.5">
        {events.map((ev) => (
          <SystemEventCard
            key={ev.id}
            event={ev}
            onAnswer={onAnswer}
            onApprove={onApprove}
            onReject={onReject}
          />
        ))}
      </div>
    </details>
  );
}
