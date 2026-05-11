// Left rail of the per-workflow execution browser. Filterable list of runs;
// click → selects and feeds the canvas / IO panel on the right. Polls every
// 4 s for fresh data.

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { workflowApi } from "@/services/workflowApi";
import { StateBadge } from "@/components/workflow/StateBadge";
import { cn } from "@/lib/utils";

interface RunRow {
  id: string;
  defId: string | null;
  state: string;
  startedAt: number | null;
  endedAt: number | null;
  requesterId: string | null;
  errorClass: string | null;
  errorMessage: string | null;
}

interface ExecutionRunListProps {
  workflowId: string;
  selectedRunId: string | null;
  onSelectRun: (runId: string) => void;
}

type Filter = "any" | "running" | "completed" | "failed" | "suspended";

function formatRel(ts: number | null): string {
  if (!ts) return "—";
  const diff = Date.now() - ts;
  if (diff < 60_000) return "just now";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}
function formatDuration(start: number | null, end: number | null): string {
  if (!start) return "—";
  const e = end ?? Date.now();
  const ms = e - start;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

function matchesFilter(state: string, f: Filter): boolean {
  if (f === "any") return true;
  if (f === "running") return state === "open.running";
  if (f === "completed") return state === "closed.completed";
  if (f === "failed") return state === "closed.terminated" || state === "closed.aborted";
  if (f === "suspended") return state === "open.not_running.suspended";
  return true;
}

export function ExecutionRunList({
  workflowId,
  selectedRunId,
  onSelectRun,
}: ExecutionRunListProps) {
  const [filter, setFilter] = useState<Filter>("any");
  const [q, setQ] = useState("");

  const list = useQuery<{ runs: RunRow[]; total: number }>({
    queryKey: ["wf-runs", workflowId],
    queryFn: () => workflowApi.runs.byProcess(workflowId, 100, 0),
    refetchInterval: 4000,
    enabled: !!workflowId,
  });

  const runs = list.data?.runs ?? [];
  const filtered = useMemo(() => {
    const ql = q.trim().toLowerCase();
    return runs.filter((r) => {
      if (!matchesFilter(r.state, filter)) return false;
      if (ql) {
        const hay = `${r.id} ${r.requesterId ?? ""} ${r.errorClass ?? ""}`.toLowerCase();
        if (!hay.includes(ql)) return false;
      }
      return true;
    });
  }, [runs, filter, q]);

  return (
    <aside className="w-[300px] border-r flex flex-col bg-background">
      <div className="p-2 border-b flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-medium">Runs</h3>
          <span className="text-xs text-muted-foreground">{list.data?.total ?? 0}</span>
          {list.isFetching && (
            <Loader2 className="w-3 h-3 animate-spin text-muted-foreground" />
          )}
          <Button
            variant="ghost"
            size="sm"
            className="ml-auto h-7 w-7 p-0"
            onClick={() => list.refetch()}
            aria-label="Refresh"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </Button>
        </div>
        <div className="relative">
          <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter runs…"
            className="h-7 text-xs pl-6"
          />
        </div>
        <div className="grid grid-cols-5 text-[10px] gap-0.5">
          {(["any", "running", "completed", "failed", "suspended"] as const).map((f) => (
            <Button
              key={f}
              variant={filter === f ? "default" : "ghost"}
              size="sm"
              className="h-6 px-1 text-[10px] capitalize"
              onClick={() => setFilter(f)}
            >
              {f}
            </Button>
          ))}
        </div>
      </div>
      <ScrollArea className="flex-1">
        {filtered.length === 0 && (
          <p className="text-xs text-muted-foreground italic p-3">
            {list.isLoading ? "Loading runs…" : "No runs match."}
          </p>
        )}
        <ul className="p-1 flex flex-col gap-0.5">
          {filtered.map((r) => (
            <li key={r.id}>
              <button
                type="button"
                onClick={() => onSelectRun(r.id)}
                className={cn(
                  "w-full text-left px-2 py-1.5 rounded hover:bg-muted flex flex-col gap-0.5",
                  selectedRunId === r.id && "bg-muted",
                )}
              >
                <div className="flex items-center gap-2">
                  <StateBadge state={r.state} />
                  <span className="text-[11px] text-muted-foreground ml-auto">
                    {formatDuration(r.startedAt, r.endedAt)}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs font-mono truncate">{r.id.slice(0, 12)}…</span>
                  <span className="text-[10px] text-muted-foreground ml-auto">
                    {formatRel(r.startedAt)}
                  </span>
                </div>
                {r.errorClass && (
                  <span className="text-[10px] text-rose-700 dark:text-rose-300 truncate">
                    {r.errorClass}: {r.errorMessage}
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      </ScrollArea>
    </aside>
  );
}
