import { useNavigate } from "react-router-dom";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

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

export function ExecutionsTable({ items, isLoading }: Props) {
  const navigate = useNavigate();

  if (isLoading && items.length === 0) {
    return <div className="rounded border bg-white p-4 text-sm text-gray-500">Loading…</div>;
  }
  if (items.length === 0) {
    return <div className="rounded border bg-white p-4 text-sm text-gray-500">No executions match the current filters.</div>;
  }

  return (
    <div className="rounded border bg-white overflow-hidden">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[170px]">Timestamp</TableHead>
            <TableHead>Skill</TableHead>
            <TableHead>Rule</TableHead>
            <TableHead className="w-[90px]">Status</TableHead>
            <TableHead className="w-[80px] text-right">Latency</TableHead>
            <TableHead>Error</TableHead>
            <TableHead className="w-[100px] text-right">Action</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((r) => (
            <TableRow
              key={r.id}
              className="cursor-pointer hover:bg-gray-50"
              onClick={() => navigate(`/rule-executions/${encodeURIComponent(r.id)}`)}
            >
              <TableCell className="text-xs whitespace-nowrap">
                {new Date(r.createdAt).toLocaleString()}
              </TableCell>
              <TableCell className="text-xs truncate max-w-[200px]" title={r.skillName}>
                {r.skillName}
              </TableCell>
              <TableCell className="text-xs font-mono truncate max-w-[260px]" title={r.ruleName}>
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
              <TableCell className="text-[11px] text-red-700 truncate max-w-[280px]" title={r.errorMessage ?? ""}>
                {r.errorMessage ?? ""}
              </TableCell>
              <TableCell className="text-right">
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/rule-executions/${encodeURIComponent(r.id)}`);
                  }}
                  className="text-xs text-blue-700 hover:underline"
                >
                  Inspect
                </button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
