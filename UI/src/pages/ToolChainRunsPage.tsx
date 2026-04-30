import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import ToolChainRunInputDialog from "@/components/toolchain/ToolChainRunInputDialog";

function toTitleCase(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

export default function ToolChainRunsPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const [rerunVersion, setRerunVersion] = useState<number | undefined>(undefined);
  const [rerunInput, setRerunInput] = useState<Record<string, any> | undefined>(undefined);

  const { data } = useQuery<any>({
    queryKey: ["toolchain-runs", id],
    queryFn: () => api.toolchains.runs(id, 100, 0),
    enabled: !!id,
    refetchInterval: 4000,
  });

  const { data: analytics } = useQuery<any>({
    queryKey: ["toolchain-analytics"],
    queryFn: () => api.toolchains.analytics(),
  });

  const runs = data?.runs || [];

  const parseInputSnapshot = (raw: any): Record<string, any> | undefined => {
    if (!raw || typeof raw !== "string") return undefined;
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === "object" ? parsed : undefined;
    } catch {
      return undefined;
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-[#123262]">ToolChain Runs</h2>
          <p className="text-sm text-slate-600">Run history with trends and drill-down trace playback.</p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={() => navigate("/toolchains")}>Back to ToolChains</Button>
          <Button variant="outline" onClick={() => navigate(`/toolchains/${id}/designer`)}>Back to Designer</Button>
        </div>
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        <div className="rounded border bg-white p-3">
          <p className="text-xs text-slate-500">Total Runs</p>
          <p className="text-2xl font-semibold">{analytics?.totalRuns || 0}</p>
        </div>
        <div className="rounded border bg-white p-3 md:col-span-2">
          <p className="text-xs text-slate-500">Status Breakdown</p>
          <p className="text-sm text-slate-700">
            {(analytics?.statusBreakdown || []).map((x: any) => `${toTitleCase(String(x.status || ""))}: ${x.count}`).join(" | ") || "No Data"}
          </p>
        </div>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Run Id</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Trigger</TableHead>
            <TableHead>Started At</TableHead>
            <TableHead>Duration (ms)</TableHead>
            <TableHead className="text-right">Action</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {runs.map((run: any) => (
            <TableRow key={run.id} className="cursor-pointer" onClick={() => navigate(`/toolchains/runs/${run.id}`)}>
              <TableCell className="font-mono text-xs">{run.id}</TableCell>
              <TableCell>{toTitleCase(String(run.status || "—"))}</TableCell>
              <TableCell>{toTitleCase(String(run.triggerSource || "—"))}</TableCell>
              <TableCell>{new Date(run.startedAt).toLocaleString()}</TableCell>
              <TableCell>{run.durationMs || "—"}</TableCell>
              <TableCell className="space-x-2 text-right">
                <Button size="sm" onClick={(event) => {
                  event.stopPropagation();
                  setRerunVersion(run.version);
                  setRerunInput(parseInputSnapshot(run.inputSnapshot));
                  setRunDialogOpen(true);
                }}>Rerun</Button>
              </TableCell>
            </TableRow>
          ))}
          {runs.length === 0 ? (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-slate-500">No Runs Yet</TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>

      <ToolChainRunInputDialog
        open={runDialogOpen}
        onOpenChange={setRunDialogOpen}
        toolChainId={id}
        triggerSource="rerun"
        initialVersion={rerunVersion}
        initialInput={rerunInput}
        onExecuted={(result) => {
          if (result?.runId) navigate(`/toolchains/runs/${result.runId}`);
        }}
      />
    </div>
  );
}
