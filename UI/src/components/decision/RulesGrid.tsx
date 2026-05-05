import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { DecisionTableDefinition } from "@/types/decision";

type Props = {
  value: DecisionTableDefinition;
  onChange: (next: DecisionTableDefinition) => void;
};

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}

export function RulesGrid({ value, onChange }: Props) {
  const update = (mutate: (draft: DecisionTableDefinition) => void) => {
    const next = clone(value);
    mutate(next);
    onChange(next);
  };

  return (
    <div className="space-y-4">
      <div className="grid gap-4 md:grid-cols-2">
        <div className="space-y-2 rounded border p-3">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold text-[#123262]">Input columns</h4>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                update((draft) => {
                  draft.inputs.push({ name: `input_${draft.inputs.length + 1}`, type: "string", label: "" });
                  draft.rules.forEach((r) => {
                    r.inputs[draft.inputs[draft.inputs.length - 1].name] = "";
                  });
                })
              }
            >
              + Add input
            </Button>
          </div>
          {value.inputs.map((column, idx) => (
            <div key={`input-col-${idx}`} className="grid grid-cols-3 gap-2">
              <Input
                value={column.name}
                placeholder="name"
                onChange={(e) =>
                  update((draft) => {
                    const prev = draft.inputs[idx].name;
                    draft.inputs[idx].name = e.target.value;
                    draft.rules.forEach((r) => {
                      r.inputs[e.target.value] = r.inputs[prev] ?? "";
                      if (prev !== e.target.value) delete r.inputs[prev];
                    });
                  })
                }
              />
              <Input
                value={column.type}
                placeholder="type"
                onChange={(e) => update((draft) => (draft.inputs[idx].type = e.target.value))}
              />
              <Input
                value={column.label || ""}
                placeholder="label"
                onChange={(e) => update((draft) => (draft.inputs[idx].label = e.target.value))}
              />
            </div>
          ))}
        </div>
        <div className="space-y-2 rounded border p-3">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold text-[#123262]">Output columns</h4>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                update((draft) => {
                  draft.outputs.push({ name: `output_${draft.outputs.length + 1}`, type: "string", label: "" });
                  draft.rules.forEach((r) => {
                    r.outputs[draft.outputs[draft.outputs.length - 1].name] = "";
                  });
                })
              }
            >
              + Add output
            </Button>
          </div>
          {value.outputs.map((column, idx) => (
            <div key={`output-col-${idx}`} className="grid grid-cols-3 gap-2">
              <Input
                value={column.name}
                placeholder="name"
                onChange={(e) =>
                  update((draft) => {
                    const prev = draft.outputs[idx].name;
                    draft.outputs[idx].name = e.target.value;
                    draft.rules.forEach((r) => {
                      r.outputs[e.target.value] = r.outputs[prev] ?? "";
                      if (prev !== e.target.value) delete r.outputs[prev];
                    });
                  })
                }
              />
              <Input
                value={column.type}
                placeholder="type"
                onChange={(e) => update((draft) => (draft.outputs[idx].type = e.target.value))}
              />
              <Input
                value={column.label || ""}
                placeholder="label"
                onChange={(e) => update((draft) => (draft.outputs[idx].label = e.target.value))}
              />
            </div>
          ))}
        </div>
      </div>

      <div className="space-y-2 rounded border p-3">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-semibold text-[#123262]">Rules</h4>
          <Button
            size="sm"
            onClick={() =>
              update((draft) => {
                const inputs = Object.fromEntries(draft.inputs.map((c) => [c.name, ""]));
                const outputs = Object.fromEntries(draft.outputs.map((c) => [c.name, ""]));
                draft.rules.push({ id: `rule-${draft.rules.length + 1}`, inputs, outputs });
              })
            }
          >
            + Add rule
          </Button>
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>#</TableHead>
              {value.inputs.map((c) => (
                <TableHead key={`in-${c.name}`}>{c.name}</TableHead>
              ))}
              {value.outputs.map((c) => (
                <TableHead key={`out-${c.name}`}>{c.name}</TableHead>
              ))}
              <TableHead>Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {value.rules.map((rule, rowIdx) => (
              <TableRow key={rule.id || rowIdx}>
                <TableCell>{rowIdx + 1}</TableCell>
                {value.inputs.map((col) => (
                  <TableCell key={`rule-in-${rowIdx}-${col.name}`}>
                    <Input
                      value={rule.inputs?.[col.name] ?? ""}
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
                    variant="outline"
                    onClick={() =>
                      update((draft) => {
                        draft.rules.splice(rowIdx, 1);
                      })
                    }
                  >
                    Delete
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
