import { Handle, Position, useReactFlow, type NodeProps } from "@xyflow/react";
import { SquareStack, X } from "lucide-react";
import { cn } from "@/lib/utils";
import type { BoardNodeData } from "@/lib/workflowSerializer";
import { deleteNodeAndEdges } from "./deleteNode";
import {
  useExecutionStatus,
  useInExecutionView,
} from "@/components/workflow/run/ExecutionStatusContext";
import {
  ExecutionStatusBadge,
  nodeStateRing,
} from "@/components/workflow/run/ExecutionStatusBadge";

export function SubflowNode({ id, data, selected }: NodeProps) {
  const board = data as BoardNodeData;
  const flow = useReactFlow();
  const label = board.label ?? "Subflow";
  const target = board.subflowDefId ?? "(no target)";

  const inExecution = useInExecutionView();
  const activity = useExecutionStatus(id);
  const ring = inExecution ? nodeStateRing(activity) : "";

  return (
    <div
      className={cn(
        "group relative min-w-[180px] rounded-md border-2 border-dashed bg-background shadow-sm flex items-center gap-2 px-3 py-2",
        selected && "ring-2 ring-primary",
        ring,
      )}
    >
      <Handle type="target" position={Position.Left} className="!bg-muted-foreground" />
      <div className="rounded bg-violet-100 dark:bg-violet-950 text-violet-700 dark:text-violet-300 p-1.5">
        <SquareStack className="w-4 h-4" />
      </div>
      <div className="flex flex-col leading-tight overflow-hidden">
        <span className="text-sm font-medium truncate">{label}</span>
        <span className="text-[11px] text-muted-foreground truncate">→ {target}</span>
      </div>
      {inExecution ? (
        <ExecutionStatusBadge activity={activity} />
      ) : (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            deleteNodeAndEdges(flow, id);
          }}
          title="Delete node"
          aria-label="Delete node"
          className="absolute -top-2 -right-2 w-5 h-5 rounded-full border bg-background text-rose-600 shadow-sm opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center hover:bg-rose-50 dark:hover:bg-rose-950"
        >
          <X className="w-3 h-3" />
        </button>
      )}
      <Handle type="source" position={Position.Right} className="!bg-muted-foreground" />
    </div>
  );
}
