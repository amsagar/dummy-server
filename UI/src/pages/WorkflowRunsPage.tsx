// Per-workflow execution browser — n8n-style split panel:
//
//   ┌──────────────┬─────────────────────────────────────────────┐
//   │ runs list    │  read-only canvas with status overlay       │
//   │ (filterable) │                                              │
//   │              ├─────────────────────────────────────────────┤
//   │              │  Tabs: Selected node IO · Timeline · Audit  │
//   └──────────────┴─────────────────────────────────────────────┘
//
// Click a run on the left → canvas paints with that run's activity statuses.
// Click a node on the canvas → bottom right shows IO for the selected
// activity in that run. Click a Gantt bar → same. Sub-flow node click →
// fetches child runs and offers drill-in. "Re-run from here" pins upstream
// outputs and starts a new run that begins at the selected node.

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft,
  ArrowUpRight,
  Loader2,
  Play,
  RotateCcw,
  Workflow as WorkflowIcon,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { ScrollArea } from "@/components/ui/scroll-area";
import { workflowApi } from "@/services/workflowApi";
import { ExecutionCanvas } from "@/components/workflow/run/ExecutionCanvas";
import { ExecutionRunList } from "@/components/workflow/run/ExecutionRunList";
import { IOPanel } from "@/components/workflow/run/IOPanel";
import { RunGantt } from "@/components/workflow/run/RunGantt";
import { AuditTab } from "@/components/workflow/run/AuditTab";
import { RunWorkflowDialog } from "@/components/workflow/RunWorkflowDialog";
import { StateBadge } from "@/components/workflow/StateBadge";
import { toast } from "sonner";
import { getAuthUser } from "@/services/api";
import type {
  ActivityInst,
  AuditTrailEntry,
  ProcessDef,
  RunSummary,
} from "@/types/workflow";

function isOpen(state: string | null | undefined): boolean {
  return !!state && state.startsWith("open");
}

export default function WorkflowRunsPage() {
  const { id = "" } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const user = useMemo(() => getAuthUser(), []);

  const def = useQuery<ProcessDef>({
    queryKey: ["workflow", id],
    queryFn: () => workflowApi.processes.get(id),
    enabled: !!id,
  });

  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedActivityDefId, setSelectedActivityDefId] = useState<string | null>(null);
  const [runDialogOpen, setRunDialogOpen] = useState(false);

  const runSummary = useQuery<RunSummary>({
    queryKey: ["workflow-run", selectedRunId],
    queryFn: () => workflowApi.runs.get(selectedRunId!),
    enabled: !!selectedRunId,
    refetchInterval: (q) => (isOpen(q.state.data?.state) ? 2000 : false),
  });

  const activities = useQuery<ActivityInst[]>({
    queryKey: ["workflow-activities", selectedRunId],
    queryFn: () => workflowApi.runs.activities(selectedRunId!),
    enabled: !!selectedRunId,
    refetchInterval: (q) => (isOpen(runSummary.data?.state) ? 2000 : (q.state.data ? false : 2000)),
  });

  const audit = useQuery<AuditTrailEntry[]>({
    queryKey: ["workflow-audit", selectedRunId],
    queryFn: () => workflowApi.runs.audit(selectedRunId!),
    enabled: !!selectedRunId,
    refetchInterval: (q) => (isOpen(runSummary.data?.state) ? 2000 : (q.state.data ? false : 2000)),
  });

  const subflows = useQuery({
    queryKey: ["workflow-subflows", selectedRunId],
    queryFn: () => workflowApi.runs.subflows(selectedRunId!),
    enabled: !!selectedRunId,
    refetchInterval: (q) => (isOpen(runSummary.data?.state) ? 4000 : (q.state.data ? false : 4000)),
  });

  // When the selected activity_def_id is a subflow, we group child runs by it.
  const subflowChildrenByActivity = useMemo(() => {
    const map = new Map<string, typeof subflows.data extends { children: infer C } ? C : never>();
    void map;
    // We don't have an activity-id ↔ child-instance link in process_inst rows
    // today; the simplest mapping is "all children, regardless of which
    // subflow node spawned them". In practice users have at most a couple of
    // subflows per workflow, so listing them all on the subflow tab is fine.
    return subflows.data?.children ?? [];
  }, [subflows.data]);

  // Reset activity selection when run changes.
  useEffect(() => {
    setSelectedActivityDefId(null);
  }, [selectedRunId]);

  // When activities arrive, default-select the failing or first activity.
  useEffect(() => {
    if (selectedActivityDefId) return;
    const list = activities.data ?? [];
    if (list.length === 0) return;
    const failed = list.find(
      (a) => a.state === "failed" || a.state === "deadline_breached" || a.state === "cancelled",
    );
    setSelectedActivityDefId((failed ?? list[0]).activityDefId);
  }, [activities.data, selectedActivityDefId]);

  // Lookup the activity_inst for the selected def_id within this run.
  const selectedActivity = useMemo(() => {
    if (!selectedActivityDefId) return null;
    // Last attempt wins.
    let pick: ActivityInst | null = null;
    for (const a of activities.data ?? []) {
      if (a.activityDefId !== selectedActivityDefId) continue;
      if (!pick || (a.startedAt ?? 0) >= (pick.startedAt ?? 0)) pick = a;
    }
    return pick;
  }, [activities.data, selectedActivityDefId]);

  const startRun = useMutation({
    // async=true so the API returns immediately while the run is still
    // open.running; polling on the right pane then shows live progress.
    mutationFn: (initialVariables: Record<string, unknown>) =>
      workflowApi.runs.start({ processDefId: id, initialVariables }, { async: true }),
    onSuccess: (r) => {
      toast.success("Run started");
      setSelectedRunId(r.instanceId);
      setRunDialogOpen(false);
      qc.invalidateQueries({ queryKey: ["wf-runs", id] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Run failed"),
  });

  const replay = useMutation({
    mutationFn: (instanceId: string) =>
      workflowApi.runs.replay(instanceId, user?.email ?? null),
    onSuccess: (r) => {
      toast.success("Replay started");
      setSelectedRunId(r.instanceId);
      qc.invalidateQueries({ queryKey: ["wf-runs", id] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Replay failed"),
  });

  const rerunFrom = useMutation({
    mutationFn: ({ runId, activityDefId }: { runId: string; activityDefId: string }) =>
      workflowApi.runs.rerunFrom(runId, activityDefId, user?.email ?? null),
    onSuccess: (r) => {
      toast.success(`Re-run from node started — ${r.state}`);
      setSelectedRunId(r.instanceId);
      qc.invalidateQueries({ queryKey: ["wf-runs", id] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Re-run failed"),
  });

  return (
    <div className="flex flex-col h-screen">
      <header className="border-b p-3 flex items-center gap-3">
        <Link
          to={`/workflows/${id}/designer`}
          className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="w-4 h-4 mr-1" /> Back to designer
        </Link>
        <div className="flex flex-col leading-tight">
          <h1 className="text-base font-semibold">{def.data?.name ?? "Workflow runs"}</h1>
          <span className="text-xs text-muted-foreground">
            v{def.data?.version} · {def.data?.activities.length ?? 0} activities
          </span>
        </div>
        <div className="ml-auto flex gap-2">
          {selectedRunId && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate(`/workflows/runs/${selectedRunId}`)}
            >
              Open run detail <ArrowUpRight className="w-3.5 h-3.5 ml-1" />
            </Button>
          )}
          {selectedRunId && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => replay.mutate(selectedRunId!)}
              disabled={replay.isPending}
            >
              {replay.isPending ? (
                <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" />
              ) : (
                <RotateCcw className="w-3.5 h-3.5 mr-1" />
              )}
              Replay
            </Button>
          )}
          <Button onClick={() => setRunDialogOpen(true)} disabled={!def.data} size="sm">
            <Play className="w-3.5 h-3.5 mr-1" /> Run
          </Button>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <ExecutionRunList
          workflowId={id}
          selectedRunId={selectedRunId}
          onSelectRun={(rid) => setSelectedRunId(rid)}
        />

        <div className="flex-1 flex flex-col overflow-hidden">
          {!def.data ? (
            <div className="flex-1 flex items-center justify-center text-muted-foreground text-sm">
              <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading workflow…
            </div>
          ) : !selectedRunId ? (
            <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground text-sm gap-2">
              <WorkflowIcon className="w-6 h-6" />
              <p>Select a run on the left to view its execution.</p>
              <p className="text-xs">Or click <strong>Run</strong> to start a new one.</p>
            </div>
          ) : (
            <>
              {/* Top: canvas */}
              <div className="flex-1 relative border-b">
                <div className="absolute top-2 left-2 z-10 flex items-center gap-2 bg-background/90 border rounded px-2 py-1 text-xs">
                  <span className="font-mono">{selectedRunId.slice(0, 8)}…</span>
                  <StateBadge state={runSummary.data?.state} />
                </div>
                <ExecutionCanvas
                  def={def.data}
                  activities={activities.data ?? []}
                  selectedActivityDefId={selectedActivityDefId}
                  onSelectActivity={setSelectedActivityDefId}
                />
              </div>

              {/* Bottom: tabs */}
              <Tabs defaultValue="selected" className="h-[40%] min-h-[260px] flex flex-col">
                <TabsList className="m-2 grid grid-cols-4 max-w-2xl">
                  <TabsTrigger value="selected">Selected node</TabsTrigger>
                  <TabsTrigger value="timeline">Timeline</TabsTrigger>
                  <TabsTrigger value="subflows">
                    Sub-flows
                    {subflowChildrenByActivity.length > 0 && (
                      <span className="ml-1 text-[10px] bg-muted rounded-full px-1.5">
                        {subflowChildrenByActivity.length}
                      </span>
                    )}
                  </TabsTrigger>
                  <TabsTrigger value="audit">Audit</TabsTrigger>
                </TabsList>
                <div className="flex-1 overflow-hidden">
                  <TabsContent value="selected" className="m-0 h-full">
                    {selectedActivity ? (
                      <div className="flex flex-col h-full">
                        <div className="px-3 py-2 border-b flex items-center gap-2 text-xs bg-muted/40">
                          <span className="font-mono">{selectedActivity.activityDefId}</span>
                          <StateBadge state={selectedActivity.state} />
                          <Button
                            variant="outline"
                            size="sm"
                            className="ml-auto h-7 text-xs"
                            onClick={() =>
                              rerunFrom.mutate({
                                runId: selectedRunId!,
                                activityDefId: selectedActivity.activityDefId,
                              })
                            }
                            disabled={rerunFrom.isPending}
                          >
                            {rerunFrom.isPending ? (
                              <Loader2 className="w-3 h-3 mr-1 animate-spin" />
                            ) : (
                              <RotateCcw className="w-3 h-3 mr-1" />
                            )}
                            Re-run from here
                          </Button>
                        </div>
                        <div className="flex-1 overflow-hidden">
                          <IOPanel activity={selectedActivity} />
                        </div>
                      </div>
                    ) : (
                      <p className="p-4 text-sm text-muted-foreground italic">
                        Click a node on the canvas to inspect its input, output, and any error.
                      </p>
                    )}
                  </TabsContent>
                  <TabsContent value="timeline" className="m-0 h-full overflow-auto p-3">
                    <RunGantt
                      activities={activities.data ?? []}
                      selectedId={selectedActivity?.id ?? null}
                      onSelect={(activityInstId) => {
                        const a = (activities.data ?? []).find((x) => x.id === activityInstId);
                        if (a) setSelectedActivityDefId(a.activityDefId);
                      }}
                      runStartedAt={runSummary.data?.startedAt}
                      runEndedAt={runSummary.data?.endedAt}
                    />
                  </TabsContent>
                  <TabsContent value="subflows" className="m-0 h-full">
                    <ScrollArea className="h-full p-3">
                      {subflowChildrenByActivity.length === 0 ? (
                        <p className="text-xs text-muted-foreground italic">
                          No sub-flow runs spawned by this run.
                        </p>
                      ) : (
                        <ul className="flex flex-col gap-2">
                          {subflowChildrenByActivity.map((child) => (
                            <li
                              key={child.id}
                              className="border rounded-md p-2 flex items-center gap-2 text-sm"
                            >
                              <StateBadge state={child.state} />
                              <span className="font-mono text-xs">{child.id.slice(0, 12)}…</span>
                              <span className="ml-auto flex gap-2">
                                <Link
                                  to={`/workflows/${child.defId}/runs`}
                                  className="text-xs hover:underline"
                                  onClick={() => setSelectedRunId(null)}
                                >
                                  Open child workflow
                                </Link>
                                <Link
                                  to={`/workflows/runs/${child.id}`}
                                  className="text-xs hover:underline"
                                >
                                  Run detail
                                </Link>
                              </span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </ScrollArea>
                  </TabsContent>
                  <TabsContent value="audit" className="m-0 h-full overflow-auto p-3">
                    <AuditTab entries={audit.data ?? []} />
                  </TabsContent>
                </div>
              </Tabs>
            </>
          )}
        </div>
      </div>

      <RunWorkflowDialog
        processDef={def.data ?? null}
        open={runDialogOpen}
        onOpenChange={setRunDialogOpen}
        onConfirm={(initialVariables) => startRun.mutate(initialVariables)}
        submitting={startRun.isPending}
      />
    </div>
  );
}
