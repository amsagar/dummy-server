// Variables side-panel. Shows the workflow's declared `variables[]` plus a
// quick reference to each tool activity's first output variable name (so
// the user can see what's available to reference as `#{#someVar}` in
// downstream expressions).
//
// Edits here mutate the workflow meta-state — `setVariables` is wired to the
// designer page state. Each row supports name, javaClass, defaultExpression,
// and required.

import { Plus, Trash2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { Node } from "@xyflow/react";
import type { BoardNodeData } from "@/lib/workflowSerializer";
import type { VariableSpec } from "@/types/workflow";

const JAVA_CLASS_OPTIONS = [
  { value: "java.lang.String", label: "String" },
  { value: "java.lang.Long", label: "Long" },
  { value: "java.lang.Double", label: "Double" },
  { value: "java.lang.Boolean", label: "Boolean" },
  { value: "java.util.Map", label: "Map" },
  { value: "java.util.List", label: "List" },
];

interface VariablesPanelProps {
  open: boolean;
  onClose: () => void;
  variables: VariableSpec[];
  setVariables: (next: VariableSpec[]) => void;
  nodes: Array<Node<BoardNodeData>>;
}

export function VariablesPanel({ open, onClose, variables, setVariables, nodes }: VariablesPanelProps) {
  if (!open) return null;

  function update(i: number, patch: Partial<VariableSpec>) {
    setVariables(variables.map((v, idx) => (idx === i ? { ...v, ...patch } : v)));
  }
  function remove(i: number) {
    setVariables(variables.filter((_, idx) => idx !== i));
  }
  function add() {
    setVariables([
      ...variables,
      { name: "", javaClass: "java.lang.String", defaultExpression: null, required: false },
    ]);
  }

  // Output variables exposed by tool nodes — surfaced as a "Available outputs"
  // hint at the bottom so users know what `#{#…}` paths exist.
  const outputs: { activityId: string; name: string; javaClass: string | null | undefined }[] = [];
  for (const n of nodes) {
    const d = n.data ?? {};
    const list = d.outputVariables ?? [];
    for (const o of list) {
      if (o?.name) outputs.push({ activityId: n.id, name: o.name, javaClass: o.javaClass });
    }
  }

  return (
    <aside className="absolute bottom-0 left-[220px] right-0 max-h-[40%] border-t bg-background z-10 flex flex-col">
      <header className="border-b p-2 flex items-center gap-2">
        <h3 className="text-sm font-medium">Workflow variables</h3>
        <span className="text-xs text-muted-foreground">{variables.length}</span>
        <Button size="sm" variant="ghost" onClick={onClose} className="ml-auto" aria-label="Close">
          <X className="w-3.5 h-3.5" />
        </Button>
      </header>
      <ScrollArea className="flex-1">
        <div className="p-3 flex flex-col gap-2">
          {variables.length === 0 && (
            <p className="text-xs text-muted-foreground italic">
              No variables declared. Every <code className="bg-muted px-1 rounded">#name</code>{" "}
              referenced in expressions must appear here.
            </p>
          )}
          {variables.map((v, i) => (
            <div key={i} className="grid grid-cols-12 gap-2 items-center">
              <Input
                value={v.name}
                onChange={(e) => update(i, { name: e.target.value })}
                placeholder="name"
                className="col-span-3 h-8 text-xs font-mono"
              />
              <Select
                value={v.javaClass ?? "java.lang.String"}
                onValueChange={(val) => update(i, { javaClass: val })}
              >
                <SelectTrigger className="col-span-3 h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {JAVA_CLASS_OPTIONS.map((o) => (
                    <SelectItem key={o.value} value={o.value}>
                      {o.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Input
                value={v.defaultExpression ?? ""}
                onChange={(e) =>
                  update(i, { defaultExpression: e.target.value || null })
                }
                placeholder="default expression"
                className="col-span-4 h-8 text-xs font-mono"
              />
              <label className="col-span-1 flex items-center gap-1 text-xs">
                <Checkbox
                  checked={!!v.required}
                  onCheckedChange={(val) => update(i, { required: val === true })}
                />
                req
              </label>
              <Button
                variant="ghost"
                size="sm"
                className="col-span-1 h-8 w-8 p-0"
                onClick={() => remove(i)}
                aria-label="Remove variable"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </Button>
            </div>
          ))}
          <Button variant="outline" size="sm" onClick={add} className="self-start">
            <Plus className="w-3.5 h-3.5 mr-1" /> Add variable
          </Button>

          {outputs.length > 0 && (
            <div className="mt-3 pt-3 border-t">
              <h4 className="text-[11px] uppercase tracking-wide text-muted-foreground mb-1">
                Available activity outputs (reference as #{`{#name}`})
              </h4>
              <div className="flex flex-wrap gap-2">
                {outputs.map((o, i) => (
                  <span
                    key={i}
                    className="text-xs font-mono border rounded px-2 py-0.5 bg-muted/40"
                  >
                    {o.name} <span className="text-muted-foreground">→ {o.activityId}</span>
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      </ScrollArea>
    </aside>
  );
}
