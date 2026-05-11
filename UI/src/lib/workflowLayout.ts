// Per-workflow node layout persistence. The wire ProcessDef intentionally
// doesn't carry x/y (it's a domain artifact, not a UI artifact), so we keep
// positions in localStorage keyed by workflow id. The shape is just
// `{ [nodeId]: {x, y} }`.

const KEY_PREFIX = "wf:positions:";

export interface NodePositions {
  [nodeId: string]: { x: number; y: number };
}

function key(workflowId: string | undefined): string | null {
  if (!workflowId) return null;
  return KEY_PREFIX + workflowId;
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
