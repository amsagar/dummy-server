import { Handle, Position, useReactFlow, type NodeProps } from "@xyflow/react";
import { CircleDot, Flag, GitBranch, X } from "lucide-react";
import { cn } from "@/lib/utils";
import type { BoardNodeData } from "@/lib/workflowSerializer";
import { deleteNodeAndEdges } from "./deleteNode";
import {
  useExecutionStatus,
  useInExecutionView,
  useJoinProgress,
} from "@/components/workflow/run/ExecutionStatusContext";
import {
  ExecutionStatusBadge,
  nodeStateRing,
} from "@/components/workflow/run/ExecutionStatusBadge";

export function RouteNode({ id, data, selected }: NodeProps) {
  const board = data as BoardNodeData;
  const isStart = !!board.isStart;
  const isEnd = !!board.isEnd;
  const label = board.label ?? (isStart ? "Start" : isEnd ? "End" : "Decision");
  const Icon = isStart ? CircleDot : isEnd ? Flag : GitBranch;
  const tone = isStart
    ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300"
    : isEnd
      ? "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300"
      : "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300";

  const flow = useReactFlow();
  const inExecution = useInExecutionView();
  const activity = useExecutionStatus(id);
  const joinProgress = useJoinProgress(id);
  const ring = inExecution ? nodeStateRing(activity) : "";

  return (
    <div
      className={cn(
        "group relative min-w-[140px] rounded-full border bg-background shadow-sm flex items-center gap-2 px-3 py-2",
        selected && "ring-2 ring-primary",
        ring,
      )}
    >
      {!isStart && <Handle type="target" position={Position.Left} className="!bg-muted-foreground" />}
      <div className={cn("rounded-full p-1.5", tone)}>
        <Icon className="w-4 h-4" />
      </div>
      <span className="text-sm font-medium">{label}</span>
      {board.andJoin && (
        <span
          className="text-[10px] uppercase tracking-wide text-muted-foreground border rounded px-1 ml-auto"
          title={
            inExecution && joinProgress
              ? `AND-join: ${joinProgress.arrived} of ${joinProgress.total} arrived`
              : "AND-join: waits for all incoming transitions"
          }
        >
          {inExecution && joinProgress
            ? `AND ${joinProgress.arrived}/${joinProgress.total}`
            : "AND"}
        </span>
      )}
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
      {!isEnd && <Handle type="source" position={Position.Right} className="!bg-muted-foreground" />}
    </div>
  );
}
