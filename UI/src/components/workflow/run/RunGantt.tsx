// Horizontal Gantt of activities for a run. Each bar = one activity instance,
// positioned by (started_at, ended_at). Bars are colored by state. Clicking a
// bar selects the activity in the parent.

import { useMemo } from "react";
import { cn } from "@/lib/utils";
import type { ActivityInst } from "@/types/workflow";

interface RunGanttProps {
  activities: ActivityInst[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  /** Run started_at, used as the t=0 reference. Falls back to min activity start. */
  runStartedAt?: number | null;
  /** Run ended_at, used as the right edge. Falls back to max activity end (or now). */
  runEndedAt?: number | null;
}

const ROW_HEIGHT = 28;

function stateColor(state: string): string {
  if (state === "completed") return "bg-emerald-500";
  if (state === "running") return "bg-blue-500";
  if (state === "failed" || state === "deadline_breached" || state === "cancelled") return "bg-rose-500";
  return "bg-muted-foreground";
}

function formatTime(ms: number): string {
  const d = new Date(ms);
  return d.toLocaleTimeString();
}

function formatDelta(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m${s}s`;
}

export function RunGantt({
  activities,
  selectedId,
  onSelect,
  runStartedAt,
  runEndedAt,
}: RunGanttProps) {
  const { t0, t1, normalized } = useMemo(() => {
    const valid = activities.filter((a) => a.startedAt != null);
    const minStart = valid.reduce<number | null>(
      (m, a) => (a.startedAt != null && (m == null || a.startedAt < m) ? a.startedAt : m),
      null,
    );
    const maxEnd = valid.reduce<number | null>((m, a) => {
      const end = a.endedAt ?? Date.now();
      return m == null || end > m ? end : m;
    }, null);
    const t0 = runStartedAt ?? minStart ?? Date.now();
    const t1Raw = runEndedAt ?? maxEnd ?? t0 + 1;
    const t1 = Math.max(t1Raw, t0 + 1); // avoid divide-by-zero
    return {
      t0,
      t1,
      normalized: activities.map((a) => {
        const start = a.startedAt ?? t0;
        const end = a.endedAt ?? Date.now();
        return {
          ...a,
          startPct: ((start - t0) / (t1 - t0)) * 100,
          widthPct: Math.max(0.5, ((end - start) / (t1 - t0)) * 100),
          duration: end - start,
        };
      }),
    };
  }, [activities, runStartedAt, runEndedAt]);

  if (activities.length === 0) {
    return <p className="text-xs text-muted-foreground italic p-3">No activities yet.</p>;
  }

  return (
    <div className="border rounded-md overflow-hidden">
      <div className="px-3 py-2 border-b text-xs flex justify-between text-muted-foreground bg-muted/30">
        <span>{formatTime(t0)}</span>
        <span>span: {formatDelta(t1 - t0)}</span>
        <span>{formatTime(t1)}</span>
      </div>
      <div className="overflow-x-auto">
        <div className="min-w-[600px] py-2 px-3">
          {normalized.map((a) => {
            const selected = selectedId === a.id;
            return (
              <div
                key={a.id}
                className="relative flex items-center gap-2"
                style={{ height: ROW_HEIGHT }}
              >
                <span className="text-[11px] font-mono text-muted-foreground w-32 truncate flex-shrink-0">
                  {a.activityDefId}
                </span>
                <div
                  className="relative flex-1 h-5 bg-muted/30 rounded overflow-visible cursor-pointer"
                  onClick={() => onSelect(a.id)}
                >
                  <div
                    className={cn(
                      "absolute top-0 bottom-0 rounded transition-all",
                      stateColor(a.state),
                      selected && "ring-2 ring-primary",
                    )}
                    style={{
                      left: `${a.startPct}%`,
                      width: `${a.widthPct}%`,
                    }}
                    title={`${a.activityDefId} · ${a.state} · ${formatDelta(a.duration)}`}
                  />
                </div>
                <span className="text-[11px] text-muted-foreground w-14 text-right flex-shrink-0">
                  {formatDelta(a.duration)}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
