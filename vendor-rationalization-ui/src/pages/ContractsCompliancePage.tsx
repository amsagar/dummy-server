// FIXME: simulated data — most of this page is wired to constants
// (managed/unmanaged spend split, compliance %, automation %, exception
// counts, missing-clauses array, exception trend series). Replace when a
// real contracts/compliance upstream source exists. Do not extend the
// simulated values; rip the file and re-wire against the real API.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
} from "recharts";
import { Loader2, FileText } from "lucide-react";
import { vrApi } from "@/services/api";
import { formatCurrency, formatPct } from "@/lib/utils";
import type {
  AiInsightsResponse,
  CategoryAnalyticsResponse,
  VendorRationalizationConfig,
} from "@/types/vendorRationalization";
import { AiInsightPanel } from "@/components/insights/AiInsightPanel";

export default function ContractsCompliancePage() {
  const { data, isLoading } = useQuery<CategoryAnalyticsResponse>({
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
  const insightsKey = ["vr-insights", "contracts"];
  const { data: aiInsights, isLoading: insightsLoading } = useQuery<AiInsightsResponse>({
    queryKey: insightsKey,
    queryFn: () => vrApi.insights("contracts"),
    enabled: modelConfigured,
    staleTime: 5 * 60_000,
  });
  const regenerate = useMutation({
    mutationFn: () => vrApi.insights("contracts", { refresh: true }),
    onSuccess: (resp: AiInsightsResponse) => qc.setQueryData(insightsKey, resp),
  });

  if (isLoading) return <LoadingState />;

  const cats = data?.categories ?? [];
  const totalSpend = data?.totalSpend ?? 0;
  const managedSpend = totalSpend * 0.85;
  const unmanagedSpend = totalSpend * 0.15;

  // Compliance scores per category (simulated from vendor count)
  const complianceData = cats.slice(0, 8).map(c => ({
    name: c.category.split(" ")[0],
    compliance: Math.max(65, Math.min(98, 100 - c.vendorCount * 0.8)),
    color: c.vendorCount >= 20 ? "#ef4444" : c.vendorCount >= 10 ? "#f59e0b" : "#22c55e",
  }));

  // Expiring contracts (simulated)
  const expiringContracts = cats.slice(0, 4).map((c, i) => ({
    vendor: ["Apex Logistics Inc", "Summit Fleet Management", "Metro Storage Solutions", "Digital Marketing Partners"][i],
    category: c.category.split(" ")[0],
    spend: [8_200_000, 5_700_000, 4_500_000, 3_100_000][i],
    daysLeft: [14, 28, 45, 58][i],
  }));

  return (
    <div className="flex flex-col h-full">
      <div className="pods-header">
        <div>
          <div className="page-title">Contracts & Invoice Compliance</div>
          <div className="page-subtitle">Governance and operational control across {formatCurrency(totalSpend)} addressable spend</div>
        </div>
        <button className="px-4 py-2 rounded-lg bg-[#005CB9] text-white text-sm font-medium hover:bg-[#00458C] transition-colors">
          View KPI Tracking
        </button>
      </div>

      <div className="flex-1 p-6 overflow-y-auto space-y-5">
        {/* KPI row */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Managed Spend</div>
            <div className="text-2xl font-bold text-[#123262]">{formatCurrency(managedSpend)}</div>
            <div className="text-xs text-green-600 mt-1">↑ 85% of addressable</div>
          </div>
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Unmanaged Spend</div>
            <div className="text-2xl font-bold text-[#123262]">{formatCurrency(unmanagedSpend)}</div>
            <div className="text-xs text-amber-600 mt-1">↓ 15% off-contract</div>
          </div>
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Contract Compliance</div>
            <div className="text-2xl font-bold text-[#123262]">91.3%</div>
            <div className="text-xs text-gray-400 mt-1">Target: 95%</div>
          </div>
          <div className="stat-card">
            <div className="text-xs text-gray-500 mb-1">Invoice Automation</div>
            <div className="text-2xl font-bold text-[#123262]">58.7%</div>
            <div className="text-xs text-gray-400 mt-1">Target: 60%</div>
          </div>
        </div>

        <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
          {/* Left — compliance chart + risk matrix */}
          <div className="xl:col-span-2 space-y-5">
            {/* Compliance by category */}
            <div className="content-card p-5">
              <div className="flex items-center justify-between mb-4">
                <h3 className="font-semibold text-[#123262]">Contract Compliance by Category</h3>
                <span className="text-xs text-[#2563eb] cursor-pointer hover:underline">View Vendors →</span>
              </div>
              <div className="space-y-3">
                {complianceData.map(c => (
                  <div key={c.name} className="flex items-center gap-3">
                    <span className="text-sm text-gray-700 w-36 flex-shrink-0">{c.name}</span>
                    <div className="flex-1 h-3 bg-gray-100 rounded-full overflow-hidden">
                      <div className="h-full rounded-full transition-all" style={{ width: `${c.compliance}%`, background: c.color }} />
                    </div>
                    <span className="text-sm font-bold w-10 text-right" style={{ color: c.color }}>
                      {Math.round(c.compliance)}%
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* Contract risk matrix */}
            <div className="content-card">
              <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
                <h3 className="font-semibold text-[#123262]">Contract Risk Matrix</h3>
                <div className="flex items-center gap-3 text-xs">
                  <span className="text-red-600 font-medium">High Risk</span>
                  <span className="text-amber-600 font-medium">Medium</span>
                  <span className="text-green-600 font-medium">Low</span>
                </div>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                      <th className="text-left px-5 py-3">Category / Vendor</th>
                      <th className="text-center px-4 py-3">Missing Agreement</th>
                      <th className="text-center px-4 py-3">Renewal Risk</th>
                      <th className="text-center px-4 py-3">Non-Std Clauses</th>
                      <th className="text-right px-4 py-3">Savings Impact</th>
                      <th className="text-center px-4 py-3">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {cats.slice(0, 5).map((c, i) => {
                      const missing = [12, 5, 3, 1, 0][i];
                      const risk = ["High", "High", "Medium", "Medium", "Low"][i];
                      const clauses = [8, 2, 6, 4, 1][i];
                      const impact = [-2_400_000, -1_800_000, -890_000, -650_000, -200_000][i];
                      const riskColor = risk === "High" ? "#b91c1c" : risk === "Medium" ? "#b45309" : "#15803d";
                      return (
                        <tr key={c.category} className="border-t border-gray-50 hover:bg-gray-50">
                          <td className="px-5 py-3 font-medium text-gray-900 text-xs">{c.category}</td>
                          <td className="px-4 py-3 text-center text-xs font-medium" style={{ color: riskColor }}>
                            {missing > 0 ? `${missing} Missing` : "None"}
                          </td>
                          <td className="px-4 py-3 text-center text-xs font-medium" style={{ color: riskColor }}>{risk}</td>
                          <td className="px-4 py-3 text-center text-xs text-amber-600">{clauses > 0 ? `${clauses} Found` : "None"}</td>
                          <td className="px-4 py-3 text-right text-xs font-bold text-red-600">{formatCurrency(impact)}</td>
                          <td className="px-4 py-3 text-center text-xs text-[#2563eb] cursor-pointer hover:underline">Drill Down</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Invoice automation metrics */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
              <div className="content-card p-5">
                <h3 className="font-semibold text-[#123262] mb-4">Invoice Automation Metrics</h3>
                <div className="grid grid-cols-2 gap-3 mb-4">
                  <div className="bg-gray-50 rounded-lg p-3 text-center">
                    <div className="text-xl font-bold text-[#2563eb]">14,847</div>
                    <div className="text-xs text-gray-500 mt-1">Auto-Processed</div>
                  </div>
                  <div className="bg-gray-50 rounded-lg p-3 text-center">
                    <div className="text-xl font-bold text-amber-600">2,341</div>
                    <div className="text-xs text-gray-500 mt-1">Exceptions</div>
                  </div>
                </div>
                <div className="space-y-2 text-xs">
                  {[["Price Errors", 127, "#ef4444"], ["Duplicates", 84, "#f59e0b"], ["Missing PO", 52, "#2563eb"]].map(([label, count, color]) => (
                    <div key={String(label)} className="flex justify-between items-center">
                      <span className="text-gray-600">{label}</span>
                      <span className="font-bold" style={{ color: String(color) }}>{count}</span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="content-card p-5">
                <h3 className="font-semibold text-[#123262] mb-4">Exception Trends</h3>
                <ResponsiveContainer width="100%" height={140}>
                  <BarChart data={[
                    { month: "Jan", exceptions: 180 }, { month: "Feb", exceptions: 220 },
                    { month: "Mar", exceptions: 195 }, { month: "Apr", exceptions: 260 },
                    { month: "May", exceptions: 240 }, { month: "Jun", exceptions: 263 },
                  ]}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="month" fontSize={10} />
                    <YAxis fontSize={10} />
                    <Tooltip />
                    <Bar dataKey="exceptions" fill="#ef4444" radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>

          {/* Right panel */}
          <div className="space-y-4">
            {/* Expiring contracts */}
            <div className="content-card p-5">
              <div className="flex items-center justify-between mb-3">
                <h3 className="font-semibold text-[#123262] text-sm">Contracts Expiring Soon</h3>
                <span className="text-xs text-red-600 font-medium">12 Critical</span>
              </div>
              <div className="space-y-2">
                {expiringContracts.map((c, i) => (
                  <div key={i} className={`rounded-lg p-3 border ${c.daysLeft <= 20 ? "bg-red-50 border-red-100" : "bg-amber-50 border-amber-100"}`}>
                    <div className="flex justify-between items-start">
                      <div>
                        <div className="text-xs font-semibold text-gray-900">{c.vendor}</div>
                        <div className="text-[10px] text-gray-500">{c.category} — {formatCurrency(c.spend)}</div>
                      </div>
                      <span className={`text-xs font-bold ${c.daysLeft <= 20 ? "text-red-600" : "text-amber-600"}`}>
                        {c.daysLeft} days
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <AiInsightPanel
              title="AI Anomaly Detection"
              modelConfigured={modelConfigured}
              data={aiInsights}
              isLoading={insightsLoading}
              onRegenerate={() => regenerate.mutate()}
              isRegenerating={regenerate.isPending}
              loadingLabel="Scanning for contract & billing anomalies…"
              emptyHint="Pick a chat model in Settings → AI Chat Model to enable anomaly detection."
            />

            {/* Document repository */}
            <div className="content-card p-5">
              <div className="flex items-center justify-between mb-3">
                <h3 className="font-semibold text-[#123262] text-sm">Document Repository</h3>
                <span className="text-xs text-[#2563eb] cursor-pointer hover:underline">Open Full View</span>
              </div>
              <div className="space-y-2">
                {[
                  { name: "MSA-Apex-2024.pdf", status: "Approved", color: "#ef4444" },
                  { name: "INV-2024-9102.pdf", status: "Pending Review", color: "#2563eb" },
                  { name: "SOW-Metro-Q3.pdf", status: "Exception Flag", color: "#f59e0b" },
                  { name: "Amendment-Fleet-2024.pdf", status: "Approved", color: "#737373" },
                ].map((doc, i) => (
                  <div key={i} className="flex items-center gap-2 p-2 rounded-lg bg-gray-50 hover:bg-gray-100 cursor-pointer transition-colors">
                    <FileText size={14} style={{ color: doc.color }} className="flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="text-xs font-medium text-gray-900 truncate">{doc.name}</div>
                      <div className="text-[10px] text-gray-500">{doc.status}</div>
                    </div>
                  </div>
                ))}
              </div>
              <div className="mt-3 pt-3 border-t border-gray-100 flex justify-between text-[10px] text-gray-400">
                <span>Total Documents: 1,247</span>
                <span>Last Sync: 2 min ago</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col h-full">
      <div className="pods-header"><div className="page-title">Contracts & Compliance</div></div>
      <div className="flex-1 flex items-center justify-center">
        <Loader2 size={20} className="animate-spin text-[#005CB9]" />
      </div>
    </div>
  );
}
