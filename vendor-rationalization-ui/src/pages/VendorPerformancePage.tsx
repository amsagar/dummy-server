// FIXME: simulated data — the vendor-count threshold buckets here
// (≥20 → Consolidate, ≤3 → Renegotiate, fragmented ≥15, highRisk ≥20,
// flagged ≥10) duplicate the Savings Buckets concept that now lives in
// the server-side tunable config. When real vendor-performance data
// arrives, rewire this page to read those thresholds from
// vrApi.getConfig() instead of from the local constants below. Until
// then this page works off the buckets it sees in code only.
import { useState, useCallback } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Loader2, RefreshCw, ChevronLeft, ChevronRight } from "lucide-react";
import { vrApi } from "@/services/api";
import { formatCurrency, formatPct, cn } from "@/lib/utils";
import type {
  AiInsightsResponse,
  CategoryAnalyticsResponse,
  VendorListResponse,
  VendorRationalizationConfig,
} from "@/types/vendorRationalization";
import { LEVER_COLOR, LEVER_BG } from "@/types/vendorRationalization";
import { AiInsightPanel } from "@/components/insights/AiInsightPanel";

const PAGE_SIZE = 25;

function getRationalizationAction(vendorCount: number, catVendorCount: number): { label: string; color: string } {
  if (catVendorCount >= 20) return { label: "Consolidate", color: "#b45309" };
  if (catVendorCount <= 3) return { label: "Renegotiate", color: "#1d4ed8" };
  return { label: "Analyze", color: "#7c3aed" };
}

export default function VendorPerformancePage() {
  const [search, setSearch] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [category, setCategory] = useState("all");
  const [offset, setOffset] = useState(0);

  const { data: catData } = useQuery<CategoryAnalyticsResponse>({
    queryKey: ["vr-categories"],
    queryFn: () => vrApi.categories(),
    staleTime: 60_000,
  });
  const { data: vrConfig } = useQuery<VendorRationalizationConfig>({
    queryKey: ["vr-config"],
    queryFn: () => vrApi.getConfig(),
    staleTime: 60_000,
  });
  const modelConfigured = Boolean(vrConfig?.chatModelRef);

  const qc = useQueryClient();
  const insightsKey = ["vr-insights", "vendor"];
  const { data: aiInsights, isLoading: insightsLoading } = useQuery<AiInsightsResponse>({
    queryKey: insightsKey,
    queryFn: () => vrApi.insights("vendor"),
    enabled: modelConfigured,
    staleTime: 5 * 60_000,
  });
  const regenerate = useMutation({
    mutationFn: () => vrApi.insights("vendor", { refresh: true }),
    onSuccess: (resp: AiInsightsResponse) => qc.setQueryData(insightsKey, resp),
  });

  const { data: catList } = useQuery<string[]>({
    queryKey: ["vr-category-list"],
    queryFn: () => vrApi.categoryList(),
    staleTime: 300_000,
  });

  const { data, isLoading, isFetching, refetch } = useQuery<VendorListResponse>({
    queryKey: ["vr-vendors", search, category, offset],
    queryFn: () => vrApi.vendors({ search: search || undefined, category, limit: PAGE_SIZE, offset }),
    staleTime: 30_000,
  });

  const handleSearch = useCallback(() => {
    setSearch(searchInput);
    setOffset(0);
  }, [searchInput]);

  const catVendorMap: Record<string, number> = {};
  catData?.categories.forEach(c => { catVendorMap[c.category] = c.vendorCount; });

  const totalPages = data ? Math.ceil(data.total / PAGE_SIZE) : 0;
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;

  // Summary stats
  const strategic = data?.vendors.filter(v => v.topGroup === "80% Spend").length ?? 0;
  const fragmented = catData?.categories.filter(c => c.vendorCount >= 15).length ?? 0;
  const highRisk = catData?.categories.filter(c => c.vendorCount >= 20).length ?? 0;

  return (
    <div className="flex flex-col h-full">
      <div className="pods-header">
        <div>
          <div className="page-title">Vendor Performance & Rationalization</div>
          <div className="page-subtitle">
            Analyze {data?.total.toLocaleString() ?? "..."} vendors across $754M addressable spend
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => refetch()} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-200 text-sm text-gray-600 hover:bg-gray-50">
            {isFetching ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          </button>
        </div>
      </div>

      <div className="flex-1 p-6 overflow-y-auto space-y-5">
        {/* Summary cards */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <SummaryCard label="Strategic Vendors" value={strategic} sub="High performance, retain" color="#16a34a" />
          <SummaryCard label="Consolidation Candidates" value={fragmented} sub="Fragmented categories" color="#d97706" />
          <SummaryCard label="High-Risk Categories" value={highRisk} sub="Requires monitoring" color="#dc2626" />
          <SummaryCard label="Flagged for Rationalization" value={catData?.categories.filter(c => c.vendorCount >= 10).length ?? 0} sub="30-70% reduction target" color="#9333ea" />
        </div>

        <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
          {/* Vendor table */}
          <div className="xl:col-span-2 content-card">
            {/* Filters */}
            <div className="flex items-center gap-3 px-5 py-4 border-b border-gray-100">
              <h3 className="font-semibold text-[#123262] mr-2">Vendor Analysis Table</h3>
              <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
                {data?.total.toLocaleString() ?? "..."} vendors
              </span>
              <div className="flex-1" />
              <div className="relative">
                <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  value={searchInput}
                  onChange={e => setSearchInput(e.target.value)}
                  onKeyDown={e => e.key === "Enter" && handleSearch()}
                  placeholder="Search vendors..."
                  className="h-8 pl-8 pr-3 rounded-lg border border-gray-200 text-sm bg-gray-50 w-48 focus:outline-none focus:border-[#005CB9]"
                />
              </div>
              <select
                value={category}
                onChange={e => { setCategory(e.target.value); setOffset(0); }}
                className="h-8 rounded-lg border border-gray-200 px-2 text-sm text-gray-700 bg-white"
              >
                <option value="all">All Categories</option>
                {(catList ?? []).map(c => <option key={c} value={c}>{c}</option>)}
              </select>
              <button
                onClick={handleSearch}
                className="h-8 px-3 rounded-lg bg-[#005CB9] text-white text-sm hover:bg-[#00458C] transition-colors"
              >
                Search
              </button>
            </div>

            {isLoading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 size={20} className="animate-spin text-[#005CB9]" />
              </div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                        <th className="text-left px-5 py-3">Vendor</th>
                        <th className="text-right px-4 py-3">Spend</th>
                        <th className="text-left px-4 py-3">Category</th>
                        <th className="text-center px-4 py-3">Group</th>
                        <th className="text-center px-4 py-3">Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(data?.vendors ?? []).map((v, i) => {
                        const catVendors = catVendorMap[v.generalizedCategory] ?? 5;
                        const action = getRationalizationAction(1, catVendors);
                        return (
                          <tr key={i} className="border-t border-gray-50 hover:bg-gray-50 transition-colors">
                            <td className="px-5 py-3">
                              <div className="flex items-center gap-2.5">
                                <div className="w-7 h-7 rounded-full flex items-center justify-center text-white text-xs font-bold flex-shrink-0"
                                  style={{ background: v.topGroup === "80% Spend" ? "#2563eb" : "#9ca3af" }}>
                                  {v.name.slice(0, 2).toUpperCase()}
                                </div>
                                <div>
                                  <div className="font-medium text-gray-900 text-xs leading-tight">{v.name}</div>
                                  <div className="text-[10px] text-gray-400">{v.vendorGroup}</div>
                                </div>
                              </div>
                            </td>
                            <td className="px-4 py-3 text-right font-semibold text-gray-900">{formatCurrency(v.sumAmount)}</td>
                            <td className="px-4 py-3 text-xs text-gray-600">{v.generalizedCategory}</td>
                            <td className="px-4 py-3 text-center">
                              <span className={cn(
                                "inline-flex px-2 py-0.5 rounded-full text-xs font-medium",
                                v.topGroup === "80% Spend" ? "bg-blue-50 text-blue-700" : "bg-gray-100 text-gray-600"
                              )}>
                                {v.topGroup || "Other"}
                              </span>
                            </td>
                            <td className="px-4 py-3 text-center">
                              <span className="text-xs font-medium" style={{ color: action.color }}>
                                {action.label}
                              </span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>

                {/* Pagination */}
                <div className="flex items-center justify-between px-5 py-3 border-t border-gray-100">
                  <span className="text-xs text-gray-500">
                    Showing {offset + 1}–{Math.min(offset + PAGE_SIZE, data?.total ?? 0)} of {data?.total.toLocaleString()}
                  </span>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
                      disabled={offset === 0}
                      className="p-1.5 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40"
                    >
                      <ChevronLeft size={14} />
                    </button>
                    <span className="px-3 py-1 text-xs text-gray-700">{currentPage} / {totalPages}</span>
                    <button
                      onClick={() => setOffset(offset + PAGE_SIZE)}
                      disabled={offset + PAGE_SIZE >= (data?.total ?? 0)}
                      className="p-1.5 rounded border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-40"
                    >
                      <ChevronRight size={14} />
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>

          {/* Right panel */}
          <div className="space-y-4">
            <AiInsightPanel
              title="AI Anomaly Detection"
              modelConfigured={modelConfigured}
              data={aiInsights}
              isLoading={insightsLoading}
              onRegenerate={() => regenerate.mutate()}
              isRegenerating={regenerate.isPending}
              loadingLabel="Scanning vendor portfolio for anomalies…"
              emptyHint="Pick a chat model in Settings → AI Chat Model to enable anomaly detection."
            />

            {/* Concentration Risk */}
            <div className="content-card p-5">
              <h3 className="font-semibold text-[#123262] text-sm mb-3">Concentration Risk by Category</h3>
              <div className="space-y-3">
                {catData?.categories.slice(0, 6).map(c => {
                  const pct = Math.min(100, (c.totalSpend / (catData.totalSpend || 1)) * 100);
                  const color = pct > 25 ? "#ef4444" : pct > 15 ? "#f59e0b" : "#22c55e";
                  return (
                    <div key={c.category}>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="text-gray-600 truncate max-w-[65%]">{c.category.split(" ")[0]}</span>
                        <span className="font-semibold" style={{ color }}>{formatPct(pct)}</span>
                      </div>
                      <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, background: color }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function SummaryCard({ label, value, sub, color }: { label: string; value: number; sub: string; color: string }) {
  return (
    <div className="stat-card">
      <div className="text-xs text-gray-500 mb-1">{label}</div>
      <div className="text-2xl font-bold" style={{ color }}>{value}</div>
      <div className="text-xs text-gray-400 mt-1">{sub}</div>
    </div>
  );
}
