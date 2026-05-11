// Insights dashboard. Six metric cards, three charts, per-workflow rollup,
// and failure hotspots — all driven by GET /workflow/insights?period=…
//
// Time period buttons cover 24h / 7d / 14d / 30d / 90d / 6mo / 1y. Charts
// use Recharts (already in the bundle). Time-saved card aggregates per
// workflow's `time_saved_seconds_per_run × completed_runs`.

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip as RTooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Link } from "react-router-dom";
import { Loader2, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { workflowApi } from "@/services/workflowApi";
import type { InsightsPeriod, InsightsResponse } from "@/types/workflow";
import { cn } from "@/lib/utils";

const PERIODS: { value: InsightsPeriod; label: string }[] = [
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "14d", label: "14d" },
  { value: "30d", label: "30d" },
  { value: "90d", label: "90d" },
  { value: "6mo", label: "6mo" },
  { value: "1y", label: "1y" },
];

function formatMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

function formatSeconds(s: number): string {
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${(s / 60).toFixed(1)}m`;
  if (s < 86_400) return `${(s / 3600).toFixed(1)}h`;
  return `${(s / 86_400).toFixed(1)}d`;
}

function failurePercent(total: number, failed: number): number {
  if (!total) return 0;
  return (failed / total) * 100;
}

export default function InsightsPage() {
  const [period, setPeriod] = useState<InsightsPeriod>("7d");

  const insights = useQuery<InsightsResponse>({
    queryKey: ["insights", period],
    queryFn: () => workflowApi.insights.get(period, 20),
    staleTime: 30_000,
  });

  const data = insights.data;
  const summary = data?.summary;
  const dayRows = (data?.byDay ?? []).map((d) => ({
    ...d,
    failPct: d.total ? (d.failed / d.total) * 100 : 0,
  }));
  const byWorkflow = data?.byWorkflow ?? [];
  const hotspots = data?.hotspots ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2 flex-wrap">
        <h1 className="text-2xl font-semibold">Insights</h1>
        {insights.isFetching && (
          <Loader2 className="w-3.5 h-3.5 animate-spin text-muted-foreground" />
        )}
        <div className="ml-auto flex items-center gap-2">
          <div className="inline-flex rounded-md border bg-muted p-0.5 text-xs">
            {PERIODS.map((p) => (
              <Button
                key={p.value}
                size="sm"
                variant={period === p.value ? "default" : "ghost"}
                className={cn("h-7 px-2 text-xs", period === p.value && "shadow-sm")}
                onClick={() => setPeriod(p.value)}
              >
                {p.label}
              </Button>
            ))}
          </div>
          <Button variant="outline" size="sm" onClick={() => insights.refetch()}>
            <RefreshCw className="w-3.5 h-3.5 mr-1" /> Refresh
          </Button>
        </div>
      </div>

      {/* Cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
        <Card label="Total runs" value={summary?.total ?? 0} />
        <Card label="Failed" value={summary?.failed ?? 0} tone={(summary?.failed ?? 0) > 0 ? "rose" : undefined} />
        <Card
          label="Failure rate"
          value={`${failurePercent(summary?.total ?? 0, summary?.failed ?? 0).toFixed(1)}%`}
          tone={
            failurePercent(summary?.total ?? 0, summary?.failed ?? 0) > 5 ? "rose" : "emerald"
          }
        />
        <Card label="p50 latency" value={formatMs(summary?.p50Ms)} />
        <Card label="p95 latency" value={formatMs(summary?.p95Ms)} />
        <Card
          label="Time saved"
          value={formatSeconds(data?.totalTimeSavedSeconds ?? 0)}
          tone="emerald"
          hint="Sum over completed runs of each workflow's time_saved_seconds_per_run."
        />
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <Section title="Throughput (runs / day)">
          <ResponsiveContainer width="100%" height={220}>
            <LineChart data={dayRows}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--muted))" />
              <XAxis dataKey="day" fontSize={11} />
              <YAxis fontSize={11} allowDecimals={false} />
              <RTooltip />
              <Legend />
              <Line type="monotone" dataKey="total" stroke="#3b82f6" name="Total" />
              <Line type="monotone" dataKey="completed" stroke="#10b981" name="Completed" />
              <Line type="monotone" dataKey="failed" stroke="#ef4444" name="Failed" />
            </LineChart>
          </ResponsiveContainer>
        </Section>

        <Section title="Failure rate %">
          <ResponsiveContainer width="100%" height={220}>
            <AreaChart data={dayRows}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--muted))" />
              <XAxis dataKey="day" fontSize={11} />
              <YAxis fontSize={11} unit="%" domain={[0, 100]} />
              <RTooltip formatter={(v: number) => `${v.toFixed(1)}%`} />
              <Area type="monotone" dataKey="failPct" stroke="#ef4444" fill="#ef4444" fillOpacity={0.25} name="Fail %" />
            </AreaChart>
          </ResponsiveContainer>
        </Section>
      </div>

      <Section title="Latency p50 / p95 (ms)">
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={dayRows}>
            <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--muted))" />
            <XAxis dataKey="day" fontSize={11} />
            <YAxis fontSize={11} />
            <RTooltip formatter={(v: number) => `${v}ms`} />
            <Legend />
            <Line type="monotone" dataKey="p50Ms" stroke="#3b82f6" name="p50" dot={false} />
            <Line type="monotone" dataKey="p95Ms" stroke="#f59e0b" name="p95" dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </Section>

      {/* Per-workflow */}
      <Section title="Per workflow">
        <ScrollArea className="max-h-[300px]">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-muted-foreground bg-muted/40 sticky top-0">
              <tr>
                <th className="py-2 px-3">Workflow</th>
                <th className="py-2 px-3">Runs</th>
                <th className="py-2 px-3">Failed</th>
                <th className="py-2 px-3">Fail %</th>
                <th className="py-2 px-3">p50</th>
                <th className="py-2 px-3">p95</th>
                <th className="py-2 px-3">Time saved</th>
              </tr>
            </thead>
            <tbody>
              {byWorkflow.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-6 text-center text-muted-foreground italic">
                    No runs in this period.
                  </td>
                </tr>
              )}
              {byWorkflow.map((w) => (
                <tr key={w.defId ?? w.name ?? "(unknown)"} className="border-t hover:bg-muted/40">
                  <td className="py-2 px-3">
                    {w.defId ? (
                      <Link to={`/workflows/${w.defId}/designer`} className="font-medium hover:underline">
                        {w.name ?? w.defId}
                      </Link>
                    ) : (
                      <span className="text-muted-foreground">{w.name ?? "(unknown)"}</span>
                    )}
                  </td>
                  <td className="py-2 px-3">{w.total}</td>
                  <td className="py-2 px-3">{w.failed}</td>
                  <td className="py-2 px-3">{failurePercent(w.total, w.failed).toFixed(1)}%</td>
                  <td className="py-2 px-3">{formatMs(w.p50Ms)}</td>
                  <td className="py-2 px-3">{formatMs(w.p95Ms)}</td>
                  <td className="py-2 px-3">{formatSeconds(w.timeSavedSeconds)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
      </Section>

      {/* Hotspots */}
      <Section title="Failure hotspots (activities)">
        {hotspots.length === 0 ? (
          <p className="text-xs text-muted-foreground italic">No failures in this period.</p>
        ) : (
          <ResponsiveContainer width="100%" height={Math.max(120, 28 * hotspots.length)}>
            <BarChart data={hotspots} layout="vertical">
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--muted))" />
              <XAxis type="number" allowDecimals={false} fontSize={11} />
              <YAxis type="category" dataKey="activityDefId" width={140} fontSize={11} />
              <RTooltip />
              <Bar dataKey="failures" fill="#ef4444" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </Section>
    </div>
  );
}

function Card({
  label,
  value,
  tone,
  hint,
}: {
  label: string;
  value: number | string;
  tone?: "rose" | "emerald";
  hint?: string;
}) {
  const toneCls =
    tone === "rose"
      ? "text-rose-700 dark:text-rose-300"
      : tone === "emerald"
        ? "text-emerald-700 dark:text-emerald-300"
        : "text-foreground";
  return (
    <div className="border rounded-md bg-background p-3" title={hint}>
      <div className="text-xs text-muted-foreground mb-1">{label}</div>
      <div className={cn("text-xl font-medium", toneCls)}>{value}</div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="border rounded-md bg-background p-3">
      <h2 className="text-sm font-medium mb-2">{title}</h2>
      {children}
    </div>
  );
}
