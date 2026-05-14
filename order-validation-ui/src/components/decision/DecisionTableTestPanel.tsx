import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { JsonBlock } from "@/components/JsonBlock";
import type {
  ColumnDef,
  DmnJson,
  EvaluateDecisionTableResponse,
} from "@/types/decisionTable";

type Props = {
  onEvaluate: (inputs: Record<string, unknown>) => Promise<EvaluateDecisionTableResponse>;
  /**
   * Current table definition. Used to synthesize a sensible default sample
   * for the textarea — the previous hardcoded stub
   * (`{journeyType, position, legCode}`) didn't match most tables and was
   * confusing when shown next to mismatched column headers.
   */
  definition?: DmnJson | null;
};

function stubForType(t: string | null | undefined): unknown {
  const type = (t ?? "string").toLowerCase();
  if (type.startsWith("array")) return [];
  if (type === "number" || type === "integer" || type === "long" || type === "double") return 0;
  if (type === "boolean") return false;
  return "";
}

function sampleFromDefinition(def: DmnJson | null | undefined): string {
  if (!def || !def.inputs?.length) return "{}";
  // Prefer the first rule's actual inputs — they're always shape-correct
  // and show the user a realistic example pulled from their own table.
  const seed = def.rules?.[0]?.inputs;
  const sample: Record<string, unknown> = {};
  for (const col of def.inputs as ColumnDef[]) {
    sample[col.name] = seed && col.name in seed ? seed[col.name] : stubForType(col.type);
  }
  return JSON.stringify(sample, null, 2);
}

export function DecisionTableTestPanel({ onEvaluate, definition }: Props) {
  const derivedDefault = useMemo(() => sampleFromDefinition(definition), [definition]);
  const [inputJson, setInputJson] = useState(derivedDefault);
  // If the table schema changes (column added/renamed, switched tables),
  // refresh the textarea ONLY when the user hasn't deviated from the prior
  // auto-generated default — never clobber their hand-edited test input.
  const [lastAutoDefault, setLastAutoDefault] = useState(derivedDefault);
  useEffect(() => {
    if (inputJson === lastAutoDefault && derivedDefault !== lastAutoDefault) {
      setInputJson(derivedDefault);
      setLastAutoDefault(derivedDefault);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [derivedDefault]);
  const [result, setResult] = useState<EvaluateDecisionTableResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const evaluate = async () => {
    setError(null);
    let inputs: Record<string, unknown>;
    try {
      inputs = JSON.parse(inputJson);
    } catch (e) {
      setError(`Invalid JSON: ${(e as Error).message}`);
      return;
    }
    setLoading(true);
    try {
      const response = await onEvaluate(inputs);
      setResult(response);
    } catch (e) {
      setError((e as Error).message || "Evaluation failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="panel-card !p-4 space-y-3">
      <h4 className="text-sm font-semibold">Test panel</h4>
      <textarea
        className="h-40 w-full rounded-md border border-border bg-muted text-foreground p-3 font-mono text-xs outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        value={inputJson}
        onChange={(e) => setInputJson(e.target.value)}
      />
      <div className="flex items-center gap-2">
        <Button size="sm" onClick={evaluate} disabled={loading} className="btn-primary-text">
          {loading ? "Evaluating…" : "Evaluate"}
        </Button>
        {error && <span className="text-xs text-error">{error}</span>}
        {result && (
          <span className="text-xs text-muted-foreground ml-auto">
            {result.matched ? `Matched ${result.matchedRows.length} rule${result.matchedRows.length === 1 ? "" : "s"}` : "No match"}
          </span>
        )}
      </div>
      {result && <JsonBlock label="Response" value={result} maxHeight="max-h-64" />}
    </div>
  );
}
