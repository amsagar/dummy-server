// Global Executions page — cross-workflow run list with multi-field filters,
// pagination, and inline replay. Reuses the existing run-detail page on click.
//
// Filters (sticky bar):
//   • State group:  any | open | closed | suspended
//   • Workflow:     combobox of process definitions
//   • Requester:    substring (case-insensitive)
//   • Date range:   from / to (started_at)
//   • Saved filters live in localStorage (key "exec.filters.v1").

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  Loader2,
  RefreshCw,
  RotateCcw,
  Search,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { SearchableSelect } from "@/components/ui/searchable-select";
import { workflowApi, type ExecutionsFilters } from "@/services/workflowApi";
import { StateBadge } from "@/components/workflow/StateBadge";
import { toast } from "sonner";
import type { ProcessDef } from "@/types/workflow";

const SAVED_KEY = "exec.filters.v1";

type StateGroup = "any" | "open" | "closed" | "suspended";

interface UiFilters {
  stateGroup: StateGroup;
  defId: string;
  requester: string;
  from: string; // yyyy-mm-dd; converted to ms before sending
  to: string;
  limit: number;
  offset: number;
}

const DEFAULT_FILTERS: UiFilters = {
  stateGroup: "any",
  defId: "",
  requester: "",
  from: "",
  to: "",
  limit: 50,
  offset: 0,
};

function loadSaved(): UiFilters {
  try {
    const raw = localStorage.getItem(SAVED_KEY);
    if (!raw) return DEFAULT_FILTERS;
    return { ...DEFAULT_FILTERS, ...(JSON.parse(raw) as UiFilters), offset: 0 };
  } catch {
    return DEFAULT_FILTERS;
  }
}

function saveSaved(f: UiFilters) {
  // Persist filters but never the current page offset.
  localStorage.setItem(SAVED_KEY, JSON.stringify({ ...f, offset: 0 }));
}

function toApi(f: UiFilters): ExecutionsFilters {
  return {
    stateGroup: f.stateGroup === "any" ? null : f.stateGroup,
    defId: f.defId || null,
    requester: f.requester.trim() || null,
    from: f.from ? Date.parse(f.from) : null,
    to: f.to ? Date.parse(f.to) + 86_399_999 : null, // include the whole day
    limit: f.limit,
    offset: f.offset,
  };
}

function formatMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  return new Date(ms).toLocaleString();
}

function formatDuration(start: number | null, end: number | null): string {
  if (start == null) return "—";
  const e = end ?? Date.now();
  const ms = e - start;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

export default function ExecutionsPage() {
  const qc = useQueryClient();
  const [filters, setFilters] = useState<UiFilters>(() => loadSaved());

  useEffect(() => {
    saveSaved(filters);
  }, [filters]);

  const processes = useQuery<ProcessDef[]>({
    queryKey: ["workflows"],
    queryFn: () => workflowApi.processes.list(),
    staleTime: 30_000,
  });

  const apiFilters = useMemo(() => toApi(filters), [filters]);
  const list = useQuery({
    queryKey: ["executions", apiFilters],
    queryFn: () => workflowApi.runs.list(apiFilters),
    refetchInterval: 5000,
  });

  const replay = useMutation({
    mutationFn: (instanceId: string) => workflowApi.runs.replay(instanceId),
    onSuccess: () => {
      toast.success("Replay started");
      qc.invalidateQueries({ queryKey: ["executions"] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Replay failed"),
  });

  const totalPages = Math.max(1, Math.ceil((list.data?.total ?? 0) / filters.limit));
  const currentPage = Math.floor(filters.offset / filters.limit) + 1;

  const processById = useMemo(() => {
    const map = new Map<string, ProcessDef>();
    for (const p of processes.data ?? []) {
      if (p.id) map.set(p.id, p);
    }
    return map;
  }, [processes.data]);

  function update<K extends keyof UiFilters>(key: K, value: UiFilters[K]) {
    setFilters((f) => ({ ...f, [key]: value, offset: 0 }));
  }

  function reset() {
    setFilters(DEFAULT_FILTERS);
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <h1 className="text-2xl font-semibold">Executions</h1>
        {list.isFetching && (
          <Loader2 className="w-3.5 h-3.5 animate-spin text-muted-foreground" />
        )}
        <div className="ml-auto flex gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => list.refetch()}
            disabled={list.isFetching}
          >
            <RefreshCw className="w-3.5 h-3.5 mr-1" /> Refresh
          </Button>
        </div>
      </div>

      {/* Filter bar */}
      <div className="border rounded-md bg-background p-3 flex flex-wrap items-end gap-3 sticky top-0 z-10">
        <FilterField label="Status">
          <Select
            value={filters.stateGroup}
            onValueChange={(v) => update("stateGroup", v as StateGroup)}
          >
            <SelectTrigger className="h-8 text-xs w-[140px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="any">Any</SelectItem>
              <SelectItem value="open">Open</SelectItem>
              <SelectItem value="closed">Closed</SelectItem>
              <SelectItem value="suspended">Suspended</SelectItem>
            </SelectContent>
          </Select>
        </FilterField>

        <FilterField label="Workflow">
          <SearchableSelect
            options={[
              { value: "", label: "Any workflow" },
              ...(processes.data ?? []).flatMap((p) =>
                p.id ? [{ value: p.id, label: p.name }] : [],
              ),
            ]}
            value={filters.defId}
            onValueChange={(v) => update("defId", v)}
            placeholder="Any workflow"
            className="w-[220px]"
          />
        </FilterField>

        <FilterField label="Requester contains">
          <div className="relative w-[180px]">
            <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={filters.requester}
              placeholder="email or id"
              className="h-8 text-xs pl-6"
              onChange={(e) => update("requester", e.target.value)}
            />
          </div>
        </FilterField>

        <FilterField label="From">
          <Input
            type="date"
            value={filters.from}
            className="h-8 text-xs w-[140px]"
            onChange={(e) => update("from", e.target.value)}
          />
        </FilterField>

        <FilterField label="To">
          <Input
            type="date"
            value={filters.to}
            className="h-8 text-xs w-[140px]"
            onChange={(e) => update("to", e.target.value)}
          />
        </FilterField>

        <FilterField label="Per page">
          <Select
            value={String(filters.limit)}
            onValueChange={(v) => update("limit", Number(v))}
          >
            <SelectTrigger className="h-8 text-xs w-[80px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="20">20</SelectItem>
              <SelectItem value="50">50</SelectItem>
              <SelectItem value="100">100</SelectItem>
              <SelectItem value="200">200</SelectItem>
            </SelectContent>
          </Select>
        </FilterField>

        <Button variant="ghost" size="sm" onClick={reset} className="ml-auto">
          <X className="w-3.5 h-3.5 mr-1" /> Reset
        </Button>
      </div>

      {/* Results */}
      <div className="border rounded-md bg-background overflow-hidden">
        <ScrollArea className="h-[calc(100vh-260px)]">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-muted-foreground bg-muted/40 sticky top-0">
              <tr>
                <th className="py-2 px-3">Workflow</th>
                <th className="py-2 px-3">Run</th>
                <th className="py-2 px-3">State</th>
                <th className="py-2 px-3">Started</th>
                <th className="py-2 px-3">Duration</th>
                <th className="py-2 px-3">Requester</th>
                <th className="py-2 px-3">Error</th>
                <th className="py-2 px-3 w-24"></th>
              </tr>
            </thead>
            <tbody>
              {list.isLoading && (
                <tr>
                  <td colSpan={8} className="py-6 text-center text-muted-foreground">
                    <Loader2 className="w-4 h-4 inline animate-spin mr-2" />
                    Loading…
                  </td>
                </tr>
              )}
              {!list.isLoading && (list.data?.runs ?? []).length === 0 && (
                <tr>
                  <td colSpan={8} className="py-10 text-center text-muted-foreground italic">
                    No runs match the filters.
                  </td>
                </tr>
              )}
              {(list.data?.runs ?? []).map((r) => {
                const def = r.defId ? processById.get(r.defId) : null;
                return (
                  <tr key={r.id} className="border-t hover:bg-muted/40">
                    <td className="py-2 px-3">
                      {def ? (
                        <Link
                          to={`/workflows/${def.id}/designer`}
                          className="font-medium hover:underline"
                        >
                          {def.name}
                        </Link>
                      ) : (
                        <span className="text-muted-foreground font-mono text-xs">
                          {r.defId ?? "—"}
                        </span>
                      )}
                    </td>
                    <td className="py-2 px-3 font-mono text-xs">
                      <Link to={`/workflows/runs/${r.id}`} className="hover:underline">
                        {r.id.slice(0, 8)}…
                      </Link>
                    </td>
                    <td className="py-2 px-3">
                      <StateBadge state={r.state} />
                    </td>
                    <td className="py-2 px-3 text-xs">{formatMs(r.startedAt)}</td>
                    <td className="py-2 px-3 text-xs">
                      {formatDuration(r.startedAt, r.endedAt)}
                    </td>
                    <td className="py-2 px-3 text-xs font-mono">
                      {r.requesterId ?? "—"}
                    </td>
                    <td className="py-2 px-3 text-xs text-rose-700 dark:text-rose-300 max-w-[260px] truncate">
                      {r.errorClass ? `${r.errorClass}: ${r.errorMessage ?? ""}` : ""}
                    </td>
                    <td className="py-2 px-3 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 px-2 text-xs"
                        onClick={() => replay.mutate(r.id)}
                        disabled={replay.isPending}
                      >
                        <RotateCcw className="w-3 h-3 mr-1" /> Replay
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </ScrollArea>

        {/* Pagination */}
        <div className="border-t flex items-center justify-between px-3 py-2 text-xs text-muted-foreground">
          <span>
            {list.data
              ? `${list.data.total === 0 ? 0 : list.data.offset + 1}–${
                  list.data.offset + (list.data.runs?.length ?? 0)
                } of ${list.data.total}`
              : "—"}
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              className="h-7 px-2 text-xs"
              onClick={() =>
                setFilters((f) => ({ ...f, offset: Math.max(0, f.offset - f.limit) }))
              }
              disabled={filters.offset === 0}
            >
              Prev
            </Button>
            <span>
              Page {currentPage} of {totalPages}
            </span>
            <Button
              variant="outline"
              size="sm"
              className="h-7 px-2 text-xs"
              onClick={() =>
                setFilters((f) => ({ ...f, offset: f.offset + f.limit }))
              }
              disabled={currentPage >= totalPages}
            >
              Next
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

function FilterField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-[11px] uppercase tracking-wide text-muted-foreground">
        {label}
      </span>
      {children}
    </div>
  );
}
