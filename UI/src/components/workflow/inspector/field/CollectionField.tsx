// COLLECTION field — repeatable array of objects, each item edited as a
// sub-form built from `prop.children`. The persisted shape is
// Array<Record<string, unknown>>. Used today by HttpRequestPlugin.headers
// (children: name, value).

import { Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { isFieldVisible } from "../displayOptions";
import { renderFieldByType } from "./renderField";
import type { FieldProps } from "./StringField";

type Row = Record<string, unknown>;

function asRows(v: unknown): Row[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x) => x && typeof x === "object" && !Array.isArray(x)) as Row[];
}

export function CollectionField({ prop, value, onChange }: FieldProps) {
  const children = prop.children ?? [];
  const rows = asRows(value);

  function update(next: Row[]) {
    onChange(next);
  }

  function addRow() {
    const blank: Row = {};
    for (const c of children) {
      blank[c.name] = c.defaultValue ?? null;
    }
    update([...rows, blank]);
  }

  function removeRow(index: number) {
    update(rows.filter((_, i) => i !== index));
  }

  function setRowField(index: number, fieldName: string, fieldValue: unknown) {
    update(rows.map((r, i) => (i === index ? { ...r, [fieldName]: fieldValue } : r)));
  }

  return (
    <div className="flex flex-col gap-2">
      {rows.length === 0 && (
        <p className="text-xs text-muted-foreground italic">No items yet.</p>
      )}
      {rows.map((row, idx) => (
        <div
          key={idx}
          className="border rounded-md p-2 flex flex-col gap-2 bg-background relative"
        >
          <button
            type="button"
            onClick={() => removeRow(idx)}
            className="absolute top-1 right-1 p-1 rounded hover:bg-muted text-muted-foreground"
            aria-label="Remove row"
          >
            <X className="w-3 h-3" />
          </button>
          {children
            .filter((c) => isFieldVisible(c, row))
            .map((child) => (
              <div key={child.name} className="flex flex-col gap-1">
                <label className="text-xs font-medium text-muted-foreground">
                  {child.label ?? child.name}
                  {child.required && <span className="text-red-500 ml-0.5">*</span>}
                </label>
                {renderFieldByType({
                  prop: child,
                  value: row[child.name],
                  onChange: (v) => setRowField(idx, child.name, v),
                  siblingValues: row,
                })}
              </div>
            ))}
        </div>
      ))}
      <Button type="button" size="sm" variant="outline" onClick={addRow}>
        <Plus className="w-3 h-3 mr-1" /> Add {prop.label ?? prop.name}
      </Button>
    </div>
  );
}
