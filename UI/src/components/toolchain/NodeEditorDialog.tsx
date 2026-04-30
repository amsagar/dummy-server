import { useMemo, type ReactNode } from "react";
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

export default function NodeEditorDialog({ open, onOpenChange, node }: Props) {
  const config = node?.config || {};
  const argMappings = useMemo(() => {
    const raw = config.argMappings;
    if (!raw || typeof raw !== "object") return [] as Array<{ target: string; source: string }>;
    return Object.entries(raw).map(([target, source]) => ({ target, source: String(source ?? "") }));
  }, [config]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="text-sm font-semibold text-[#123262]">
            Edit node {node ? <span className="font-mono">{node.id}</span> : ""}
          </DialogTitle>
          <DialogDescription className="text-[11px] text-slate-500">
            Read-only preview. Direct node editing lands in the next iteration; for now ask the AI to refine the
            graph via the chat panel.
          </DialogDescription>
        </DialogHeader>

        {node ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FieldRow label="Node id" hint="Stable identifier — do not change after edges reference it.">
              <Input value={node.id} disabled className="h-8 font-mono text-xs" />
            </FieldRow>
            <FieldRow label="Type" hint="One of: start, end, tool, mcp_tool, decision, synthesis.">
              <Input value={node.type} disabled className="h-8 font-mono text-xs" />
            </FieldRow>
            <FieldRow label="Label" hint="Display name shown on the flow board.">
              <Input value={node.label || ""} disabled className="h-8 text-xs sm:col-span-2" />
            </FieldRow>

            {(node.type === "tool" || node.type === "mcp_tool") && (
              <>
                <FieldRow label="Tool name" hint="Catalog entry the runtime will invoke.">
                  <Input value={String(config.toolName || "")} disabled className="h-8 font-mono text-xs" />
                </FieldRow>
                <FieldRow
                  label="Input key"
                  hint="If set, runtime takes context[<inputKey>] (dotted path) as the base payload."
                >
                  <Input value={String(config.inputKey || "")} disabled className="h-8 font-mono text-xs" />
                </FieldRow>
              </>
            )}

            {node.type === "decision" && (
              <>
                <FieldRow label="Source key" hint="Context path to read for the boolean comparison.">
                  <Input value={String(config.sourceKey || "")} disabled className="h-8 font-mono text-xs" />
                </FieldRow>
                <FieldRow label="Equals" hint="Value the source must equal to take the true branch.">
                  <Input value={String(config.equals || "")} disabled className="h-8 font-mono text-xs" />
                </FieldRow>
                <FieldRow label="True branch" hint="Node id taken when the condition is true.">
                  <Input value={String(config.trueBranch || "")} disabled className="h-8 font-mono text-xs" />
                </FieldRow>
                <FieldRow label="False branch" hint="Node id taken when the condition is false.">
                  <Input value={String(config.falseBranch || "")} disabled className="h-8 font-mono text-xs" />
                </FieldRow>
              </>
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
                    value={String(config.prompt || "")}
                    disabled
                    rows={8}
                    className="w-full rounded border border-slate-200 bg-slate-50 p-2 font-mono text-[11px] leading-snug text-slate-700"
                  />
                </FieldRow>
              </div>
            )}
          </div>
        ) : (
          <div className="text-xs text-slate-500">No node selected.</div>
        )}

        <DialogFooter>
          <Button size="sm" variant="outline" className="h-8 px-3 text-xs" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
