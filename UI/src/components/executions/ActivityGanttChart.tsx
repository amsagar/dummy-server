import { useMemo, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ActivityEvent } from "@/components/bpmn/ActivityValuesPanel";

interface Props {
  events: ActivityEvent[];
  selectedActivityId?: string | null;
  onSelect?: (activityId: string, iterationIndex: number | null) => void;
}

interface Row {
  key: string;
  activityId: string;
  label: string;
  iterations: ActivityEvent[];
  delegate?: string | null;
  failed: boolean;
}

/**
 * Bar-per-activity timeline. Each row is one activity grouped by
 * activityId; multi-instance subprocesses collapse all iterations into
 * a single expandable row labeled "id × N". Bar position is
 * (event.startTs - run_start_ts), bar width is durationMs. Click to
 * notify the parent so it can drive the side panel + diagram selection.
 */
export function ActivityGanttChart({ events, selectedActivityId, onSelect }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const { rows, runStart, runEnd } = useMemo(() => {
    if (events.length === 0) return { rows: [] as Row[], runStart: 0, runEnd: 0 };
    let s = events[0].startTs;
    let e = events[0].endTs ?? events[0].startTs;
    for (const ev of events) {
      if (ev.startTs < s) s = ev.startTs;
      const end = ev.endTs ?? ev.startTs + (ev.durationMs ?? 0);
      if (end > e) e = end;
    }
    const grouped = new Map<string, Row>();
    for (const ev of events) {
      const r = grouped.get(ev.activityId);
      if (r) {
        r.iterations.push(ev);
        if (ev.errorCode) r.failed = true;
      } else {
        grouped.set(ev.activityId, {
          key: ev.activityId,
          activityId: ev.activityId,
          label: ev.activityName || ev.activityId,
          iterations: [ev],
          delegate: ev.delegateBean,
          failed: !!ev.errorCode,
        });
      }
    }
    const sorted = Array.from(grouped.values()).sort((a, b) => {
      const aStart = Math.min(...a.iterations.map((x) => x.startTs));
      const bStart = Math.min(...b.iterations.map((x) => x.startTs));
      return aStart - bStart;
    });
    return { rows: sorted, runStart: s, runEnd: e };
  }, [events]);

  const totalMs = Math.max(runEnd - runStart, 1);

  if (rows.length === 0) {
    return (
      <div className="rounded border bg-white p-4 text-sm text-gray-500">
        No activity events recorded for this execution.
      </div>
    );
  }

  return (
    <div className="rounded border bg-white overflow-hidden">
      <div className="px-3 py-2 border-b bg-gray-50 flex items-center justify-between">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
          Activity timeline
        </div>
        <div className="text-[11px] text-gray-500">
          {rows.length} activities · {totalMs} ms total
        </div>
      </div>
      <div className="divide-y">
        {rows.map((row) => {
          const isMulti = row.iterations.length > 1;
          const isExpanded = expanded.has(row.key);
          const aggStart = Math.min(...row.iterations.map((e) => e.startTs));
          const aggEnd = Math.max(
            ...row.iterations.map((e) => e.endTs ?? e.startTs + (e.durationMs ?? 0)),
          );
          const aggDuration = aggEnd - aggStart;
          const aggLeft = ((aggStart - runStart) / totalMs) * 100;
          const aggWidth = Math.max((aggDuration / totalMs) * 100, 0.5);
          const isSelected = selectedActivityId === row.activityId;
          return (
            <div key={row.key} className={isSelected ? "bg-blue-50/60" : ""}>
              <button
                type="button"
                onClick={() => {
                  if (isMulti) {
                    setExpanded((prev) => {
                      const next = new Set(prev);
                      if (next.has(row.key)) next.delete(row.key);
                      else next.add(row.key);
                      return next;
                    });
                  }
                  onSelect?.(row.activityId, isMulti ? null : row.iterations[0]?.iterationIndex ?? null);
                }}
                className="w-full text-left grid grid-cols-[18px_220px_1fr_70px] items-center gap-2 px-2 py-1.5 hover:bg-gray-50"
              >
                <span className="text-gray-400">
                  {isMulti ? (isExpanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />) : null}
                </span>
                <div className="min-w-0">
                  <div className="text-xs font-mono truncate">{row.label}</div>
                  <div className="text-[10px] text-gray-500 truncate">
                    {row.delegate ?? "activity"}{isMulti ? ` × ${row.iterations.length}` : ""}
                  </div>
                </div>
                <div className="relative h-4 bg-gray-100 rounded">
                  <div
                    className={`absolute top-0 bottom-0 rounded ${row.failed ? "bg-red-400" : "bg-emerald-400"}`}
                    style={{ left: `${aggLeft}%`, width: `${aggWidth}%` }}
                    title={`${aggDuration} ms`}
                  />
                </div>
                <div className="text-[11px] text-right text-gray-600 tabular-nums">
                  {aggDuration} ms
                </div>
              </button>
              {isMulti && isExpanded && (
                <div className="bg-gray-50/60">
                  {row.iterations
                    .slice()
                    .sort((a, b) => (a.iterationIndex ?? 0) - (b.iterationIndex ?? 0))
                    .map((ev) => {
                      const left = ((ev.startTs - runStart) / totalMs) * 100;
                      const width = Math.max(((ev.durationMs ?? 0) / totalMs) * 100, 0.5);
                      const failed = !!ev.errorCode;
                      return (
                        <button
                          key={ev.id}
                          type="button"
                          onClick={() => onSelect?.(ev.activityId, ev.iterationIndex ?? null)}
                          className="w-full text-left grid grid-cols-[18px_220px_1fr_70px] items-center gap-2 px-2 py-1 hover:bg-gray-100"
                        >
                          <span />
                          <div className="text-[11px] font-mono text-gray-600 pl-4 truncate">
                            iter {ev.iterationIndex ?? 0}
                          </div>
                          <div className="relative h-3 bg-gray-100 rounded">
                            <div
                              className={`absolute top-0 bottom-0 rounded ${failed ? "bg-red-400" : "bg-emerald-400"}`}
                              style={{ left: `${left}%`, width: `${width}%` }}
                              title={`${ev.durationMs ?? 0} ms`}
                            />
                          </div>
                          <div className="text-[11px] text-right text-gray-500 tabular-nums">
                            {ev.durationMs ?? 0} ms
                          </div>
                        </button>
                      );
                    })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
