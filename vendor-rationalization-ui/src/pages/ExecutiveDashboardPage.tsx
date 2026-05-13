import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
} from "recharts";
import {
  Users, DollarSign, BarChart3, TrendingUp,
  AlertTriangle, RefreshCw, Loader2,
} from "lucide-react";
import { vrApi } from "@/services/api";
import { formatCurrency, formatPct } from "@/lib/utils";
import type {
  AiInsightsResponse,
  DashboardSummary,
  StrategicLeverSummary,
  VendorRationalizationConfig,
} from "@/types/vendorRationalization";
import { LEVER_COLOR } from "@/types/vendorRationalization";
import { AiInsightPanel } from "@/components/insights/AiInsightPanel";

const CATEGORY_COLORS = [
  "#2563eb","#7c3aed","#059669","#d97706","#dc2626",
  "#0891b2","#be185d","#65a30d","#9333ea","#ea580c",
];

// Human-readable subtitle shown under each lever name on the Strategic
// Levers panel. The numeric scope and example categories come from the
// server-side /strategic-levers endpoint — this is just the descriptive
// label per lever name.
const LEVER_SUBTITLE: Record<string, string> = {
  "Aggregate & Rationalize": "Fragmented Categories",
  "Renegotiate & Partner": "Concentrated Categories",
  "Deep-Dive & Rebid": "Outlier Categories",
};

// Single-line abbreviations for the dashboard's category chart. The longer
// names overflow the y-axis allocation and wrap to two or three lines,
// which collides with neighbouring labels. Keep these short enough to fit
// on one line within an axis width of ~150px.
const CATEGORY_SHORT_NAMES: Record<string, string> = {
  "Financial & Accounting Services": "Financial Svcs",
  "Fleet, Vehicle & Auto Services": "Fleet & Vehicle",
  "Freight & Transportation": "Freight",
  "Marketing & Advertising": "Marketing",
  "Construction & Real Estate": "Construction",
  "Construction & Contracting": "Construction",
  "Insurance & Risk Management": "Insurance",
  "Technology & Software": "Technology",
  "Consulting & Professional Services": "Consulting",
  "General Services": "General Svcs",
  "Industrial Equipment": "Industrial Eq.",
  "HR & Staffing": "HR & Staffing",
  "Legal": "Legal",
  "Moving": "Moving",
  "Government": "Government",
  "Rent Space": "Rent Space",
};

function shortCategoryName(cat: string): string {
  const mapped = CATEGORY_SHORT_NAMES[cat];
  if (mapped) return mapped;
  // Fallback: truncate anything longer than 18 chars so it never wraps.
  return cat.length > 18 ? cat.slice(0, 16) + "…" : cat;
}

export default function ExecutiveDashboardPage() {
  const { data, isLoading, refetch, isFetching } = useQuery<DashboardSummary>({
    queryKey: ["vr-dashboard"],
    queryFn: () => vrApi.dashboard(),
    staleTime: 60_000,
  });
  // Tunable knobs (KPI target text, insight thresholds, lever map) edited
  // from the Settings admin page. Fall back to inline defaults if the
  // request fails so the dashboard always renders.
  const { data: config } = useQuery<VendorRationalizationConfig>({
    queryKey: ["vr-config"],
    queryFn: () => vrApi.getConfig(),
    staleTime: 60_000,
  });
  // Strategic Levers panel — scope $ + example categories now computed
  // server-side from the lever map + live spend. Replaces the hardcoded
  // ~$360M / ~$298M / ~$6M array that used to live in this file.
  const { data: strategicLevers } = useQuery<StrategicLeverSummary[]>({
    queryKey: ["vr-strategic-levers"],
    queryFn: () => vrApi.strategicLevers(),
    staleTime: 60_000,
  });
  // AI-generated dashboard insights — replaces the three hardcoded template
  // cards. Backend caches for ~10 minutes; the Regenerate button forces
  // a fresh LLM call.
  const queryClient = useQueryClient();
  const { data: aiInsights, isLoading: insightsLoading } = useQuery<AiInsightsResponse>({
    queryKey: ["vr-insights", "dashboard"],
    queryFn: () => vrApi.insights("dashboard"),
    staleTime: 5 * 60_000,
  });
  const regenerate = useMutation({
    mutationFn: () => vrApi.insights("dashboard", { refresh: true }),
    onSuccess: (resp: AiInsightsResponse) => {
      queryClient.setQueryData(["vr-insights", "dashboard"], resp);
    },
  });

  if (isLoading) return <LoadingState />;

  const d = data!;
  const totalSpend = d.totalSpend;
  const chartData = d.byCategory.slice(0, 10).map((c, i) => ({
    name: shortCategoryName(c.category),
    spend: Math.round(c.totalSpend / 1_000_000),
    vendors: c.vendorCount,
    color: CATEGORY_COLORS[i % CATEGORY_COLORS.length],
  }));

  const topCat = d.byCategory[0];
  const mostFragmented = [...d.byCategory].sort((a, b) => b.vendorCount - a.vendorCount)[0];
  const kpiActiveVendorsTarget = config?.kpiTargets.activeVendorsTarget ?? "Target: 75-85";
  const modelConfigured = Boolean(config?.chatModelRef);

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="pods-header">
        <div>
          <div className="page-title">Executive Dashboard</div>
          <div className="page-subtitle">Vendor Ecosystem Health & Savings Progress Overview</div>
        </div>
        <button
          onClick={() => refetch()}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-200 text-sm text-gray-600 hover:bg-gray-50 transition-colors"
        >
          {isFetching ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          Refresh
        </button>
      </div>

      <div className="flex-1 p-6 space-y-5 overflow-y-auto">
        {/* KPI Cards */}
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
          <KpiCard
            label="Total Spend"
            value={formatCurrency(totalSpend)}
            sub="Addressable"
            icon={<DollarSign size={20} className="text-[#005CB9]" />}
            highlight
          />
          <KpiCard
            label="Active Vendors"
            value={d.totalVendors.toLocaleString()}
            sub={kpiActiveVendorsTarget}
            icon={<Users size={20} className="text-[#005CB9]" />}
          />
          <KpiCard
            label="Categories"
            value={d.totalCategories}
            sub="Procurement"
            icon={<BarChart3 size={20} className="text-[#005CB9]" />}
          />
          <KpiCard
            label="80% Spend"
            value={`${d.top80PctVendors} vendors`}
            sub={`${formatPct(d.pareto.pctOfVendors)} of total`}
            icon={<TrendingUp size={20} className="text-[#7c3aed]" />}
          />
          <KpiCard
            label="Top Category"
            value={formatCurrency(topCat?.totalSpend ?? 0)}
            sub={topCat?.category?.split(" ")[0] ?? "—"}
            icon={<BarChart3 size={20} className="text-[#059669]" />}
          />
          <KpiCard
            label="Most Fragmented"
            value={`${mostFragmented?.vendorCount ?? 0} vendors`}
            sub={mostFragmented?.category?.split(" ")[0] ?? "—"}
            icon={<AlertTriangle size={20} className="text-[#d97706]" />}
          />
        </div>

        {/* Charts row */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          {/* Spend by Category bar chart */}
          <div className="content-card p-5 lg:col-span-2">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="font-semibold text-[#123262]">Spend by Category</h3>
                <p className="text-xs text-gray-500 mt-0.5">Top 10 categories by total spend ($M)</p>
              </div>
            </div>
            <ResponsiveContainer width="100%" height={320}>
              <BarChart data={chartData} layout="vertical" margin={{ left: 8, right: 24, top: 4, bottom: 4 }}>
                <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f0f0f0" />
                <XAxis type="number" fontSize={11} tickFormatter={v => `$${v}M`} />
                <YAxis
                  type="category"
                  dataKey="name"
                  width={150}
                  fontSize={11}
                  interval={0}
                  tickLine={false}
                  axisLine={false}
                />
                <Tooltip formatter={(v: number) => [`$${v}M`, "Spend"]} />
                <Bar dataKey="spend" radius={[0, 4, 4, 0]}>
                  {chartData.map((entry, i) => (
                    <Cell key={i} fill={entry.color} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>

          <AiInsightPanel
            title="AI Insights"
            modelConfigured={modelConfigured}
            data={aiInsights}
            isLoading={insightsLoading}
            onRegenerate={() => regenerate.mutate()}
            isRegenerating={regenerate.isPending}
            loadingLabel={`Generating insights from ${aiInsights?.modelRef ?? config?.chatModelRef}…`}
            emptyHint="Open Settings → AI Chat Model and pick a model to enable AI-generated dashboard insights."
          />
        </div>

        {/* Strategic Levers — scope $ + example categories now computed
            server-side from the lever map + live spend. Edit via Settings. */}
        <div>
          <h3 className="font-semibold text-[#123262] mb-3">Strategic Levers</h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {(strategicLevers ?? []).map((lever, i) => {
              const color = LEVER_COLOR[lever.lever] ?? "#475569";
              const sub = LEVER_SUBTITLE[lever.lever] ?? `${lever.categoryCount} categories`;
              return (
                <div key={lever.lever} className="content-card p-5">
                  <div className="flex items-center gap-3 mb-3">
                    <div className="w-9 h-9 rounded-full flex items-center justify-center text-white font-bold text-sm flex-shrink-0"
                      style={{ background: color }}>
                      {i + 1}
                    </div>
                    <div>
                      <div className="font-semibold text-sm text-gray-900">{lever.lever}</div>
                      <div className="text-xs text-gray-500">{sub}</div>
                    </div>
                  </div>
                  <div className="space-y-1.5 text-xs">
                    <div className="flex justify-between">
                      <span className="text-gray-500">Scope</span>
                      <span className="font-semibold" style={{ color }}>
                        {formatCurrency(lever.scopeSpend)}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-gray-500">Categories</span>
                      <span className="text-gray-700">{lever.categoryCount}</span>
                    </div>
                    <div className="pt-1 border-t border-gray-100 text-gray-500">
                      {lever.exampleCategories.length > 0
                        ? lever.exampleCategories.join(", ")
                        : "—"}
                    </div>
                  </div>
                </div>
              );
            })}
            {(strategicLevers ?? []).length === 0 && (
              <div className="content-card p-5 text-sm text-gray-500 md:col-span-3">
                No strategic levers to display — no spend data loaded.
              </div>
            )}
          </div>
        </div>

        {/* Top Vendors table */}
        <div className="content-card">
          <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
            <h3 className="font-semibold text-[#123262]">Top Vendors by Spend</h3>
            <span className="text-xs text-gray-400">Top 10 of {d.totalVendors.toLocaleString()}</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                  <th className="text-left px-5 py-3">Vendor</th>
                  <th className="text-left px-4 py-3">Category</th>
                  <th className="text-right px-4 py-3">Spend</th>
                  <th className="text-right px-4 py-3">% of Total</th>
                  <th className="text-center px-4 py-3">Group</th>
                </tr>
              </thead>
              <tbody>
                {d.topVendors.slice(0, 10).map((v, i) => (
                  <tr key={i} className="border-t border-gray-50 hover:bg-gray-50 transition-colors">
                    <td className="px-5 py-3 font-medium text-gray-900">{v.name}</td>
                    <td className="px-4 py-3 text-gray-600 text-xs">{v.generalizedCategory}</td>
                    <td className="px-4 py-3 text-right font-semibold text-gray-900">{formatCurrency(v.sumAmount)}</td>
                    <td className="px-4 py-3 text-right text-gray-600">
                      {totalSpend > 0 ? formatPct((v.sumAmount / totalSpend) * 100) : "—"}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${
                        v.topGroup === "80% Spend"
                          ? "bg-blue-50 text-blue-700"
                          : "bg-gray-100 text-gray-600"
                      }`}>
                        {v.topGroup || "Other"}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

function KpiCard({ label, value, sub, icon, highlight }: {
  label: string; value: string | number; sub: string; icon: React.ReactNode; highlight?: boolean;
}) {
  return (
    <div className={`stat-card ${highlight ? "border-[#005CB9]/20" : ""}`}>
      <div className="flex items-start justify-between mb-2">
        <span className="text-xs text-gray-500">{label}</span>
        <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center flex-shrink-0">{icon}</div>
      </div>
      <div className="text-2xl font-bold text-[#123262]">{value}</div>
      <div className="text-xs text-gray-400 mt-1">{sub}</div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col h-full">
      <div className="pods-header">
        <div className="page-title">Executive Dashboard</div>
      </div>
      <div className="flex-1 flex items-center justify-center">
        <div className="flex items-center gap-3 text-gray-500">
          <Loader2 size={20} className="animate-spin text-[#005CB9]" />
          <span>Loading dashboard data...</span>
        </div>
      </div>
    </div>
  );
}
