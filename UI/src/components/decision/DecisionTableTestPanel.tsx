import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";
import type {
  DecisionTableColumn,
  DecisionTableDefinition,
} from "@/types/decision";

type Props = {
  onEvaluate: (inputs: Record<string, any>) => Promise<any>;
  /**
   * Current table definition. Used to synthesize a sensible default sample
   * for the textarea — the previous hardcoded stub
   * (`{journeyType, position, legCode}`) didn't match most tables and was
   * confusing when shown next to mismatched column headers.
   */
  definition?: DecisionTableDefinition | null;
};

function stubForType(t: string | null | undefined): unknown {
  const type = (t ?? "string").toLowerCase();
  if (type.startsWith("array")) return [];
  if (type === "number" || type === "integer" || type === "long" || type === "double") return 0;
  if (type === "boolean") return false;
  return "";
}

// Rules in this codebase store input values as strings (see
// DecisionTableRule.inputs: Record<string,string>), so a list-shaped value
// like ["NEW","FPU"] is persisted as the comma-joined string "NEW,FPU". When
// the column type is array<...> and the seed is a string, split it back into
// an array so the sample JSON shows real array shape.
function seedValue(type: string | null | undefined, raw: unknown): unknown {
  const t = (type ?? "string").toLowerCase();
  if (t.startsWith("array") && typeof raw === "string") {
    return raw
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }
  if ((t === "number" || t === "integer" || t === "long" || t === "double") && typeof raw === "string") {
    const n = Number(raw);
    return Number.isFinite(n) ? n : 0;
  }
  if (t === "boolean" && typeof raw === "string") {
    return raw.toLowerCase() === "true";
  }
  return raw;
}

function sampleFromDefinition(def: DecisionTableDefinition | null | undefined): string {
  if (!def || !def.inputs?.length) return "{}";
  const seed = def.rules?.[0]?.inputs;
  const sample: Record<string, unknown> = {};
  for (const col of def.inputs as DecisionTableColumn[]) {
    sample[col.name] =
      seed && col.name in seed ? seedValue(col.type, seed[col.name]) : stubForType(col.type);
  }
  return JSON.stringify(sample, null, 2);
}

export function DecisionTableTestPanel({ onEvaluate, definition }: Props) {
  const derivedDefault = useMemo(() => sampleFromDefinition(definition), [definition]);
  const [inputJson, setInputJson] = useState(derivedDefault);
  const [result, setResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  // Refresh the textarea ONLY when the user hasn't deviated from the prior
  // auto-default — never clobber hand-edited test input.
  const [lastAutoDefault, setLastAutoDefault] = useState(derivedDefault);
  useEffect(() => {
    if (inputJson === lastAutoDefault && derivedDefault !== lastAutoDefault) {
      setInputJson(derivedDefault);
      setLastAutoDefault(derivedDefault);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [derivedDefault]);

  const evaluate = async () => {
    try {
      const inputs = JSON.parse(inputJson);
      setLoading(true);
      const response = await onEvaluate(inputs);
      setResult(response);
    } catch (e: any) {
      toast.error(e.message || "Evaluation failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-2 rounded border p-3">
      <h4 className="text-sm font-semibold text-[#123262]">Test panel</h4>
      <textarea
        className="h-36 w-full rounded border p-2 font-mono text-xs"
        value={inputJson}
        onChange={(e) => setInputJson(e.target.value)}
      />
      <div className="flex items-center gap-2">
        <Button size="sm" onClick={evaluate} disabled={loading}>
          {loading ? "Evaluating..." : "Evaluate"}
        </Button>
      </div>
      <pre className="max-h-64 overflow-auto rounded bg-slate-50 p-2 text-xs">{JSON.stringify(result, null, 2)}</pre>
    </div>
  );
}
