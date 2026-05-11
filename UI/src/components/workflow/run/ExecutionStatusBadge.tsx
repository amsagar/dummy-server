// Compact status icon embedded in the corner of an execution-view canvas
// node. Hover for tooltip with start/end/duration/error.

import { CheckCircle2, Clock, Loader2, Pause, XCircle } from "lucide-react";
import type { ActivityInst } from "@/types/workflow";
import { cn } from "@/lib/utils";

function formatMs(ms: number | null): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

export function ExecutionStatusBadge({ activity }: { activity: ActivityInst | null }) {
  if (!activity) {
    return (
      <span
        className="absolute -top-2 -right-2 w-5 h-5 rounded-full border bg-background text-muted-foreground flex items-center justify-center"
        title="Not run in this execution"
      >
        <Clock className="w-3 h-3" />
      </span>
    );
  }
  const dur = activity.startedAt != null && activity.endedAt != null
    ? activity.endedAt - activity.startedAt
    : null;
  const tip =
    `${activity.state}\n` +
    (activity.startedAt ? `started: ${new Date(activity.startedAt).toLocaleTimeString()}\n` : "") +
    (activity.endedAt ? `ended:   ${new Date(activity.endedAt).toLocaleTimeString()}\n` : "") +
    `duration: ${formatMs(dur)}` +
    (activity.errorClass ? `\nerror: ${activity.errorClass}: ${activity.errorMessage ?? ""}` : "");
  let Icon = Clock;
  let toneCls = "border-muted-foreground/40 text-muted-foreground bg-background";
  if (activity.state === "completed") {
    Icon = CheckCircle2;
    toneCls = "border-emerald-500 text-emerald-600 bg-emerald-50 dark:bg-emerald-950";
  } else if (activity.state === "failed" || activity.state === "deadline_breached" || activity.state === "cancelled") {
    Icon = XCircle;
    toneCls = "border-rose-500 text-rose-600 bg-rose-50 dark:bg-rose-950";
  } else if (activity.state === "running") {
    Icon = Loader2;
    toneCls = "border-blue-500 text-blue-600 bg-blue-50 dark:bg-blue-950";
  } else if (activity.state === "suspended") {
    Icon = Pause;
    toneCls = "border-amber-500 text-amber-600 bg-amber-50 dark:bg-amber-950";
  }
  return (
    <span
      className={cn(
        "absolute -top-2 -right-2 w-5 h-5 rounded-full border flex items-center justify-center",
        toneCls,
      )}
      title={tip}
    >
      <Icon className={cn("w-3 h-3", activity.state === "running" && "animate-spin")} />
    </span>
  );
}

/** Border-color helper for the host node's left edge. */
export function nodeStateRing(activity: ActivityInst | null): string {
  if (!activity) return "border-l-2 border-l-transparent";
  if (activity.state === "completed") return "border-l-2 border-l-emerald-500";
  if (activity.state === "failed" || activity.state === "deadline_breached" || activity.state === "cancelled")
    return "border-l-2 border-l-rose-500";
  if (activity.state === "running") return "border-l-2 border-l-blue-500";
  if (activity.state === "suspended") return "border-l-2 border-l-amber-500";
  return "border-l-2 border-l-transparent";
}
