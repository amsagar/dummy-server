import { useEffect, useMemo, useState } from "react";
import { Loader2, Play } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import type { ProcessDef, VariableSpec } from "@/types/workflow";

interface RunWorkflowDialogProps {
  processDef: ProcessDef | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (initialVariables: Record<string, unknown>) => void;
  submitting?: boolean;
}

type FieldKind = "string" | "number" | "boolean" | "json";

/**
 * Map a Java class declared on a `VariableSpec` to a UI input flavor. Maps
 * and Lists are edited as JSON in a textarea; primitives get typed inputs.
 * Unknown classes fall back to "string" — the user can still type whatever
 * they need and the backend's SpEL/Jackson will sort it out.
 */
function fieldKind(javaClass: string | null | undefined): FieldKind {
  if (!javaClass) return "string";
  if (javaClass.endsWith("Long") || javaClass.endsWith("Integer") ||
      javaClass.endsWith("Double") || javaClass.endsWith("Float")) {
    return "number";
  }
  if (javaClass.endsWith("Boolean")) return "boolean";
  if (javaClass.endsWith("Map") || javaClass.endsWith("List")) return "json";
  return "string";
}

function defaultValueForKind(kind: FieldKind): string {
  switch (kind) {
    case "number": return "";
    case "boolean": return "false";
    case "json": return "";
    default: return "";
  }
}

interface FieldState {
  raw: string;
  error?: string;
}

type ParseResult =
  | { ok: true; value: unknown }
  | { ok: false; error: string };

/**
 * Parse a raw textbox value into the typed value the engine expects.
 * Returns `{ ok: true, value }` on success or `{ ok: false, error }` so the
 * dialog can show a per-field validation message and block submit.
 */
function parseField(spec: VariableSpec, raw: string): ParseResult {
  const kind = fieldKind(spec.javaClass);
  const trimmed = raw.trim();
  if (trimmed === "") {
    return spec.required
      ? { ok: false, error: "Required" }
      : { ok: true, value: undefined };
  }
  switch (kind) {
    case "number": {
      const n = Number(trimmed);
      return Number.isFinite(n) ? { ok: true, value: n } : { ok: false, error: "Not a number" };
    }
    case "boolean":
      if (trimmed === "true") return { ok: true, value: true };
      if (trimmed === "false") return { ok: true, value: false };
      return { ok: false, error: "Must be true or false" };
    case "json":
      try {
        return { ok: true, value: JSON.parse(trimmed) };
      } catch (e) {
        return { ok: false, error: `Invalid JSON: ${(e as Error).message}` };
      }
    default:
      return { ok: true, value: trimmed };
  }
}

export function RunWorkflowDialog({
  processDef,
  open,
  onOpenChange,
  onConfirm,
  submitting,
}: RunWorkflowDialogProps) {
  const variables = processDef?.variables ?? [];

  // Split for layout: required up top (mandatory), optional below (collapsed
  // by default so the dialog isn't a wall of textareas for workflows like
  // foreach-accumulate where almost nothing is user-provided).
  const { required, optional } = useMemo(() => {
    const req: VariableSpec[] = [];
    const opt: VariableSpec[] = [];
    for (const v of variables) (v.required ? req : opt).push(v);
    return { required: req, optional: opt };
  }, [variables]);

  const [fields, setFields] = useState<Record<string, FieldState>>({});
  const [showOptional, setShowOptional] = useState(false);

  // Seed fields each time a different workflow opens.
  useEffect(() => {
    const seed: Record<string, FieldState> = {};
    for (const v of variables) {
      seed[v.name] = { raw: defaultValueForKind(fieldKind(v.javaClass)) };
    }
    setFields(seed);
    setShowOptional(false);
  }, [processDef?.id]);

  // Compute parsed values + validation up front so the Run button reflects
  // current state without a second pass.
  const parsed = useMemo(() => {
    const values: Record<string, unknown> = {};
    const errors: Record<string, string> = {};
    for (const v of variables) {
      const state = fields[v.name];
      if (!state) continue;
      const r = parseField(v, state.raw);
      if (r.ok) {
        const value = (r as { ok: true; value: unknown }).value;
        if (value !== undefined) values[v.name] = value;
      } else {
        errors[v.name] = (r as { ok: false; error: string }).error;
      }
    }
    return { values, errors };
  }, [fields, variables]);

  const hasErrors = Object.keys(parsed.errors).length > 0;
  const canSubmit = !hasErrors && !submitting;

  const handleSubmit = () => {
    if (!canSubmit) return;
    // Final validation sweep — show errors on any required field the user
    // left blank rather than silently submitting empty.
    const finalErrors: Record<string, string> = { ...parsed.errors };
    for (const v of variables) {
      if (v.required && !(v.name in parsed.values)) {
        finalErrors[v.name] = "Required";
      }
    }
    if (Object.keys(finalErrors).length > 0) {
      setFields((prev) => {
        const next = { ...prev };
        for (const [name, err] of Object.entries(finalErrors)) {
          next[name] = { raw: prev[name]?.raw ?? "", error: err };
        }
        return next;
      });
      toast.error("Fix the highlighted inputs and try again");
      return;
    }
    onConfirm(parsed.values);
  };

  const renderField = (v: VariableSpec) => {
    const kind = fieldKind(v.javaClass);
    const state = fields[v.name] ?? { raw: "" };
    const err = parsed.errors[v.name];

    const set = (raw: string) =>
      setFields((prev) => ({ ...prev, [v.name]: { raw } }));

    return (
      <div key={v.name}>
        <label className="flex items-baseline gap-2 text-xs font-medium">
          <span className="text-foreground">{v.name}</span>
          <span className="text-muted-foreground font-mono text-[10px]">
            {v.javaClass?.split(".").pop() ?? "Object"}
          </span>
          {v.required && (
            <span className="text-[10px] text-destructive uppercase tracking-wide">required</span>
          )}
        </label>
        {kind === "json" ? (
          <textarea
            value={state.raw}
            onChange={(e) => set(e.target.value)}
            spellCheck={false}
            placeholder={v.javaClass?.endsWith("List") ? "[]" : "{}"}
            className="mt-1 w-full min-h-20 rounded-md border bg-background px-2 py-1.5 font-mono text-xs outline-none focus:ring-2 focus:ring-ring/40"
          />
        ) : kind === "boolean" ? (
          <select
            value={state.raw}
            onChange={(e) => set(e.target.value)}
            className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm outline-none focus:ring-2 focus:ring-ring/40"
          >
            <option value="false">false</option>
            <option value="true">true</option>
          </select>
        ) : (
          <Input
            type={kind === "number" ? "number" : "text"}
            value={state.raw}
            onChange={(e) => set(e.target.value)}
            placeholder={v.defaultExpression ?? ""}
            className="mt-1"
          />
        )}
        {err && <div className="mt-1 text-xs text-destructive">{err}</div>}
        {!err && v.defaultExpression && (
          <div className="mt-1 text-[11px] text-muted-foreground">
            Default: <code className="font-mono">{v.defaultExpression}</code>
          </div>
        )}
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Run "{processDef?.name ?? "workflow"}"</DialogTitle>
          <DialogDescription>
            {variables.length === 0
              ? "This workflow doesn't declare any input variables — it'll start immediately."
              : required.length === 0
                ? "All inputs are optional. Override defaults if you need to."
                : `Provide the ${required.length} required input${required.length === 1 ? "" : "s"} below.`}
          </DialogDescription>
        </DialogHeader>

        {variables.length > 0 && (
          <div className="flex flex-col gap-3 max-h-[60vh] overflow-auto pr-1">
            {required.length > 0 && (
              <div className="flex flex-col gap-3">{required.map(renderField)}</div>
            )}
            {optional.length > 0 && (
              <div>
                <button
                  type="button"
                  onClick={() => setShowOptional((v) => !v)}
                  className="text-xs text-muted-foreground hover:text-foreground underline underline-offset-2"
                >
                  {showOptional
                    ? `Hide ${optional.length} optional variable${optional.length === 1 ? "" : "s"}`
                    : `Show ${optional.length} optional variable${optional.length === 1 ? "" : "s"}`}
                </button>
                {showOptional && (
                  <div className="mt-2 flex flex-col gap-3 border-l-2 pl-3">
                    {optional.map(renderField)}
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit}>
            {submitting ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <>
                <Play className="w-3.5 h-3.5 mr-1" /> Run
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
