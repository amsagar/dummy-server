import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Play, X } from "lucide-react";
import { toast } from "sonner";
import {
  extractBpmnVariables,
  defaultForVar,
  looksMultiline,
  coerce,
} from "@/lib/bpmnVariables";

interface Props {
  ruleId: string | null;
  ruleLabel: string;
  onClose: () => void;
}

interface RuleDomainDetail {
  id: string;
  bpmnXml: string;
}

interface TestRunResult {
  success: boolean;
  error?: string | null;
  outputs?: Record<string, unknown> | null;
  latencyMs?: number | null;
  executionId?: string | null;
}

/**
 * Small modal-style dialog for running a single rule from the list page,
 * without navigating into the editor. The form is auto-derived from the
 * BPMN's referenced variables so the user doesn't have to know the input
 * shape.
 */
export function QuickTestRuleDialog({ ruleId, ruleLabel, onClose }: Props) {
  const open = ruleId !== null;

  const { data: detail } = useQuery<RuleDomainDetail>({
    queryKey: ["rule-domain-detail-for-test", ruleId],
    queryFn: () => api.ruleDomains.get(ruleId!),
    enabled: open,
  });

  const inputVars = useMemo(() => extractBpmnVariables(detail?.bpmnXml), [detail]);

  const [values, setValues] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!open) {
      setValues({});
      setResult(null);
      return;
    }
    setValues(Object.fromEntries(inputVars.map((v) => [v.name, defaultForVar(v)])));
  }, [open, inputVars]);

  const [result, setResult] = useState<TestRunResult | null>(null);

  const runMutation = useMutation({
    mutationFn: (inputs: Record<string, unknown>) => api.ruleDomains.test(ruleId!, inputs),
    onSuccess: (r: any) => {
      setResult(r);
      toast.success(r?.success ? "Test passed" : "Test failed — see details");
    },
    onError: (e: any) => {
      toast.error(e?.message || "Test run failed");
      setResult({ success: false, error: e?.message ?? "Request failed" });
    },
  });

  const run = () => {
    const parsed: Record<string, unknown> = {};
    for (const k of Object.keys(values)) {
      const raw = values[k];
      if (raw === "") continue;
      parsed[k] = coerce(raw);
    }
    setResult(null);
    runMutation.mutate(parsed);
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-2xl max-h-[85vh] overflow-hidden flex flex-col">
        <div className="flex items-start justify-between p-4 border-b">
          <div>
            <div className="text-xs text-gray-500 uppercase font-semibold">Quick test</div>
            <div className="font-mono text-sm">{ruleLabel}</div>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-800 p-1">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="p-4 overflow-y-auto space-y-3">
          {!detail && (
            <div className="text-sm text-gray-500">Loading rule…</div>
          )}
          {detail && inputVars.length === 0 && (
            <div className="text-sm text-gray-500">
              No input variables were detected in this rule's BPMN. Click "Run" to execute it with
              an empty input map.
            </div>
          )}
          {detail && inputVars.map((v) => (
            <div key={v.name}>
              <div className="flex items-baseline justify-between mb-0.5">
                <label className="text-xs font-mono text-gray-800">{v.name}</label>
                {v.usages.length > 0 && (
                  <span
                    className="text-[10px] text-gray-500 font-mono truncate ml-2 max-w-[60%]"
                    title={v.usages.join("\n")}
                  >
                    used as: {v.usages[0]}
                  </span>
                )}
              </div>
              {looksMultiline(v.usages) ? (
                <textarea
                  value={values[v.name] ?? ""}
                  onChange={(e) =>
                    setValues((prev) => ({ ...prev, [v.name]: e.target.value }))
                  }
                  spellCheck={false}
                  rows={3}
                  className="w-full rounded border bg-gray-50 p-2 text-xs font-mono"
                  placeholder="Scalar, JSON object, or JSON array"
                />
              ) : (
                <input
                  type="text"
                  value={values[v.name] ?? ""}
                  onChange={(e) =>
                    setValues((prev) => ({ ...prev, [v.name]: e.target.value }))
                  }
                  spellCheck={false}
                  className="w-full rounded border bg-gray-50 p-2 text-xs font-mono"
                  placeholder="Scalar, or JSON for objects/arrays"
                />
              )}
            </div>
          ))}

          {result && (
            <div className="mt-4 pt-3 border-t space-y-2">
              <div className="flex items-center gap-2">
                <span
                  className={`rounded px-2 py-0.5 text-[10px] font-semibold ${
                    result.success
                      ? "bg-green-100 text-green-800"
                      : "bg-red-100 text-red-800"
                  }`}
                >
                  {result.success ? "SUCCESS" : "FAILURE"}
                </span>
                {typeof result.latencyMs === "number" && (
                  <span className="text-xs text-gray-500">{result.latencyMs} ms</span>
                )}
              </div>
              {result.error && (
                <pre className="text-[11px] text-red-700 bg-red-50 border border-red-200 rounded p-2 whitespace-pre-wrap break-words">
                  {result.error}
                </pre>
              )}
              {result.outputs && (
                <pre className="text-[11px] bg-gray-50 border rounded p-2 overflow-x-auto max-h-48">
                  {JSON.stringify(result.outputs, null, 2)}
                </pre>
              )}
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 p-3 border-t bg-gray-50">
          <Button variant="outline" size="sm" onClick={onClose}>
            Close
          </Button>
          <Button
            size="sm"
            onClick={run}
            disabled={runMutation.isPending || !detail}
            className="gap-1.5 bg-[#E31837] hover:bg-[#c41530]"
          >
            <Play className="h-3.5 w-3.5" />
            {runMutation.isPending ? "Running…" : "Run"}
          </Button>
        </div>
      </div>
    </div>
  );
}

// defaultForVar / looksMultiline / coerce live in @/lib/bpmnVariables now
// — same logic shared with QuickTestSkillDialog and the editor's TestPanel.
