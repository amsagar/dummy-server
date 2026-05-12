// Per-workflow node layout persistence. The wire ProcessDef intentionally
// doesn't carry x/y (it's a domain artifact, not a UI artifact), so we keep
// positions in localStorage keyed by workflow id. The shape is just
// `{ [nodeId]: {x, y} }`.

import { autoLayout } from "./workflowAutoLayout";

const KEY_PREFIX = "wf:positions:";
const META_KEY_PREFIX = "wf:positions:meta:";

export interface NodePositions {
  [nodeId: string]: { x: number; y: number };
}

function key(workflowId: string | undefined): string | null {
  if (!workflowId) return null;
  return KEY_PREFIX + workflowId;
}

function metaKey(workflowId: string | undefined): string | null {
  if (!workflowId) return null;
  return META_KEY_PREFIX + workflowId;
}

function graphSignature(
  nodes: ReadonlyArray<{ id: string }>,
  edges: ReadonlyArray<{ id: string; source: string; target: string }>,
): string {
  const nodeIds = nodes.map((n) => n.id).sort();
  const edgeDefs = edges.map((e) => `${e.source}->${e.target}#${e.id}`).sort();
  return `n:${nodeIds.join(",")}|e:${edgeDefs.join(",")}`;
}

function loadGraphSignature(workflowId: string | undefined): string | null {
  const k = metaKey(workflowId);
  if (!k) return null;
  try {
    const raw = localStorage.getItem(k);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { graphSignature?: unknown };
    return typeof parsed.graphSignature === "string" ? parsed.graphSignature : null;
  } catch {
    return null;
  }
}

function saveGraphSignature(workflowId: string | undefined, signature: string): void {
  const k = metaKey(workflowId);
  if (!k) return;
  try {
    localStorage.setItem(k, JSON.stringify({ graphSignature: signature }));
  } catch {
    // localStorage full or disabled — fail silently; metadata is best-effort.
  }
}

export function loadLayout(workflowId: string | undefined): NodePositions {
  const k = key(workflowId);
  if (!k) return {};
  try {
    const raw = localStorage.getItem(k);
    if (!raw) return {};
    return JSON.parse(raw) as NodePositions;
  } catch {
    return {};
  }
}

export function saveLayout(workflowId: string | undefined, positions: NodePositions): void {
  const k = key(workflowId);
  if (!k) return;
  try {
    localStorage.setItem(k, JSON.stringify(positions));
  } catch {
    // localStorage full or disabled — fail silently; layout is best-effort.
  }
}

// Returns a position map covering every node. Saved drags always win; nodes
// without a saved position get auto-laid out via dagre. Persists the merged
// result back to localStorage unless `persist: false` (read-only viewers).
export function getOrComputeLayout(
  workflowId: string | undefined,
  nodes: ReadonlyArray<{ id: string }>,
  edges: ReadonlyArray<{ id: string; source: string; target: string }>,
  opts: { persist?: boolean } = {},
): NodePositions {
  const saved = loadLayout(workflowId);
  const signature = graphSignature(nodes, edges);
  const savedSignature = loadGraphSignature(workflowId);
  const hasCompleteSavedLayout = nodes.every((n) => saved[n.id]);
  const structureChanged = !!savedSignature && savedSignature !== signature;

  if (nodes.length === 0) return saved;
  if (hasCompleteSavedLayout && !structureChanged) {
    if (opts.persist !== false && savedSignature !== signature) saveGraphSignature(workflowId, signature);
    return saved;
  }

  const computed = autoLayout(nodes, edges);
  // Keep manual drags only when the graph structure is unchanged. If node/edge
  // structure changed, stale coordinates can create awkward crossings.
  const layout: NodePositions = structureChanged ? computed : { ...computed, ...saved };
  if (opts.persist !== false) {
    saveLayout(workflowId, layout);
    saveGraphSignature(workflowId, signature);
  }
  return layout;
}
