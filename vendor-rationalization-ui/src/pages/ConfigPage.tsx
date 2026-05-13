import * as React from "react";
import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Check, Loader2, Plus, Save, Sparkles, Trash2 } from "lucide-react";
import { chatApi, vrApi } from "@/services/api";
import type {
  CategoryAnalyticsResponse,
  SavingsBucket,
  VendorRationalizationConfig,
} from "@/types/vendorRationalization";
import { LEVER_OPTIONS } from "@/types/vendorRationalization";

/**
 * Settings admin page — edits the tunable vendor-rationalization config
 * (lever assignments, savings buckets, KPI targets, insight thresholds +
 * templates, Pareto threshold). One PUT per Save click; the dashboard's
 * react-query refetch picks up the new values within its stale window.
 */
export default function ConfigPage() {
  const qc = useQueryClient();

  const { data: serverCfg, isLoading } = useQuery<VendorRationalizationConfig>({
    queryKey: ["vr-config"],
    queryFn: () => vrApi.getConfig(),
  });
  const { data: categories } = useQuery<CategoryAnalyticsResponse>({
    queryKey: ["vr-categories"],
    queryFn: () => vrApi.categories(),
    staleTime: 60_000,
  });
  const { data: models, isLoading: modelsLoading, error: modelsError } = useQuery<
    Array<{ providerID: string; modelID: string; displayName?: string; modelKind?: string }>
  >({
    queryKey: ["vr-models"],
    queryFn: () => chatApi.models(),
    staleTime: 60_000,
  });

  const [draft, setDraft] = useState<VendorRationalizationConfig | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (serverCfg && !draft) setDraft(structuredClone(serverCfg));
  }, [serverCfg, draft]);

  const saveMutation = useMutation({
    mutationFn: (cfg: VendorRationalizationConfig) => vrApi.putConfig(cfg),
    onSuccess: () => {
      setSavedAt(Date.now());
      setError(null);
      qc.invalidateQueries({ queryKey: ["vr-config"] });
      qc.invalidateQueries({ queryKey: ["vr-strategic-levers"] });
      qc.invalidateQueries({ queryKey: ["vr-dashboard"] });
      qc.invalidateQueries({ queryKey: ["vr-insights"] });
    },
    onError: (e: Error) => setError(e.message || "Save failed"),
  });

  const dirty = useMemo(() => {
    if (!draft || !serverCfg) return false;
    return JSON.stringify(draft) !== JSON.stringify(serverCfg);
  }, [draft, serverCfg]);

  if (isLoading || !draft) return <LoadingState />;

  const allCategoryNames = (() => {
    const fromConfig = Object.keys(draft.leverAssignments);
    const fromData = (categories?.categories ?? []).map((c) => c.category);
    const seen = new Set<string>();
    return [...fromConfig, ...fromData].filter((c) => {
      if (!c || seen.has(c)) return false;
      seen.add(c);
      return true;
    }).sort();
  })();

  return (
    <div className="flex flex-col h-full">
      <div className="pods-header">
        <div>
          <div className="page-title">Settings</div>
          <div className="page-subtitle">
            Tune lever assignments, savings bands, KPI targets, and insight templates without a deploy.
          </div>
        </div>
        <div className="flex items-center gap-2">
          {savedAt && !dirty && (
            <span className="flex items-center gap-1 text-xs text-[#15803d]">
              <Check size={12} /> Saved
            </span>
          )}
          <button
            onClick={() => draft && saveMutation.mutate(draft)}
            disabled={!dirty || saveMutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#2563eb] text-white text-sm font-medium disabled:opacity-50"
          >
            {saveMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
            Save
          </button>
        </div>
      </div>

      <div className="flex-1 p-6 space-y-5 overflow-y-auto">
        {error && (
          <div className="content-card p-4 flex items-center gap-2"
               style={{ background: "#fef2f2", border: "1px solid #fecaca", color: "#b91c1c" }}>
            <AlertTriangle size={14} /> {error}
          </div>
        )}

        {/* ── AI Chat Model ─────────────────────────────────────────── */}
        <div className="content-card p-5">
          <div className="mb-4 flex items-start gap-2">
            <Sparkles size={16} className="text-[#2563eb] mt-0.5" />
            <div>
              <h3 className="font-semibold text-[#123262]">AI Chat Model</h3>
              <p className="text-xs text-gray-500 mt-0.5">
                Powers the AI Assistant chat <em>and</em> the dashboard's AI-generated insight cards.
                Pick from the providers your platform admin enabled. Stored server-side — applies to everyone.
              </p>
            </div>
          </div>
          {modelsLoading ? (
            <div className="text-sm text-gray-400 flex items-center gap-2">
              <Loader2 size={14} className="animate-spin" /> Loading models…
            </div>
          ) : modelsError ? (
            <div className="text-sm text-red-600 flex items-center gap-2">
              <AlertTriangle size={14} /> Failed to load models: {(modelsError as Error).message}
            </div>
          ) : (
            <label className="block max-w-xl">
              <div className="text-xs font-medium text-gray-700 mb-1">Chat model</div>
              <select
                value={draft.chatModelRef ?? ""}
                onChange={(e) => setDraft({ ...draft, chatModelRef: e.target.value || null })}
                className="w-full px-2 py-1.5 rounded border border-gray-200 text-sm bg-white"
              >
                <option value="">— Select a model —</option>
                {(models ?? [])
                  .filter((m) => m.modelKind !== "embedding")
                  .map((m) => {
                    const ref = `${m.providerID}/${m.modelID}`;
                    return (
                      <option key={ref} value={ref}>
                        {m.displayName || m.modelID} · {m.providerID}
                      </option>
                    );
                  })}
              </select>
              {!draft.chatModelRef && (
                <div className="text-[11px] text-amber-600 mt-1.5">
                  No model selected — AI Assistant and the dashboard AI Insights will be disabled until you pick one.
                </div>
              )}
            </label>
          )}
        </div>

        {/* ── Lever Assignments ─────────────────────────────────────── */}
        <Section
          title="Category → Strategic Lever"
          subtitle="Maps each procurement category to the recommended rationalization strategy. New categories from the data fall back to Deep-Dive & Rebid until assigned."
        >
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                <th className="text-left px-4 py-2">Category</th>
                <th className="text-left px-4 py-2 w-72">Lever</th>
              </tr>
            </thead>
            <tbody>
              {allCategoryNames.map((cat) => {
                const lever = draft.leverAssignments[cat] ?? "Deep-Dive & Rebid";
                const notInConfig = !(cat in draft.leverAssignments);
                return (
                  <tr key={cat} className="border-t border-gray-50">
                    <td className="px-4 py-2 text-gray-900">
                      {cat}
                      {notInConfig && (
                        <span className="ml-2 text-[10px] text-amber-600 uppercase">unassigned (fallback)</span>
                      )}
                    </td>
                    <td className="px-4 py-2">
                      <select
                        value={lever}
                        onChange={(e) =>
                          setDraft({
                            ...draft,
                            leverAssignments: { ...draft.leverAssignments, [cat]: e.target.value },
                          })
                        }
                        className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
                      >
                        {LEVER_OPTIONS.map((opt) => (
                          <option key={opt} value={opt}>{opt}</option>
                        ))}
                      </select>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Section>

        {/* ── Savings Buckets ───────────────────────────────────────── */}
        <Section
          title="Savings Buckets"
          subtitle="Vendor-count ranges that drive the Savings Opportunities page. First match wins. Percentages are multiplied against category spend to produce the low/high savings range."
        >
          <div className="space-y-3">
            {draft.savingsBuckets.map((b, i) => (
              <BucketEditor
                key={i}
                bucket={b}
                onChange={(next) => {
                  const buckets = [...draft.savingsBuckets];
                  buckets[i] = next;
                  setDraft({ ...draft, savingsBuckets: buckets });
                }}
                onDelete={() => {
                  const buckets = draft.savingsBuckets.filter((_, j) => j !== i);
                  setDraft({ ...draft, savingsBuckets: buckets });
                }}
              />
            ))}
            <button
              onClick={() =>
                setDraft({
                  ...draft,
                  savingsBuckets: [
                    ...draft.savingsBuckets,
                    {
                      minVendors: 0, maxVendors: null,
                      savingsLowPct: 0.05, savingsHighPct: 0.10,
                      confidence: 0.7,
                      stage: "Identified", timeline: "Q1 2026", action: "Analyze",
                      identifiedStageWhenVendorsAtLeast: null,
                    },
                  ],
                })
              }
              className="flex items-center gap-1.5 px-3 py-1.5 rounded border border-dashed border-gray-300 text-xs text-gray-600 hover:bg-gray-50"
            >
              <Plus size={12} /> Add bucket
            </button>
          </div>
        </Section>

        {/* ── KPI Targets + Thresholds + Pareto ─────────────────────── */}
        <Section
          title="KPI Targets, Insight Thresholds &amp; Pareto"
          subtitle="Small dials that control headline numbers on the dashboard."
        >
          <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-4">
            <Field label="Active Vendors KPI target text"
                   hint="Shown under the Active Vendors KPI card.">
              <input
                type="text"
                value={draft.kpiTargets.activeVendorsTarget}
                onChange={(e) =>
                  setDraft({ ...draft, kpiTargets: { activeVendorsTarget: e.target.value } })
                }
                className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
              />
            </Field>
            <Field label="Pareto threshold (0–1)"
                   hint="The 80/20 boundary. 0.80 = standard Pareto.">
              <input
                type="number" step="0.01" min="0.01" max="0.99"
                value={draft.paretoThresholdPct}
                onChange={(e) =>
                  setDraft({ ...draft, paretoThresholdPct: parseFloat(e.target.value) || 0.8 })
                }
                className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
              />
            </Field>
            <Field label="Quick-win: max vendors"
                   hint="Categories with ≤ this many vendors are candidates for the Quick Win Available card.">
              <input
                type="number" min="1"
                value={draft.insightThresholds.quickWinMaxVendors}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    insightThresholds: {
                      ...draft.insightThresholds,
                      quickWinMaxVendors: parseInt(e.target.value, 10) || 0,
                    },
                  })
                }
                className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
              />
            </Field>
            <Field label="Quick-win: min spend ($)"
                   hint="Spend threshold (USD) above which the quick-win heuristic fires.">
              <input
                type="number" min="0" step="1000000"
                value={draft.insightThresholds.quickWinMinSpend}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    insightThresholds: {
                      ...draft.insightThresholds,
                      quickWinMinSpend: parseFloat(e.target.value) || 0,
                    },
                  })
                }
                className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
              />
            </Field>
            <Field label="Consolidation savings low (0–1)"
                   hint="Multiplied by spend to estimate the LOW end of the consolidation savings range on the Insights card.">
              <input
                type="number" step="0.01" min="0" max="1"
                value={draft.insightThresholds.consolidationLowPct}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    insightThresholds: {
                      ...draft.insightThresholds,
                      consolidationLowPct: parseFloat(e.target.value) || 0,
                    },
                  })
                }
                className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
              />
            </Field>
            <Field label="Consolidation savings high (0–1)"
                   hint="Multiplied by spend to estimate the HIGH end of the consolidation savings range.">
              <input
                type="number" step="0.01" min="0" max="1"
                value={draft.insightThresholds.consolidationHighPct}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    insightThresholds: {
                      ...draft.insightThresholds,
                      consolidationHighPct: parseFloat(e.target.value) || 0,
                    },
                  })
                }
                className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
              />
            </Field>
          </div>
        </Section>

      </div>
    </div>
  );
}

// ── Sub-components ──────────────────────────────────────────────────────────

function Section({
  title, subtitle, children,
}: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div className="content-card p-5">
      <div className="mb-4">
        <h3 className="font-semibold text-[#123262]">{title}</h3>
        {subtitle && <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>}
      </div>
      {children}
    </div>
  );
}

function Field({
  label, hint, children,
}: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <div className="text-xs font-medium text-gray-700 mb-1">{label}</div>
      {children}
      {hint && <div className="text-[11px] text-gray-400 mt-1">{hint}</div>}
    </label>
  );
}

function BucketEditor({
  bucket, onChange, onDelete,
}: { bucket: SavingsBucket; onChange: (b: SavingsBucket) => void; onDelete: () => void }) {
  return (
    <div className="border border-gray-200 rounded-lg p-3 space-y-2 bg-gray-50/50">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Field label="Min vendors">
          <input
            type="number" min="0"
            value={bucket.minVendors}
            onChange={(e) => onChange({ ...bucket, minVendors: parseInt(e.target.value, 10) || 0 })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Max vendors (blank = ∞)">
          <input
            type="number" min="0"
            value={bucket.maxVendors ?? ""}
            onChange={(e) =>
              onChange({ ...bucket, maxVendors: e.target.value === "" ? null : parseInt(e.target.value, 10) })
            }
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Savings low (0–1)">
          <input
            type="number" step="0.01" min="0" max="1"
            value={bucket.savingsLowPct}
            onChange={(e) => onChange({ ...bucket, savingsLowPct: parseFloat(e.target.value) || 0 })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Savings high (0–1)">
          <input
            type="number" step="0.01" min="0" max="1"
            value={bucket.savingsHighPct}
            onChange={(e) => onChange({ ...bucket, savingsHighPct: parseFloat(e.target.value) || 0 })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Confidence (0–1)">
          <input
            type="number" step="0.01" min="0" max="1"
            value={bucket.confidence}
            onChange={(e) => onChange({ ...bucket, confidence: parseFloat(e.target.value) || 0 })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Stage">
          <input
            type="text"
            value={bucket.stage}
            onChange={(e) => onChange({ ...bucket, stage: e.target.value })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Timeline">
          <input
            type="text"
            value={bucket.timeline}
            onChange={(e) => onChange({ ...bucket, timeline: e.target.value })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
        <Field label="Action">
          <input
            type="text"
            value={bucket.action}
            onChange={(e) => onChange({ ...bucket, action: e.target.value })}
            className="w-full px-2 py-1 rounded border border-gray-200 text-sm"
          />
        </Field>
      </div>
      <div className="flex items-center justify-between text-xs">
        <label className="flex items-center gap-2 text-gray-600">
          <span>Promote stage to "Identified" when vendors ≥</span>
          <input
            type="number" min="0"
            value={bucket.identifiedStageWhenVendorsAtLeast ?? ""}
            placeholder="—"
            onChange={(e) =>
              onChange({
                ...bucket,
                identifiedStageWhenVendorsAtLeast: e.target.value === "" ? null : parseInt(e.target.value, 10),
              })
            }
            className="w-20 px-2 py-1 rounded border border-gray-200"
          />
        </label>
        <button
          onClick={onDelete}
          className="flex items-center gap-1 text-red-600 hover:text-red-700"
        >
          <Trash2 size={12} /> Delete bucket
        </button>
      </div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex items-center justify-center h-full">
      <Loader2 size={20} className="animate-spin text-gray-400" />
    </div>
  );
}
