// Shared delete helper used by every custom node type and by the inspector
// trash button. Removes the node and any edges connected to it from the
// xyflow store.

import type { ReactFlowInstance } from "@xyflow/react";

export function deleteNodeAndEdges(flow: ReactFlowInstance, nodeId: string) {
  flow.setNodes((nodes) => nodes.filter((n) => n.id !== nodeId));
  flow.setEdges((edges) => edges.filter((e) => e.source !== nodeId && e.target !== nodeId));
}
