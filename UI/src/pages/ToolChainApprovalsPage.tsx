import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { toast } from "sonner";

export default function ToolChainApprovalsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data } = useQuery<any>({
    queryKey: ["toolchain-approvals"],
    queryFn: () => api.toolchains.approvals(),
    refetchInterval: 3000,
  });
  const pending = data?.pending || [];

  const approveMutation = useMutation({
    mutationFn: ({ runId, nodeId }: { runId: string; nodeId: string }) => api.toolchains.approveStep(runId, nodeId, {}),
    onSuccess: () => {
      toast.success("Step approved");
      queryClient.invalidateQueries({ queryKey: ["toolchain-approvals"] });
    },
    onError: (e: any) => toast.error(e.message || "Failed to approve"),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ runId, nodeId }: { runId: string; nodeId: string }) => api.toolchains.rejectStep(runId, nodeId, {}),
    onSuccess: () => {
      toast.success("Step rejected");
      queryClient.invalidateQueries({ queryKey: ["toolchain-approvals"] });
    },
    onError: (e: any) => toast.error(e.message || "Failed to reject"),
  });

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-xl font-semibold text-[#123262]">ToolChain Approvals</h2>
        <p className="text-sm text-slate-600">Runs waiting for review appear here. Approve/reject to continue.</p>
      </div>
      <div>
        <Button variant="outline" onClick={() => navigate("/toolchains")}>Back to ToolChains</Button>
      </div>
      {pending.length === 0 ? (
        <Card className="p-4 text-sm text-slate-500">No pending approvals.</Card>
      ) : (
        <div className="space-y-3">
          {pending.map((item: any) => (
            <Card key={item.id} className="space-y-2 p-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-semibold">Run {item.runId}</p>
                  <p className="text-xs text-slate-500">Node {item.nodeId} {item.approvalGroup ? `· ${item.approvalGroup}` : ""}</p>
                </div>
                <span className="text-xs uppercase text-amber-600">{item.status}</span>
              </div>
              <p className="text-sm text-slate-700">{item.prompt}</p>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => approveMutation.mutate({ runId: item.runId, nodeId: item.nodeId })}>Approve</Button>
                <Button size="sm" variant="outline" onClick={() => rejectMutation.mutate({ runId: item.runId, nodeId: item.nodeId })}>Reject</Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
