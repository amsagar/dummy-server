import { useState } from "react";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";

type Props = {
  onEvaluate: (inputs: Record<string, any>) => Promise<any>;
};

export function DecisionTableTestPanel({ onEvaluate }: Props) {
  const [inputJson, setInputJson] = useState('{\n  "journeyType": "Storage Warehouse",\n  "position": 2,\n  "legCode": "WRT"\n}');
  const [result, setResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);

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
