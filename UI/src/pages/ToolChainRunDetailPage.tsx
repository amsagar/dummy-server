import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { Background, Controls, MiniMap, ReactFlow } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

type RuntimeEventRow = {
  id: string;
  runId: string;
  eventType: string;
  payload: string;
  createdAt: number;
};

function parsePayload(raw: any) {
  if (raw == null) return {};
  if (typeof raw === "object") return raw;
  if (typeof raw !== "string") return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function formatJson(raw: any) {
  if (raw == null) return "—";
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return JSON.stringify(parsed, null, 2);
    } catch {
      return raw;
    }
  }
  try {
    return JSON.stringify(raw, null, 2);
  } catch {
    return String(raw);
  }
}

function parseStructured(raw: any): any {
  if (raw == null) return null;
  if (typeof raw !== "string") return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function extractHybridSummary(snapshot: any): string {
  const parsedSnapshot = parseStructured(snapshot);
  if (!parsedSnapshot || typeof parsedSnapshot !== "object" || Array.isArray(parsedSnapshot)) return "";
  const topLevelSummary = (parsedSnapshot as any).summary;
  if (typeof topLevelSummary === "string" && topLevelSummary.trim()) return topLevelSummary.trim();
  const resultPayload = parseStructured((parsedSnapshot as any).result);
  if (!resultPayload || typeof resultPayload !== "object" || Array.isArray(resultPayload)) return "";
  const nestedSummary = (resultPayload as any).summary;
  if (typeof nestedSummary !== "string") return "";
  return nestedSummary.trim();
}

function toValueText(value: any): string {
  if (value === null) return "null";
  if (value === undefined) return "undefined";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function flattenData(value: any, maxRows = 220) {
  const rows: Array<{ key: string; value: string }> = [];
  let truncated = false;

  const walk = (node: any, prefix: string) => {
    if (rows.length >= maxRows) {
      truncated = true;
      return;
    }
    if (node === null || node === undefined || typeof node !== "object") {
      rows.push({ key: prefix || "value", value: toValueText(node) });
      return;
    }
    if (Array.isArray(node)) {
      if (node.length === 0) {
        rows.push({ key: prefix || "value", value: "[]" });
        return;
      }
      node.forEach((item, idx) => walk(item, `${prefix}[${idx}]`));
      return;
    }
    const entries = Object.entries(node);
    if (entries.length === 0) {
      rows.push({ key: prefix || "value", value: "{}" });
      return;
    }
    entries.forEach(([k, v]) => walk(v, prefix ? `${prefix}.${k}` : k));
  };

  walk(value, "");
  return { rows, truncated };
}

function StructuredDataView({ value, emptyLabel = "—" }: { value: any; emptyLabel?: string }) {
  if (value === null || value === undefined || value === "") {
    return <p className="text-xs text-slate-500">{emptyLabel}</p>;
  }
  const { rows, truncated } = flattenData(value);
  if (rows.length === 0) {
    return <p className="text-xs text-slate-500">{emptyLabel}</p>;
  }
  return (
    <div className="space-y-2">
      <div className="max-h-48 overflow-auto rounded border bg-slate-50">
        {rows.map((row, idx) => (
          <div key={`${row.key}-${idx}`} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-2 border-b px-2 py-1 text-xs last:border-b-0">
            <p className="break-all font-medium text-slate-600">{row.key}</p>
            <p className="break-all text-slate-800">{row.value}</p>
          </div>
        ))}
      </div>
      {truncated ? <p className="text-[11px] text-slate-500">Showing first {rows.length} fields.</p> : null}
    </div>
  );
}

function toFiniteNumber(value: any): number | null {
  const out = typeof value === "number" ? value : Number(value);
  return Number.isFinite(out) ? out : null;
}

function buildReadableFallbackPositions(nodes: any[], edges: any[]) {
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
  const queue = nodeIds.filter((nodeId) => (inDegree.get(nodeId) || 0) === 0);
  const level = new Map<string, number>();
  queue.forEach((id) => level.set(id, 0));
  while (queue.length > 0) {
    const current = queue.shift()!;
    const currentLevel = level.get(current) || 0;
    for (const next of outgoing.get(current) || []) {
      level.set(next, Math.max(level.get(next) || 0, currentLevel + 1));
      inDegree.set(next, (inDegree.get(next) || 0) - 1);
      if ((inDegree.get(next) || 0) === 0) queue.push(next);
    }
  }
  const grouped = new Map<number, string[]>();
  nodeIds.forEach((id) => {
    const depth = level.get(id) || 0;
    if (!grouped.has(depth)) grouped.set(depth, []);
    grouped.get(depth)!.push(id);
  });
  const positions: Record<string, { x: number; y: number }> = {};
  for (const [depth, ids] of Array.from(grouped.entries()).sort((a, b) => a[0] - b[0])) {
    ids.forEach((id, idx) => {
      positions[id] = { x: 80 + depth * 260, y: 80 + idx * 120 };
    });
  }
  return positions;
}

function statusStyles(status: string) {
  const normalized = String(status || "").toLowerCase();
  if (normalized === "success") return { bg: "#DCFCE7", border: "#16A34A" };
  if (normalized === "failed" || normalized === "rejected") return { bg: "#FEE2E2", border: "#DC2626" };
  if (normalized === "running" || normalized === "waiting_for_approval") return { bg: "#FEF3C7", border: "#D97706" };
  return { bg: "#E2E8F0", border: "#64748B" };
}

function buildStepMap(steps: any[]) {
  const map = new Map<string, any>();
  for (const step of steps || []) {
    const existing = map.get(step.nodeId);
    const stepStarted = Number(step.startedAt || 0);
    const existingStarted = Number(existing?.startedAt || 0);
    if (!existing || stepStarted >= existingStarted) {
      map.set(step.nodeId, step);
    }
  }
  return map;
}

export default function ToolChainRunDetailPage() {
  const { runId = "" } = useParams();
  const navigate = useNavigate();
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [isSummaryMaximized, setIsSummaryMaximized] = useState(false);

  const { data } = useQuery<any>({
    queryKey: ["toolchain-run", runId],
    queryFn: () => api.toolchains.run(runId),
    enabled: !!runId,
    refetchInterval: (query) => {
      const status = query.state.data?.run?.status;
      return String(status || "").toLowerCase() === "running" ? 2000 : false;
    },
  });
  const run = data?.run;
  const steps = data?.steps || [];
  const persistedEvents = (data?.events || []) as RuntimeEventRow[];
  const { data: versions = [] } = useQuery<any[]>({
    queryKey: ["toolchain-versions", run?.toolChainId, "run-detail"],
    queryFn: () => api.toolchains.versions(run.toolChainId),
    enabled: !!run?.toolChainId,
  });

  const runEvents = useMemo(
    () => [...persistedEvents].sort((a, b) => a.createdAt - b.createdAt),
    [persistedEvents]
  );

  const stepMap = useMemo(() => buildStepMap(steps), [steps]);
  const selectedVersion = useMemo(
    () => versions.find((v: any) => Number(v.version) === Number(run?.version)),
    [run?.version, versions]
  );

  const graph = useMemo(() => {
    const parsed = parsePayload(selectedVersion?.graphJson);
    const graphNodes = Array.isArray(parsed?.nodes) ? parsed.nodes : [];
    const graphEdges = Array.isArray(parsed?.edges) ? parsed.edges : [];
    const fallbackPositions = buildReadableFallbackPositions(graphNodes, graphEdges);
    const nodes = graphNodes.map((node: any, idx: number) => {
      const id = String(node.id);
      const step = stepMap.get(id);
      const status = step?.status || "queued";
      const { bg, border } = statusStyles(status);
      const rawX = toFiniteNumber(node?.position?.x);
      const rawY = toFiniteNumber(node?.position?.y);
      return {
        id,
        type: node.type === "start" ? "input" : node.type === "end" ? "output" : "default",
        position:
          rawX !== null && rawY !== null
            ? { x: rawX, y: rawY }
            : fallbackPositions[id] || { x: 80 + idx * 220, y: 100 },
        data: {
          label: `${node.label || id}${step?.status ? ` (${step.status})` : ""}`,
        },
        style: {
          backgroundColor: bg,
          border: `2px solid ${border}`,
          borderRadius: 10,
          minWidth: 160,
          fontSize: 12,
        },
      };
    });
    const edges = graphEdges.map((edge: any, idx: number) => ({
      id: edge.id || `e-${idx}-${edge.from || edge.source}-${edge.to || edge.target}`,
      source: String(edge.from || edge.source),
      target: String(edge.to || edge.target),
      label: edge.condition || edge.data?.label || undefined,
      animated: false,
    }));
    return { nodes, edges };
  }, [selectedVersion?.graphJson, stepMap]);

  const selectedStep = useMemo(() => {
    if (!selectedNodeId) return null;
    return stepMap.get(selectedNodeId) || null;
  }, [selectedNodeId, stepMap]);

  const selectedNodeRuntimeEvents = useMemo(() => {
    if (!selectedNodeId) return [];
    return runEvents.filter((event) => String(parsePayload(event.payload).nodeId || "") === selectedNodeId);
  }, [runEvents, selectedNodeId]);
  const selectedStepInput = useMemo(() => parseStructured(selectedStep?.inputPayload), [selectedStep?.inputPayload]);
  const selectedStepOutput = useMemo(() => parseStructured(selectedStep?.outputPayload), [selectedStep?.outputPayload]);
  const runSnapshot = useMemo(() => parseStructured(run?.outputSnapshot), [run?.outputSnapshot]);
  const runAiSummary = useMemo(() => extractHybridSummary(run?.outputSnapshot), [run?.outputSnapshot]);

  useEffect(() => {
    if (!graph.nodes.length) {
      setSelectedNodeId(null);
      return;
    }
    if (!selectedNodeId || !graph.nodes.some((node: any) => node.id === selectedNodeId)) {
      setSelectedNodeId(graph.nodes[0].id);
    }
  }, [graph.nodes, selectedNodeId]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-[#123262]">Run Detail</h2>
          <p className="font-mono text-xs text-slate-500">{runId}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => navigate("/toolchains")}>Back to ToolChains</Button>
          <Button variant="outline" onClick={() => navigate(run?.toolChainId ? `/toolchains/${run.toolChainId}/runs` : "/toolchains")}>
            Back to Runs
          </Button>
        </div>
      </div>

      <div className="grid gap-3 md:grid-cols-4">
        <Card className="p-3"><p className="text-xs text-slate-500">Status</p><p className="font-semibold">{run?.status || "—"}</p></Card>
        <Card className="p-3"><p className="text-xs text-slate-500">Trigger</p><p className="font-semibold">{run?.triggerSource || "—"}</p></Card>
        <Card className="p-3"><p className="text-xs text-slate-500">Started</p><p className="font-semibold text-sm">{run?.startedAt ? new Date(run.startedAt).toLocaleString() : "—"}</p></Card>
        <Card className="p-3"><p className="text-xs text-slate-500">Duration</p><p className="font-semibold">{run?.durationMs || "—"}</p></Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <Card className="p-2" style={{ height: "calc(100vh - 260px)", minHeight: 520 }}>
          {graph.nodes.length === 0 ? (
            <div className="flex h-full items-center justify-center text-sm text-slate-500">
              No graph found for this run version.
            </div>
          ) : (
            <ReactFlow
              nodes={graph.nodes}
              edges={graph.edges}
              onNodeClick={(_, node) => setSelectedNodeId(String(node.id))}
              fitView
              proOptions={{ hideAttribution: true }}
            >
              <Background />
              <Controls />
              <MiniMap />
            </ReactFlow>
          )}
        </Card>

        <Card className="max-h-[calc(100vh-260px)] space-y-3 overflow-auto p-3">
          <p className="text-sm font-medium text-[#123262]">Node Inspector</p>
          {!selectedNodeId ? (
            <p className="text-sm text-slate-500">Select a node to inspect its runtime input/output.</p>
          ) : (
            <div className="space-y-2 text-sm">
              <div className="rounded border p-2">
                <p><span className="font-medium">Node:</span> {selectedNodeId}</p>
                <p><span className="font-medium">Status:</span> {selectedStep?.status || "not executed"}</p>
                <p><span className="font-medium">Type:</span> {selectedStep?.nodeType || "—"}</p>
                <p><span className="font-medium">Started:</span> {selectedStep?.startedAt ? new Date(selectedStep.startedAt).toLocaleString() : "—"}</p>
                <p><span className="font-medium">Ended:</span> {selectedStep?.endedAt ? new Date(selectedStep.endedAt).toLocaleString() : "—"}</p>
              </div>
              <div>
                <p className="mb-1 text-xs font-medium text-slate-600">Input</p>
                <StructuredDataView value={selectedStepInput} />
              </div>
              <div>
                <p className="mb-1 text-xs font-medium text-slate-600">Output</p>
                <StructuredDataView value={selectedStepOutput} />
              </div>
              <div>
                <p className="mb-1 text-xs font-medium text-slate-600">Error</p>
                <StructuredDataView value={selectedStep?.errorMessage} />
              </div>
              <div>
                <p className="mb-1 text-xs font-medium text-slate-600">Events</p>
                {selectedNodeRuntimeEvents.length === 0 ? (
                  <p className="text-xs text-slate-500">No runtime events for this node.</p>
                ) : (
                  <div className="max-h-28 space-y-1 overflow-auto text-xs text-slate-700">
                    {selectedNodeRuntimeEvents.map((event) => (
                      <div key={event.id} className="rounded bg-slate-50 px-2 py-1">
                        [{new Date(event.createdAt).toLocaleTimeString()}] {event.eventType}
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div>
                <div className="mb-1 flex items-center justify-between">
                  <p className="text-xs font-medium text-slate-600">AI Summary</p>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-6 px-2 text-[11px]"
                    onClick={() => setIsSummaryMaximized(true)}
                    disabled={!runAiSummary}
                  >
                    Maximize
                  </Button>
                </div>
                <div className="max-h-48 overflow-auto rounded border bg-slate-50 p-2 text-xs text-slate-800 whitespace-pre-wrap">
                  {runAiSummary ? (
                    <div className="prose prose-sm max-w-none prose-p:my-1 prose-li:my-0">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {runAiSummary}
                      </ReactMarkdown>
                    </div>
                  ) : (
                    "No AI summary available for this run."
                  )}
                </div>
              </div>
              <Dialog open={isSummaryMaximized} onOpenChange={setIsSummaryMaximized}>
                <DialogContent className="w-[90vw] !max-w-5xl sm:!max-w-5xl gap-0 p-0">
                  <DialogHeader className="border-b border-slate-200 px-5 py-3">
                    <DialogTitle className="text-sm font-semibold text-[#123262]">AI Summary</DialogTitle>
                  </DialogHeader>
                  <div className="max-h-[75vh] overflow-auto bg-white px-6 py-4 text-sm text-slate-800">
                    {runAiSummary ? (
                      <div className="prose prose-sm max-w-none prose-headings:text-[#123262] prose-headings:font-semibold prose-p:my-2 prose-li:my-0 prose-table:my-2 prose-pre:bg-slate-50 prose-pre:text-slate-800">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {runAiSummary}
                        </ReactMarkdown>
                      </div>
                    ) : (
                      <span className="italic text-slate-400">No AI summary available for this run.</span>
                    )}
                  </div>
                </DialogContent>
              </Dialog>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
