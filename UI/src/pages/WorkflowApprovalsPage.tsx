// Approvals queue. Lists every suspended workflow run waiting for human
// review. Approve/Reject hits the new /api/v1/workflow/approvals endpoints,
// which write the decision into the run's variable scope and resume it.

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Check, ChevronRight, Loader2, RefreshCw, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { workflowApi } from "@/services/workflowApi";
import { getAuthUser } from "@/services/api";
import { toast } from "sonner";
import type { PendingApproval } from "@/types/workflow";

function formatRelative(ts: number): string {
  const diff = Date.now() - ts;
  if (diff < 60_000) return "just now";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

export default function WorkflowApprovalsPage() {
  const qc = useQueryClient();
  const user = useMemo(() => getAuthUser(), []);
  const [comment, setComment] = useState<Record<string, string>>({});

  const list = useQuery({
    queryKey: ["workflow-approvals"],
    queryFn: () => workflowApi.approvals.list(50),
    refetchInterval: 3000,
  });

  const decide = useMutation({
    mutationFn: async ({ id, kind }: { id: string; kind: "approve" | "reject" }) => {
      const fn = kind === "approve" ? workflowApi.approvals.approve : workflowApi.approvals.reject;
      return fn(id, user?.email ?? null, comment[id] ?? null);
    },
    onSuccess: (r, vars) => {
      toast.success(
        `${vars.kind === "approve" ? "Approved" : "Rejected"} — run is ${r.state}`,
      );
      setComment((c) => ({ ...c, [vars.id]: "" }));
      qc.invalidateQueries({ queryKey: ["workflow-approvals"] });
      qc.invalidateQueries({ queryKey: ["workflow-run", r.instanceId] });
      qc.invalidateQueries({ queryKey: ["executions"] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Decision failed"),
  });

  const pending: PendingApproval[] = list.data?.pending ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <h1 className="text-2xl font-semibold">Approvals</h1>
        {list.isFetching && (
          <Loader2 className="w-3.5 h-3.5 animate-spin text-muted-foreground" />
        )}
        <span className="text-xs text-muted-foreground">
          {list.data?.total ?? 0} pending
        </span>
        <Button
          variant="outline"
          size="sm"
          className="ml-auto"
          onClick={() => list.refetch()}
        >
          <RefreshCw className="w-3.5 h-3.5 mr-1" /> Refresh
        </Button>
      </div>

      {pending.length === 0 ? (
        <div className="border rounded-md bg-background p-10 text-center text-sm text-muted-foreground">
          No pending approvals. Set <code className="bg-muted px-1 rounded">requireApproval: true</code> on
          a manual activity in the designer to suspend a run for review.
        </div>
      ) : (
        <ScrollArea className="max-h-[calc(100vh-200px)]">
          <ul className="space-y-3">
            {pending.map((p) => (
              <li key={p.id} className="border rounded-md bg-background p-4 flex flex-col gap-3">
                <div className="flex items-start gap-3">
                  <div className="flex flex-col flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      {p.defId ? (
                        <Link
                          to={`/workflows/${p.defId}/designer`}
                          className="font-medium hover:underline"
                        >
                          {p.workflowName ?? p.defId}
                        </Link>
                      ) : (
                        <span className="font-medium">{p.workflowName ?? "(unknown workflow)"}</span>
                      )}
                      <ChevronRight className="w-3 h-3 text-muted-foreground" />
                      <span className="text-xs font-mono text-muted-foreground">
                        {p.activityDefId}
                      </span>
                    </div>
                    <div className="text-xs text-muted-foreground mt-1">
                      Run{" "}
                      <Link
                        to={`/workflows/runs/${p.instId}`}
                        className="font-mono hover:underline"
                      >
                        {p.instId.slice(0, 8)}…
                      </Link>{" "}
                      · requested by {p.requestedBy ?? "(system)"} · {formatRelative(p.requestedAt)}
                    </div>
                    {p.reason && (
                      <div className="mt-2 text-sm bg-muted/40 rounded p-2 italic">{p.reason}</div>
                    )}
                  </div>
                </div>
                <Input
                  value={comment[p.id] ?? ""}
                  onChange={(e) => setComment((c) => ({ ...c, [p.id]: e.target.value }))}
                  placeholder="Comment (optional)"
                  className="h-8 text-sm"
                />
                <div className="flex gap-2 justify-end">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => decide.mutate({ id: p.id, kind: "reject" })}
                    disabled={decide.isPending}
                  >
                    <X className="w-3.5 h-3.5 mr-1" /> Reject
                  </Button>
                  <Button
                    size="sm"
                    onClick={() => decide.mutate({ id: p.id, kind: "approve" })}
                    disabled={decide.isPending}
                  >
                    <Check className="w-3.5 h-3.5 mr-1" /> Approve
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        </ScrollArea>
      )}
    </div>
  );
}
