import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Play, X, AlertTriangle } from "lucide-react";
import { toast } from "sonner";
import {
  extractBpmnVariables,
  classifyVariables,
  defaultForVar,
  looksMultiline,
  coerce,
  type BpmnVarRef,
} from "@/lib/bpmnVariables";

interface Props {
  ruleId: string | null;
  ruleLabel: string;
  onClose: () => void;
}

interface RuleDomainDetail {
  id: string;
  bpmnXml: string;
  status?: string;
  lastError?: string | null;
}

interface TestDiagnostic {
  type?: string;
  subprocessId?: string;
  collectionVar?: string;
  aggregationTarget?: string;
  emptyOutputPath?: string;
  message?: string;
}

interface TestRunResult {
  success: boolean;
  error?: string | null;
  outputs?: Record<string, unknown> | null;
  latencyMs?: number | null;
  executionId?: string | null;
  diagnostics?: TestDiagnostic[] | null;
}

/**
 * Single-rule quick-test dialog. Auto-generates a form from the BPMN's
 * variable references and splits them into "required" (the values the
 * lookup tool consumes — what users actually provide) and "suspect"
 * (values the compiler should have derived from a prior tool's response
 * but left as bare process variables).
 */
export function QuickTestRuleDialog({ ruleId, ruleLabel, onClose }: Props) {
  const open = ruleId !== null;

  const { data: detail } = useQuery<RuleDomainDetail>({
    queryKey: ["rule-domain-detail-for-test", ruleId],
    queryFn: () => api.ruleDomains.get(ruleId!),
    enabled: open,
  });

  const inputVars = useMemo(() => extractBpmnVariables(detail?.bpmnXml), [detail]);
  const { required } = useMemo(
    () => classifyVariables(detail?.bpmnXml, inputVars),
    [detail, inputVars],
  );

  const [values, setValues] = useState<Record<string, string>>({});
  // Stable signature so editing a field doesn't trigger the reset effect.
  const inputVarsKey = useMemo(() => inputVars.map((v) => v.name).join("|"), [inputVars]);
  useEffect(() => {
    if (!open) {
      setValues({});
      setResult(null);
      return;
    }
    setValues(Object.fromEntries(inputVars.map((v) => [v.name, defaultForVar(v)])));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, inputVarsKey]);

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
          {detail?.status === "DEPRECATED" && (
            <DeprecatedBanner lastError={detail.lastError} />
          )}
          {!detail && <div className="text-sm text-gray-500">Loading rule…</div>}
          {detail && inputVars.length === 0 && (
            <div className="text-sm text-gray-500">
              No input variables were detected in this rule's BPMN. Click "Run" to execute it
              with an empty input map.
            </div>
          )}
          {detail && required.length > 0 && (
            <FieldGroup
              title="Required inputs"
              subtitle="Values the lookup tool consumes"
              variant="required"
              variables={required}
              values={values}
              onChange={(name, val) =>
                setValues((prev) => ({ ...prev, [name]: val }))
              }
            />
          )}

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
              {result.diagnostics && result.diagnostics.length > 0 && (
                <div className="space-y-1">
                  <div className="text-[11px] font-semibold uppercase tracking-wide text-amber-700">
                    Diagnostics
                  </div>
                  {result.diagnostics.map((d, idx) => (
                    <div
                      key={idx}
                      className="text-[11px] text-amber-900 bg-amber-50 border border-amber-200 rounded p-2"
                    >
                      {d.message ?? d.type ?? "diagnostic"}
                      {d.collectionVar && (
                        <div className="font-mono text-[10px] text-amber-700 mt-0.5">
                          collection: <code>{d.collectionVar}</code>
                          {d.subprocessId && <> · subprocess: <code>{d.subprocessId}</code></>}
                          {d.emptyOutputPath && <> · empty at: <code>{d.emptyOutputPath}</code></>}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
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

// ── Shared sub-components ────────────────────────────────────────────────

export function DeprecatedBanner({ lastError }: { lastError?: string | null }) {
  return (
    <div className="rounded border border-amber-200 bg-amber-50 p-2 text-[12px] text-amber-900">
      <div className="font-semibold flex items-center gap-1">
        <AlertTriangle className="h-3.5 w-3.5" />
        Deprecated rule
      </div>
      <div className="mt-0.5">
        This rule is no longer routed at runtime. Admin tests still execute it for validation.
      </div>
      {lastError && (
        <div className="mt-0.5 text-[11px] font-mono text-amber-800">
          Last error: {lastError}
        </div>
      )}
    </div>
  );
}

interface FieldGroupProps {
  title: string;
  subtitle?: string;
  variant?: "required";
  variables: BpmnVarRef[];
  values: Record<string, string>;
  onChange: (name: string, value: string) => void;
}

export function FieldGroup({
  title,
  subtitle,
  variables,
  values,
  onChange,
}: FieldGroupProps) {
  return (
    <div className="rounded border p-3 space-y-3 border-gray-200">
      <div>
        <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
          {title}
        </div>
        {subtitle && (
          <div className="text-[11px] text-gray-600 mt-0.5">{subtitle}</div>
        )}
      </div>
      {variables.map((v) => (
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
              onChange={(e) => onChange(v.name, e.target.value)}
              spellCheck={false}
              rows={3}
              className="w-full rounded border bg-white p-2 text-xs font-mono"
              placeholder="Scalar, JSON object, or JSON array"
            />
          ) : (
            <input
              type="text"
              value={values[v.name] ?? ""}
              onChange={(e) => onChange(v.name, e.target.value)}
              spellCheck={false}
              className="w-full rounded border bg-white p-2 text-xs font-mono"
              placeholder="Scalar, or JSON for objects/arrays"
            />
          )}
        </div>
      ))}
    </div>
  );
}

// defaultForVar / looksMultiline / coerce live in @/lib/bpmnVariables now
// — same logic shared with QuickTestSkillDialog and the editor's TestPanel.
