// Settings tab — manual fields that aren't part of the plugin descriptor:
//   - Display name (BoardNodeData.label)
//   - isStart / isEnd flags (route activities only)
//   - andJoin flag
//   - Deadline expression (ISO-8601 duration)
//   - Output variable name (writes the plugin's return into a process variable)
//
// We keep this minimal for Phase 1 — full output-variables editor with type
// hints / required flag is a Phase 7 polish item.

import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import type { BoardNodeData } from "@/lib/workflowSerializer";

interface SettingsTabProps {
  data: BoardNodeData;
  onChange: (next: BoardNodeData) => void;
}

export function SettingsTab({ data, onChange }: SettingsTabProps) {
  const isRoute = data.activityType === "route";
  const isForEach = data.activityType === "foreach";
  const isWhile = data.activityType === "while";
  const isBatch = data.activityType === "batch";
  const firstOutput = data.outputVariables?.[0];

  function setOutputVariable(name: string) {
    if (!name.trim()) {
      onChange({ ...data, outputVariables: [] });
      return;
    }
    onChange({
      ...data,
      outputVariables: [
        {
          name: name.trim(),
          javaClass: firstOutput?.javaClass ?? "java.util.Map",
          defaultExpression: firstOutput?.defaultExpression ?? null,
          required: firstOutput?.required ?? false,
        },
      ],
    });
  }

  return (
    <div className="flex flex-col gap-3">
      <Field label="Display name">
        <Input
          value={data.label ?? ""}
          onChange={(e) => onChange({ ...data, label: e.target.value })}
          placeholder="Untitled"
        />
      </Field>

      {isRoute && (
        <div className="flex flex-col gap-2">
          <Toggle
            checked={!!data.isStart}
            onCheckedChange={(v) => onChange({ ...data, isStart: v })}
            label="Start of workflow"
          />
          <Toggle
            checked={!!data.isEnd}
            onCheckedChange={(v) => onChange({ ...data, isEnd: v })}
            label="End of workflow"
          />
        </div>
      )}

      <Toggle
        checked={!!data.andJoin}
        onCheckedChange={(v) => onChange({ ...data, andJoin: v })}
        label="Wait for all incoming arrivals (AND-join)"
      />

      <Field label="Deadline (ISO-8601 duration)">
        <Input
          value={data.deadlineExpression ?? ""}
          onChange={(e) => onChange({ ...data, deadlineExpression: e.target.value || null })}
          placeholder="e.g. PT30S"
        />
      </Field>

      {data.activityType === "tool" && (
        <Field label="Output variable">
          <Input
            value={firstOutput?.name ?? ""}
            onChange={(e) => setOutputVariable(e.target.value)}
            placeholder="(none)"
          />
        </Field>
      )}

      {(isForEach || isBatch) && (
        <Field label="Collection expression">
          <Input
            value={String(data.properties?.collection ?? "")}
            onChange={(e) =>
              onChange({
                ...data,
                properties: { ...(data.properties ?? {}), collection: e.target.value },
              })
            }
            placeholder="#{#items}"
          />
        </Field>
      )}

      {isForEach && (
        <>
          <Field label="Item variable">
            <Input
              value={String(data.properties?.itemVar ?? "item")}
              onChange={(e) =>
                onChange({
                  ...data,
                  properties: { ...(data.properties ?? {}), itemVar: e.target.value || "item" },
                })
              }
              placeholder="item"
            />
          </Field>
          <Field label="Index variable">
            <Input
              value={String(data.properties?.indexVar ?? "index")}
              onChange={(e) =>
                onChange({
                  ...data,
                  properties: { ...(data.properties ?? {}), indexVar: e.target.value || "index" },
                })
              }
              placeholder="index"
            />
          </Field>
        </>
      )}

      {isWhile && (
        <Field label="While condition">
          <Input
            value={String(data.properties?.condition ?? "")}
            onChange={(e) =>
              onChange({
                ...data,
                properties: { ...(data.properties ?? {}), condition: e.target.value },
              })
            }
            placeholder="#{#shouldContinue}"
          />
        </Field>
      )}

      {(isForEach || isWhile || isBatch) && (
        <Field label="Max iterations">
          <Input
            type="number"
            value={String(data.properties?.maxIterations ?? "1000")}
            onChange={(e) =>
              onChange({
                ...data,
                properties: {
                  ...(data.properties ?? {}),
                  maxIterations: e.target.value ? Number(e.target.value) : 1000,
                },
              })
            }
            placeholder="1000"
          />
        </Field>
      )}

      {isBatch && (
        <Field label="Batch size">
          <Input
            type="number"
            value={String(data.properties?.batchSize ?? "10")}
            onChange={(e) =>
              onChange({
                ...data,
                properties: {
                  ...(data.properties ?? {}),
                  batchSize: e.target.value ? Number(e.target.value) : 10,
                },
              })
            }
            placeholder="10"
          />
        </Field>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-xs font-medium text-foreground">{label}</label>
      {children}
    </div>
  );
}

function Toggle({
  checked,
  onCheckedChange,
  label,
}: {
  checked: boolean;
  onCheckedChange: (v: boolean) => void;
  label: string;
}) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <Checkbox checked={checked} onCheckedChange={(v) => onCheckedChange(v === true)} />
      <span className="text-muted-foreground">{label}</span>
    </label>
  );
}
