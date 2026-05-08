import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";

function typeTone(type: string) {
  const normalized = String(type || "").toLowerCase();
  if (normalized === "start") return "border-emerald-300 bg-emerald-50 text-emerald-800";
  if (normalized === "end") return "border-rose-300 bg-rose-50 text-rose-800";
  if (normalized === "decision" || normalized === "switch") return "border-amber-300 bg-amber-50 text-amber-900";
  if (normalized === "parallel") return "border-amber-400 bg-amber-100 text-amber-900";
  if (normalized === "iterator") return "border-cyan-300 bg-cyan-50 text-cyan-900";
  if (normalized === "assign") return "border-sky-300 bg-sky-50 text-sky-900";
  if (normalized === "subchain") return "border-indigo-300 bg-indigo-50 text-indigo-900";
  if (normalized === "synthesis") return "border-violet-300 bg-violet-50 text-violet-900";
  return "border-slate-300 bg-white text-slate-900";
}

export default function CapabilityFlowNode({ data, selected }: NodeProps) {
  const label = String((data as any)?.label || "");
  const nodeType = String((data as any)?.nodeType || "");
  const normalizedType = nodeType.toLowerCase();
  const capability = String((data as any)?.capability || nodeType);
  const needsApproval = Boolean((data as any)?.needsApproval);
  const branchCount = Number((data as any)?.branchCount || 0);
  const isLoop = normalizedType === "iterator";
  const isSynthesis = normalizedType === "synthesis";
  const inner = (
    <div
      className={cn(
        "min-w-[190px] rounded-md border p-2 shadow-sm transition-colors",
        typeTone(nodeType),
        selected ? "ring-2 ring-blue-400" : ""
      )}
    >
      <Handle type="target" position={Position.Left} />
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-1">
          <div className="truncate text-sm font-semibold">{label}</div>
          {isSynthesis ? (
            <Sparkles className="h-3 w-3 shrink-0 text-violet-600" aria-label="AI synthesis" />
          ) : null}
        </div>
        <span className="rounded bg-white/70 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide">
          {capability}
        </span>
      </div>
      <div className="mt-1 flex items-center gap-1 text-[11px] text-slate-600">
        <span className="font-mono">{nodeType}</span>
        {branchCount > 0 ? <span>• {branchCount} branches</span> : null}
        {needsApproval ? <span>• approval</span> : null}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
  if (isLoop) {
    return (
      <div className="rounded-lg border-2 border-dashed border-cyan-400 bg-cyan-50/30 p-1.5">
        {inner}
      </div>
    );
  }
  return inner;
}
