import { useState } from "react";
import { Button } from "@/components/ui/button";
import { JsonBlock } from "@/components/JsonBlock";
import type { EvaluateDecisionTableResponse } from "@/types/decisionTable";

type Props = {
  onEvaluate: (inputs: Record<string, unknown>) => Promise<EvaluateDecisionTableResponse>;
};

const DEFAULT_INPUT = `{
  "journeyType": "Long Distance",
  "position": 1,
  "legCode": "NEW"
}`;

export function DecisionTableTestPanel({ onEvaluate }: Props) {
  const [inputJson, setInputJson] = useState(DEFAULT_INPUT);
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
