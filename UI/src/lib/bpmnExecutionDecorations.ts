import type { BpmnExecutionDecoration, BpmnElementRuntimeState } from "@/components/bpmn/types";

interface RuleExecutionSummary {
  success: boolean;
  errorMessage?: string;
  outputsJson?: string;
  createdAt: number;
}

interface FlowNode {
  id: string;
  kind: string;
}

const FLOW_NODE_REGEX =
  /<(?:[A-Za-z_][\w.-]*:)?(startEvent|endEvent|serviceTask|userTask|scriptTask|businessRuleTask|subProcess|callActivity|exclusiveGateway|parallelGateway|inclusiveGateway|task)\b([^>]*?)>/gi;
const ID_REGEX = /\bid=(['"])(.*?)\1/i;

function extractFlowNodes(xml: string): FlowNode[] {
  const nodes: FlowNode[] = [];
  for (let m = FLOW_NODE_REGEX.exec(xml); m; m = FLOW_NODE_REGEX.exec(xml)) {
    const idMatch = ID_REGEX.exec(m[2]);
    if (!idMatch?.[2]) continue;
    nodes.push({ id: idMatch[2], kind: m[1] });
  }
  return nodes;
}

function parseJsonObject(json?: string): unknown {
  if (!json) return null;
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}

function collectMatchingIds(value: unknown, knownIds: Set<string>, sink: Set<string>) {
  if (value == null) return;
  if (typeof value === "string") {
    if (knownIds.has(value)) sink.add(value);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry) => collectMatchingIds(entry, knownIds, sink));
    return;
  }
  if (typeof value === "object") {
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      if (knownIds.has(key)) sink.add(key);
      collectMatchingIds(nested, knownIds, sink);
    }
  }
}

function extractIdFromError(errorMessage: string | undefined, knownIds: Set<string>): string | null {
  if (!errorMessage) return null;
  for (const id of knownIds) {
    if (errorMessage.includes(id)) return id;
  }
  return null;
}

export function buildExecutionDecorations(
  xml: string,
  executions: RuleExecutionSummary[],
): BpmnExecutionDecoration {
  const flowNodes = extractFlowNodes(xml);
  if (flowNodes.length === 0 || executions.length === 0) {
    return { stateByElementId: {}, badgeByElementId: {} };
  }

  const latest = [...executions].sort((a, b) => b.createdAt - a.createdAt)[0];
  const knownIds = new Set(flowNodes.map((n) => n.id));
  const stateByElementId: Record<string, BpmnElementRuntimeState> = {};
  const badgeByElementId: Record<string, string> = {};

  // Optimistic baseline: every flow node in the diagram (including those
  // nested inside subprocesses) is tagged with the overall run outcome.
  // This guarantees the drilled-in subprocess view also shows colours
  // instead of looking like an unrelated, untouched diagram.
  //   success → "completed" (green)
  //   failure → "warning"   (amber)
  // Specific failures override the baseline below.
  const baseline: BpmnElementRuntimeState = latest.success ? "completed" : "warning";
  for (const node of flowNodes) {
    stateByElementId[node.id] = baseline;
  }

  // Anything that appears verbatim in the run's structured outputs JSON is
  // a strong "this ran successfully" signal — promote to completed.
  const outputs = parseJsonObject(latest.outputsJson);
  const matchedFromOutputs = new Set<string>();
  collectMatchingIds(outputs, knownIds, matchedFromOutputs);
  for (const id of matchedFromOutputs) {
    stateByElementId[id] = "completed";
  }

  // The terminal end event reflects the overall outcome of the run.
  const endNode = [...flowNodes].reverse().find((n) => n.kind === "endEvent");
  if (endNode) {
    stateByElementId[endNode.id] = latest.success ? "completed" : "failed";
  }

  // Specific element mentioned in the error message wins as the failure point.
  const erroredId = extractIdFromError(latest.errorMessage, knownIds);
  if (erroredId) stateByElementId[erroredId] = "failed";

  // Fallback when we can't pin a specific failure to an element id.
  if (!latest.success && !erroredId) {
    const fallback = [...flowNodes].reverse().find((n) =>
      ["serviceTask", "userTask", "businessRuleTask", "subProcess", "task"].includes(n.kind),
    );
    if (fallback) stateByElementId[fallback.id] = "failed";
  }

  // Start event always reflects overall outcome + carries the execution-count badge.
  const startNode = flowNodes.find((n) => n.kind === "startEvent");
  if (startNode) {
    badgeByElementId[startNode.id] = String(executions.length);
    stateByElementId[startNode.id] = latest.success ? "completed" : "warning";
  }

  return { stateByElementId, badgeByElementId };
}

