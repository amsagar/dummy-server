// List of workflow process definitions managed by the Joget-style
// engine using /api/v1/workflow/* endpoints via workflowApi.

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { Plus, Play, Pencil, Loader2, List, Sparkles, Activity, Database, Terminal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { CopyCurlDialog } from "@/components/workflow/CopyCurlDialog";
import { RunWorkflowDialog } from "@/components/workflow/RunWorkflowDialog";
import { workflowApi } from "@/services/workflowApi";
import type {
  ProcessDef,
  ProcessDefMetadata,
  RunSummary,
  WorkflowProposal,
} from "@/types/workflow";
import { toast } from "sonner";

export default function WorkflowsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [search, setSearch] = useState("");
  const [curlForId, setCurlForId] = useState<string | null>(null);
  const [runDialogForId, setRunDialogForId] = useState<string | null>(null);

  const list = useQuery<ProcessDef[]>({
    queryKey: ["workflows"],
    queryFn: () => workflowApi.processes.list(),
  });
  const proposals = useQuery<{ proposals: WorkflowProposal[] }>({
    queryKey: ["workflow-proposals"],
    queryFn: () => workflowApi.processes.proposals(),
  });

  // Phase C: rolling aggregates surfaced as badges. We fetch the whole
  // collection in one batch round-trip; the backend returns zeros for
  // never-run defs so badges render uniformly.
  const defIds = useMemo(
    () => (list.data ?? []).map((wf) => wf.id).filter((id): id is string => Boolean(id)),
    [list.data],
  );
  const metadata = useQuery<{ metadata: Record<string, ProcessDefMetadata> }>({
    queryKey: ["workflow-metadata", defIds],
    queryFn: () => workflowApi.processes.metadataBatch(defIds),
    enabled: defIds.length > 0,
    staleTime: 15_000,
  });
  const metaById = metadata.data?.metadata ?? {};

  const create = useMutation({
    mutationFn: (def: ProcessDef) => workflowApi.processes.create(def),
    onSuccess: (saved) => {
      qc.invalidateQueries({ queryKey: ["workflows"] });
      toast.success("Workflow created");
      if (saved.id) navigate(`/workflows/${saved.id}/designer`);
      setCreating(false);
      setName("");
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to create"),
  });
  const createFromTemplate = useMutation({
    mutationFn: (templateId: string) => workflowApi.processes.createFromTemplate(templateId),
    onSuccess: (saved) => {
      qc.invalidateQueries({ queryKey: ["workflows"] });
      toast.success("Workflow created from template");
      if (saved.id) navigate(`/workflows/${saved.id}/designer`);
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to create from template"),
  });

  const startRun = useMutation({
    // async=true so the backend returns immediately with state=open.running;
    // the detail page then polls until terminal. Without async, the API blocks
    // until the run finishes and we'd land on the detail page after it's
    // already closed, missing all the live status updates.
    mutationFn: ({ id, initialVariables }: { id: string; initialVariables: Record<string, unknown> }) =>
      workflowApi.runs.start({ processDefId: id, initialVariables }, { async: true }) as Promise<RunSummary>,
    onSuccess: (r) => {
      toast.success(`Run started: ${r.instanceId}`);
      setRunDialogForId(null);
      navigate(`/workflows/runs/${r.instanceId}`);
    },
    onError: (e: Error) => toast.error(e.message ?? "Run failed"),
  });

  const handleCreate = () => {
    if (!name.trim()) {
      toast.error("Name required");
      return;
    }
    // Minimal viable starter definition: a start route → an end route.
    create.mutate({
      name: name.trim(),
      version: "1",
      activities: [
        { id: "start", name: "Start", type: "route", isStart: true },
        { id: "end", name: "End", type: "route", isEnd: true },
      ],
      transitions: [
        { id: "t1", fromActivityId: "start", toActivityId: "end" },
      ],
    });
  };

  const remove = useMutation({
    mutationFn: (id: string) => workflowApi.processes.remove(id, { force: true }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["workflows"] });
      // Workflow deletion scrubs the def id from every API key's scope on
      // the server; refresh the cached list so stale UUIDs don't linger in
      // the API Keys page.
      qc.invalidateQueries({ queryKey: ["workflow-api-keys"] });
      toast.success("Workflow deleted");
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to delete workflow"),
  });
  const approveProposal = useMutation({
    mutationFn: (id: string) => workflowApi.processes.approveProposal(id),
    onSuccess: ({ proposal }) => {
      qc.invalidateQueries({ queryKey: ["workflow-proposals"] });
      qc.invalidateQueries({ queryKey: ["workflows"] });
      toast.success("Proposal approved and workflow materialized");
      if (proposal.materializedDefId) navigate(`/workflows/${proposal.materializedDefId}/designer`);
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to approve proposal"),
  });
  const rejectProposal = useMutation({
    mutationFn: (id: string) => workflowApi.processes.rejectProposal(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["workflow-proposals"] });
      toast.success("Proposal rejected");
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to reject proposal"),
  });

  const filtered = (list.data ?? []).filter((wf) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return String(wf.name || "").toLowerCase().includes(q) || String(wf.description || "").toLowerCase().includes(q);
  });

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-semibold">Workflows</h1>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={() => navigate("/workflows/approvals")}>Approvals</Button>
          <Button variant="outline" onClick={() => createFromTemplate.mutate("starter-route")}>
            From Template
          </Button>
          <Button onClick={() => setCreating(true)}>
            <Plus className="w-4 h-4 mr-1" /> New workflow
          </Button>
        </div>
      </div>
      <Input
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search workflows"
        className="max-w-sm mb-4"
      />

      {(proposals.data?.proposals?.length ?? 0) > 0 && (
        <div className="border rounded-md mb-4">
          <div className="px-4 py-2 border-b bg-muted/30 text-sm font-medium">
            Pending Workflow Proposals
          </div>
          {(proposals.data?.proposals ?? []).map((proposal) => (
            <div key={proposal.id} className="p-4 border-b last:border-b-0">
              <div className="text-sm font-medium">{proposal.reason || "Reusable workflow proposal"}</div>
              <div className="text-xs text-muted-foreground mt-1">
                Confidence: {proposal.confidence?.toFixed(2) ?? "n/a"}
              </div>
              {proposal.userPrompt ? (
                <div className="text-xs mt-2 text-muted-foreground">Prompt: {proposal.userPrompt}</div>
              ) : null}
              <div className="flex gap-2 mt-3">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => approveProposal.mutate(proposal.id)}
                  disabled={approveProposal.isPending}
                >
                  Approve
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => rejectProposal.mutate(proposal.id)}
                  disabled={rejectProposal.isPending}
                >
                  Reject
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {creating && (
        <div className="border rounded-md p-4 mb-4 flex gap-2 items-end">
          <div className="flex-1">
            <label className="text-sm text-muted-foreground">Name</label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="My workflow" />
          </div>
          <Button onClick={handleCreate} disabled={create.isPending}>
            {create.isPending ? <Loader2 className="w-4 h-4 animate-spin" /> : "Create"}
          </Button>
          <Button variant="ghost" onClick={() => setCreating(false)}>
            Cancel
          </Button>
        </div>
      )}

      {list.isLoading ? (
        <div className="text-muted-foreground">Loading…</div>
      ) : list.error ? (
        <div className="text-red-600">Error: {(list.error as Error).message}</div>
      ) : (list.data ?? []).length === 0 ? (
        <div className="text-muted-foreground">No workflows yet. Create one to get started.</div>
      ) : (
        <div className="border rounded-md divide-y">
          {filtered.map((wf) => {
            const m = wf.id ? metaById[wf.id] : undefined;
            return (
            <div key={wf.id} className="p-4 flex items-center justify-between">
              <div>
                <div className="font-medium">{wf.name}</div>
                <div className="text-xs text-muted-foreground">
                  v{wf.version} · {wf.activities.length} activities · {wf.transitions.length} edges
                </div>
                {m ? <MetadataBadges meta={m} /> : null}
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => wf.id && setRunDialogForId(wf.id)}
                  disabled={!wf.id}
                >
                  <Play className="w-3.5 h-3.5 mr-1" /> Run
                </Button>
                <Button asChild variant="outline" size="sm">
                  <Link to={`/workflows/${wf.id}/designer`}>
                    <Pencil className="w-3.5 h-3.5 mr-1" /> Edit
                  </Link>
                </Button>
                <Button asChild variant="outline" size="sm">
                  <Link to={`/workflows/${wf.id}/runs`}>
                    <List className="w-3.5 h-3.5 mr-1" /> Runs
                  </Link>
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => wf.id && setCurlForId(wf.id)}
                  disabled={!wf.id}
                >
                  <Terminal className="w-3.5 h-3.5 mr-1" /> Copy curl
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    if (!wf.id) return;
                    const confirmed = window.confirm(`Delete "${wf.name}" and all its runs/activity history?`);
                    if (!confirmed) return;
                    remove.mutate(wf.id);
                  }}
                >
                  Delete
                </Button>
              </div>
            </div>
            );
          })}
        </div>
      )}

      <CopyCurlDialog
        processDef={
          curlForId
            ? (list.data ?? []).find((wf) => wf.id === curlForId) ?? null
            : null
        }
        open={!!curlForId}
        onOpenChange={(o) => !o && setCurlForId(null)}
      />

      <RunWorkflowDialog
        processDef={
          runDialogForId
            ? (list.data ?? []).find((wf) => wf.id === runDialogForId) ?? null
            : null
        }
        open={!!runDialogForId}
        onOpenChange={(o) => !o && setRunDialogForId(null)}
        onConfirm={(initialVariables) => {
          if (!runDialogForId) return;
          startRun.mutate({ id: runDialogForId, initialVariables });
        }}
        submitting={startRun.isPending}
      />
    </div>
  );
}

/**
 * Renders the per-workflow metadata strip (success rate, average latency,
 * AI-node count, embedding presence). Self-contained so the index page stays
 * cheap to scan; no actions, no popovers — just at-a-glance signals.
 */
function MetadataBadges({ meta }: { meta: ProcessDefMetadata }) {
  const runs = meta.allTime.totalRuns;
  if (runs === 0 && !meta.hasEmbedding) {
    return (
      <div className="text-[11px] text-muted-foreground mt-1 italic">
        No runs yet
      </div>
    );
  }
  const rate = meta.allTime.successRate;
  const avgMs = meta.allTime.avgLatencyMs;
  const aiNodeCount = meta.aiNodes?.length ?? 0;
  return (
    <div className="flex flex-wrap items-center gap-2 mt-1.5">
      {runs > 0 && (
        <span className="inline-flex items-center gap-1 rounded-md border bg-muted/40 px-1.5 py-0.5 text-[11px] text-muted-foreground">
          <Activity className="w-3 h-3" />
          {runs} run{runs === 1 ? "" : "s"}
          {rate != null && <span className="ml-1">· {Math.round(rate * 100)}% ok</span>}
        </span>
      )}
      {avgMs != null && avgMs > 0 && (
        <span className="inline-flex items-center gap-1 rounded-md border bg-muted/40 px-1.5 py-0.5 text-[11px] text-muted-foreground">
          ~{formatLatency(avgMs)}
        </span>
      )}
      {aiNodeCount > 0 && (
        <span className="inline-flex items-center gap-1 rounded-md border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-800 dark:border-amber-900/40 dark:bg-amber-900/20 dark:text-amber-200">
          <Sparkles className="w-3 h-3" />
          {aiNodeCount} AI node{aiNodeCount === 1 ? "" : "s"}
        </span>
      )}
      {meta.hasEmbedding && (
        <span
          title="Indexed for intent matching"
          className="inline-flex items-center gap-1 rounded-md border border-emerald-200 bg-emerald-50 px-1.5 py-0.5 text-[11px] text-emerald-800 dark:border-emerald-900/40 dark:bg-emerald-900/20 dark:text-emerald-200"
        >
          <Database className="w-3 h-3" />
          indexed
        </span>
      )}
    </div>
  );
}

function formatLatency(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}
