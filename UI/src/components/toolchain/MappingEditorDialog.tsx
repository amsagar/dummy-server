import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { toast } from "sonner";
import { api } from "@/services/api";

type Mapping = {
  expr: string;
  fallback?: any;
  policy: "strict" | "llm_assisted";
};

type NodeRow = {
  id: string;
  toolName: string;
  args: { argName: string; mapping: Mapping }[];
};

const SYSTEM_FUNCTION_HINTS = [
  "upper(x)", "lower(x)", "trim(x)", "coalesce(a,b)", "len(x)", "slice(x,0,10)", "parseInt(x)",
  "replace(x,a,b)", "concat(a,b,c)", "now()", "uuid()", "keys(obj)", "values(obj)", "contains(arr,x)",
  "first(arr)", "last(arr)", "length(arr)", "join(arr,sep)", "split(s,sep)", "hash(x,sha256)",
  "ref:tool/get_order", "ref:chain/<chainId>", "ref:dt/<tableName>", "ref:secret/<key>",
];

function parseGraph(graphJson: any): NodeRow[] {
  let graph: any = graphJson;
  if (typeof graphJson === "string") {
    try { graph = JSON.parse(graphJson); } catch { return []; }
  }
  if (!graph || !Array.isArray(graph.nodes)) return [];
  const rows: NodeRow[] = [];
  for (const node of graph.nodes) {
    const type = String(node?.type || "").toLowerCase();
    if (type !== "tool" && type !== "mcp_tool" && type !== "decision_table") continue;
    const cfg = node?.config || {};
    const argMappings = cfg.argMappings || {};
    const args: NodeRow["args"] = [];
    for (const [argName, raw] of Object.entries(argMappings)) {
      args.push({ argName, mapping: normalizeMapping(raw) });
    }
    rows.push({ id: String(node.id), toolName: String(cfg.toolName || node.label || node.id), args });
  }
  return rows;
}

function normalizeMapping(raw: any): Mapping {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const policy = String(raw.policy || "strict").toLowerCase();
    return {
      expr: raw.expr == null ? "" : String(raw.expr),
      fallback: raw.fallback,
      policy: policy === "llm_assisted" ? "llm_assisted" : "strict",
    };
  }
  if (typeof raw === "string") return { expr: raw, policy: "strict" };
  return { expr: raw == null ? "" : String(raw), policy: "strict" };
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  toolChainId: string | null;
  toolChainName?: string;
}

export function MappingEditorDialog({ open, onOpenChange, toolChainId, toolChainName }: Props) {
  const queryClient = useQueryClient();
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, Record<string, Mapping>>>({});
  const [testResults, setTestResults] = useState<Record<string, { ok: boolean; value?: any; error?: string }>>({});

  const { data: versions = [], isLoading } = useQuery<any[]>({
    queryKey: ["toolchain-versions", toolChainId, "mapping-editor"],
    queryFn: () => api.toolchains.versions(toolChainId!),
    enabled: !!toolChainId && open,
  });

  const latestVersion = Array.isArray(versions) && versions.length > 0 ? versions[0] : null;
  const nodes = useMemo(() => parseGraph(latestVersion?.graphJson), [latestVersion]);

  useEffect(() => {
    if (!open) {
      setDrafts({});
      setTestResults({});
      setActiveNodeId(null);
      return;
    }
    if (nodes.length > 0 && !activeNodeId) setActiveNodeId(nodes[0].id);
  }, [open, nodes, activeNodeId]);

  const draftFor = (nodeId: string, argName: string, original: Mapping): Mapping => {
    return drafts[nodeId]?.[argName] ?? original;
  };

  const setDraft = (nodeId: string, argName: string, patch: Partial<Mapping>) => {
    setDrafts((prev) => {
      const node = { ...(prev[nodeId] || {}) };
      const original = nodes.find((n) => n.id === nodeId)?.args.find((a) => a.argName === argName)?.mapping;
      const current = node[argName] ?? (original ? { ...original } : { expr: "", policy: "strict" as const });
      node[argName] = { ...current, ...patch } as Mapping;
      return { ...prev, [nodeId]: node };
    });
  };

  const testMutation = useMutation({
    mutationFn: async ({ nodeId, argName, expr }: { nodeId: string; argName: string; expr: string }) => {
      const result = await api.toolchains.testMapping(toolChainId!, expr);
      return { nodeId, argName, result };
    },
    onSuccess: ({ nodeId, argName, result }) => {
      setTestResults((prev) => ({
        ...prev,
        [`${nodeId}::${argName}`]: result.ok ? { ok: true, value: result.value } : { ok: false, error: result.error },
      }));
    },
    onError: (e: any, vars) => {
      setTestResults((prev) => ({
        ...prev,
        [`${vars.nodeId}::${vars.argName}`]: { ok: false, error: e?.message || "Test failed" },
      }));
    },
  });

  const saveMutation = useMutation({
    mutationFn: async ({ nodeId, argName, mapping }: { nodeId: string; argName: string; mapping: Mapping }) => {
      await api.toolchains.updateMapping(toolChainId!, nodeId, argName, mapping);
      return { nodeId, argName };
    },
    onSuccess: ({ nodeId, argName }) => {
      toast.success(`Saved ${nodeId}.${argName}`);
      setDrafts((prev) => {
        const node = { ...(prev[nodeId] || {}) };
        delete node[argName];
        return { ...prev, [nodeId]: node };
      });
      queryClient.invalidateQueries({ queryKey: ["toolchain-versions", toolChainId, "mapping-editor"] });
    },
    onError: (e: any) => toast.error(e?.message || "Save failed"),
  });

  const activeNode = nodes.find((n) => n.id === activeNodeId) || null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            Mappings — {toolChainName || "Toolchain"}
          </DialogTitle>
        </DialogHeader>

        {isLoading && <div className="py-8 text-center text-sm text-slate-500">Loading mappings…</div>}
        {!isLoading && nodes.length === 0 && (
          <div className="py-8 text-center text-sm text-slate-500">
            No tool nodes with argMappings found on the latest version.
          </div>
        )}

        {!isLoading && nodes.length > 0 && (
          <div className="flex flex-col gap-4">
            <Tabs value={activeNodeId || ""} onValueChange={(v) => setActiveNodeId(v)}>
              <TabsList variant="line" className="flex-wrap">
                {nodes.map((node) => (
                  <TabsTrigger key={node.id} value={node.id}>
                    {node.toolName}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>

            {activeNode && (
              <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto pr-2">
                {activeNode.args.length === 0 && (
                  <div className="text-sm text-slate-500">This node has no argMappings to edit.</div>
                )}
                {activeNode.args.map(({ argName, mapping }) => {
                  const draft = draftFor(activeNode.id, argName, mapping);
                  const dirty = drafts[activeNode.id]?.[argName] !== undefined;
                  const testKey = `${activeNode.id}::${argName}`;
                  const testResult = testResults[testKey];
                  return (
                    <div
                      key={argName}
                      className="rounded-md border border-slate-200 bg-white p-3 shadow-xs"
                    >
                      <div className="flex items-center justify-between">
                        <div className="font-medium text-sm">{argName}</div>
                        <div className="flex items-center gap-2">
                          <select
                            value={draft.policy}
                            onChange={(e) => setDraft(activeNode.id, argName, { policy: e.target.value as any })}
                            className="rounded border border-slate-200 bg-white px-2 py-1 text-xs"
                          >
                            <option value="strict">strict</option>
                            <option value="llm_assisted">llm_assisted</option>
                          </select>
                        </div>
                      </div>
                      <div className="mt-2 grid gap-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() =>
                              setDraft(activeNode.id, argName, {
                                expr: draft.expr || '#if($.input.country == "US") "USD" #else "EUR" #endif',
                              })
                            }
                          >
                            Insert conditional
                          </Button>
                          <span className="text-[11px] text-slate-500">
                            Functions: {SYSTEM_FUNCTION_HINTS.slice(0, 6).join(", ")}...
                          </span>
                        </div>
                        <label className="flex flex-col gap-1 text-xs">
                          <span className="text-slate-600">JSONata expression</span>
                          <Input
                            value={draft.expr}
                            onChange={(e) => setDraft(activeNode.id, argName, { expr: e.target.value })}
                            placeholder="$.tool_1.output.customer.id"
                            className="font-mono text-xs"
                            list="mapping-system-functions"
                          />
                        </label>
                        <label className="flex flex-col gap-1 text-xs">
                          <span className="text-slate-600">Fallback (optional, JSON)</span>
                          <Input
                            value={draft.fallback === undefined ? "" : JSON.stringify(draft.fallback)}
                            onChange={(e) => {
                              const raw = e.target.value;
                              if (raw === "") {
                                setDraft(activeNode.id, argName, { fallback: undefined });
                                return;
                              }
                              try {
                                setDraft(activeNode.id, argName, { fallback: JSON.parse(raw) });
                              } catch {
                                setDraft(activeNode.id, argName, { fallback: raw });
                              }
                            }}
                            placeholder='"US" or 30 or {"key":"value"}'
                            className="font-mono text-xs"
                          />
                        </label>
                      </div>
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={!draft.expr || testMutation.isPending}
                          onClick={() => testMutation.mutate({ nodeId: activeNode.id, argName, expr: draft.expr })}
                        >
                          Test against recorded turn
                        </Button>
                        <Button
                          size="sm"
                          disabled={!dirty || saveMutation.isPending}
                          onClick={() => saveMutation.mutate({ nodeId: activeNode.id, argName, mapping: draft })}
                        >
                          Save
                        </Button>
                        {testResult && (
                          <span
                            className={`text-xs font-mono ${
                              testResult.ok ? "text-emerald-700" : "text-rose-700"
                            }`}
                            title={testResult.ok ? "Resolved value" : "Error"}
                          >
                            {testResult.ok ? formatPreview(testResult.value) : `error: ${testResult.error}`}
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
        </DialogFooter>
      </DialogContent>
      <datalist id="mapping-system-functions">
        {SYSTEM_FUNCTION_HINTS.map((hint) => (
          <option key={hint} value={hint} />
        ))}
      </datalist>
    </Dialog>
  );
}

function formatPreview(value: any): string {
  if (value === null || value === undefined) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  try {
    const json = JSON.stringify(value);
    return json.length > 100 ? json.slice(0, 100) + "…" : json;
  } catch {
    return String(value);
  }
}
