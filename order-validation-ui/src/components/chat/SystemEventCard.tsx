import { useState } from "react";
import {
  Check,
  ChevronRight,
  CircleHelp,
  ExternalLink,
  Loader2,
  Wrench,
  X,
} from "lucide-react";
import { Link } from "react-router-dom";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { JsonTree } from "@/components/JsonTree";
import type { SystemEvent } from "@/types/chat";

interface Props {
  event: SystemEvent;
  onAnswer?: (interactionId: string, answer: string) => Promise<void>;
  onApprove?: (interactionId: string) => Promise<void>;
  onReject?: (interactionId: string) => Promise<void>;
}

export function SystemEventCard({ event, onAnswer, onApprove, onReject }: Props) {
  const [open, setOpen] = useState<boolean>(
    event.type === "question" || event.type === "approval_required" ? !event.resolved : false,
  );
  const [answer, setAnswer] = useState("");
  const [busy, setBusy] = useState(false);

  const heading = renderHeading(event);
  const tone = renderTone(event);

  const expandable =
    event.input != null || event.output != null || event.payload != null || heading.expandable;

  return (
    <div className="border border-border rounded-md bg-muted/40 text-xs">
      <div
        className={
          "flex items-center gap-2 px-3 py-2 select-none " +
          (expandable ? "cursor-pointer hover:bg-accent/30" : "")
        }
        onClick={() => expandable && setOpen((o) => !o)}
      >
        {expandable ? (
          <ChevronRight
            className={"size-3.5 text-muted-foreground transition-transform " + (open ? "rotate-90" : "")}
          />
        ) : (
          <span className="size-3.5" />
        )}
        {heading.icon}
        <span className="font-medium text-foreground/90">{heading.label}</span>
        {tone && (
          <Badge variant={tone} className="ml-1">
            {heading.statusText}
          </Badge>
        )}
        {event.runId && (
          <Link
            to={`/runs/${event.runId}`}
            onClick={(e) => e.stopPropagation()}
            className="ml-auto inline-flex items-center gap-1 text-pods-blue hover:underline"
          >
            Open run <ExternalLink className="size-3" />
          </Link>
        )}
      </div>
      {open && (
        <div className="px-3 pb-3 pt-1 space-y-3 border-t border-border">
          {event.type === "question" && event.message && (
            <div className="text-foreground/90">{event.message}</div>
          )}
          {event.type === "question" && event.resolved && event.answeredText && (
            <div className="text-foreground/80 italic border-l-2 border-pods-blue pl-2">
              Your answer: {event.answeredText}
            </div>
          )}
          {event.type === "question" && !event.resolved && onAnswer && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (!answer.trim() || !event.callId) return;
                setBusy(true);
                onAnswer(event.callId, answer.trim()).finally(() => {
                  setBusy(false);
                  setAnswer("");
                });
              }}
              className="space-y-2"
            >
              <div className="flex gap-2">
                <Input
                  value={answer}
                  onChange={(e) => setAnswer(e.target.value)}
                  placeholder="Type your answer…"
                  autoFocus
                  disabled={busy}
                />
                <Button type="submit" size="sm" disabled={busy || !answer.trim()}>
                  {busy ? <Loader2 className="size-3 animate-spin" /> : "Send"}
                </Button>
              </div>
            </form>
          )}
          {event.type === "approval_required" && !event.resolved && onApprove && onReject && (
            <div className="space-y-2">
              <div className="text-foreground/90">{event.message ?? "Approval required."}</div>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => event.callId && onReject(event.callId)}
                >
                  <X className="size-3" /> Reject
                </Button>
                <Button size="sm" onClick={() => event.callId && onApprove(event.callId)}>
                  <Check className="size-3" /> Approve
                </Button>
              </div>
            </div>
          )}
          {event.input !== undefined && (
            <Section label="Input">
              <JsonTree value={event.input} defaultOpenDepth={2} />
            </Section>
          )}
          {event.output !== undefined && (
            <Section label="Output">
              <JsonTree value={event.output} defaultOpenDepth={2} />
            </Section>
          )}
        </div>
      )}
    </div>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <div className="text-[11px] uppercase tracking-wider text-muted-foreground">{label}</div>
      {children}
    </div>
  );
}

function renderHeading(event: SystemEvent): { icon: React.ReactNode; label: string; statusText: string; expandable: boolean } {
  switch (event.type) {
    case "tool.call":
      return {
        icon: <Loader2 className="size-3.5 text-pods-blue animate-spin" />,
        label: `Calling tool: ${event.name ?? "?"}`,
        statusText: "running",
        expandable: true,
      };
    case "tool.done":
    case "tool.result":
      return {
        icon: <Wrench className="size-3.5 text-status-pass" />,
        label: `Tool: ${event.name ?? "?"}`,
        statusText: "done",
        expandable: true,
      };
    case "tool.match":
      return {
        icon: <Wrench className="size-3.5 text-muted-foreground" />,
        label: `Tool matched: ${event.name ?? "?"}`,
        statusText: "matched",
        expandable: true,
      };
    case "task.started":
      return {
        icon: <Loader2 className="size-3.5 text-pods-blue animate-spin" />,
        label: `Task: ${event.name ?? "?"}`,
        statusText: "running",
        expandable: true,
      };
    case "task.done":
      return {
        icon: <Check className="size-3.5 text-status-pass" />,
        label: `Task: ${event.name ?? "?"}`,
        statusText: "done",
        expandable: true,
      };
    case "question":
      return {
        icon: <CircleHelp className="size-3.5 text-pods-blue" />,
        label: "Assistant asked",
        statusText: event.resolved ? "answered" : "waiting",
        expandable: true,
      };
    case "approval_required":
      return {
        icon: <CircleHelp className="size-3.5 text-status-warning" />,
        label: "Approval needed",
        statusText: event.resolved ? "resolved" : "waiting",
        expandable: true,
      };
    case "workflow.run.bound":
      return {
        icon: <Wrench className="size-3.5 text-pods-blue" />,
        label: `Workflow run #${event.runId?.slice(0, 8) ?? "?"}…`,
        statusText: "linked",
        expandable: false,
      };
    default:
      return {
        icon: <Wrench className="size-3.5 text-muted-foreground" />,
        label: event.type,
        statusText: "info",
        expandable: event.payload != null,
      };
  }
}

function renderTone(event: SystemEvent): "pass" | "fail" | "warn" | "info" | "muted" | null {
  switch (event.type) {
    case "tool.call":
    case "task.started":
      return "info";
    case "tool.done":
    case "tool.result":
    case "task.done":
      return "pass";
    case "question":
      return event.resolved ? "muted" : "info";
    case "approval_required":
      return event.resolved ? "muted" : "warn";
    case "workflow.run.bound":
      return "info";
    default:
      return null;
  }
}
