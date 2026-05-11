// Single source of truth for run / activity status pills. Maps the engine's
// dotted wire states ("open.running", "closed.completed", "failed", …) to
// a friendly label and a tone, so every page shows the same badge.
//
// Pass either:
//   - a process state string (e.g. "open.running", "closed.terminated"), or
//   - an activity state string (e.g. "running", "failed", "suspended"),
// or any prefix-truncated wire value.

import { cn } from "@/lib/utils";

interface StateBadgeProps {
  state: string | null | undefined;
  /** Optional className override for sizing. */
  className?: string;
}

interface Mapping {
  label: string;
  tone: string;
}

function mappingFor(state: string | null | undefined): Mapping {
  if (!state) return { label: "—", tone: "bg-muted text-muted-foreground" };
  const s = state.toLowerCase();

  // Process states
  if (s === "open.running" || s === "running")
    return { label: "Running", tone: "bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200" };
  if (s === "open.not_running.suspended" || s === "suspended")
    return { label: "Suspended", tone: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200" };
  if (s === "closed.completed" || s === "completed")
    return { label: "Completed", tone: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200" };
  if (s === "closed.terminated" || s === "terminated")
    return { label: "Terminated", tone: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200" };
  if (s === "closed.aborted" || s === "aborted")
    return { label: "Aborted", tone: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200" };

  // Activity states
  if (s === "ready") return { label: "Ready", tone: "bg-muted text-muted-foreground" };
  if (s === "failed") return { label: "Failed", tone: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200" };
  if (s === "deadline_breached") return { label: "Timed out", tone: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200" };
  if (s === "cancelled") return { label: "Cancelled", tone: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200" };

  // Generic fallback by prefix
  if (s.startsWith("closed."))
    return { label: titleCase(s.split(".").pop() ?? s), tone: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200" };
  if (s.startsWith("open."))
    return { label: titleCase(s.split(".").pop() ?? s), tone: "bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200" };

  return { label: titleCase(s), tone: "bg-muted text-foreground" };
}

function titleCase(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1).replace(/[._]/g, " ");
}

export function StateBadge({ state, className }: StateBadgeProps) {
  const m = mappingFor(state);
  return (
    <span
      className={cn(
        "inline-block text-[11px] px-2 py-0.5 rounded-full font-medium whitespace-nowrap",
        m.tone,
        className,
      )}
      title={state ?? undefined}
    >
      {m.label}
    </span>
  );
}
