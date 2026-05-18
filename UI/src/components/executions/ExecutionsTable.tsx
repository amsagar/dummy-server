import { Fragment, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ChevronDown, ChevronRight } from "lucide-react";

export interface ExecutionRow {
  id: string;
  domainId: string;
  sessionId?: string | null;
  flowableProcId?: string | null;
  success: boolean;
  errorMessage?: string | null;
  latencyMs?: number | null;
  createdAt: number;
  skillId: string;
  skillName: string;
  intentLabel: string;
  ruleName: string;
}

interface Props {
  items: ExecutionRow[];
  isLoading?: boolean;
}

interface RunGroup {
  /** Stable key — sessionId, or a synthetic fallback for rows without one. */
  key: string;
  /** Display label for the group header. */
  skillName: string;
  rows: ExecutionRow[];
  successCount: number;
  failedCount: number;
  earliestTs: number;
  latestTs: number;
}

export function ExecutionsTable({ items, isLoading }: Props) {
  const navigate = useNavigate();

  // Group by sessionId — every rule that fanned out from one trigger
  // shares the same session_id, so grouping this way gives one row
  // per "submission" (e.g. one Submit-order / Quick-test click) with
  // its N per-rule runs nested under it. For rows with no session_id,
  // bucket by a 2-second window so near-simultaneous runs still
  // collapse together rather than spamming one group per row.
  const groups: RunGroup[] = useMemo(() => {
    const map = new Map<string, RunGroup>();
    for (const r of items) {
      const key =
        r.sessionId && r.sessionId.length > 0
          ? r.sessionId
          : `anon-${Math.floor(r.createdAt / 2000)}-${r.skillId}`;
      let g = map.get(key);
      if (!g) {
        g = {
          key,
          skillName: r.skillName,
          rows: [],
          successCount: 0,
          failedCount: 0,
          earliestTs: r.createdAt,
          latestTs: r.createdAt,
        };
        map.set(key, g);
      }
      g.rows.push(r);
      if (r.success) g.successCount++;
      else g.failedCount++;
      if (r.createdAt < g.earliestTs) g.earliestTs = r.createdAt;
      if (r.createdAt > g.latestTs) g.latestTs = r.createdAt;
    }
    return Array.from(map.values()).sort((a, b) => b.latestTs - a.latestTs);
  }, [items]);

  // Skill groups expanded by default — admins typically want to see runs.
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggle = (skillId: string): void => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(skillId)) next.delete(skillId);
      else next.add(skillId);
      return next;
    });
  };

  if (isLoading && items.length === 0) {
    return <div className="rounded border bg-white p-4 text-sm text-gray-500">Loading…</div>;
  }
  if (items.length === 0) {
    return <div className="rounded border bg-white p-4 text-sm text-gray-500">No executions match the current filters.</div>;
  }

  return (
    <div className="rounded border bg-white overflow-hidden">
      <Table className="table-fixed">
        <colgroup>
          <col className="w-[200px]" />
          <col />
          <col className="w-[100px]" />
          <col className="w-[100px]" />
          <col className="w-[280px]" />
          <col className="w-[100px]" />
        </colgroup>
        <TableHeader>
          <TableRow>
            <TableHead>Timestamp</TableHead>
            <TableHead>Rule</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="text-right">Latency</TableHead>
            <TableHead>Error</TableHead>
            <TableHead className="text-right">Action</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {groups.map((g) => {
            const isOpen = !collapsed.has(g.key);
            const spanMs = g.latestTs - g.earliestTs;
            return (
              <Fragment key={g.key}>
                <TableRow
                  className="bg-gray-50 hover:bg-gray-100 cursor-pointer border-y"
                  onClick={() => { toggle(g.key); }}
                >
                  <TableCell colSpan={6} className="py-2">
                    <div className="flex items-center gap-2 text-xs">
                      {isOpen ? (
                        <ChevronDown className="h-3.5 w-3.5 text-gray-500" />
                      ) : (
                        <ChevronRight className="h-3.5 w-3.5 text-gray-500" />
                      )}
                      <span className="font-semibold text-[#123262] tabular-nums">
                        {new Date(g.earliestTs).toLocaleString()}
                      </span>
                      <span className="text-gray-500">·</span>
                      <span className="text-gray-700">{g.skillName}</span>
                      <span className="text-gray-500">·</span>
                      <span className="text-gray-600 tabular-nums">
                        {g.rows.length} rule{g.rows.length === 1 ? "" : "s"}
                      </span>
                      {g.successCount > 0 && (
                        <span className="rounded bg-green-100 text-green-800 px-1.5 py-0.5 text-[10px] font-semibold">
                          {g.successCount} success
                        </span>
                      )}
                      {g.failedCount > 0 && (
                        <span className="rounded bg-red-100 text-red-800 px-1.5 py-0.5 text-[10px] font-semibold">
                          {g.failedCount} failed
                        </span>
                      )}
                      {spanMs > 0 && (
                        <span className="text-[10px] text-gray-400 ml-auto tabular-nums">
                          spanned {spanMs.toLocaleString()} ms
                        </span>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
                {isOpen &&
                  g.rows.map((r) => (
                    <TableRow
                      key={r.id}
                      className="cursor-pointer hover:bg-gray-50"
                      onClick={() => { void navigate(`/rule-executions/${encodeURIComponent(r.id)}`); }}
                    >
                      <TableCell className="text-xs whitespace-nowrap pl-8">
                        {new Date(r.createdAt).toLocaleString()}
                      </TableCell>
                      <TableCell className="text-xs font-mono truncate max-w-[300px]" title={r.ruleName}>
                        {r.ruleName}
                      </TableCell>
                      <TableCell>
                        <span
                          className={`rounded px-2 py-0.5 text-[10px] font-semibold ${
                            r.success ? "bg-green-100 text-green-800" : "bg-red-100 text-red-800"
                          }`}
                        >
                          {r.success ? "SUCCESS" : "FAILED"}
                        </span>
                      </TableCell>
                      <TableCell className="text-xs text-right tabular-nums">
                        {r.latencyMs ?? "—"}{typeof r.latencyMs === "number" ? " ms" : ""}
                      </TableCell>
                      <TableCell className="text-[11px] text-red-700 truncate max-w-[320px]" title={r.errorMessage ?? ""}>
                        {r.errorMessage ?? ""}
                      </TableCell>
                      <TableCell className="text-right">
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            void navigate(`/rule-executions/${encodeURIComponent(r.id)}`);
                          }}
                          className="text-xs text-blue-700 hover:underline"
                        >
                          Inspect
                        </button>
                      </TableCell>
                    </TableRow>
                  ))}
              </Fragment>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
