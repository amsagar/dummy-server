import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { ChevronDown, ChevronRight, Minus, X } from "lucide-react";
import { capabilitySummary, collectGraphWarnings } from "@/components/toolchain/graphCapabilities";

type GraphNode = {
  id: string;
  type: string;
  label?: string;
  config?: Record<string, any>;
};

type GraphEdge = {
  from?: string;
  to?: string;
  source?: string;
  target?: string;
  condition?: string;
};

type CatalogToolRow = {
  name: string;
  description?: string;
  method?: string;
  endpoint?: string;
  requiredInputKeys?: string[];
  pathParams?: string[];
  sampleInput?: any;
};

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  node: GraphNode | null;
  graph: { nodes: GraphNode[]; edges: GraphEdge[] } | null;
  synthesisPrompt?: string | null;
  inputSchema?: {
    type?: string;
    properties?: Record<string, { type?: string; description?: string; default?: any }>;
    required?: string[];
  } | null;
  toolsCatalog?: CatalogToolRow[];
  mcpCatalog?: CatalogToolRow[];
  onEdit?: (nodeId: string) => void;
};

const BOTTOM_PANEL_HEIGHT_STORAGE_KEY = "toolchain-designer.node-bottom-panel-height";
const BOTTOM_PANEL_HEIGHT_MIN = 220;
const BOTTOM_PANEL_HEIGHT_DEFAULT = 340;

function clampBottomPanelHeight(candidateHeight: number): number {
  const height = Number.isFinite(candidateHeight) ? candidateHeight : BOTTOM_PANEL_HEIGHT_DEFAULT;
  const max = typeof window === "undefined"
    ? 640
    : Math.max(BOTTOM_PANEL_HEIGHT_MIN, Math.floor(window.innerHeight * 0.72));
  return Math.max(BOTTOM_PANEL_HEIGHT_MIN, Math.min(max, Math.round(height)));
}

function readInitialBottomPanelHeight(): number {
  if (typeof window === "undefined") return BOTTOM_PANEL_HEIGHT_DEFAULT;
  const raw = window.localStorage.getItem(BOTTOM_PANEL_HEIGHT_STORAGE_KEY);
  if (!raw) return clampBottomPanelHeight(BOTTOM_PANEL_HEIGHT_DEFAULT);
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return clampBottomPanelHeight(BOTTOM_PANEL_HEIGHT_DEFAULT);
  return clampBottomPanelHeight(parsed);
}

const NODE_TYPE_COLORS: Record<string, string> = {
  start: "bg-emerald-100 text-emerald-800 border-emerald-300",
  end: "bg-rose-100 text-rose-800 border-rose-300",
  tool: "bg-sky-100 text-sky-800 border-sky-300",
  mcp_tool: "bg-indigo-100 text-indigo-800 border-indigo-300",
  decision: "bg-amber-100 text-amber-800 border-amber-300",
  switch: "bg-orange-100 text-orange-800 border-orange-300",
  iterator: "bg-cyan-100 text-cyan-800 border-cyan-300",
  parallel: "bg-amber-200 text-amber-900 border-amber-400",
  assign: "bg-sky-100 text-sky-900 border-sky-300",
  code_execute: "bg-teal-100 text-teal-900 border-teal-300",
  subchain: "bg-indigo-100 text-indigo-800 border-indigo-300",
  synthesis: "bg-violet-100 text-violet-800 border-violet-300",
  approval: "bg-fuchsia-100 text-fuchsia-800 border-fuchsia-300",
};

function TypeChip({ type }: { type: string }) {
  const cls = NODE_TYPE_COLORS[type] || "bg-slate-100 text-slate-800 border-slate-300";
  return (
    <span className={cn("inline-flex items-center rounded border px-2 py-0.5 text-[11px] font-medium", cls)}>
      {type}
    </span>
  );
}

function MetaRow({ label, children, mono }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[120px_1fr] items-start gap-2 py-1">
      <div className="text-[11px] font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className={cn("text-xs text-slate-700 break-all", mono && "font-mono")}>{children}</div>
    </div>
  );
}

function Section({
  title,
  defaultOpen = true,
  children,
}: {
  title: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="rounded-md border border-slate-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-1 border-b border-slate-100 px-3 py-2 text-left text-xs font-semibold text-slate-700"
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        {title}
      </button>
      {open ? <div className="px-3 py-2">{children}</div> : null}
    </div>
  );
}

function resolveNodeLabel(graph: Props["graph"], id: string | undefined): string {
  if (!id || !graph) return id || "—";
  const found = graph.nodes.find((n) => n.id === id);
  return found?.label || id;
}

function MappingTable({
  rows,
  leftLabel,
  rightLabel,
}: {
  rows: Array<{ target: string; source: string }>;
  leftLabel: string;
  rightLabel: string;
}) {
  if (!rows.length) {
    return (
      <div className="rounded border border-dashed border-slate-200 px-3 py-2 text-xs italic text-slate-400">
        No mappings configured.
      </div>
    );
  }
  return (
    <div className="overflow-hidden rounded border border-slate-200">
      <table className="w-full text-xs">
        <thead>
          <tr className="bg-slate-50 text-left text-[10px] uppercase tracking-wide text-slate-500">
            <th className="px-2 py-1.5 font-medium">{leftLabel}</th>
            <th className="px-2 py-1.5 font-medium">{rightLabel}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.target}:${row.source}`} className="border-t border-slate-100">
              <td className="px-2 py-1.5 font-mono text-slate-700">{row.target}</td>
              <td className="px-2 py-1.5 font-mono text-slate-600">{row.source}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function NodeBottomInspectorPanel({
  open,
  onOpenChange,
  node,
  graph,
  synthesisPrompt,
  inputSchema,
  toolsCatalog = [],
  mcpCatalog = [],
  onEdit,
}: Props) {
  const [minimized, setMinimized] = useState(false);
  const [activeTab, setActiveTab] = useState("configuration");
  const [panelHeight, setPanelHeight] = useState<number>(() => readInitialBottomPanelHeight());
  const [isResizingPanel, setIsResizingPanel] = useState(false);
  const panelResizeStartRef = useRef<{ startY: number; startHeight: number } | null>(null);
  const hasHydratedHeightRef = useRef(false);

  const clampPanelHeight = useCallback((candidateHeight: number) => {
    return clampBottomPanelHeight(candidateHeight);
  }, []);

  const inboundEdges = useMemo(
    () => (graph?.edges || []).filter((e) => (e.to || e.target) === node?.id),
    [graph, node]
  );
  const outboundEdges = useMemo(
    () => (graph?.edges || []).filter((e) => (e.from || e.source) === node?.id),
    [graph, node]
  );

  const toolMeta = useMemo<CatalogToolRow | undefined>(() => {
    if (!node) return undefined;
    const toolName = String(node.config?.toolName || "").trim();
    if (!toolName) return undefined;
    const list = node.type === "mcp_tool" ? mcpCatalog : toolsCatalog;
    return list.find((row) => row.name === toolName);
  }, [node, toolsCatalog, mcpCatalog]);

  const argMappings = useMemo(() => {
    const raw = node?.config?.argMappings;
    if (!raw || typeof raw !== "object") return [] as Array<{ target: string; source: string }>;
    return Object.entries(raw).map(([target, source]) => ({
      target,
      source: String(source ?? ""),
    }));
  }, [node]);
  const nodeWarnings = useMemo(() => {
    if (!graph || !node) return [];
    return collectGraphWarnings(graph as any).filter((warning) => warning.nodeId === String(node.id));
  }, [graph, node]);
  const upstreamSourceNodes = useMemo(() => {
    if (!graph || !node) return [] as Array<{ id: string; label: string; type: string }>;
    const incoming = new Map<string, Set<string>>();
    for (const edge of graph.edges || []) {
      const from = String(edge.from || edge.source || "");
      const to = String(edge.to || edge.target || "");
      if (!from || !to) continue;
      if (!incoming.has(to)) incoming.set(to, new Set());
      incoming.get(to)!.add(from);
    }
    const visited = new Set<string>();
    const queue = [...Array.from(incoming.get(String(node.id)) || [])];
    while (queue.length > 0) {
      const current = queue.shift()!;
      if (!current || visited.has(current)) continue;
      visited.add(current);
      for (const parent of Array.from(incoming.get(current) || [])) {
        if (!visited.has(parent)) queue.push(parent);
      }
    }
    return Array.from(visited)
      .map((id) => {
        const match = graph.nodes.find((n) => String(n.id) === id);
        return match
          ? { id: String(match.id), label: String(match.label || match.id), type: String(match.type || "node") }
          : { id, label: id, type: "node" };
      })
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [graph, node]);
  const mappingExpressionRows = useMemo(
    () =>
      argMappings.map((row) => ({
        source: row.source,
        target: row.target,
        expression: row.source.startsWith("$")
          ? row.source
          : row.source.startsWith("context.")
          ? `$.${row.source}`
          : `$.${row.source}`,
      })),
    [argMappings]
  );
  const workflowInputEntries = useMemo(
    () => Object.entries(inputSchema?.properties || {}),
    [inputSchema]
  );
  const requiredWorkflowInputs = useMemo(
    () => new Set((inputSchema?.required || []).map((k) => String(k))),
    [inputSchema]
  );

  useEffect(() => {
    if (!hasHydratedHeightRef.current) {
      hasHydratedHeightRef.current = true;
      return;
    }
    window.localStorage.setItem(BOTTOM_PANEL_HEIGHT_STORAGE_KEY, String(clampPanelHeight(panelHeight)));
  }, [panelHeight, clampPanelHeight]);

  useEffect(() => {
    const onResize = () => {
      setPanelHeight((prev) => clampPanelHeight(prev));
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [clampPanelHeight]);

  useEffect(() => {
    if (!isResizingPanel) return;
    const onMouseMove = (event: MouseEvent) => {
      const start = panelResizeStartRef.current;
      if (!start) return;
      const delta = start.startY - event.clientY;
      setPanelHeight(clampPanelHeight(start.startHeight + delta));
    };
    const onMouseUp = () => {
      setIsResizingPanel(false);
      panelResizeStartRef.current = null;
    };
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
    return () => {
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
    };
  }, [isResizingPanel, clampPanelHeight]);

  if (!open || !node) return null;

  const config = node.config || {};
  const effectiveSynthesisPrompt = String(config.prompt || synthesisPrompt || "").trim();

  return (
    <div
      className="mt-2 flex flex-col overflow-hidden rounded-md border border-slate-200 bg-slate-50"
      style={{ height: minimized ? undefined : panelHeight }}
    >
      {!minimized ? (
        <div
          role="separator"
          aria-orientation="horizontal"
          aria-label="Resize node configuration panel"
          className={cn(
            "h-1.5 shrink-0 cursor-row-resize bg-transparent transition-colors hover:bg-slate-200",
            isResizingPanel ? "bg-slate-300" : ""
          )}
          onMouseDown={(event) => {
            event.preventDefault();
            panelResizeStartRef.current = { startY: event.clientY, startHeight: panelHeight };
            setIsResizingPanel(true);
          }}
        />
      ) : null}
      <div className="flex items-center justify-between gap-2 border-b border-slate-200 bg-white px-3 py-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-semibold text-[#123262]">{node.label || node.id}</p>
            <TypeChip type={node.type} />
          </div>
          <p className="text-[11px] text-slate-500">Node configuration and mapping details</p>
        </div>
        <div className="flex items-center gap-1">
          <Button
            size="icon"
            variant="ghost"
            className="h-7 w-7"
            onClick={() => setMinimized((value) => !value)}
            title={minimized ? "Expand panel" : "Minimize panel"}
            aria-label={minimized ? "Expand panel" : "Minimize panel"}
          >
            <Minus size={14} />
          </Button>
          <Button
            size="icon"
            variant="ghost"
            className="h-7 w-7"
            onClick={() => onOpenChange(false)}
            title="Close panel"
            aria-label="Close panel"
          >
            <X size={14} />
          </Button>
        </div>
      </div>

      {!minimized ? (
        <div className="min-h-0 flex-1 overflow-auto p-3">
          <Tabs value={activeTab} onValueChange={setActiveTab}>
            <TabsList variant="line" className="w-full justify-start border-b border-slate-200 px-0">
              <TabsTrigger value="configuration" className="h-8 rounded-none px-3 text-xs">
                Configuration
              </TabsTrigger>
              <TabsTrigger value="input" className="h-8 rounded-none px-3 text-xs">
                Input
              </TabsTrigger>
              <TabsTrigger value="output" className="h-8 rounded-none px-3 text-xs">
                Output
              </TabsTrigger>
            </TabsList>

            <TabsContent value="configuration" className="mt-3 space-y-2">
              <Section title="Capability summary">
                <div className="rounded border border-slate-200 bg-slate-50 px-2 py-1.5 text-xs text-slate-700">
                  {capabilitySummary(node as any)}
                </div>
                {nodeWarnings.length > 0 ? (
                  <div className="mt-2 rounded border border-amber-200 bg-amber-50 px-2 py-1.5 text-xs text-amber-800">
                    <p className="mb-1 font-medium">Validation warnings</p>
                    <ul className="list-disc pl-4">
                      {nodeWarnings.map((warning, idx) => (
                        <li key={`${warning.message}-${idx}`}>{warning.message}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                {onEdit ? (
                  <div className="mt-2">
                    <Button size="sm" variant="outline" className="h-7 px-2 text-xs" onClick={() => onEdit(node.id)}>
                      Edit Capability
                    </Button>
                  </div>
                ) : null}
              </Section>
              <Section title="Identity">
                <MetaRow label="Node id" mono>
                  {node.id}
                </MetaRow>
                <MetaRow label="Type">
                  <TypeChip type={node.type} />
                </MetaRow>
                <MetaRow label="Label">{node.label || <span className="text-slate-400">—</span>}</MetaRow>
              </Section>

              {(node.type === "tool" || node.type === "mcp_tool") && (
                <Section title="Tool binding">
                  <MetaRow label="Tool name" mono>
                    {String(config.toolName || "")}
                  </MetaRow>
                  {toolMeta?.method ? (
                    <MetaRow label="Method" mono>
                      {toolMeta.method}
                    </MetaRow>
                  ) : null}
                  {toolMeta?.endpoint ? (
                    <MetaRow label="Endpoint" mono>
                      {toolMeta.endpoint}
                    </MetaRow>
                  ) : null}
                  {toolMeta?.description ? <MetaRow label="Description">{toolMeta.description}</MetaRow> : null}
                  {toolMeta?.requiredInputKeys?.length ? (
                    <MetaRow label="Required input" mono>
                      {toolMeta.requiredInputKeys.join(", ")}
                    </MetaRow>
                  ) : null}
                  {!toolMeta ? (
                    <div className="text-[11px] italic text-slate-400">
                      Tool metadata not found in catalog snapshot.
                    </div>
                  ) : null}
                </Section>
              )}

              {node.type === "decision" && (
                <Section title="Decision">
                  {String(config.expression || "").trim() ? (
                    <MetaRow label="Expression" mono>
                      {String(config.expression)}
                    </MetaRow>
                  ) : null}
                  <MetaRow label="Source key" mono>
                    {String(config.sourceKey || "")}
                  </MetaRow>
                  <MetaRow label="Equals" mono>
                    {String(config.equals || "")}
                  </MetaRow>
                  <MetaRow label="True branch">
                    <span className="font-mono">{String(config.trueBranch || "—")}</span>
                    {config.trueBranch ? (
                      <span className="ml-1 text-slate-500">→ {resolveNodeLabel(graph, config.trueBranch)}</span>
                    ) : null}
                  </MetaRow>
                  <MetaRow label="False branch">
                    <span className="font-mono">{String(config.falseBranch || "—")}</span>
                    {config.falseBranch ? (
                      <span className="ml-1 text-slate-500">→ {resolveNodeLabel(graph, config.falseBranch)}</span>
                    ) : null}
                  </MetaRow>
                </Section>
              )}
              {node.type === "switch" && (
                <Section title="Switch routing">
                  <MetaRow label="Source key" mono>
                    {String(config.sourceKey || "")}
                  </MetaRow>
                  <MetaRow label="Default" mono>
                    {String(config.default || "—")}
                  </MetaRow>
                  <div className="mt-2 space-y-1 text-xs">
                    {Array.isArray(config.cases) && config.cases.length > 0 ? (
                      config.cases.map((row: any, idx: number) => (
                        <div key={`case-${idx}`} className="rounded border border-slate-200 bg-slate-50 px-2 py-1 font-mono">
                          {String(row?.whenExpression || "").trim()
                            ? `whenExpression=${String(row?.whenExpression)}`
                            : `when=${String(row?.when || "")}`}{" "}
                          → {String(row?.to || "")}
                        </div>
                      ))
                    ) : (
                      <div className="text-[11px] italic text-slate-400">No switch cases configured.</div>
                    )}
                  </div>
                </Section>
              )}
              {node.type === "iterator" && (
                <Section title="Loop routing">
                  <MetaRow label="Alias" mono>
                    {String(config.as || "item")}
                  </MetaRow>
                  <MetaRow label="Over" mono>
                    {String(config.over || "—")}
                  </MetaRow>
                  <MetaRow label="Mode" mono>
                    {config.toolName ? `Inline tool (${String(config.toolName)})` : `Subchain (${String(config.subChainId || "—")})`}
                  </MetaRow>
                  <MetaRow label="Loop mode" mono>
                    {String(config.loopMode || "foreach")}
                  </MetaRow>
                  <MetaRow label="Exit condition" mono>
                    {String(config.exitCondition || "—")}
                  </MetaRow>
                  <MetaRow label="Collect output" mono>
                    {config.collectOutput ? "true" : "false"}
                  </MetaRow>
                </Section>
              )}
              {node.type === "assign" && (
                <Section title="Variable assignments">
                  {Array.isArray(config.assignments) && config.assignments.length > 0 ? (
                    <div className="space-y-1 text-xs">
                      {config.assignments.map((row: any, idx: number) => (
                        <div key={`assignment-${idx}`} className="rounded border border-slate-200 bg-slate-50 px-2 py-1 font-mono">
                          {String(row?.var || "")} = {String(row?.expression || "")}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="text-[11px] italic text-slate-400">No assignments configured.</div>
                  )}
                </Section>
              )}
              {node.type === "parallel" && (
                <Section title="Parallel fan-out">
                  <div className="text-xs text-slate-600">
                    Executes all outgoing branches concurrently; downstream merge waits for all incoming branches.
                  </div>
                </Section>
              )}
              {node.type === "code_execute" && (
                <Section title="Code execution">
                  <MetaRow label="Language" mono>
                    {String(config.language || "javascript")}
                  </MetaRow>
                  <MetaRow label="Timeout" mono>
                    {String(config.timeoutMs || "default")}
                  </MetaRow>
                  <MetaRow label="Memory MB" mono>
                    {String(config.memoryLimitMb || "default")}
                  </MetaRow>
                  <MetaRow label="Inputs" mono>
                    {Array.isArray(config.inputs) ? String(config.inputs.length) : "0"}
                  </MetaRow>
                  <div className="mt-2 rounded border border-slate-200 bg-slate-50 p-2">
                    <div className="text-[10px] uppercase tracking-wide text-slate-500">Snippet</div>
                    <pre className="mt-1 max-h-44 overflow-auto whitespace-pre-wrap font-mono text-[11px] text-slate-700">
                      {String(config.code || "") || "// empty"}
                    </pre>
                  </div>
                </Section>
              )}

              {node.type === "synthesis" && (
                <Section title="Synthesis prompt" defaultOpen={false}>
                  {effectiveSynthesisPrompt ? (
                    <pre className="whitespace-pre-wrap rounded bg-slate-50 p-2 font-mono text-[11px] leading-snug text-slate-700">
                      {effectiveSynthesisPrompt}
                    </pre>
                  ) : (
                    <div className="text-[11px] italic text-slate-400">
                      Empty prompt. Runtime falls back to artifact-level synthesis prompt.
                    </div>
                  )}
                </Section>
              )}
            </TabsContent>

            <TabsContent value="input" className="mt-3 space-y-2">
              {node.type === "start" ? (
                <Section title="Workflow input schema">
                  {workflowInputEntries.length ? (
                    <div className="space-y-2">
                      {workflowInputEntries.map(([key, prop]) => (
                        <div key={key} className="rounded border border-slate-200 bg-white px-2 py-1.5">
                          <div className="flex items-center gap-2">
                            <span className="font-mono text-xs text-slate-800">{key}</span>
                            {requiredWorkflowInputs.has(key) ? (
                              <span className="text-[10px] font-semibold uppercase tracking-wide text-red-600">
                                required
                              </span>
                            ) : null}
                          </div>
                          <div className="mt-0.5 text-[11px] text-slate-500">
                            type: <span className="font-mono">{String(prop?.type || "string")}</span>
                          </div>
                          {prop?.description ? (
                            <div className="mt-0.5 text-[11px] text-slate-600">{String(prop.description)}</div>
                          ) : null}
                          {prop?.default !== undefined ? (
                            <div className="mt-0.5 text-[11px] text-slate-500">
                              default: <span className="font-mono">{JSON.stringify(prop.default)}</span>
                            </div>
                          ) : null}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="text-[11px] italic text-slate-400">
                      No workflow input fields defined in schema. Runs use an empty input object by default.
                    </div>
                  )}
                </Section>
              ) : (
                <>
                  <Section title="Input source">
                    {config.inputKey ? (
                      <MetaRow label="Input key" mono>
                        context.{String(config.inputKey)}
                      </MetaRow>
                    ) : (
                      <div className="text-[11px] italic text-slate-400">
                        No inputKey. Runtime uses the entire context map.
                      </div>
                    )}
                  </Section>
                  <Section title="Data mapper">
                    <div className="overflow-hidden rounded border border-slate-200 bg-white">
                      <div className="grid grid-cols-[minmax(0,1fr)_72px_minmax(0,1fr)_minmax(0,1fr)] border-b border-slate-200 bg-slate-50 text-[11px] font-medium text-slate-600">
                        <div className="px-2 py-1.5">Source</div>
                        <div className="border-l border-slate-200 px-2 py-1.5 text-center">Map</div>
                        <div className="border-l border-slate-200 px-2 py-1.5">Target</div>
                        <div className="border-l border-slate-200 px-2 py-1.5">Mapping Expressions</div>
                      </div>
                      <div className="grid grid-cols-[minmax(0,1fr)_72px_minmax(0,1fr)_minmax(0,1fr)] text-xs">
                        <div className="min-h-[120px] border-r border-slate-200 px-2 py-2">
                          <div className="space-y-1">
                            {upstreamSourceNodes.map((sourceNode) => (
                              <div key={sourceNode.id} className="rounded border border-slate-200 px-2 py-1">
                                <div className="truncate font-medium text-slate-800">{sourceNode.label}</div>
                                <div className="text-[10px] text-slate-500">{sourceNode.type}</div>
                              </div>
                            ))}
                            {upstreamSourceNodes.length === 0 ? (
                              <div className="text-[11px] italic text-slate-400">No connected upstream nodes.</div>
                            ) : null}
                          </div>
                        </div>
                        <div className="min-h-[120px] border-r border-slate-200 px-2 py-2">
                          {mappingExpressionRows.length > 0 ? (
                            <div className="space-y-1">
                              {mappingExpressionRows.map((row, idx) => (
                                <div
                                  key={`line-${row.target}-${idx}`}
                                  className="flex h-6 items-center justify-center"
                                  title={`${row.source} -> ${row.target}`}
                                >
                                  <span className="h-2 w-2 rounded-full bg-rose-500" />
                                  <span className="mx-1 h-px flex-1 border-t border-dashed border-rose-400" />
                                  <span className="h-2 w-2 rounded-full bg-rose-500" />
                                </div>
                              ))}
                            </div>
                          ) : (
                            <div className="text-[11px] italic text-slate-400">No mapper lines.</div>
                          )}
                        </div>
                        <div className="min-h-[120px] border-r border-slate-200 px-2 py-2">
                          {mappingExpressionRows.length > 0 ? (
                            <div className="space-y-1">
                              {mappingExpressionRows.map((row, idx) => (
                                <div key={`${row.target}-${idx}`} className="h-6 font-mono text-slate-700">
                                  {row.target}
                                </div>
                              ))}
                            </div>
                          ) : (
                            <div className="text-[11px] italic text-slate-400">No mapped target params.</div>
                          )}
                        </div>
                        <div className="min-h-[120px] px-2 py-2">
                          {mappingExpressionRows.length > 0 ? (
                            <div className="space-y-1">
                              {mappingExpressionRows.map((row, idx) => (
                                <div
                                  key={`${row.target}:${row.expression}:${idx}`}
                                  className="h-6 rounded border border-rose-200 bg-rose-50 px-1.5 py-1 font-mono text-[11px] text-rose-900"
                                >
                                  {row.expression}
                                </div>
                              ))}
                            </div>
                          ) : (
                            <div className="text-[11px] italic text-slate-400">No mapping expressions.</div>
                          )}
                        </div>
                      </div>
                    </div>
                  </Section>
                </>
              )}
            </TabsContent>

            <TabsContent value="output" className="mt-3 space-y-2">
              <Section title="Output mapping (target <- source)">
                <MappingTable
                  rows={argMappings}
                  leftLabel="Target (endpoint param)"
                  rightLabel="Source (context path)"
                />
              </Section>
              <Section title="Edges" defaultOpen={false}>
                <div className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
                  Inbound ({inboundEdges.length})
                </div>
                {inboundEdges.length === 0 ? (
                  <div className="text-[11px] italic text-slate-400">None</div>
                ) : (
                  <ul className="mt-1 space-y-0.5 text-xs">
                    {inboundEdges.map((e, i) => {
                      const from = e.from || e.source || "";
                      return (
                        <li key={`in-${i}-${from}`} className="font-mono text-slate-700">
                          <span className="text-slate-500">{from}</span>
                          <span className="mx-1 text-slate-400">→</span>
                          <span>{node.id}</span>
                          {e.condition ? <span className="ml-1 text-amber-600">[{e.condition}]</span> : null}
                        </li>
                      );
                    })}
                  </ul>
                )}
                <div className="mt-2 text-[11px] font-medium uppercase tracking-wide text-slate-500">
                  Outbound ({outboundEdges.length})
                </div>
                {outboundEdges.length === 0 ? (
                  <div className="text-[11px] italic text-slate-400">None</div>
                ) : (
                  <ul className="mt-1 space-y-0.5 text-xs">
                    {outboundEdges.map((e, i) => {
                      const to = e.to || e.target || "";
                      return (
                        <li key={`out-${i}-${to}`} className="font-mono text-slate-700">
                          <span>{node.id}</span>
                          <span className="mx-1 text-slate-400">→</span>
                          <span className="text-slate-500">{to}</span>
                          {e.condition ? <span className="ml-1 text-amber-600">[{e.condition}]</span> : null}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </Section>
            </TabsContent>
          </Tabs>
        </div>
      ) : null}
    </div>
  );
}
