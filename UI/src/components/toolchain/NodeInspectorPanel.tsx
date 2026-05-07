import { useMemo, useState, type ReactNode } from "react";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Pencil, ChevronDown, ChevronRight } from "lucide-react";

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
  toolsCatalog?: CatalogToolRow[];
  mcpCatalog?: CatalogToolRow[];
  onEdit?: (nodeId: string) => void;
};

const NODE_TYPE_COLORS: Record<string, string> = {
  start: "bg-emerald-100 text-emerald-800 border-emerald-300",
  end: "bg-rose-100 text-rose-800 border-rose-300",
  tool: "bg-sky-100 text-sky-800 border-sky-300",
  mcp_tool: "bg-indigo-100 text-indigo-800 border-indigo-300",
  decision: "bg-amber-100 text-amber-800 border-amber-300",
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

function Row({ label, children, mono }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[120px_1fr] items-start gap-2 py-1.5">
      <div className="text-[11px] font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className={cn("text-xs text-slate-800 break-all", mono && "font-mono")}>{children}</div>
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
    <div className="border-t border-slate-200 py-2 first:border-t-0">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-1 text-left text-[12px] font-semibold text-slate-700 hover:text-[#123262]"
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        {title}
      </button>
      {open ? <div className="mt-1.5">{children}</div> : null}
    </div>
  );
}

function resolveNodeLabel(graph: Props["graph"], id: string | undefined): string {
  if (!id || !graph) return id || "—";
  const found = graph.nodes.find((n) => n.id === id);
  return found?.label || id;
}

export default function NodeInspectorPanel({
  open,
  onOpenChange,
  node,
  graph,
  synthesisPrompt,
  toolsCatalog = [],
  mcpCatalog = [],
  onEdit,
}: Props) {
  const inboundEdges = useMemo(
    () =>
      (graph?.edges || []).filter((e) => (e.to || e.target) === node?.id),
    [graph, node]
  );
  const outboundEdges = useMemo(
    () =>
      (graph?.edges || []).filter((e) => (e.from || e.source) === node?.id),
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

  if (!node) return null;

  const config = node.config || {};
  const effectiveSynthesisPrompt = String(config.prompt || synthesisPrompt || "").trim();

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-[380px] sm:max-w-[380px] gap-0 p-0">
        <SheetHeader className="border-b border-slate-200 px-4 py-3">
          <SheetTitle className="flex items-center gap-2 text-sm font-semibold text-[#123262]">
            <span className="truncate">{node.label || node.id}</span>
            <TypeChip type={node.type} />
          </SheetTitle>
          <SheetDescription className="text-[11px] text-slate-500">
            Read-only details. Double-click the node on the board to edit.
          </SheetDescription>
        </SheetHeader>

        <div className="flex-1 overflow-auto px-4 py-2">
          <Section title="Identity">
            <Row label="Node id" mono>
              {node.id}
            </Row>
            <Row label="Type">
              <TypeChip type={node.type} />
            </Row>
            <Row label="Label">{node.label || <span className="text-slate-400">—</span>}</Row>
          </Section>

          {(node.type === "tool" || node.type === "mcp_tool") && (
            <Section title="Tool binding">
              <Row label="Tool name" mono>
                {String(config.toolName || "")}
              </Row>
              {toolMeta?.method ? (
                <Row label="Method" mono>
                  {toolMeta.method}
                </Row>
              ) : null}
              {toolMeta?.endpoint ? (
                <Row label="Endpoint" mono>
                  {toolMeta.endpoint}
                </Row>
              ) : null}
              {toolMeta?.description ? (
                <Row label="Description">{toolMeta.description}</Row>
              ) : null}
              {toolMeta?.pathParams && toolMeta.pathParams.length ? (
                <Row label="Path params" mono>
                  {toolMeta.pathParams.join(", ")}
                </Row>
              ) : null}
              {toolMeta?.requiredInputKeys && toolMeta.requiredInputKeys.length ? (
                <Row label="Required input keys" mono>
                  {toolMeta.requiredInputKeys.join(", ")}
                </Row>
              ) : null}
              {!toolMeta && (
                <div className="text-[11px] italic text-slate-400">
                  Tool metadata not in catalog snapshot — the chain may still execute correctly at runtime.
                </div>
              )}
            </Section>
          )}

          {(node.type === "tool" || node.type === "mcp_tool") && (
            <Section title="Input wiring">
              {config.inputKey ? (
                <Row label="Input key" mono>
                  <span title="The runtime reads context[inputKey] as the base payload before applying argMappings.">
                    context.{String(config.inputKey)}
                  </span>
                </Row>
              ) : (
                <div className="text-[11px] italic text-slate-400">
                  No <span className="font-mono">inputKey</span> — the entire context map is dumped as the base payload.
                </div>
              )}
              {argMappings.length > 0 ? (
                <div className="mt-2">
                  <div className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
                    Arg mappings (target ← source)
                  </div>
                  <table className="mt-1 w-full text-xs">
                    <thead>
                      <tr className="text-left text-[10px] uppercase text-slate-400">
                        <th className="pb-1 font-medium">Target (endpoint param)</th>
                        <th className="pb-1 font-medium">Source (context path)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {argMappings.map((row) => (
                        <tr key={row.target} className="border-t border-slate-100">
                          <td className="py-1 pr-2 font-mono">{row.target}</td>
                          <td className="py-1 font-mono text-slate-600">{row.source}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="mt-1 text-[11px] italic text-slate-400">
                  No <span className="font-mono">argMappings</span> — payload keys must already match the endpoint's expected names.
                </div>
              )}
            </Section>
          )}

          {node.type === "decision" && (
            <Section title="Decision">
              <Row label="Source key" mono>
                {String(config.sourceKey || "")}
              </Row>
              <Row label="Equals" mono>
                {String(config.equals || "")}
              </Row>
              <Row label="True branch">
                <span className="font-mono">{String(config.trueBranch || "—")}</span>
                {config.trueBranch ? (
                  <span className="ml-1 text-slate-500">→ {resolveNodeLabel(graph, config.trueBranch)}</span>
                ) : null}
              </Row>
              <Row label="False branch">
                <span className="font-mono">{String(config.falseBranch || "—")}</span>
                {config.falseBranch ? (
                  <span className="ml-1 text-slate-500">→ {resolveNodeLabel(graph, config.falseBranch)}</span>
                ) : null}
              </Row>
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
                  Empty — runtime will use the artifact-level <span className="font-mono">synthesisPrompt</span>.
                </div>
              )}
            </Section>
          )}

          <Section title="Edges" defaultOpen={false}>
            <div className="text-[11px] font-medium uppercase tracking-wide text-slate-500">Inbound ({inboundEdges.length})</div>
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
            <div className="mt-2 text-[11px] font-medium uppercase tracking-wide text-slate-500">Outbound ({outboundEdges.length})</div>
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
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-slate-200 px-4 py-3">
          <Button
            size="sm"
            variant="outline"
            className="h-8 px-3 text-xs"
            onClick={() => onEdit?.(node.id)}
            disabled={!onEdit}
          >
            <Pencil size={12} className="mr-1" />
            Edit
          </Button>
          <Button size="sm" variant="ghost" className="h-8 px-3 text-xs" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
}
