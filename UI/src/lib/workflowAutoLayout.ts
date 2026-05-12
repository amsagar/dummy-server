// Programmatic auto-layout for the workflow canvas. Consumes the deserialized
// nodes + edges (the shape produced by `deserializeProcessDef`) and produces a
// `NodePositions` map. Wraps dagre and demotes back-edges (foreach loop-backs)
// so the accumulator doesn't get dragged back into the main spine.

import dagre from "@dagrejs/dagre";
import type { NodePositions } from "./workflowLayout";

interface LayoutNode {
  id: string;
}

interface LayoutEdge {
  id: string;
  source: string;
  target: string;
}

export interface AutoLayoutOptions {
  direction?: "LR" | "TB";
  nodeWidth?: number;
  nodeHeight?: number;
  rankSep?: number;
  nodeSep?: number;
  edgeSep?: number;
}

const DEFAULT_NODE_W = 240;
const DEFAULT_NODE_H = 96;
const DEFAULT_RANK_SEP = 150;
const DEFAULT_NODE_SEP = 90;
const DEFAULT_EDGE_SEP = 40;

export function autoLayout(
  nodes: ReadonlyArray<LayoutNode>,
  edges: ReadonlyArray<LayoutEdge>,
  opts: AutoLayoutOptions = {},
): NodePositions {
  const out: NodePositions = {};
  if (nodes.length === 0) return out;

  const nodeW = opts.nodeWidth ?? DEFAULT_NODE_W;
  const nodeH = opts.nodeHeight ?? DEFAULT_NODE_H;

  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir: opts.direction ?? "LR",
    ranksep: opts.rankSep ?? DEFAULT_RANK_SEP,
    nodesep: opts.nodeSep ?? DEFAULT_NODE_SEP,
    edgesep: opts.edgeSep ?? DEFAULT_EDGE_SEP,
  });
  g.setDefaultEdgeLabel(() => ({}));

  for (const n of nodes) g.setNode(n.id, { width: nodeW, height: nodeH });

  const backEdges = detectBackEdges(nodes, edges);
  for (const e of edges) {
    // Only wire edges that connect known nodes (defensive against stale defs).
    if (!g.hasNode(e.source) || !g.hasNode(e.target)) continue;
    const isBack = backEdges.has(e.id);
    g.setEdge(e.source, e.target, {
      weight: isBack ? 0 : 1,
      minlen: isBack ? 2 : 1,
    });
  }

  dagre.layout(g);

  for (const n of nodes) {
    const v = g.node(n.id);
    if (!v || !Number.isFinite(v.x) || !Number.isFinite(v.y)) continue;
    // dagre returns center coordinates; xyflow expects top-left.
    out[n.id] = { x: v.x - nodeW / 2, y: v.y - nodeH / 2 };
  }
  return out;
}

// DFS three-color back-edge detection over the directed graph. An edge whose
// target is currently on the DFS stack (GRAY) goes into a cycle and would
// otherwise constrain dagre's ranking — we tag it so the caller can demote it.
function detectBackEdges(
  nodes: ReadonlyArray<LayoutNode>,
  edges: ReadonlyArray<LayoutEdge>,
): Set<string> {
  const adj = new Map<string, { id: string; target: string }[]>();
  for (const n of nodes) adj.set(n.id, []);
  for (const e of edges) adj.get(e.source)?.push({ id: e.id, target: e.target });

  const WHITE = 0;
  const GRAY = 1;
  const BLACK = 2;
  const color = new Map<string, number>(nodes.map((n) => [n.id, WHITE]));
  const back = new Set<string>();

  const stack: { node: string; iter: Iterator<{ id: string; target: string }> }[] = [];

  const visit = (start: string): void => {
    color.set(start, GRAY);
    stack.push({ node: start, iter: (adj.get(start) ?? [])[Symbol.iterator]() });
    while (stack.length > 0) {
      const top = stack[stack.length - 1];
      const next = top.iter.next();
      if (next.done) {
        color.set(top.node, BLACK);
        stack.pop();
        continue;
      }
      const { id, target } = next.value;
      const c = color.get(target);
      if (c === GRAY) {
        back.add(id);
      } else if (c === WHITE) {
        color.set(target, GRAY);
        stack.push({ node: target, iter: (adj.get(target) ?? [])[Symbol.iterator]() });
      }
    }
  };

  for (const n of nodes) {
    if (color.get(n.id) === WHITE) visit(n.id);
  }
  return back;
}
