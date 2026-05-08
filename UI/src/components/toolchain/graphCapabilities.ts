import type { Edge, Node } from "@xyflow/react";

export type ToolGraphNode = {
  id: string;
  type: string;
  label?: string;
  config?: Record<string, any>;
  position?: { x?: number; y?: number };
};

export type ToolGraphEdge = {
  id?: string;
  from?: string;
  to?: string;
  source?: string;
  target?: string;
  condition?: string;
  kind?: "success" | "error";
  data?: Record<string, any>;
};

export type ToolGraph = {
  nodes: ToolGraphNode[];
  edges: ToolGraphEdge[];
};

export type GraphWarning = {
  nodeId: string;
  message: string;
};

const CAPABILITY_TITLES: Record<string, string> = {
  start: "Start",
  end: "End",
  tool: "Tool",
  mcp_tool: "MCP Tool",
  decision: "Condition",
  switch: "Switch",
  iterator: "Loop",
  parallel: "Parallel",
  assign: "Assign",
  subchain: "Subchain",
  merge: "Merge",
  wait: "Wait",
  decision_table: "Decision Table",
  synthesis: "Synthesis",
};

function toFiniteNumber(value: any): number | null {
  const out = typeof value === "number" ? value : Number(value);
  return Number.isFinite(out) ? out : null;
}

export function normalizeGraph(raw: any): ToolGraph {
  const parsed = raw && typeof raw === "object" ? raw : {};
  return {
    nodes: Array.isArray(parsed.nodes) ? parsed.nodes : [],
    edges: Array.isArray(parsed.edges) ? parsed.edges : [],
  };
}

export function canonicalizeToolNodeType(
  nodeType: string,
  toolName: string,
  validToolNames: Set<string>,
  validMcpNames: Set<string>
): string {
  const normalizedType = String(nodeType || "").toLowerCase();
  if (normalizedType !== "tool" && normalizedType !== "mcp_tool") return normalizedType;
  const normalizedToolName = String(toolName || "").trim();
  if (!normalizedToolName) return normalizedType;
  const inTools = validToolNames.has(normalizedToolName);
  const inMcp = validMcpNames.has(normalizedToolName);
  if (inTools && !inMcp) return "tool";
  if (inMcp && !inTools) return "mcp_tool";
  return normalizedType;
}

export function canonicalizeGraphToolNodeTypes(
  graph: ToolGraph,
  validToolNames: Set<string>,
  validMcpNames: Set<string>
): ToolGraph {
  let changed = false;
  const nodes = graph.nodes.map((node) => {
    const config = node?.config && typeof node.config === "object" ? node.config : {};
    const toolName = String((config as any).toolName || (config as any).name || "");
    const canonicalType = canonicalizeToolNodeType(node.type, toolName, validToolNames, validMcpNames);
    if (canonicalType === node.type) return node;
    changed = true;
    return { ...node, type: canonicalType };
  });
  return changed ? { ...graph, nodes } : graph;
}

export function parseGraphJson(raw: string | null | undefined): ToolGraph | null {
  if (!raw || !raw.trim()) return null;
  try {
    return normalizeGraph(JSON.parse(raw));
  } catch {
    return null;
  }
}

export function edgeLabel(edge: ToolGraphEdge): string {
  const condition = String(edge?.condition || "").trim();
  if (condition) return condition;
  const dataLabel = String(edge?.data?.label || "").trim();
  return dataLabel;
}

export function capabilityTitle(node: ToolGraphNode | null | undefined): string {
  const type = String(node?.type || "").toLowerCase();
  return CAPABILITY_TITLES[type] || "Node";
}

export function capabilitySummary(node: ToolGraphNode | null | undefined): string {
  if (!node) return "";
  const type = String(node.type || "").toLowerCase();
  const cfg = node.config || {};
  switch (type) {
    case "decision":
      if (String(cfg.expression || "").trim()) {
        return `Routes by expression: ${String(cfg.expression).slice(0, 80)}${String(cfg.expression).length > 80 ? "…" : ""}`;
      }
      return `Routes to true/false branches by comparing ${String(cfg.sourceKey || "context value")} with ${JSON.stringify(cfg.equals ?? "")}.`;
    case "switch":
      if (Array.isArray(cfg.cases) && cfg.cases.some((row: any) => String(row?.whenExpression || "").trim())) {
        return `Routes across ${cfg.cases.length} expression/equality cases.`;
      }
      return `Routes across ${Array.isArray(cfg.cases) ? cfg.cases.length : 0} case paths using source key ${String(cfg.sourceKey || "value")}.`;
    case "iterator":
      const loopMode = String(cfg.loopMode || "foreach");
      const collect = cfg.collectOutput ? " Collects $.<iteratorId>.results." : "";
      return cfg.toolName
        ? `Runs ${loopMode} and invokes ${String(cfg.toolName)} per item.${collect}`
        : `Runs ${loopMode} and executes subchain ${String(cfg.subChainId || "unknown")} per item.${collect}`;
    case "subchain":
      return `Calls child toolchain ${String(cfg.chainId || "unknown")} ${cfg.version ? `(v${cfg.version})` : ""}.`;
    case "assign":
      return `Updates workflow variables (${Array.isArray(cfg.assignments) ? cfg.assignments.length : 0} assignment(s)).`;
    case "parallel":
      return "Fans out to all outgoing branches concurrently.";
    case "tool":
    case "mcp_tool":
      return `Invokes ${String(cfg.toolName || "tool")} with mapped inputs${cfg.approvalMode ? ` (approval: ${cfg.approvalMode})` : ""}.`;
    case "synthesis":
      return "Synthesizes final response from prior node outputs.";
    default:
      return `${capabilityTitle(node)} workflow step.`;
  }
}

export function buildReadableFallbackPositions(nodes: ToolGraphNode[], edges: ToolGraphEdge[]) {
  const nodeIds = nodes.map((node) => String(node.id));
  const inDegree = new Map<string, number>();
  const outgoing = new Map<string, string[]>();
  for (const id of nodeIds) {
    inDegree.set(id, 0);
    outgoing.set(id, []);
  }
  for (const edge of edges || []) {
    const from = String(edge.from ?? edge.source ?? "");
    const to = String(edge.to ?? edge.target ?? "");
    if (!inDegree.has(from) || !inDegree.has(to)) continue;
    outgoing.get(from)?.push(to);
    inDegree.set(to, (inDegree.get(to) || 0) + 1);
  }
  const queue: string[] = nodeIds.filter((nodeId) => (inDegree.get(nodeId) || 0) === 0);
  const level = new Map<string, number>();
  for (const id of queue) level.set(id, 0);
  while (queue.length > 0) {
    const current = queue.shift()!;
    const currentLevel = level.get(current) || 0;
    for (const next of outgoing.get(current) || []) {
      level.set(next, Math.max(level.get(next) || 0, currentLevel + 1));
      inDegree.set(next, (inDegree.get(next) || 0) - 1);
      if ((inDegree.get(next) || 0) === 0) queue.push(next);
    }
  }
  const maxKnownLevel = Math.max(0, ...Array.from(level.values(), (v) => v || 0));
  let fallbackLevel = maxKnownLevel;
  for (const id of nodeIds) {
    if (!level.has(id)) {
      fallbackLevel += 1;
      level.set(id, fallbackLevel);
    }
  }
  const grouped = new Map<number, string[]>();
  for (const id of nodeIds) {
    const depth = level.get(id) || 0;
    if (!grouped.has(depth)) grouped.set(depth, []);
    grouped.get(depth)!.push(id);
  }
  const positions: Record<string, { x: number; y: number }> = {};
  for (const [depth, ids] of Array.from(grouped.entries()).sort((a, b) => a[0] - b[0])) {
    ids.forEach((id, idx) => {
      positions[id] = { x: 100 + depth * 280, y: 80 + idx * 140 };
    });
  }
  return positions;
}

export function graphToFlow(
  parsed: ToolGraph,
  layoutPositions: Record<string, { x: number; y: number }>,
  fallbackY: number
): { rfNodes: Node[]; rfEdges: Edge[] } {
  const autoPositions = buildReadableFallbackPositions(parsed.nodes, parsed.edges);
  const rfNodes: Node[] = parsed.nodes.map((node: ToolGraphNode, idx: number) => {
    const id = String(node.id);
    const rawX = toFiniteNumber(node?.position?.x);
    const rawY = toFiniteNumber(node?.position?.y);
    const config = node.config || {};
    const approvalMode = String(config?.approvalMode || "").toLowerCase();
    const needsApproval = approvalMode === "required" || approvalMode === "required_if_sensitive";
    const branchCount =
      node.type === "switch" && Array.isArray(config.cases) ? config.cases.length : undefined;
    return {
      id,
      type: "capabilityNode",
      data: {
        label: node.label || node.id,
        nodeType: node.type,
        config,
        capability: capabilityTitle(node),
        needsApproval,
        branchCount,
      },
      position:
        layoutPositions[id] ||
        (rawX !== null && rawY !== null
          ? { x: rawX, y: rawY }
          : autoPositions[id] || { x: 80 + idx * 220, y: fallbackY }),
    };
  });
  const rfEdges: Edge[] = parsed.edges.map((edge: ToolGraphEdge, idx: number) => {
    const from = String(edge.from || edge.source || "");
    const to = String(edge.to || edge.target || "");
    const label = edgeLabel(edge);
    const kind = String(edge.kind || "").toLowerCase() === "error" ? "error" : "success";
    return {
      id: edge.id || `e-${idx}-${from}-${to}`,
      source: from,
      target: to,
      label: label || undefined,
      type: "capabilityEdge",
      data: { label, kind },
    };
  });
  return { rfNodes, rfEdges };
}

export function flowEdgesToGraph(edges: Edge[]): ToolGraphEdge[] {
  return (edges || []).map((edge) => {
    const label = String(edge?.data?.label || edge?.label || "").trim();
    const kind = String((edge?.data as any)?.kind || "success").toLowerCase() === "error" ? "error" : "success";
    return {
      id: String(edge.id || ""),
      from: String(edge.source || ""),
      to: String(edge.target || ""),
      condition: label || undefined,
      kind,
    };
  });
}

function hasBalancedParens(text: string): boolean {
  let depth = 0;
  for (const c of text) {
    if (c === "(") depth++;
    if (c === ")") {
      depth--;
      if (depth < 0) return false;
    }
  }
  return depth === 0;
}

export function collectGraphWarnings(graph: ToolGraph): GraphWarning[] {
  const warnings: GraphWarning[] = [];
  const nodeIds = new Set(graph.nodes.map((n) => String(n.id)));
  for (const node of graph.nodes) {
    const type = String(node.type || "").toLowerCase();
    const cfg = node.config || {};
    if (type === "decision") {
      const expression = String(cfg.expression || "").trim();
      if (!expression && !cfg.sourceKey) warnings.push({ nodeId: String(node.id), message: "Decision sourceKey is missing." });
      if (expression && !hasBalancedParens(expression)) {
        warnings.push({ nodeId: String(node.id), message: "Decision expression appears malformed (unbalanced parentheses)." });
      }
      if (!cfg.trueBranch || !nodeIds.has(String(cfg.trueBranch))) {
        warnings.push({ nodeId: String(node.id), message: "Decision trueBranch is missing or invalid." });
      }
      if (!cfg.falseBranch || !nodeIds.has(String(cfg.falseBranch))) {
        warnings.push({ nodeId: String(node.id), message: "Decision falseBranch is missing or invalid." });
      }
    }
    if (type === "switch") {
      const cases = Array.isArray(cfg.cases) ? cfg.cases : [];
      const hasDefault = String(cfg.defaultBranch || cfg.default || "").trim().length > 0;
      if (cases.length === 0 && !hasDefault) warnings.push({ nodeId: String(node.id), message: "Switch has no cases." });
      const hasExpressionCase = cases.some((row: any) => String(row?.whenExpression || "").trim().length > 0);
      if (!hasExpressionCase && !String(cfg.sourceKey || "").trim()) {
        warnings.push({ nodeId: String(node.id), message: "Switch sourceKey is missing." });
      }
      for (const row of cases) {
        if (!row?.to || !nodeIds.has(String(row.to))) {
          warnings.push({ nodeId: String(node.id), message: "Switch case target is missing or invalid." });
          break;
        }
        const whenExpression = String(row?.whenExpression || "").trim();
        if (whenExpression && !hasBalancedParens(whenExpression)) {
          warnings.push({ nodeId: String(node.id), message: "Switch case whenExpression appears malformed." });
          break;
        }
      }
    }
    if (type === "iterator") {
      const loopMode = String(cfg.loopMode || "foreach").toLowerCase();
      if (cfg.toolName) {
        if (!cfg.argMappings || !cfg.argMappings.items) {
          warnings.push({ nodeId: String(node.id), message: "Iterator inline tool requires argMappings.items." });
        }
      } else if (!cfg.subChainId) {
        warnings.push({ nodeId: String(node.id), message: "Iterator requires either toolName or subChainId." });
      }
      if (loopMode !== "foreach" && !String(cfg.exitCondition || "").trim()) {
        warnings.push({ nodeId: String(node.id), message: "Iterator exitCondition is required for while/until." });
      }
      if (loopMode !== "foreach" && !Number.isFinite(Number(cfg.maxIterations ?? 1000))) {
        warnings.push({ nodeId: String(node.id), message: "Iterator maxIterations must be a number." });
      }
    }
    if (type === "subchain" && !cfg.chainId) {
      warnings.push({ nodeId: String(node.id), message: "Subchain chainId is missing." });
    }
    if (type === "parallel") {
      const outgoingCount = graph.edges.filter((edge) => String(edge.from || edge.source || "") === String(node.id)).length;
      if (outgoingCount <= 1) {
        warnings.push({ nodeId: String(node.id), message: "Parallel node should have at least 2 outgoing edges." });
      }
    }
    if (type === "assign") {
      const assignments = Array.isArray(cfg.assignments) ? cfg.assignments : [];
      if (assignments.length === 0) {
        warnings.push({ nodeId: String(node.id), message: "Assign node has no assignments." });
      }
    }
  }
  return warnings;
}
