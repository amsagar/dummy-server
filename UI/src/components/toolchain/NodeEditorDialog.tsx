import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Info } from "lucide-react";
import { cn } from "@/lib/utils";
import { api } from "@/services/api";

type GraphNode = {
  id: string;
  type: string;
  label?: string;
  config?: Record<string, any>;
};

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  node: GraphNode | null;
  allNodes?: GraphNode[];
  onSave?: (nextNode: GraphNode) => void;
};

function FieldRow({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-1">
        <label className="text-[11px] font-medium uppercase tracking-wide text-slate-500">{label}</label>
        {hint ? (
          <span title={hint}>
            <Info size={11} className="text-slate-400" />
          </span>
        ) : null}
      </div>
      {children}
    </div>
  );
}

export default function NodeEditorDialog({ open, onOpenChange, node, allNodes = [], onSave }: Props) {
  const config = node?.config || {};
  const [label, setLabel] = useState("");
  const [draftConfig, setDraftConfig] = useState<Record<string, any>>({});
  const [validationError, setValidationError] = useState("");
  const [expressionError, setExpressionError] = useState("");

  useEffect(() => {
    setLabel(String(node?.label || ""));
    setDraftConfig({ ...(node?.config || {}) });
    setValidationError("");
    setExpressionError("");
  }, [node?.id, node?.label, node?.config]);

  useEffect(() => {
    const type = String(node?.type || "").toLowerCase();
    if (type !== "decision") return;
    const expr = String(draftConfig.expression || "").trim();
    if (!expr) {
      setExpressionError("");
      return;
    }
    const timer = window.setTimeout(async () => {
      try {
        const result = await api.toolchains.validateExpression(expr);
        setExpressionError(result?.valid ? "" : String(result?.error || "Invalid expression"));
      } catch {
        setExpressionError("Expression validation request failed.");
      }
    }, 300);
    return () => window.clearTimeout(timer);
  }, [node?.type, draftConfig.expression]);

  const argMappings = useMemo(() => {
    const raw = draftConfig.argMappings;
    if (!raw || typeof raw !== "object") return [] as Array<{ target: string; source: string }>;
    return Object.entries(raw).map(([target, source]) => ({ target, source: String(source ?? "") }));
  }, [draftConfig.argMappings]);
  const branchTargets = useMemo(
    () => allNodes.filter((candidate) => candidate.id !== node?.id).map((candidate) => candidate.id),
    [allNodes, node?.id]
  );

  const save = () => {
    if (!node) return;
    const type = String(node.type || "").toLowerCase();
    if (type === "decision") {
      const expression = String(draftConfig.expression || "").trim();
      if (!expression && !String(draftConfig.sourceKey || "").trim()) {
        return setValidationError("Decision sourceKey is required in simple mode.");
      }
      if (!String(draftConfig.trueBranch || "").trim()) return setValidationError("Decision trueBranch is required.");
      if (!String(draftConfig.falseBranch || "").trim()) return setValidationError("Decision falseBranch is required.");
      if (expressionError) return setValidationError(expressionError);
    }
    if (type === "switch") {
      const cases = Array.isArray(draftConfig.cases) ? draftConfig.cases : [];
      const hasExprCase = cases.some((row: any) => String(row?.whenExpression || "").trim().length > 0);
      if (!hasExprCase && !String(draftConfig.sourceKey || "").trim()) {
        return setValidationError("Switch sourceKey is required unless cases use whenExpression.");
      }
      if (cases.length === 0) return setValidationError("Switch must define at least one case.");
    }
    if (type === "iterator") {
      const loopMode = String(draftConfig.loopMode || "foreach").toLowerCase();
      const hasTool = !!String(draftConfig.toolName || "").trim();
      const hasSubchain = !!String(draftConfig.subChainId || "").trim();
      if (!hasTool && !hasSubchain) return setValidationError("Iterator requires toolName or subChainId.");
      if (hasTool && !draftConfig?.argMappings?.items) {
        return setValidationError("Iterator inline tool mode requires argMappings.items.");
      }
      if (loopMode !== "foreach" && !String(draftConfig.exitCondition || "").trim()) {
        return setValidationError("Iterator exitCondition is required for while/until loopMode.");
      }
    }
    if (type === "subchain" && !String(draftConfig.chainId || "").trim()) {
      return setValidationError("Subchain chainId is required.");
    }
    if (type === "assign") {
      const assignments = Array.isArray(draftConfig.assignments) ? draftConfig.assignments : [];
      if (assignments.length === 0) return setValidationError("Assign node requires at least one assignment.");
    }
    setValidationError("");
    onSave?.({
      ...node,
      label: label.trim() || node.label,
      config: { ...draftConfig },
    });
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="text-sm font-semibold text-[#123262]">
            Edit node {node ? <span className="font-mono">{node.id}</span> : ""}
          </DialogTitle>
          <DialogDescription className="text-[11px] text-slate-500">Capability-aware node configuration.</DialogDescription>
        </DialogHeader>

        {node ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FieldRow label="Node id" hint="Stable identifier — do not change after edges reference it.">
              <Input value={node.id} disabled className="h-8 font-mono text-xs" />
            </FieldRow>
            <FieldRow label="Type" hint="One of: start, end, tool, mcp_tool, decision, switch, iterator, parallel, assign, synthesis.">
              <Input value={node.type} disabled className="h-8 font-mono text-xs" />
            </FieldRow>
            <FieldRow label="Label" hint="Display name shown on the flow board.">
              <Input value={label} onChange={(e) => setLabel(e.target.value)} className="h-8 text-xs sm:col-span-2" />
            </FieldRow>

            {(node.type === "tool" || node.type === "mcp_tool") && (
              <>
                <FieldRow label="Tool name" hint="Catalog entry the runtime will invoke.">
                  <Input
                    value={String(draftConfig.toolName || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, toolName: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow
                  label="Input key"
                  hint="If set, runtime takes context[<inputKey>] (dotted path) as the base payload."
                >
                  <Input
                    value={String(draftConfig.inputKey || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, inputKey: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Approval mode" hint="required / required_if_sensitive / empty">
                  <Input
                    value={String(draftConfig.approvalMode || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, approvalMode: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
              </>
            )}

            {node.type === "decision" && (
              <>
                <FieldRow label="Mode" hint="Simple compares sourceKey/equals. Expression mode evaluates a boolean expression.">
                  <select
                    value={String(draftConfig.expression || "").trim() ? "expression" : "simple"}
                    onChange={(e) =>
                      setDraftConfig((prev) => ({
                        ...prev,
                        expression: e.target.value === "expression" ? String(prev.expression || "") : "",
                      }))
                    }
                    className="h-8 rounded border border-slate-200 bg-white px-2 text-xs"
                  >
                    <option value="simple">Simple (field equals value)</option>
                    <option value="expression">Expression</option>
                  </select>
                </FieldRow>
                {String(draftConfig.expression || "").trim() ? (
                  <div className="sm:col-span-2">
                    <FieldRow
                      label="Expression"
                      hint='Example: $.input.amount > 100 && $.input.country == "US"'
                    >
                      <textarea
                        value={String(draftConfig.expression || "")}
                        onChange={(e) => setDraftConfig((prev) => ({ ...prev, expression: e.target.value }))}
                        rows={4}
                        className="w-full rounded border border-slate-200 bg-slate-50 p-2 font-mono text-[11px] leading-snug text-slate-700"
                      />
                    </FieldRow>
                    {expressionError ? (
                      <p className="mt-1 text-xs text-rose-700">{expressionError}</p>
                    ) : (
                      <p className="mt-1 text-[11px] text-slate-500">Supports &&, ||, !, ==, !=, &lt;, &lt;=, &gt;, &gt;=, IN, CONTAINS.</p>
                    )}
                  </div>
                ) : null}
                <FieldRow label="Source key" hint="Context path to read for the boolean comparison.">
                  <Input
                    value={String(draftConfig.sourceKey || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, sourceKey: e.target.value }))}
                    className="h-8 font-mono text-xs"
                    disabled={String(draftConfig.expression || "").trim().length > 0}
                  />
                </FieldRow>
                <FieldRow label="Equals" hint="Value the source must equal to take the true branch.">
                  <Input
                    value={String(draftConfig.equals || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, equals: e.target.value }))}
                    className="h-8 font-mono text-xs"
                    disabled={String(draftConfig.expression || "").trim().length > 0}
                  />
                </FieldRow>
                <FieldRow label="True branch" hint="Node id taken when the condition is true.">
                  <Input
                    list={`branch-targets-${node.id}`}
                    value={String(draftConfig.trueBranch || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, trueBranch: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="False branch" hint="Node id taken when the condition is false.">
                  <Input
                    list={`branch-targets-${node.id}`}
                    value={String(draftConfig.falseBranch || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, falseBranch: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
              </>
            )}
            {node.type === "switch" && (
              <>
                <FieldRow label="Source key" hint="Context path used to match switch cases.">
                  <Input
                    value={String(draftConfig.sourceKey || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, sourceKey: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Default target" hint="Fallback branch node id.">
                  <Input
                    list={`branch-targets-${node.id}`}
                    value={String(draftConfig.defaultBranch || draftConfig.default || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, default: e.target.value, defaultBranch: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <div className="sm:col-span-2 rounded border border-slate-200 p-2">
                  <p className="mb-2 text-[11px] font-medium uppercase tracking-wide text-slate-500">Cases</p>
                  {(Array.isArray(draftConfig.cases) ? draftConfig.cases : []).map((row: any, idx: number) => (
                    <div key={`${idx}`} className="mb-2 grid grid-cols-[1fr_1fr_1fr_auto] gap-2">
                      <Input
                        value={String(row?.when || "")}
                        onChange={(e) =>
                          setDraftConfig((prev) => {
                            const cases = Array.isArray(prev.cases) ? [...prev.cases] : [];
                            cases[idx] = { ...(cases[idx] || {}), when: e.target.value };
                            return { ...prev, cases };
                          })
                        }
                        placeholder="when"
                        className="h-8 font-mono text-xs"
                        disabled={String(row?.whenExpression || "").trim().length > 0}
                      />
                      <Input
                        value={String(row?.whenExpression || "")}
                        onChange={(e) =>
                          setDraftConfig((prev) => {
                            const cases = Array.isArray(prev.cases) ? [...prev.cases] : [];
                            cases[idx] = { ...(cases[idx] || {}), whenExpression: e.target.value };
                            return { ...prev, cases };
                          })
                        }
                        placeholder="when expression (optional)"
                        className="h-8 font-mono text-xs"
                      />
                      <Input
                        list={`branch-targets-${node.id}`}
                        value={String(row?.to || "")}
                        onChange={(e) =>
                          setDraftConfig((prev) => {
                            const cases = Array.isArray(prev.cases) ? [...prev.cases] : [];
                            cases[idx] = { ...(cases[idx] || {}), to: e.target.value };
                            return { ...prev, cases };
                          })
                        }
                        placeholder="to node id"
                        className="h-8 font-mono text-xs"
                      />
                      <Button
                        type="button"
                        variant="outline"
                        className="h-8 px-2 text-xs"
                        onClick={() =>
                          setDraftConfig((prev) => ({
                            ...prev,
                            cases: (Array.isArray(prev.cases) ? prev.cases : []).filter((_: any, i: number) => i !== idx),
                          }))
                        }
                      >
                        Remove
                      </Button>
                    </div>
                  ))}
                  <Button
                    type="button"
                    variant="outline"
                    className="h-8 px-2 text-xs"
                    onClick={() =>
                      setDraftConfig((prev) => ({
                        ...prev,
                        cases: [...(Array.isArray(prev.cases) ? prev.cases : []), { when: "", to: "" }],
                      }))
                    }
                  >
                    Add case
                  </Button>
                </div>
              </>
            )}
            {node.type === "iterator" && (
              <>
                <FieldRow label="Alias (as)" hint="Iterator item variable alias.">
                  <Input
                    value={String(draftConfig.as || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, as: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Loop source (over)" hint="Path or expression producing loop items.">
                  <Input
                    value={String(draftConfig.over || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, over: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Loop mode" hint="foreach runs over all items; while/until uses exitCondition.">
                  <select
                    value={String(draftConfig.loopMode || "foreach")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, loopMode: e.target.value }))}
                    className="h-8 rounded border border-slate-200 bg-white px-2 text-xs"
                  >
                    <option value="foreach">foreach</option>
                    <option value="while">while</option>
                    <option value="until">until</option>
                  </select>
                </FieldRow>
                <FieldRow label="Max iterations" hint="Safety cap for while/until loops.">
                  <Input
                    value={String(draftConfig.maxIterations ?? 1000)}
                    onChange={(e) =>
                      setDraftConfig((prev) => ({
                        ...prev,
                        maxIterations: e.target.value ? Number(e.target.value) : 1000,
                      }))
                    }
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Tool name" hint="Inline loop tool mode (optional).">
                  <Input
                    value={String(draftConfig.toolName || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, toolName: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Subchain id" hint="Subchain loop mode (optional).">
                  <Input
                    value={String(draftConfig.subChainId || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, subChainId: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Exit condition" hint='Boolean expression for while/until (e.g. $.vars.done == true).'>
                  <Input
                    value={String(draftConfig.exitCondition || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, exitCondition: e.target.value }))}
                    className="h-8 font-mono text-xs"
                    disabled={String(draftConfig.loopMode || "foreach").toLowerCase() === "foreach"}
                  />
                </FieldRow>
                <FieldRow label="Collect output" hint="Expose $.<iteratorId>.results for downstream mappings.">
                  <select
                    value={draftConfig.collectOutput ? "true" : "false"}
                    onChange={(e) =>
                      setDraftConfig((prev) => ({ ...prev, collectOutput: e.target.value === "true" }))
                    }
                    className="h-8 rounded border border-slate-200 bg-white px-2 text-xs"
                  >
                    <option value="false">false</option>
                    <option value="true">true</option>
                  </select>
                </FieldRow>
              </>
            )}
            {node.type === "subchain" && (
              <>
                <FieldRow label="Chain id" hint="Target subchain id.">
                  <Input
                    value={String(draftConfig.chainId || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, chainId: e.target.value }))}
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
                <FieldRow label="Version" hint="Optional fixed version number.">
                  <Input
                    value={String(draftConfig.version || "")}
                    onChange={(e) =>
                      setDraftConfig((prev) => ({ ...prev, version: e.target.value ? Number(e.target.value) : undefined }))
                    }
                    className="h-8 font-mono text-xs"
                  />
                </FieldRow>
              </>
            )}
            {node.type === "assign" && (
              <div className="sm:col-span-2 rounded border border-slate-200 p-2">
                <p className="mb-2 text-[11px] font-medium uppercase tracking-wide text-slate-500">Assignments</p>
                {(Array.isArray(draftConfig.assignments) ? draftConfig.assignments : []).map((row: any, idx: number) => (
                  <div key={`${idx}`} className="mb-2 grid grid-cols-[1fr_2fr_auto] gap-2">
                    <Input
                      value={String(row?.var || "")}
                      onChange={(e) =>
                        setDraftConfig((prev) => {
                          const assignments = Array.isArray(prev.assignments) ? [...prev.assignments] : [];
                          assignments[idx] = { ...(assignments[idx] || {}), var: e.target.value };
                          return { ...prev, assignments };
                        })
                      }
                      placeholder="var name"
                      className="h-8 font-mono text-xs"
                    />
                    <Input
                      value={String(row?.expression || "")}
                      onChange={(e) =>
                        setDraftConfig((prev) => {
                          const assignments = Array.isArray(prev.assignments) ? [...prev.assignments] : [];
                          assignments[idx] = { ...(assignments[idx] || {}), expression: e.target.value };
                          return { ...prev, assignments };
                        })
                      }
                      placeholder="expression"
                      className="h-8 font-mono text-xs"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      className="h-8 px-2 text-xs"
                      onClick={() =>
                        setDraftConfig((prev) => ({
                          ...prev,
                          assignments: (Array.isArray(prev.assignments) ? prev.assignments : []).filter((_: any, i: number) => i !== idx),
                        }))
                      }
                    >
                      Remove
                    </Button>
                  </div>
                ))}
                <Button
                  type="button"
                  variant="outline"
                  className="h-8 px-2 text-xs"
                  onClick={() =>
                    setDraftConfig((prev) => ({
                      ...prev,
                      assignments: [...(Array.isArray(prev.assignments) ? prev.assignments : []), { var: "", expression: "" }],
                    }))
                  }
                >
                  Add assignment
                </Button>
              </div>
            )}

            {(node.type === "tool" || node.type === "mcp_tool") && (
              <div className="sm:col-span-2">
                <FieldRow label="Arg mappings" hint="target ← source. Target is the endpoint param; source is a context path.">
                  <div className={cn("rounded border border-slate-200 bg-slate-50 p-2", argMappings.length === 0 && "text-[11px] italic text-slate-400")}>
                    {argMappings.length === 0 ? (
                      "No arg mappings configured."
                    ) : (
                      <table className="w-full text-xs">
                        <thead>
                          <tr className="text-left text-[10px] uppercase text-slate-400">
                            <th className="pb-1 font-medium">Target</th>
                            <th className="pb-1 font-medium">Source</th>
                          </tr>
                        </thead>
                        <tbody>
                          {argMappings.map((row) => (
                            <tr key={row.target} className="border-t border-slate-200">
                              <td className="py-1 pr-2 font-mono">{row.target}</td>
                              <td className="py-1 font-mono text-slate-600">{row.source}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </FieldRow>
              </div>
            )}

            {node.type === "synthesis" && (
              <div className="sm:col-span-2">
                <FieldRow label="Synthesis prompt" hint="System prompt for the synthesis LLM (Stage 2).">
                  <textarea
                    value={String(draftConfig.prompt || "")}
                    onChange={(e) => setDraftConfig((prev) => ({ ...prev, prompt: e.target.value }))}
                    rows={8}
                    className="w-full rounded border border-slate-200 bg-slate-50 p-2 font-mono text-[11px] leading-snug text-slate-700"
                  />
                </FieldRow>
              </div>
            )}
            {validationError ? (
              <div className="sm:col-span-2 rounded border border-rose-200 bg-rose-50 px-2 py-1 text-xs text-rose-700">
                {validationError}
              </div>
            ) : null}
          </div>
        ) : (
          <div className="text-xs text-slate-500">No node selected.</div>
        )}
        <datalist id={`branch-targets-${node?.id || "node"}`}>
          {branchTargets.map((target) => (
            <option key={target} value={target} />
          ))}
        </datalist>

        <DialogFooter>
          <Button size="sm" className="h-8 px-3 text-xs" onClick={save} disabled={!node}>
            Save
          </Button>
          <Button size="sm" variant="outline" className="h-8 px-3 text-xs" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
