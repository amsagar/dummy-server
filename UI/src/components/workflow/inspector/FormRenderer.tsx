// Top-level descriptor-driven form. Walks `properties`, evaluates
// `displayOptions`, and dispatches each visible field to its component.
//
// State is held by the parent (BoardNodeData.properties is the source of
// truth) and committed back via `onChange` on every field change. We do not
// use react-hook-form here — the form is shallow, the parent already owns
// the canonical map, and live two-way binding keeps the canvas in sync.

import { isFieldVisible } from "./displayOptions";
import { renderFieldByType } from "./field/renderField";
import type { PluginPropertyDescriptor } from "@/types/workflow";

export interface FormRendererProps {
  properties: PluginPropertyDescriptor[];
  values: Record<string, unknown>;
  onChange: (next: Record<string, unknown>) => void;
}

export function FormRenderer({ properties, values, onChange }: FormRendererProps) {
  function setField(name: string, value: unknown) {
    onChange({ ...values, [name]: value });
  }

  return (
    <div className="flex flex-col gap-3">
      {properties
        .filter((p) => isFieldVisible(p, values))
        .map((prop) => (
          <div key={prop.name} className="flex flex-col gap-1">
            {prop.type !== "BOOLEAN" && prop.type !== "NOTICE" && (
              <label className="text-xs font-medium text-foreground">
                {prop.label ?? prop.name}
                {prop.required && <span className="text-red-500 ml-0.5">*</span>}
              </label>
            )}
            {renderFieldByType({
              prop,
              value: values[prop.name],
              onChange: (v) => setField(prop.name, v),
              siblingValues: values,
            })}
            {prop.description &&
              prop.type !== "NOTICE" &&
              prop.type !== "JSON" &&
              prop.type !== "CODE" && (
                <p className="text-xs text-muted-foreground">{prop.description}</p>
              )}
          </div>
        ))}
    </div>
  );
}
