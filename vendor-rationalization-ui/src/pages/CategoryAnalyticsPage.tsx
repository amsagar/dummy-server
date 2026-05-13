import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
  PieChart, Pie, Legend,
} from "recharts";
import { Loader2, RefreshCw, AlertTriangle, TrendingUp, Handshake } from "lucide-react";
import { vrApi } from "@/services/api";
import { formatCurrency, formatPct, cn } from "@/lib/utils";
import type { AiInsightsResponse, CategoryAnalyticsResponse, CategoryDetail, VendorRationalizationConfig } from "@/types/vendorRationalization";
import { LEVER_COLOR, LEVER_BG } from "@/types/vendorRationalization";
import { AiInsightPanel } from "@/components/insights/AiInsightPanel";

const LEVER_ICON: Record<string, React.ReactNode> = {
  "Aggregate & Rationalize": <TrendingUp size={13} />,
  "Renegotiate & Partner": <Handshake size={13} />,
  "Deep-Dive & Rebid": <AlertTriangle size={13} />,
};

export default function CategoryAnalyticsPage() {
  const [selected, setSelected] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<"spend" | "vendors">("spend");

  const qc = useQueryClient();
  const { data, isLoading, refetch, isFetching } = useQuery<CategoryAnalyticsResponse>({
    queryKey: ["vr-categories"],
    queryFn: () => vrApi.categories(),
    staleTime: 60_000,
  });
  const { data: vrConfig } = useQuery<VendorRationalizationConfig>({
    queryKey: ["vr-config"],
    queryFn: () => vrApi.getConfig(),
    staleTime: 60_000,
  });

  // Hooks have to run on every render — derive the selected category here
  // (allowing for the loading state) before the early return so the hook
  // order is stable. React's Rules of Hooks forbid adding more hooks below
  // a conditional `return`.
  const cats = [...(data?.categories ?? [])].sort((a, b) =>
    sortBy === "spend" ? b.totalSpend - a.totalSpend : b.vendorCount - a.vendorCount
  );
  const selectedCat = cats.find(c => c.category === selected) ?? cats[0] ?? null;
  const modelConfigured = Boolean(vrConfig?.chatModelRef);

  const insightsKey = ["vr-insights", "category", selectedCat?.category ?? ""];
  const { data: aiInsights, isLoading: insightsLoading } = useQuery<AiInsightsResponse>({
    queryKey: insightsKey,
    queryFn: () => vrApi.insights("category", { scope: selectedCat!.category }),
    enabled: !!selectedCat && modelConfigured,
    staleTime: 5 * 60_000,
  });
  const regenerate = useMutation({
    mutationFn: () => vrApi.insights("category", { scope: selectedCat!.category, refresh: true }),
    onSuccess: (resp: AiInsightsResponse) => qc.setQueryData(insightsKey, resp),
  });

  if (isLoading) return <LoadingState />;

  const totalSpend = data?.totalSpend ?? 0;

  // Lever distribution for pie chart
  const leverMap: Record<string, number> = {};
  cats.forEach(c => { leverMap[c.rationalizationLever] = (leverMap[c.rationalizationLever] ?? 0) + c.totalSpend; });
  const leverPieData = Object.entries(leverMap).map(([name, value]) => ({ name, value }));

  return (
    <div className="flex flex-col h-full">
      <div className="pods-header">
        <div>
          <div className="page-title">Category Spend Analytics</div>
          <div className="page-subtitle">
            Analyze spend patterns across {cats.length} procurement categories • {formatCurrency(totalSpend)} total
          </div>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={sortBy}
            onChange={e => setSortBy(e.target.value as "spend" | "vendors")}
            className="h-8 rounded-lg border border-gray-200 px-2 text-sm text-gray-700 bg-white"
          >
            <option value="spend">Sort by Spend</option>
            <option value="vendors">Sort by Vendors</option>
          </select>
          <button onClick={() => refetch()} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-200 text-sm text-gray-600 hover:bg-gray-50">
            {isFetching ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          </button>
        </div>
      </div>

      <div className="flex-1 p-6 overflow-y-auto">
        <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
          {/* Category table — left 2/3 */}
          <div className="xl:col-span-2 space-y-5">
            {/* Summary KPIs */}
            <div className="grid grid-cols-3 gap-4">
              <div className="stat-card">
                <div className="text-xs text-gray-500 mb-1">Total Spend</div>
                <div className="text-2xl font-bold text-[#123262]">{formatCurrency(totalSpend)}</div>
                <div className="text-xs text-green-600 mt-1">↓ 3.2% vs last year</div>
              </div>
              <div className="stat-card">
                <div className="text-xs text-gray-500 mb-1">Active Vendors</div>
                <div className="text-2xl font-bold text-[#123262]">{data?.totalVendors.toLocaleString()}</div>
                <div className="text-xs text-amber-600 mt-1">Target: 75-85</div>
              </div>
              <div className="stat-card">
                <div className="text-xs text-gray-500 mb-1">Unmanaged Spend</div>
                <div className="text-2xl font-bold text-[#123262]">18.4%</div>
                <div className="text-xs text-red-600 mt-1">{formatCurrency(totalSpend * 0.184)} at risk</div>
              </div>
            </div>

            {/* Category table */}
            <div className="content-card">
              <div className="px-5 py-4 border-b border-gray-100">
                <h3 className="font-semibold text-[#123262]">Category Spend Distribution & Health</h3>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                      <th className="text-left px-5 py-3">Category</th>
                      <th className="text-right px-4 py-3">Spend</th>
                      <th className="text-center px-4 py-3">Vendors</th>
                      <th className="text-right px-4 py-3">% Total</th>
                      <th className="text-center px-4 py-3">Lever</th>
                    </tr>
                  </thead>
                  <tbody>
                    {cats.map(cat => (
                      <tr
                        key={cat.category}
                        onClick={() => setSelected(cat.category)}
                        className={cn(
                          "border-t border-gray-50 cursor-pointer transition-colors",
                          selected === cat.category ? "bg-blue-50" : "hover:bg-gray-50"
                        )}
                      >
                        <td className="px-5 py-3 font-medium text-gray-900">{cat.category}</td>
                        <td className="px-4 py-3 text-right font-semibold text-gray-900">{formatCurrency(cat.totalSpend)}</td>
                        <td className="px-4 py-3 text-center">
                          <span className={cn(
                            "font-semibold",
                            cat.vendorCount >= 20 ? "text-red-600" : cat.vendorCount >= 10 ? "text-amber-600" : "text-green-600"
                          )}>
                            {cat.vendorCount}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-right text-gray-600">{formatPct(cat.pctOfTotal)}</td>
                        <td className="px-4 py-3 text-center">
                          <span
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium"
                            style={{
                              background: LEVER_BG[cat.rationalizationLever] ?? "#f5f5f5",
                              color: LEVER_COLOR[cat.rationalizationLever] ?? "#525252",
                            }}
                          >
                            {LEVER_ICON[cat.rationalizationLever]}
                            {cat.rationalizationLever.split(" ")[0]}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Charts */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
              <div className="content-card p-5">
                <h3 className="font-semibold text-[#123262] mb-4">Spend by Lever Strategy</h3>
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie data={leverPieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={70} label={({ name, percent }) => `${(percent * 100).toFixed(0)}%`}>
                      {leverPieData.map((entry, i) => (
                        <Cell key={i} fill={LEVER_COLOR[entry.name] ?? "#999"} />
                      ))}
                    </Pie>
                    <Legend formatter={v => <span className="text-xs">{v}</span>} />
                    <Tooltip formatter={(v: number) => formatCurrency(v)} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="content-card p-5">
                <h3 className="font-semibold text-[#123262] mb-4">Vendor Concentration</h3>
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={cats.slice(0, 8)} margin={{ left: 0, right: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="category" fontSize={9} tickFormatter={v => v.split(" ")[0]} />
                    <YAxis fontSize={11} />
                    <Tooltip formatter={(v: number) => [v, "Vendors"]} labelFormatter={l => l} />
                    <Bar dataKey="vendorCount" fill="#2563eb" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>

          {/* Right panel — selected category detail */}
          <div className="space-y-4">
            {selectedCat && (
              <>
                <AiInsightPanel
                  title="AI-Generated Insights"
                  modelConfigured={modelConfigured}
                  data={aiInsights}
                  isLoading={insightsLoading}
                  onRegenerate={() => regenerate.mutate()}
                  isRegenerating={regenerate.isPending}
                  loadingLabel={`Generating insights for ${selectedCat.category}…`}
                />

                <div className="content-card p-5">
                  <h3 className="font-semibold text-[#123262] mb-3">Selected: {selectedCat.category}</h3>
                  <div className="space-y-2 text-sm">
                    {[
                      ["Total Spend", formatCurrency(selectedCat.totalSpend)],
                      ["Active Vendors", selectedCat.vendorCount],
                      ["Avg Vendor Spend", formatCurrency(selectedCat.avgVendorSpend)],
                      ["% of Total", formatPct(selectedCat.pctOfTotal)],
                      ["Lever", selectedCat.rationalizationLever],
                    ].map(([k, v]) => (
                      <div key={String(k)} className="flex justify-between py-1.5 border-b border-gray-50">
                        <span className="text-gray-500">{k}</span>
                        <span className="font-medium text-gray-900 text-right max-w-[55%]">{v}</span>
                      </div>
                    ))}
                  </div>
                  <div className="mt-4">
                    <div className="text-xs font-semibold text-gray-700 mb-2">Top Vendors</div>
                    {selectedCat.topVendors.slice(0, 5).map((v, i) => (
                      <div key={i} className="flex justify-between items-center py-1.5 border-b border-gray-50 text-xs">
                        <span className="text-gray-700 truncate max-w-[60%]">{v.name}</span>
                        <span className="font-semibold text-gray-900">{formatCurrency(v.sumAmount)}</span>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="content-card p-5">
                  <h3 className="font-semibold text-[#123262] mb-3">Categories Requiring Attention</h3>
                  {cats.filter(c => c.vendorCount >= 15).slice(0, 3).map(c => (
                    <div key={c.category} className="flex items-center justify-between py-2 border-b border-gray-50 text-xs">
                      <div>
                        <div className="font-medium text-gray-800">{c.category.split(" ")[0]}</div>
                        <div className="text-gray-500">{c.vendorCount} vendors</div>
                      </div>
                      <span className="text-red-600 font-semibold">Sprawl</span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col h-full">
      <div className="pods-header"><div className="page-title">Category Analytics</div></div>
      <div className="flex-1 flex items-center justify-center">
        <Loader2 size={20} className="animate-spin text-[#005CB9]" />
      </div>
    </div>
  );
}
