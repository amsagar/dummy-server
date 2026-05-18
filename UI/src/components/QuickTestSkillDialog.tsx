import { useEffect, useMemo, useState } from "react";
import { useMutation, useQueries } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Play, X } from "lucide-react";
import { toast } from "sonner";
import {
  extractBpmnVariables,
  classifyVariables,
  unionVariables,
  defaultForVar,
  coerce,
  type BpmnVarRef,
} from "@/lib/bpmnVariables";
import { FieldGroup } from "@/components/QuickTestRuleDialog";
import { AlertTriangle } from "lucide-react";

interface RuleRef {
  id: string;
  ruleName?: string | null;
  intentLabel: string;
  status?: string;
}

interface RuleDomainDetail {
  id: string;
  bpmnXml: string;
  status?: string;
}

interface PerRuleDiagnostic {
  type?: string;
  subprocessId?: string;
  collectionVar?: string;
  aggregationTarget?: string;
  emptyOutputPath?: string;
  message?: string;
}

interface PerRuleResult {
  ruleId: string;
  ruleName?: string;
  success: boolean;
  error?: string | null;
  outputs?: Record<string, unknown> | null;
  latencyMs?: number | null;
  diagnostics?: PerRuleDiagnostic[] | null;
}

interface Props {
  skillId: string | null;
  skillName: string;
  rules: RuleRef[];
  onClose: () => void;
}

/**
 * Skill-level quick-test dialog. Collects the UNION of input variables
 * across every rule in the skill, renders one form, and submits the same
 * input map to `/skill/{id}/test` — which dispatches it to every rule
 * server-side.
 */
export function QuickTestSkillDialog({ skillId, skillName, rules, onClose }: Props) {
  const open = skillId !== null;

  // Fetch each rule's BPMN in parallel — we need bpmnXml to derive the
  // input variables. The /rule-domains/{id} endpoint already returns it.
  const detailQueries = useQueries({
    queries: open
      ? rules.map((r) => ({
          queryKey: ["rule-domain-detail-for-skill-test", r.id],
          queryFn: () => api.ruleDomains.get(r.id) as Promise<RuleDomainDetail>,
        }))
      : [],
  });

  const allLoaded = open && detailQueries.length > 0 && detailQueries.every((q) => q.data);

  // Union the per-rule input lists. Output and delegate filtering happens
  // inside extractBpmnVariables — the union just dedupes by name and
  // concatenates usage hints.
  const inputVars = useMemo(() => {
    if (!allLoaded) return [];
    const perRule = detailQueries.map((q) => extractBpmnVariables(q.data?.bpmnXml));
    return unionVariables(perRule);
  }, [allLoaded, detailQueries]);

  // A variable is "required" if it's referenced by the FIRST tool's
  // inputBindings in ANY rule — that's the user-provided identifier
  // (e.g. orderId). We only render required fields; suspect/derived
  // variables are computed by the BPMN at runtime.
  const required = useMemo(() => {
    if (!allLoaded) return [] as BpmnVarRef[];
    const reqUnion = new Set<string>();
    for (const q of detailQueries) {
      const xml = q.data?.bpmnXml;
      if (!xml) continue;
      const localVars = extractBpmnVariables(xml);
      const { required: req } = classifyVariables(xml, localVars);
      for (const v of req) reqUnion.add(v.name);
    }
    return inputVars.filter((v) => reqUnion.has(v.name));
  }, [allLoaded, detailQueries, inputVars]);

  const deprecatedCount = useMemo(
    () => detailQueries.filter((q) => q.data?.status === "DEPRECATED").length,
    [detailQueries],
  );

  const [values, setValues] = useState<Record<string, string>>({});
  // Use a stable string signature (joined names) instead of the inputVars
  // array reference. useQueries returns a new array on every render, so
  // inputVars also gets a new ref every render — depending on it directly
  // would re-run this effect every render and wipe the user's edits.
  const inputVarsKey = useMemo(() => inputVars.map((v) => v.name).join("|"), [inputVars]);
  useEffect(() => {
    if (!open) {
      setValues({});
      setResults(null);
      return;
    }
    setValues(Object.fromEntries(inputVars.map((v) => [v.name, defaultForVar(v)])));
    // inputVars intentionally read at effect-time; the trigger is the
    // stable name signature, not the array reference.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, inputVarsKey]);

  const [results, setResults] = useState<{
    ruleCount: number;
    results: PerRuleResult[];
  } | null>(null);

  const runMutation = useMutation({
    mutationFn: (inputs: Record<string, unknown>) =>
      api.ruleDomains.testAllForSkill(skillId!, inputs),
    onSuccess: (resp: any) => {
      setResults({
        ruleCount: Number(resp?.ruleCount ?? 0),
        results: Array.isArray(resp?.results) ? resp.results : [],
      });
      const n = Number(resp?.ruleCount ?? 0);
      const ok = (resp?.results ?? []).filter((r: any) => r.success).length;
      toast.success(`Tested ${n} rule${n === 1 ? "" : "s"} — ${ok} ok / ${n - ok} failed`);
    },
    onError: (e: any) => {
      toast.error(e?.message || "Test run failed");
      setResults({ ruleCount: 0, results: [] });
    },
  });

  const run = () => {
    const parsed: Record<string, unknown> = {};
    for (const k of Object.keys(values)) {
      const raw = values[k];
      if (raw === "") continue;
      parsed[k] = coerce(raw);
    }
    setResults(null);
    runMutation.mutate(parsed);
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-3xl max-h-[88vh] overflow-hidden flex flex-col">
        <div className="flex items-start justify-between p-4 border-b">
          <div>
            <div className="text-xs text-gray-500 uppercase font-semibold">Quick test · whole skill</div>
            <div className="font-mono text-sm">{skillName}</div>
            <div className="text-[11px] text-gray-500 mt-0.5">
              {rules.length} rule{rules.length === 1 ? "" : "s"} — same input map sent to every rule
            </div>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-800 p-1">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="p-4 overflow-y-auto space-y-3">
          {!allLoaded && (
            <div className="text-sm text-gray-500">Loading rule BPMNs…</div>
          )}
          {allLoaded && deprecatedCount > 0 && (
            <div className="rounded border border-amber-200 bg-amber-50 p-2 text-[12px] text-amber-900">
              <div className="font-semibold flex items-center gap-1">
                <AlertTriangle className="h-3.5 w-3.5" />
                {deprecatedCount} of {rules.length} rule
                {rules.length === 1 ? "" : "s"} {deprecatedCount === 1 ? "is" : "are"} deprecated
              </div>
              <div className="mt-0.5">
                Deprecated rules aren't routed at runtime, but admin tests still execute them
                for validation.
              </div>
            </div>
          )}
          {allLoaded && inputVars.length === 0 && (
            <div className="text-sm text-gray-500">
              No input variables were detected across the skill's rules. Click "Run all rules" to
              execute every rule with an empty input map.
            </div>
          )}
          {allLoaded && required.length > 0 && (
            <FieldGroup
              title="Required inputs"
              subtitle="Values the lookup tool of one or more rules consumes — same map is sent to every rule"
              variant="required"
              variables={required}
              values={values}
              onChange={(name, val) => setValues((prev) => ({ ...prev, [name]: val }))}
            />
          )}

          {results && (
            <div className="mt-4 pt-3 border-t space-y-2">
              <div className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
                Per-rule results
              </div>
              {results.results.length === 0 && (
                <div className="text-xs text-gray-500">No rules returned a result.</div>
              )}
              {results.results.map((r) => (
                <div key={r.ruleId} className="border rounded p-2 bg-gray-50">
                  <div className="flex items-center gap-2 mb-1">
                    <span
                      className={`rounded px-2 py-0.5 text-[10px] font-semibold ${
                        r.success
                          ? "bg-green-100 text-green-800"
                          : "bg-red-100 text-red-800"
                      }`}
                    >
                      {r.success ? "SUCCESS" : "FAILURE"}
                    </span>
                    <span className="font-mono text-xs">{r.ruleName ?? r.ruleId}</span>
                    {typeof r.latencyMs === "number" && (
                      <span className="text-[11px] text-gray-500">{r.latencyMs} ms</span>
                    )}
                  </div>
                  {r.error && (
                    <pre className="text-[11px] text-red-700 bg-red-50 border border-red-200 rounded p-2 whitespace-pre-wrap break-words">
                      {r.error}
                    </pre>
                  )}
                  {r.outputs && (
                    <pre className="text-[11px] bg-white border rounded p-2 overflow-x-auto max-h-40">
                      {JSON.stringify(r.outputs, null, 2)}
                    </pre>
                  )}
                  {r.diagnostics && r.diagnostics.length > 0 && (
                    <div className="space-y-1 mt-1">
                      {r.diagnostics.map((d, idx) => (
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
              ))}
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
            disabled={runMutation.isPending || !allLoaded}
            className="gap-1.5 bg-[#E31837] hover:bg-[#c41530]"
          >
            <Play className="h-3.5 w-3.5" />
            {runMutation.isPending ? "Running…" : "Run all rules"}
          </Button>
        </div>
      </div>
    </div>
  );
}
