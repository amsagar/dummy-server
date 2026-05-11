// React Flow ↔ Joget process definition serializer.
//
// The board uses @xyflow/react with custom node + edge data carrying the
// Joget activity / transition fields. This module converts between the
// React Flow shape and the wire ProcessDef the backend expects.
//
// Node `data` shape expected on the board:
//   {
//     activityType: ActivityType;        // "tool" | "route" | "subflow" | "normal"
//     pluginName?: string;
//     properties?: Record<string, unknown>;
//     deadlineExpression?: string;
//     isStart?: boolean;
//     isEnd?: boolean;
//     subflowDefId?: string;
//     subflowInputs?: Record<string, string>;
//     subflowOutputs?: Record<string, string>;
//     outputVariables?: VariableSpec[];
//     label?: string;                    // human name; falls back to id
//   }
//
// Edge `data` shape:
//   {
//     condition?: string;
//     isErrorEdge?: boolean;
//     matchesErrorClass?: ErrorClass;
//   }

import type { Edge, Node } from "@xyflow/react";
import type {
  Activity,
  ActivityType,
  ErrorClass,
  ProcessDef,
  Transition,
  VariableSpec,
} from "../types/workflow";

const ACTIVITY_TYPES: ReadonlySet<string> = new Set([
  "normal",
  "tool",
  "route",
  "subflow",
  "foreach",
  "while",
  "batch",
]);

// xyflow requires node.data and edge.data extend Record<string, unknown>; the
// index-signature member preserves that constraint while keeping our typed
// fields ergonomic.

export interface BoardNodeData {
  activityType?: ActivityType;
  pluginName?: string | null;
  properties?: Record<string, unknown>;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  deadlineExpression?: string | null;
  isStart?: boolean;
  isEnd?: boolean;
  subflowDefId?: string | null;
  subflowInputs?: Record<string, string>;
  subflowOutputs?: Record<string, string>;
  outputVariables?: VariableSpec[];
  andJoin?: boolean;
  errorPolicy?: {
    retryCount?: number;
    backoffMs?: number;
    timeoutMs?: number | null;
    failFast?: boolean;
    continueOnError?: boolean;
  };
  label?: string;
  [key: string]: unknown;
}

export interface BoardEdgeData {
  condition?: string | null;
  isErrorEdge?: boolean;
  matchesErrorClass?: ErrorClass | null;
  trigger?: "ON_SUCCESS" | "ON_NO_MATCH" | "ON_ERROR" | "ON_TIMEOUT" | "ON_VALIDATION_ERROR" | null;
  priority?: number | null;
  isDefault?: boolean;
  [key: string]: unknown;
}

export interface SerializeMeta {
  id?: string;
  name: string;
  version: string;
  packageId?: string | null;
  description?: string | null;
  variables?: VariableSpec[];
}

/**
 * Convert a React Flow board (nodes + edges) into the wire ProcessDef.
 * Throws if any node lacks a valid activity type or any edge references a
 * missing node — fail-fast at the boundary, mirroring the engine's
 * ProcessDefinition.build validation.
 */
export function serializeBoard(
  nodes: Array<Node<BoardNodeData>>,
  edges: Array<Edge<BoardEdgeData>>,
  meta: SerializeMeta,
): ProcessDef {
  const activities: Activity[] = nodes.map((n) => {
    const data = n.data ?? {};
    const type = data.activityType;
    if (!type || !ACTIVITY_TYPES.has(type)) {
      throw new Error(
        `node ${n.id} has invalid activityType: ${String(type)}`,
      );
    }
    return {
      id: n.id,
      name: data.label ?? n.id,
      type,
      pluginName: data.pluginName ?? null,
      properties: data.properties ?? {},
      inputSchema: data.inputSchema ?? {},
      outputSchema: data.outputSchema ?? {},
      deadlineExpression: data.deadlineExpression ?? null,
      isStart: !!data.isStart,
      isEnd: !!data.isEnd,
      subflowDefId: data.subflowDefId ?? null,
      subflowInputs: data.subflowInputs ?? {},
      subflowOutputs: data.subflowOutputs ?? {},
      outputVariables: data.outputVariables ?? [],
      andJoin: !!data.andJoin,
      errorPolicy: data.errorPolicy ?? undefined,
    };
  });

  const nodeIds = new Set(nodes.map((n) => n.id));
  const transitions: Transition[] = edges.map((e) => {
    if (!nodeIds.has(e.source) || !nodeIds.has(e.target)) {
      throw new Error(
        `edge ${e.id} references unknown node: ${e.source} -> ${e.target}`,
      );
    }
    const data = e.data ?? {};
    return {
      id: e.id,
      fromActivityId: e.source,
      toActivityId: e.target,
      condition: data.condition ?? null,
      isErrorEdge: !!data.isErrorEdge,
      matchesErrorClass: data.matchesErrorClass ?? null,
      trigger: data.trigger ?? null,
      priority: data.priority ?? null,
      isDefault: !!data.isDefault,
    };
  });

  return {
    id: meta.id,
    name: meta.name,
    version: meta.version,
    packageId: meta.packageId ?? null,
    description: meta.description ?? null,
    variables: meta.variables ?? [],
    activities,
    transitions,
  };
}

/**
 * Reverse: pull nodes + edges out of a ProcessDef. Position information is
 * NOT round-tripped (the backend doesn't store layout); callers should merge
 * with their own positions store.
 */
export function deserializeProcessDef(
  def: ProcessDef,
): { nodes: Array<Node<BoardNodeData>>; edges: Array<Edge<BoardEdgeData>> } {
  const nodes: Array<Node<BoardNodeData>> = def.activities.map((a) => ({
    id: a.id,
    type: nodeTypeFor(a.type),
    position: { x: 0, y: 0 }, // caller overrides from layout store
    data: {
      activityType: a.type,
      pluginName: a.pluginName,
      properties: a.properties,
      inputSchema: a.inputSchema,
      outputSchema: a.outputSchema,
      deadlineExpression: a.deadlineExpression,
      isStart: a.isStart,
      isEnd: a.isEnd,
      subflowDefId: a.subflowDefId,
      subflowInputs: a.subflowInputs,
      subflowOutputs: a.subflowOutputs,
      outputVariables: a.outputVariables,
      andJoin: a.andJoin,
      errorPolicy: a.errorPolicy,
      label: a.name ?? a.id,
    },
  }));

  const edges: Array<Edge<BoardEdgeData>> = def.transitions.map((t) => ({
    id: t.id,
    source: t.fromActivityId,
    target: t.toActivityId,
    data: {
      condition: t.condition,
      isErrorEdge: t.isErrorEdge,
      matchesErrorClass: t.matchesErrorClass,
      trigger: t.trigger,
      priority: t.priority,
      isDefault: t.isDefault,
    },
    type: t.isErrorEdge ? "error" : "default",
  }));

  return { nodes, edges };
}

/**
 * Map an engine activity type to the React Flow node-type registry key. The
 * board's nodeTypes registry should declare components for each. Tool/route/
 * subflow/normal renderers can stay as-is from the toolchain board — only
 * the data flowing into them changes.
 */
function nodeTypeFor(type: ActivityType): string {
  switch (type) {
    case "tool":
      return "tool";
    case "route":
      return "route";
    case "subflow":
      return "subflow";
    case "foreach":
      return "foreach";
    case "while":
      return "while";
    case "batch":
      return "batch";
    case "normal":
    default:
      return "normal";
  }
}
