import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { workflowRunsApi } from "@/services/api";

interface Props {
  open: boolean;
  onClose: () => void;
  workflowId: string;
  onStarted: (instanceId: string) => void;
}

export function SubmitOrderDialog({ open, onClose, workflowId, onStarted }: Props) {
  const [orderId, setOrderId] = useState("");
  const qc = useQueryClient();

  const mutation = useMutation({
    mutationFn: () =>
      workflowRunsApi.start({
        processDefId: workflowId,
        initialVariables: { orderId: orderId.trim() },
        requesterId: "order-validation-ui",
      }),
    onSuccess: (run) => {
      qc.invalidateQueries({ queryKey: ["orderQueue"] });
      qc.invalidateQueries({ queryKey: ["dashboard"] });
      onStarted(run.instanceId);
      setOrderId("");
      onClose();
    },
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!orderId.trim()) return;
    mutation.mutate();
  };

  return (
    <Dialog
      open={open}
      onClose={() => {
        if (!mutation.isPending) onClose();
      }}
      title="Submit order for validation"
      description="Triggers the selected workflow asynchronously. The run will appear in the queue shortly."
    >
      <form onSubmit={submit} className="space-y-4">
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-foreground/80">Order ID</span>
          <Input
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            placeholder="e.g. 600030510"
            autoFocus
            disabled={mutation.isPending}
          />
        </label>
        {mutation.error && (
          <div className="text-xs text-error">
            {(mutation.error as Error).message}
          </div>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button type="submit" disabled={!orderId.trim() || mutation.isPending}>
            {mutation.isPending ? "Submitting…" : "Submit"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
