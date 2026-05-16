import { useEffect, useMemo, useState } from "react";
import { useMutation, useQueries } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Play, X } from "lucide-react";
import { toast } from "sonner";
import {
  extractBpmnVariables,
  unionVariables,
  defaultForVar,
  looksMultiline,
  coerce,
} from "@/lib/bpmnVariables";

interface RuleRef {
  id: string;
  ruleName?: string | null;
  intentLabel: string;
}

interface RuleDomainDetail {
  id: string;
  bpmnXml: string;
}

interface PerRuleResult {
  ruleId: string;
  ruleName?: string;
  success: boolean;
  error?: string | null;
  outputs?: Record<string, unknown> | null;
  latencyMs?: number | null;
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

  const [values, setValues] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!open) {
      setValues({});
      setResults(null);
      return;
    }
    setValues(Object.fromEntries(inputVars.map((v) => [v.name, defaultForVar(v)])));
  }, [open, inputVars]);

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
          {allLoaded && inputVars.length === 0 && (
            <div className="text-sm text-gray-500">
              No input variables were detected across the skill's rules. Click "Run" to execute
              every rule with an empty input map.
            </div>
          )}
          {allLoaded && inputVars.map((v) => (
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
