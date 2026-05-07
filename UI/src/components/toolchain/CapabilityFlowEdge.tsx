import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from "@xyflow/react";

export default function CapabilityFlowEdge(props: EdgeProps) {
  const { sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition, style, markerEnd, data, label } =
    props;
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });
  const text = String((data as any)?.label || label || "").trim();
  return (
    <>
      <BaseEdge path={edgePath} markerEnd={markerEnd} style={style} />
      {text ? (
        <EdgeLabelRenderer>
          <div
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)` }}
            className="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-slate-700 shadow-sm"
          >
            {text}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
}
