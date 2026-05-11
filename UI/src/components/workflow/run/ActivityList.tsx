// Left rail: filterable list of activities for a run. Click an item to
// select it; the parent renders the IOPanel for the selection.

import { useMemo, useState } from "react";
import { CheckCircle2, Clock, Loader2, Search, XCircle } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";
import type { ActivityInst, ActivityState } from "@/types/workflow";

type Filter = "all" | "running" | "completed" | "failed";

interface ActivityListProps {
  activities: ActivityInst[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

function stateIcon(state: ActivityState) {
  if (state === "completed") return <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />;
  if (state === "failed" || state === "deadline_breached" || state === "cancelled")
    return <XCircle className="w-3.5 h-3.5 text-rose-600" />;
  if (state === "running") return <Loader2 className="w-3.5 h-3.5 animate-spin text-blue-600" />;
  return <Clock className="w-3.5 h-3.5 text-muted-foreground" />;
}

function durationMs(a: ActivityInst): number | null {
  if (a.startedAt == null) return null;
  const end = a.endedAt ?? Date.now();
  return Math.max(0, end - a.startedAt);
}

function formatMs(ms: number | null): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

export function ActivityList({ activities, selectedId, onSelect }: ActivityListProps) {
  const [filter, setFilter] = useState<Filter>("all");
  const [q, setQ] = useState("");

  const filtered = useMemo(() => {
    const ql = q.trim().toLowerCase();
    return activities.filter((a) => {
      if (filter === "running" && a.state !== "running") return false;
      if (filter === "completed" && a.state !== "completed") return false;
      if (filter === "failed" && a.state !== "failed" && a.state !== "deadline_breached" && a.state !== "cancelled")
        return false;
      if (ql) {
        const hay = `${a.activityDefId} ${a.pluginName ?? ""} ${a.errorClass ?? ""}`.toLowerCase();
        if (!hay.includes(ql)) return false;
      }
      return true;
    });
  }, [activities, filter, q]);

  return (
    <aside className="w-[260px] border-r flex flex-col bg-background">
      <div className="p-2 border-b flex flex-col gap-2">
        <div className="relative">
          <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter…"
            className="h-7 text-xs pl-6"
          />
        </div>
        <div className="grid grid-cols-4 text-[11px]">
          {(["all", "running", "completed", "failed"] as const).map((f) => (
            <Button
              key={f}
              variant={filter === f ? "default" : "ghost"}
              size="sm"
              className="h-6 px-1 text-[11px] capitalize"
              onClick={() => setFilter(f)}
            >
              {f}
            </Button>
          ))}
        </div>
      </div>
      <ScrollArea className="flex-1">
        <ul className="p-1 flex flex-col gap-0.5">
          {filtered.length === 0 && (
            <li className="text-xs text-muted-foreground italic p-2">No activities match.</li>
          )}
          {filtered.map((a) => (
            <li key={a.id}>
              <button
                type="button"
                onClick={() => onSelect(a.id)}
                className={cn(
                  "w-full text-left px-2 py-1.5 rounded hover:bg-muted flex items-center gap-2",
                  selectedId === a.id && "bg-muted",
                )}
              >
                {stateIcon(a.state)}
                <div className="flex flex-col leading-tight overflow-hidden flex-1">
                  <span className="text-xs font-mono truncate">{a.activityDefId}</span>
                  <span className="text-[10px] text-muted-foreground truncate">
                    {a.pluginName ?? a.type} · {formatMs(durationMs(a))}
                  </span>
                </div>
              </button>
            </li>
          ))}
        </ul>
      </ScrollArea>
    </aside>
  );
}
