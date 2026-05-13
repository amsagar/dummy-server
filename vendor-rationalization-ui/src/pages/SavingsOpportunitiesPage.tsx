// FIXME: parts of this page are simulated — the vendor-reduction slider
// math, baselineVendors=124, addressable spend $754M denominator, and
// baseline compliance/automation metrics are placeholder constants
// rather than computed from data. The opportunity rows themselves come
// from the real /savings endpoint (which now reads the configurable
// savings buckets from the Settings admin page), so those are live.
// Replace the simulated bits when real upstream baselines exist.
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
} from "recharts";
import { Loader2, Zap, TrendingUp, Target, CheckCircle2 } from "lucide-react";
import { vrApi } from "@/services/api";
import { formatCurrency, formatPct, cn } from "@/lib/utils";
import type {
  AiInsightsResponse,
  SavingsOpportunitiesResponse,
  VendorRationalizationConfig,
} from "@/types/vendorRationalization";
import { LEVER_COLOR, LEVER_BG, STAGE_COLOR } from "@/types/vendorRationalization";
import { AiInsightPanel } from "@/components/insights/AiInsightPanel";

export default function SavingsOpportunitiesPage() {
  const [leverFilter, setLeverFilter] = useState("all");
  const [stageFilter, setStageFilter] = useState("all");
  const [vendorReduction, setVendorReduction] = useState(40);

  const { data, isLoading } = useQuery<SavingsOpportunitiesResponse>({
    queryKey: ["vr-savings"],
    queryFn: () => vrApi.savings(),
    staleTime: 60_000,
  });
  const { data: vrConfig } = useQuery<VendorRationalizationConfig>({
    queryKey: ["vr-config"],
    queryFn: () => vrApi.getConfig(),
    staleTime: 60_000,
  });
  const modelConfigured = Boolean(vrConfig?.chatModelRef);

  const qc = useQueryClient();
  const insightsKey = ["vr-insights", "savings"];
  const { data: aiInsights, isLoading: insightsLoading } = useQuery<AiInsightsResponse>({
    queryKey: insightsKey,
    queryFn: () => vrApi.insights("savings"),
    enabled: modelConfigured,
    staleTime: 5 * 60_000,
  });
  const regenerate = useMutation({
    mutationFn: () => vrApi.insights("savings", { refresh: true }),
    onSuccess: (resp: AiInsightsResponse) => qc.setQueryData(insightsKey, resp),
  });

  if (isLoading) return <LoadingState />;

  const opps = (data?.opportunities ?? []).filter(o => {
    if (leverFilter !== "all" && o.lever !== leverFilter) return false;
    if (stageFilter !== "all" && o.stage !== stageFilter) return false;
    return true;
  });

  const totalSavings = data?.totalIdentifiedSavings ?? 0;
  const quickWinSavings = (data?.opportunities ?? [])
    .filter(o => o.stage === "In Progress" || o.stage === "Validated")
    .reduce((s, o) => s + (o.estimatedSavingsLow + o.estimatedSavingsHigh) / 2, 0);

  // Waterfall chart data
  const waterfallData = (data?.opportunities ?? []).slice(0, 6).map(o => ({
    name: o.category.split(" ")[0],
    savings: Math.round((o.estimatedSavingsLow + o.estimatedSavingsHigh) / 2 / 1_000_000),
    color: LEVER_COLOR[o.lever] ?? "#2563eb",
  }));

  // Scenario modeling
  const baselineVendors = 124;
  const proposedVendors = Math.round(baselineVendors * (1 - vendorReduction / 100));
  const projectedSavings = totalSavings * (vendorReduction / 40);

  return (
    <div className="flex flex-col h-full">
      <div className="pods-header">
        <div>
          <div className="page-title">Savings Opportunities & Scenario Modeling</div>
          <div className="page-subtitle">Identify, size, and simulate cost optimization strategies across $754M addressable spend</div>
        </div>
        <button className="px-4 py-2 rounded-lg bg-[#005CB9] text-white text-sm font-medium hover:bg-[#00458C] transition-colors">
          New Scenario
        </button>
      </div>

      <div className="flex-1 p-6 overflow-y-auto space-y-5">
        {/* KPI row */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Total Identified Savings</div>
            <div className="text-2xl font-bold text-[#123262]">{formatCurrency(totalSavings)}</div>
            <div className="text-xs text-green-600 mt-1">↑ {formatPct((totalSavings / 754_000_000) * 100)} of addressable spend</div>
          </div>
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Active Opportunities</div>
            <div className="text-2xl font-bold text-[#123262]">{data?.activeOpportunities}</div>
            <div className="text-xs text-gray-400 mt-1">Across {data?.opportunities.length} categories</div>
          </div>
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Quick Wins Ready</div>
            <div className="text-2xl font-bold text-[#123262]">{data?.quickWins}</div>
            <div className="text-xs text-amber-600 mt-1">{formatCurrency(quickWinSavings)} potential</div>
          </div>
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Avg. Confidence</div>
            <div className="text-2xl font-bold text-[#123262]">{formatPct((data?.avgConfidence ?? 0) * 100)}</div>
            <div className="text-xs text-gray-400 mt-1">Based on AI analysis</div>
          </div>
        </div>

        {/* Lever summary */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {[
            { lever: "Aggregate & Rationalize", num: "L1", scope: "~$360M", opps: (data?.opportunities ?? []).filter(o => o.lever === "Aggregate & Rationalize").length },
            { lever: "Renegotiate & Partner", num: "L2", scope: "~$298M", opps: (data?.opportunities ?? []).filter(o => o.lever === "Renegotiate & Partner").length },
            { lever: "Deep-Dive & Rebid", num: "L3", scope: "~$6M", opps: (data?.opportunities ?? []).filter(o => o.lever === "Deep-Dive & Rebid").length },
          ].map(l => {
            const leverSavings = (data?.opportunities ?? [])
              .filter(o => o.lever === l.lever)
              .reduce((s, o) => s + (o.estimatedSavingsLow + o.estimatedSavingsHigh) / 2, 0);
            return (
              <div key={l.lever} className="content-card p-4" style={{ background: LEVER_BG[l.lever], border: `1px solid ${LEVER_COLOR[l.lever]}30` }}>
                <div className="flex items-center gap-2 mb-2">
                  <div className="w-7 h-7 rounded-lg flex items-center justify-center text-white text-xs font-bold"
                    style={{ background: LEVER_COLOR[l.lever] }}>
                    {l.num}
                  </div>
                  <span className="font-semibold text-sm text-gray-900">{l.lever}</span>
                </div>
                <div className="text-xs text-gray-500 mb-2">{l.scope} addressable</div>
                <div className="flex justify-between text-sm">
                  <span className="text-gray-600">{l.opps} opportunities</span>
                  <span className="font-bold" style={{ color: LEVER_COLOR[l.lever] }}>{formatCurrency(leverSavings)}</span>
                </div>
                <div className="mt-2 h-1.5 bg-white/60 rounded-full overflow-hidden">
                  <div className="h-full rounded-full" style={{
                    width: `${Math.min(100, (leverSavings / totalSavings) * 100)}%`,
                    background: LEVER_COLOR[l.lever],
                  }} />
                </div>
              </div>
            );
          })}
        </div>

        <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
          {/* Opportunity pipeline */}
          <div className="xl:col-span-2 content-card">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-gray-100">
              <h3 className="font-semibold text-[#123262]">Opportunity Pipeline</h3>
              <div className="flex-1" />
              <select value={leverFilter} onChange={e => setLeverFilter(e.target.value)}
                className="h-7 rounded-lg border border-gray-200 px-2 text-xs text-gray-700 bg-white">
                <option value="all">All Levers</option>
                <option value="Aggregate & Rationalize">Lever 1</option>
                <option value="Renegotiate & Partner">Lever 2</option>
                <option value="Deep-Dive & Rebid">Lever 3</option>
              </select>
              <select value={stageFilter} onChange={e => setStageFilter(e.target.value)}
                className="h-7 rounded-lg border border-gray-200 px-2 text-xs text-gray-700 bg-white">
                <option value="all">All Stages</option>
                <option value="Identified">Identified</option>
                <option value="Validated">Validated</option>
                <option value="In Progress">In Progress</option>
              </select>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                    <th className="text-left px-5 py-3">Opportunity</th>
                    <th className="text-left px-4 py-3">Category</th>
                    <th className="text-right px-4 py-3">Current Spend</th>
                    <th className="text-right px-4 py-3">Est. Savings</th>
                    <th className="text-center px-4 py-3">Confidence</th>
                    <th className="text-center px-4 py-3">Stage</th>
                    <th className="text-center px-4 py-3">Timeline</th>
                    <th className="text-center px-4 py-3">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {opps.map(o => (
                    <tr key={o.id} className="border-t border-gray-50 hover:bg-gray-50 transition-colors">
                      <td className="px-5 py-3">
                        <div className="flex items-center gap-2">
                          <div className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: LEVER_COLOR[o.lever] ?? "#999" }} />
                          <span className="font-medium text-gray-900 text-xs">{o.name}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-600">{o.category.split(" ")[0]}</td>
                      <td className="px-4 py-3 text-right text-xs text-gray-700">{formatCurrency(o.currentSpend)}</td>
                      <td className="px-4 py-3 text-right text-xs font-semibold text-green-700">
                        {formatCurrency(o.estimatedSavingsLow)}–{formatCurrency(o.estimatedSavingsHigh)}
                      </td>
                      <td className="px-4 py-3 text-center">
                        <span className={cn("text-xs font-semibold", o.confidence >= 0.8 ? "text-green-700" : "text-amber-600")}>
                          {formatPct(o.confidence * 100)}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-center">
                        <span className="text-xs font-medium" style={{ color: STAGE_COLOR[o.stage] ?? "#525252" }}>
                          {o.stage}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-center text-xs text-gray-500">{o.timeline}</td>
                      <td className="px-4 py-3 text-center">
                        <span className="text-xs font-semibold text-[#2563eb] cursor-pointer hover:underline">{o.action}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Right panel */}
          <div className="space-y-4">
            {/* Scenario modeling */}
            <div className="content-card p-5">
              <h3 className="font-semibold text-[#123262] mb-4">Scenario Modeling</h3>
              <div className="space-y-4">
                <div>
                  <div className="flex justify-between text-xs mb-2">
                    <span className="text-gray-500">Target Vendor Reduction</span>
                    <span className="font-bold text-[#2563eb]">{vendorReduction}%</span>
                  </div>
                  <input
                    type="range" min={10} max={70} value={vendorReduction}
                    onChange={e => setVendorReduction(Number(e.target.value))}
                    className="w-full accent-[#2563eb]"
                  />
                  <div className="flex justify-between text-[10px] text-gray-400 mt-1">
                    <span>10%</span><span>70%</span>
                  </div>
                </div>
                <button className="w-full py-2 rounded-lg bg-[#005CB9] text-white text-sm font-medium hover:bg-[#00458C] transition-colors">
                  Run Scenario
                </button>
              </div>
            </div>

            {/* Baseline vs Proposed */}
            <div className="content-card p-5">
              <h3 className="font-semibold text-[#123262] mb-3">Baseline vs. Proposed</h3>
              <div className="space-y-2 text-sm">
                {[
                  ["Total Vendors", `${baselineVendors}`, `${proposedVendors}`],
                  ["Annual Spend", "$754M", formatCurrency(754_000_000 - projectedSavings)],
                  ["Compliance Rate", "72%", "95%"],
                ].map(([label, baseline, proposed]) => (
                  <div key={label} className="flex items-center justify-between py-2 border-b border-gray-50">
                    <span className="text-xs text-gray-500">{label}</span>
                    <div className="flex items-center gap-2 text-xs">
                      <span className="text-gray-400">{baseline}</span>
                      <span className="text-gray-300">→</span>
                      <span className="font-bold text-[#2563eb]">{proposed}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Savings waterfall */}
            <div className="content-card p-5">
              <h3 className="font-semibold text-[#123262] mb-3">Savings by Category ($M)</h3>
              <ResponsiveContainer width="100%" height={180}>
                <BarChart data={waterfallData} margin={{ left: 0, right: 8, top: 4, bottom: 4 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                  <XAxis dataKey="name" fontSize={10} />
                  <YAxis fontSize={10} tickFormatter={v => `$${v}M`} />
                  <Tooltip formatter={(v: number) => [`$${v}M`, "Est. Savings"]} />
                  <Bar dataKey="savings" radius={[4, 4, 0, 0]}>
                    {waterfallData.map((entry, i) => <Cell key={i} fill={entry.color} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>

            <AiInsightPanel
              title="AI Recommendations"
              modelConfigured={modelConfigured}
              data={aiInsights}
              isLoading={insightsLoading}
              onRegenerate={() => regenerate.mutate()}
              isRegenerating={regenerate.isPending}
              loadingLabel="Ranking savings opportunities…"
              emptyHint="Pick a chat model in Settings → AI Chat Model to enable AI recommendations."
            />
          </div>
        </div>
      </div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col h-full">
      <div className="pods-header"><div className="page-title">Savings Opportunities</div></div>
      <div className="flex-1 flex items-center justify-center">
        <Loader2 size={20} className="animate-spin text-[#005CB9]" />
      </div>
    </div>
  );
}
