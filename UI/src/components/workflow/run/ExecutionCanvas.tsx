// Read-only xyflow canvas for the per-workflow execution browser. Renders
// the workflow definition with the chosen run's activity statuses overlaid
// onto each node, and bolds / dims the edges based on whether they fired.
//
// The same TaskNode/RouteNode/SubflowNode components are used as in the
// designer; they read the ExecutionStatusContext when present and switch
// from the editor-style affordances (delete button on hover) to status
// badges and colored rings.

import { useEffect, useMemo, useRef } from "react";
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  deserializeProcessDef,
  type BoardEdgeData,
  type BoardNodeData,
} from "@/lib/workflowSerializer";
import { getOrComputeLayout } from "@/lib/workflowLayout";
import { workflowNodeTypes } from "@/components/workflow/designer/nodes";
import { ExecutionStatusProvider } from "./ExecutionStatusContext";
import type { ActivityInst, ProcessDef } from "@/types/workflow";

interface ExecutionCanvasProps {
  def: ProcessDef;
  activities: ActivityInst[];
  selectedActivityDefId: string | null;
  onSelectActivity: (activityDefId: string) => void;
}

export function ExecutionCanvas(props: ExecutionCanvasProps) {
  return (
    <ReactFlowProvider>
      <ExecutionCanvasInner {...props} />
    </ReactFlowProvider>
  );
}

function ExecutionCanvasInner({
  def,
  activities,
  selectedActivityDefId,
  onSelectActivity,
}: ExecutionCanvasProps) {
  const flow = useReactFlow();
  const lastRunSig = useRef<string>("");

  // Build nodes/edges + overlay metadata.
  const { nodes, edges, joinProgress, ranActivityIds } = useMemo(() => {
    const { nodes: rawNodes, edges: rawEdges } = deserializeProcessDef(def);
    const layout = getOrComputeLayout(def.id, rawNodes, rawEdges, { persist: false });

    // Last attempt per activity_def_id.
    const lastAttempt = new Map<string, ActivityInst>();
    for (const a of activities) {
      const prev = lastAttempt.get(a.activityDefId);
      if (!prev || (a.startedAt ?? 0) >= (prev.startedAt ?? 0)) {
        lastAttempt.set(a.activityDefId, a);
      }
    }
    const ran = new Set<string>(lastAttempt.keys());

    // Incoming edges per node, used for join progress + edge highlighting.
    const incoming = new Map<string, string[]>();
    for (const t of def.transitions) {
      if (!incoming.has(t.toActivityId)) incoming.set(t.toActivityId, []);
      incoming.get(t.toActivityId)!.push(t.fromActivityId);
    }
    const joinProgress = new Map<string, { arrived: number; total: number }>();
    for (const a of def.activities) {
      if (!a.andJoin) continue;
      const sources = incoming.get(a.id) ?? [];
      if (sources.length <= 1) continue;
      let arrived = 0;
      for (const src of sources) {
        const ai = lastAttempt.get(src);
        if (ai && ai.state === "completed") arrived++;
      }
      joinProgress.set(a.id, { arrived, total: sources.length });
    }

    // Style + layout each node.
    const styledNodes: Node<BoardNodeData>[] = rawNodes.map((n) => ({
      ...n,
      draggable: false,
      connectable: false,
      selectable: true,
      selected: n.id === selectedActivityDefId,
      position: layout[n.id] ?? { x: 0, y: 0 },
    }));

    // Style edges: bolded if both endpoints ran, dimmed otherwise. Error
    // edges that fired stay red; edges that are "error" but didn't fire
    // are dimmed below the success threshold.
    const styledEdges: Edge<BoardEdgeData>[] = rawEdges.map((e) => {
      const fromRan = ran.has(e.source);
      const toRan = ran.has(e.target);
      const fired = fromRan && toRan;
      const isError = !!e.data?.isErrorEdge;
      let stroke = "#94a3b8"; // slate-400 default
      if (fired) stroke = isError ? "#ef4444" : "#10b981"; // rose-500 / emerald-500
      return {
        ...e,
        animated: fired && lastAttempt.get(e.target)?.state === "running",
        style: {
          stroke,
          strokeWidth: fired ? 2.5 : 1,
          opacity: fired ? 1 : 0.35,
        },
      };
    });

    return { nodes: styledNodes, edges: styledEdges, joinProgress, ranActivityIds: ran };
  }, [def, activities, selectedActivityDefId]);

  // Recenter when a new run is selected (different last-run-signature).
  useEffect(() => {
    const sig =
      activities.length === 0
        ? def.id ?? ""
        : `${def.id}:${activities[0]?.instId}:${activities.length}`;
    if (sig !== lastRunSig.current) {
      lastRunSig.current = sig;
      // Wait one tick so xyflow has the new nodes.
      const t = setTimeout(() => flow.fitView({ padding: 0.2, duration: 250 }), 50);
      return () => clearTimeout(t);
    }
  }, [activities, def.id, flow]);

  // ranActivityIds is computed for future "didn't run" badges; suppress unused warning.
  void ranActivityIds;

  return (
    <ExecutionStatusProvider
      activities={activities}
      joinProgressByActivityId={joinProgress}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={workflowNodeTypes}
        onNodeClick={(_, n) => onSelectActivity(n.id)}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        fitView
        proOptions={{ hideAttribution: true }}
      >
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
    </ExecutionStatusProvider>
  );
}
