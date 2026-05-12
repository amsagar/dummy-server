import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Trash2, Plus } from "lucide-react";
import type { DmnJson } from "@/types/decisionTable";

type Props = {
  value: DmnJson;
  onChange: (next: DmnJson) => void;
};

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}

export function RulesGrid({ value, onChange }: Props) {
  const update = (mutate: (draft: DmnJson) => void) => {
    const next = clone(value);
    mutate(next);
    onChange(next);
  };

  return (
    <div className="space-y-4">
      <div className="grid gap-4 md:grid-cols-2">
        <ColumnConfigCard
          title="Input columns"
          columns={value.inputs}
          onAdd={() =>
            update((draft) => {
              const name = `input_${draft.inputs.length + 1}`;
              draft.inputs.push({ name, type: "string", label: "" });
              draft.rules.forEach((r) => {
                r.inputs[name] = "";
              });
            })
          }
          onRename={(idx, newName) =>
            update((draft) => {
              const prev = draft.inputs[idx].name;
              draft.inputs[idx].name = newName;
              draft.rules.forEach((r) => {
                r.inputs[newName] = r.inputs[prev] ?? "";
                if (prev !== newName) delete r.inputs[prev];
              });
            })
          }
          onChangeType={(idx, type) => update((draft) => (draft.inputs[idx].type = type))}
          onChangeLabel={(idx, label) => update((draft) => (draft.inputs[idx].label = label))}
          onDelete={(idx) =>
            update((draft) => {
              const removed = draft.inputs[idx].name;
              draft.inputs.splice(idx, 1);
              draft.rules.forEach((r) => delete r.inputs[removed]);
            })
          }
        />
        <ColumnConfigCard
          title="Output columns"
          columns={value.outputs}
          onAdd={() =>
            update((draft) => {
              const name = `output_${draft.outputs.length + 1}`;
              draft.outputs.push({ name, type: "string", label: "" });
              draft.rules.forEach((r) => {
                r.outputs[name] = "";
              });
            })
          }
          onRename={(idx, newName) =>
            update((draft) => {
              const prev = draft.outputs[idx].name;
              draft.outputs[idx].name = newName;
              draft.rules.forEach((r) => {
                r.outputs[newName] = r.outputs[prev] ?? "";
                if (prev !== newName) delete r.outputs[prev];
              });
            })
          }
          onChangeType={(idx, type) => update((draft) => (draft.outputs[idx].type = type))}
          onChangeLabel={(idx, label) => update((draft) => (draft.outputs[idx].label = label))}
          onDelete={(idx) =>
            update((draft) => {
              const removed = draft.outputs[idx].name;
              draft.outputs.splice(idx, 1);
              draft.rules.forEach((r) => delete r.outputs[removed]);
            })
          }
        />
      </div>

      <div className="panel-card !p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-semibold">Rules</h4>
          <Button
            size="sm"
            onClick={() =>
              update((draft) => {
                const inputs = Object.fromEntries(draft.inputs.map((c) => [c.name, ""]));
                const outputs = Object.fromEntries(draft.outputs.map((c) => [c.name, ""]));
                draft.rules.push({ id: `rule-${draft.rules.length + 1}`, inputs, outputs });
              })
            }
            disabled={value.inputs.length === 0 && value.outputs.length === 0}
            className="btn-primary-text"
          >
            <Plus className="size-3.5" /> Add rule
          </Button>
        </div>
        {value.rules.length === 0 ? (
          <div className="text-xs text-muted-foreground py-3">
            No rules yet. Add input/output columns first, then add rules.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-12">#</TableHead>
                  {value.inputs.map((c) => (
                    <TableHead key={`in-${c.name}`}>{c.name}</TableHead>
                  ))}
                  {value.outputs.map((c) => (
                    <TableHead key={`out-${c.name}`}>{c.name}</TableHead>
                  ))}
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {value.rules.map((rule, rowIdx) => (
                  <TableRow key={rule.id || rowIdx}>
                    <TableCell className="text-muted-foreground text-xs">{rowIdx + 1}</TableCell>
                    {value.inputs.map((col) => (
                      <TableCell key={`rule-in-${rowIdx}-${col.name}`}>
                        <Input
                          value={String(rule.inputs?.[col.name] ?? "")}
                          onChange={(e) =>
                            update((draft) => {
                              draft.rules[rowIdx].inputs[col.name] = e.target.value;
                            })
                          }
                        />
                      </TableCell>
                    ))}
                    {value.outputs.map((col) => (
                      <TableCell key={`rule-out-${rowIdx}-${col.name}`}>
                        <Input
                          value={String(rule.outputs?.[col.name] ?? "")}
                          onChange={(e) =>
                            update((draft) => {
                              draft.rules[rowIdx].outputs[col.name] = e.target.value;
                            })
                          }
                        />
                      </TableCell>
                    ))}
                    <TableCell>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          update((draft) => {
                            draft.rules.splice(rowIdx, 1);
                          })
                        }
                      >
                        <Trash2 className="size-3.5 icon-error" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </div>
  );
}

function ColumnConfigCard({
  title,
  columns,
  onAdd,
  onRename,
  onChangeType,
  onChangeLabel,
  onDelete,
}: {
  title: string;
  columns: { name: string; type?: string | null; label?: string | null }[];
  onAdd: () => void;
  onRename: (idx: number, value: string) => void;
  onChangeType: (idx: number, value: string) => void;
  onChangeLabel: (idx: number, value: string) => void;
  onDelete: (idx: number) => void;
}) {
  return (
    <div className="panel-card !p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-semibold">{title}</h4>
        <Button size="sm" variant="outline" onClick={onAdd}>
          <Plus className="size-3.5" /> Add
        </Button>
      </div>
      {columns.length === 0 ? (
        <div className="text-xs text-muted-foreground py-2">No columns yet.</div>
      ) : (
        <div className="space-y-2">
          {columns.map((c, idx) => (
            <div key={idx} className="grid grid-cols-[1fr_1fr_1fr_auto] gap-2">
              <Input value={c.name} placeholder="name" onChange={(e) => onRename(idx, e.target.value)} />
              <Input
                value={c.type ?? ""}
                placeholder="type"
                onChange={(e) => onChangeType(idx, e.target.value)}
              />
              <Input
                value={c.label ?? ""}
                placeholder="label"
                onChange={(e) => onChangeLabel(idx, e.target.value)}
              />
              <Button size="sm" variant="ghost" onClick={() => onDelete(idx)}>
                <Trash2 className="size-3.5 icon-error" />
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
