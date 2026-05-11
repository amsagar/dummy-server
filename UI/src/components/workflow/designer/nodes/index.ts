// xyflow nodeTypes registry. Maps the activity-type strings used by the
// serializer (`tool`, `route`, `subflow`, `normal`) to the React components
// that render them on the canvas.

import { TaskNode } from "./TaskNode";
import { RouteNode } from "./RouteNode";
import { SubflowNode } from "./SubflowNode";

export const workflowNodeTypes = {
  tool: TaskNode,
  normal: TaskNode, // for now, render normal (manual) activities as TaskNode too
  route: RouteNode,
  subflow: SubflowNode,
  foreach: TaskNode,
  while: TaskNode,
  batch: TaskNode,
};
