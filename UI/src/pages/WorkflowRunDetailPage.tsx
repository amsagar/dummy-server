// Run detail. Default landing tab is the Canvas — read-only graph with the
// run's status overlay, just like the per-workflow execution browser. The
// other tabs are: Overview cards · Activities (filterable left rail + IO) ·
// Timeline (Gantt) · Audit · JSON. Polls every 2 s while the run is open.

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Loader2, RefreshCw, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { workflowApi } from "@/services/workflowApi";
import { ActivityList } from "@/components/workflow/run/ActivityList";
import { AuditTab } from "@/components/workflow/run/AuditTab";
import { ExecutionCanvas } from "@/components/workflow/run/ExecutionCanvas";
import { IOPanel } from "@/components/workflow/run/IOPanel";
import { JsonTab } from "@/components/workflow/run/JsonTab";
import { OverviewTab } from "@/components/workflow/run/OverviewTab";
import { RunGantt } from "@/components/workflow/run/RunGantt";
import { StateBadge } from "@/components/workflow/StateBadge";
import { getAuthUser } from "@/services/api";
import type {
  ActivityInst,
  AuditTrailEntry,
  ProcessDef,
  ProcessState,
  RunSummary,
} from "@/types/workflow";
import { toast } from "sonner";

function isOpen(state: ProcessState | string | null | undefined): boolean {
  if (!state) return true;
  return state.startsWith("open");
}

export default function WorkflowRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const user = useMemo(() => getAuthUser(), []);

  const summary = useQuery<RunSummary>({
    queryKey: ["workflow-run", runId],
    queryFn: () => workflowApi.runs.get(runId!),
    enabled: !!runId,
    refetchInterval: (q) => (isOpen(q.state.data?.state) ? 2000 : false),
  });
  const activities = useQuery<ActivityInst[]>({
    queryKey: ["workflow-activities", runId],
    queryFn: () => workflowApi.runs.activities(runId!),
    enabled: !!runId,
    refetchInterval: (q) => (isOpen(summary.data?.state) ? 2000 : (q.state.data ? false : 2000)),
  });
  const audit = useQuery<AuditTrailEntry[]>({
    queryKey: ["workflow-audit", runId],
    queryFn: () => workflowApi.runs.audit(runId!),
    enabled: !!runId,
    refetchInterval: (q) => (isOpen(summary.data?.state) ? 2000 : (q.state.data ? false : 2000)),
  });

  const defId = summary.data?.defId ?? null;
  const def = useQuery<ProcessDef>({
    queryKey: ["workflow", defId],
    queryFn: () => workflowApi.processes.get(defId!),
    enabled: !!defId,
    staleTime: 60_000,
  });

  const replay = useMutation({
    mutationFn: () => workflowApi.runs.replay(runId!, user?.email ?? null),
    onSuccess: (r) => {
      toast.success("Replay started");
      navigate(`/workflows/runs/${r.instanceId}`);
    },
    onError: (e: Error) => toast.error(e.message ?? "Replay failed"),
  });

  const rerunFrom = useMutation({
    mutationFn: (activityDefId: string) =>
      workflowApi.runs.rerunFrom(runId!, activityDefId, user?.email ?? null),
    onSuccess: (r) => {
      toast.success(`Re-run from node started — ${r.state}`);
      navigate(`/workflows/runs/${r.instanceId}`);
    },
    onError: (e: Error) => toast.error(e.message ?? "Re-run failed"),
  });

  // Two selection states: by activity_inst.id (for the Activities tab list)
  // and by activity_def_id (for the Canvas, since defs are unique per workflow).
  const [selectedActivityId, setSelectedActivityId] = useState<string | null>(null);
  const [selectedActivityDefId, setSelectedActivityDefId] = useState<string | null>(null);

  // When activities first arrive, default to the failing activity, else the first.
  useEffect(() => {
    if (selectedActivityId || selectedActivityDefId) return;
    const list = activities.data ?? [];
    if (list.length === 0) return;
    const failed = list.find(
      (a) => a.state === "failed" || a.state === "deadline_breached" || a.state === "cancelled",
    );
    const pick = failed ?? list[0];
    setSelectedActivityId(pick.id);
    setSelectedActivityDefId(pick.activityDefId);
  }, [activities.data, selectedActivityId, selectedActivityDefId]);

  // Activity-instance row for the IO panel (last attempt of the selected def).
  const selected = useMemo(() => {
    if (!selectedActivityDefId) {
      return (activities.data ?? []).find((a) => a.id === selectedActivityId) ?? null;
    }
    let pick: ActivityInst | null = null;
    for (const a of activities.data ?? []) {
      if (a.activityDefId !== selectedActivityDefId) continue;
      if (!pick || (a.startedAt ?? 0) >= (pick.startedAt ?? 0)) pick = a;
    }
    return pick ?? (activities.data ?? []).find((a) => a.id === selectedActivityId) ?? null;
  }, [activities.data, selectedActivityDefId, selectedActivityId]);

  if (summary.isLoading) {
    return (
      <div className="p-6 flex items-center gap-2 text-muted-foreground">
        <Loader2 className="animate-spin w-4 h-4" /> Loading run…
      </div>
    );
  }
  if (summary.error) {
    return <div className="p-6 text-red-600">Error: {(summary.error as Error).message}</div>;
  }
  const s = summary.data!;

  return (
    <div className="flex flex-col h-screen">
      <header className="border-b p-3 flex items-center gap-3">
        <Link
          to={s.defId ? `/workflows/${s.defId}/runs` : "/executions"}
          className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="w-4 h-4 mr-1" /> Back to runs
        </Link>
        <div className="flex flex-col leading-tight">
          <div className="flex items-center gap-2">
            <h1 className="text-base font-semibold">{def.data?.name ?? "Run"}</h1>
            <StateBadge state={s.state} />
          </div>
          <div className="text-xs text-muted-foreground">
            <span className="font-mono">{runId?.slice(0, 8)}…</span>
            {s.defId && (
              <>
                {" · "}
                <Link to={`/workflows/${s.defId}/designer`} className="underline">
                  Open in designer
                </Link>
              </>
            )}
          </div>
        </div>
        <div className="ml-auto flex gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              summary.refetch();
              activities.refetch();
              audit.refetch();
              if (def.data) def.refetch();
            }}
          >
            <RefreshCw className="w-3.5 h-3.5 mr-1" /> Refresh
          </Button>
          <Button size="sm" onClick={() => replay.mutate()} disabled={replay.isPending}>
            {replay.isPending ? (
              <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" />
            ) : (
              <RotateCcw className="w-3.5 h-3.5 mr-1" />
            )}
            Replay
          </Button>
        </div>
      </header>

      <Tabs defaultValue="canvas" className="flex-1 flex flex-col overflow-hidden">
        <TabsList className="m-2 grid grid-cols-6 max-w-3xl">
          <TabsTrigger value="canvas">Canvas</TabsTrigger>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="activities">Activities</TabsTrigger>
          <TabsTrigger value="timeline">Timeline</TabsTrigger>
          <TabsTrigger value="audit">Audit</TabsTrigger>
          <TabsTrigger value="json">JSON</TabsTrigger>
        </TabsList>

        <div className="flex-1 overflow-hidden">
          {/* Canvas tab — n8n-style read-only canvas with status overlay,
              IOPanel below for whichever node is selected. */}
          <TabsContent value="canvas" className="m-0 h-full">
            {!def.data ? (
              <div className="p-6 flex items-center gap-2 text-muted-foreground text-sm">
                <Loader2 className="w-4 h-4 animate-spin" /> Loading workflow definition…
              </div>
            ) : (
              <div className="flex flex-col h-full">
                <div className="flex-1 relative border-b">
                  <ExecutionCanvas
                    def={def.data}
                    activities={activities.data ?? []}
                    selectedActivityDefId={selectedActivityDefId}
                    onSelectActivity={(defId) => {
                      setSelectedActivityDefId(defId);
                      const a = (activities.data ?? []).find((x) => x.activityDefId === defId);
                      if (a) setSelectedActivityId(a.id);
                    }}
                  />
                </div>
                <div className="h-[40%] min-h-[240px] flex flex-col">
                  {selected ? (
                    <>
                      <div className="px-3 py-2 border-b flex items-center gap-2 text-xs bg-muted/40">
                        <span className="font-mono">{selected.activityDefId}</span>
                        <StateBadge state={selected.state} />
                        <Button
                          variant="outline"
                          size="sm"
                          className="ml-auto h-7 text-xs"
                          onClick={() => rerunFrom.mutate(selected.activityDefId)}
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
                        <IOPanel activity={selected} />
                      </div>
                    </>
                  ) : (
                    <div className="p-6 text-muted-foreground text-sm">
                      Click a node on the canvas to inspect its input, output, and any error.
                    </div>
                  )}
                </div>
              </div>
            )}
          </TabsContent>

          <TabsContent value="overview" className="m-0 h-full overflow-auto p-4">
            <OverviewTab summary={s} activities={activities.data ?? []} />
          </TabsContent>

          <TabsContent value="activities" className="m-0 h-full">
            <div className="flex h-full">
              <ActivityList
                activities={activities.data ?? []}
                selectedId={selectedActivityId}
                onSelect={(aid) => {
                  setSelectedActivityId(aid);
                  const a = (activities.data ?? []).find((x) => x.id === aid);
                  if (a) setSelectedActivityDefId(a.activityDefId);
                }}
              />
              <div className="flex-1 overflow-hidden">
                {selected ? (
                  <IOPanel activity={selected} />
                ) : (
                  <div className="p-6 text-muted-foreground text-sm">
                    Select an activity from the list to inspect its input, output, and any error.
                  </div>
                )}
              </div>
            </div>
          </TabsContent>

          <TabsContent value="timeline" className="m-0 h-full overflow-auto p-4">
            <RunGantt
              activities={activities.data ?? []}
              selectedId={selectedActivityId}
              onSelect={(aid) => {
                setSelectedActivityId(aid);
                const a = (activities.data ?? []).find((x) => x.id === aid);
                if (a) setSelectedActivityDefId(a.activityDefId);
              }}
              runStartedAt={s.startedAt}
              runEndedAt={s.endedAt}
            />
          </TabsContent>

          <TabsContent value="audit" className="m-0 h-full overflow-auto p-4">
            <AuditTab entries={audit.data ?? []} />
          </TabsContent>

          <TabsContent value="json" className="m-0 h-full p-4">
            <JsonTab summary={s} activities={activities.data ?? []} audit={audit.data ?? []} runId={runId!} />
          </TabsContent>
        </div>
      </Tabs>
    </div>
  );
}
