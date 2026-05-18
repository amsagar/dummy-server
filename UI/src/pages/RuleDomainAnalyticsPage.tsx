import { useMemo, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api } from "@/services/api";
import {
  BarChart,
  Bar,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Activity, AlertTriangle, CheckCircle2, Clock, Layers } from "lucide-react";

interface Summary {
  totalRules: number;
  activeRules: number;
  runs24h: number;
  runsWindow: number;
  successRate: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
}

interface TimeseriesRow {
  date: string;
  successCount: number;
  failureCount: number;
}

interface ErrorRow {
  errorMessage: string;
  count: number;
  lastSeen: number;
}

interface SlowRuleRow {
  domainId: string;
  skillName: string;
  ruleName: string;
  runs: number;
  avgMs: number;
  p95Ms: number;
}

interface PerSkillRow {
  skillId: string;
  skillName: string;
  ruleCount: number;
  runs: number;
  successRate: number;
}

const WINDOWS: Array<{ label: string; days: number }> = [
  { label: "24h", days: 1 },
  { label: "7d", days: 7 },
  { label: "30d", days: 30 },
  { label: "90d", days: 90 },
];

export default function RuleDomainAnalyticsPage() {
  const [days, setDays] = useState(30);

  const { data: summary } = useQuery<Summary>({
    queryKey: ["analytics-summary", days],
    queryFn: () => api.ruleExecutions.analyticsSummary(days),
  });

  const { data: timeseries = [] } = useQuery<TimeseriesRow[]>({
    queryKey: ["analytics-timeseries", days],
    queryFn: () => api.ruleExecutions.analyticsTimeseries(days),
  });

  const { data: topErrors = [] } = useQuery<ErrorRow[]>({
    queryKey: ["analytics-top-errors", days],
    queryFn: () => api.ruleExecutions.analyticsTopErrors(days, 10),
  });

  const { data: slowRules = [] } = useQuery<SlowRuleRow[]>({
    queryKey: ["analytics-slow-rules", days],
    queryFn: () => api.ruleExecutions.analyticsSlowRules(days, 10),
  });

  const { data: perSkill = [] } = useQuery<PerSkillRow[]>({
    queryKey: ["analytics-per-skill", days],
    queryFn: () => api.ruleExecutions.analyticsPerSkill(days),
  });

  const successPct = useMemo(
    () => (summary ? Math.round(summary.successRate * 100) : 0),
    [summary],
  );

  // Pad timeseries with zero-rows for every day in the window so the chart
  // shows a real distribution. Without this, one busy day stretches across
  // the full chart width.
  const paddedTimeseries = useMemo(() => {
    const map = new Map<string, TimeseriesRow>();
    for (const r of timeseries) map.set(r.date, r);
    const out: TimeseriesRow[] = [];
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(d.getDate() - i);
      const key = d.toISOString().slice(0, 10);
      out.push(map.get(key) ?? { date: key, successCount: 0, failureCount: 0 });
    }
    return out;
  }, [timeseries, days]);

  return (
    <div className="space-y-4 w-full">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-[#123262]">Rule-domain analytics</h2>
          <p className="text-xs text-gray-500">
            Aggregate health across every compiled rule. Window selector applies to every panel below.
          </p>
        </div>
        <div className="flex items-center gap-1 rounded border bg-white p-0.5">
          {WINDOWS.map((w) => (
            <button
              key={w.label}
              type="button"
              onClick={() => setDays(w.days)}
              className={`px-2.5 py-1 text-xs rounded ${
                days === w.days ? "bg-[#123262] text-white" : "text-gray-700 hover:bg-gray-100"
              }`}
            >
              {w.label}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        <StatCard
          icon={<Layers className="h-4 w-4" />}
          label="Total rules"
          value={summary?.totalRules ?? "—"}
          sub={`${summary?.activeRules ?? 0} active`}
        />
        <StatCard
          icon={<Activity className="h-4 w-4" />}
          label="Runs (24h)"
          value={summary?.runs24h?.toLocaleString() ?? "—"}
        />
        <StatCard
          icon={<Activity className="h-4 w-4" />}
          label={`Runs (${days}d)`}
          value={summary?.runsWindow?.toLocaleString() ?? "—"}
        />
        <StatCard
          icon={<CheckCircle2 className="h-4 w-4" />}
          label="Success rate"
          value={`${successPct}%`}
          tone={successPct >= 95 ? "green" : successPct >= 80 ? "amber" : "red"}
        />
        <StatCard
          icon={<Clock className="h-4 w-4" />}
          label="p95 latency"
          value={`${summary?.p95LatencyMs ?? 0} ms`}
          sub={`avg ${summary?.avgLatencyMs ?? 0} ms`}
        />
      </div>

      <div className="rounded border bg-white p-3">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-700 mb-2">
          Runs per day
        </div>
        <div style={{ width: "100%", height: 240 }}>
          <ResponsiveContainer>
            <BarChart data={paddedTimeseries} barCategoryGap={2}>
              <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 10 }}
                interval={Math.max(Math.floor(paddedTimeseries.length / 10), 0)}
                tickFormatter={(d: string) => d.slice(5)}
              />
              <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Bar dataKey="successCount" stackId="a" fill="#10b981" name="Success" maxBarSize={28} />
              <Bar dataKey="failureCount" stackId="a" fill="#ef4444" name="Failed" maxBarSize={28} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 items-start">
        <div className="rounded border bg-white overflow-hidden">
          <div className="px-3 py-2 border-b bg-gray-50 flex items-center gap-1.5">
            <AlertTriangle className="h-3.5 w-3.5 text-red-600" />
            <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
              Top errors
            </span>
          </div>
          {topErrors.length === 0 ? (
            <div className="p-4 text-xs text-gray-500">No failures in this window.</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Error</TableHead>
                  <TableHead className="w-[80px] text-right">Count</TableHead>
                  <TableHead className="w-[150px]">Last seen</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {topErrors.map((e, i) => (
                  <TableRow key={i}>
                    <TableCell className="text-[11px] text-red-700 max-w-[480px] truncate" title={e.errorMessage}>
                      {e.errorMessage}
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">{e.count}</TableCell>
                    <TableCell className="text-[11px] text-gray-500">
                      {new Date(e.lastSeen).toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>

        <div className="rounded border bg-white overflow-hidden">
          <div className="px-3 py-2 border-b bg-gray-50 flex items-center gap-1.5">
            <Clock className="h-3.5 w-3.5 text-amber-600" />
            <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
              Slowest rules
            </span>
          </div>
          {slowRules.length === 0 ? (
            <div className="p-4 text-xs text-gray-500">No timing data in this window.</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Skill</TableHead>
                  <TableHead>Rule</TableHead>
                  <TableHead className="w-[60px] text-right">Runs</TableHead>
                  <TableHead className="w-[70px] text-right">Avg</TableHead>
                  <TableHead className="w-[70px] text-right">p95</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {slowRules.map((r) => (
                  <TableRow key={r.domainId}>
                    <TableCell className="text-xs truncate max-w-[140px]" title={r.skillName}>
                      {r.skillName}
                    </TableCell>
                    <TableCell className="text-xs font-mono truncate max-w-[180px]" title={r.ruleName}>
                      <Link to={`/rule-domains/${encodeURIComponent(r.domainId)}`} className="hover:underline">
                        {r.ruleName}
                      </Link>
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">{r.runs}</TableCell>
                    <TableCell className="text-xs text-right tabular-nums">{r.avgMs}</TableCell>
                    <TableCell className="text-xs text-right tabular-nums">{r.p95Ms}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </div>

      <div className="rounded border bg-white overflow-hidden">
        <div className="px-3 py-2 border-b bg-gray-50">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
            Per-skill rollup
          </span>
        </div>
        {perSkill.length === 0 ? (
          <div className="p-4 text-xs text-gray-500">No skills with compiled rules.</div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Skill</TableHead>
                <TableHead className="w-[100px] text-right">Rules</TableHead>
                <TableHead className="w-[100px] text-right">Runs</TableHead>
                <TableHead className="w-[140px] text-right">Success rate</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {perSkill.map((r) => (
                <TableRow key={r.skillId}>
                  <TableCell className="text-xs">{r.skillName}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">{r.ruleCount}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">{r.runs}</TableCell>
                  <TableCell className="text-xs text-right tabular-nums">
                    {r.runs === 0 ? "—" : `${Math.round(r.successRate * 100)}%`}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>
    </div>
  );
}

function StatCard({
  icon,
  label,
  value,
  sub,
  tone,
}: {
  icon: ReactNode;
  label: string;
  value: number | string;
  sub?: string;
  tone?: "green" | "red" | "amber";
}) {
  const toneClass =
    tone === "green"
      ? "text-green-700"
      : tone === "red"
      ? "text-red-700"
      : tone === "amber"
      ? "text-amber-700"
      : "text-gray-900";
  return (
    <div className="rounded border bg-white p-3">
      <div className="flex items-center gap-1.5 text-[10px] uppercase tracking-wide text-gray-500">
        {icon}
        <span>{label}</span>
      </div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${toneClass}`}>{value}</div>
      {sub && <div className="text-[11px] text-gray-500">{sub}</div>}
    </div>
  );
}
