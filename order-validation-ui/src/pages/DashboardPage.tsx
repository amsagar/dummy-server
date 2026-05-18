import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { NoWorkflowSelected } from "@/components/NoWorkflowSelected";
import { useSettings } from "@/hooks/useSettings";
import { orderValidationApi } from "@/services/api";
import { formatNumber, formatPercent, formatStatus } from "@/lib/utils";
import type { OverallStatus, ResultStatus } from "@/types/orderValidation";

function statusPill(status: ResultStatus | OverallStatus) {
  const map: Record<string, "pass" | "fail" | "warn" | "muted" | "info"> = {
    pass: "pass",
    clear: "pass",
    fail: "fail",
    failed: "fail",
    review: "warn",
    exception: "warn",
    skipped: "muted",
    na: "muted",
    unknown: "muted",
    running: "info",
    error: "fail",
  };
  return <Badge variant={map[status] ?? "muted"}>{formatStatus(status)}</Badge>;
}

function containerPill(status: ResultStatus) {
  if (status === "fail" || status === "exception")
    return <Badge variant="warn">Need Rescheduling</Badge>;
  return statusPill(status);
}

export function DashboardPage() {
  const { workflowId, dateRange } = useSettings();
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["dashboard", workflowId, dateRange.fromTs, dateRange.toTs],
    queryFn: () =>
      orderValidationApi.dashboard({
        workflowId: workflowId!,
        fromTs: dateRange.fromTs,
        toTs: dateRange.toTs,
      }),
    enabled: !!workflowId,
  });

  return (
    <>
      <TopBar title="Validation Dashboard" subtitle="Live · Order intelligence overview" />
      <main className="flex-1 p-6 overflow-auto">
        {!workflowId ? (
          <NoWorkflowSelected />
        ) : error ? (
          <div className="text-sm text-error">Failed to load: {(error as Error).message}</div>
        ) : (
          <div className="space-y-5">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <StatCard
                label="Orders validated"
                value={isLoading ? null : formatNumber(data?.ordersValidated ?? 0)}
                hint="in selected range"
              />
              <StatCard
                label="Pass rate"
                value={isLoading ? null : formatPercent(data?.passRate)}
                hint={data && `${formatNumber(data.passFailByCheck.legSequencePass)} legs pass`}
              />
              <StatCard
                label="Failed validations"
                value={isLoading ? null : formatNumber(data?.failedValidations ?? 0)}
                hint="across all checks"
                tone="bad"
              />
              <StatCard
                label="Avg validation time"
                value={
                  isLoading
                    ? null
                    : data?.avgValidationMs != null
                      ? `${(data.avgValidationMs / 1000).toFixed(2)}s`
                      : "—"
                }
                hint="end-to-end"
              />
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <Card>
                <CardHeader>
                  <CardTitle>Pass / fail by check</CardTitle>
                </CardHeader>
                <CardContent>
                  {isLoading ? (
                    <Skeleton className="h-32 w-full" />
                  ) : (
                    <div className="space-y-3">
                      <CheckBar
                        label="Leg Sequence"
                        pass={data!.passFailByCheck.legSequencePass}
                        fail={data!.passFailByCheck.legSequenceFail}
                      />
                      <CheckBar
                        label="Serviceability"
                        pass={data!.passFailByCheck.serviceabilityPass}
                        fail={data!.passFailByCheck.serviceabilityFail}
                      />
                      <CheckBar
                        label="Container Avail."
                        pass={data!.passFailByCheck.containerPass}
                        fail={data!.passFailByCheck.containerFail}
                        skipped={data!.passFailByCheck.containerSkipped}
                      />
                    </div>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle>Validation volume</CardTitle>
                </CardHeader>
                <CardContent>
                  {isLoading ? (
                    <Skeleton className="h-32 w-full" />
                  ) : (data?.volumeBuckets.length ?? 0) === 0 ? (
                    <div className="text-sm text-muted-foreground">No runs in this range.</div>
                  ) : (
                    <div className="h-32">
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={data!.volumeBuckets}>
                          <XAxis
                            dataKey="dayStartTs"
                            tickFormatter={(ts) =>
                              new Date(ts).toLocaleDateString("en-US", { weekday: "short" })
                            }
                            stroke="var(--chart-axis)"
                            fontSize={11}
                          />
                          <YAxis stroke="var(--chart-axis)" fontSize={11} />
                          <Tooltip
                            contentStyle={{
                              background: "var(--chart-tooltip-bg)",
                              border: "1px solid var(--chart-tooltip-border)",
                              borderRadius: 8,
                              fontSize: 12,
                              color: "var(--foreground)",
                            }}
                            labelStyle={{ color: "var(--foreground)" }}
                            labelFormatter={(ts) =>
                              new Date(ts as number).toLocaleDateString("en-US", {
                                month: "short",
                                day: "numeric",
                              })
                            }
                          />
                          <Bar dataKey="total" fill="var(--chart-success)" radius={[4, 4, 0, 0]} />
                          <Bar dataKey="failures" fill="var(--chart-failure)" radius={[4, 4, 0, 0]} />
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  )}
                </CardContent>
              </Card>
            </div>

            <Card>
              <CardHeader>
                <CardTitle>Recent validation results</CardTitle>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <Skeleton className="h-40 w-full" />
                ) : (data?.recentResults.length ?? 0) === 0 ? (
                  <div className="text-sm text-muted-foreground">No recent runs.</div>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Order ID</TableHead>
                        <TableHead>Journey type</TableHead>
                        <TableHead>Leg sequence</TableHead>
                        <TableHead>Serviceability</TableHead>
                        <TableHead>Container avail.</TableHead>
                        <TableHead>Status</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {data!.recentResults.map((r) => (
                        <TableRow
                          key={r.instId}
                          className="cursor-pointer"
                          onClick={() => navigate(`/runs/${r.instId}`)}
                        >
                          <TableCell className="font-mono text-xs">{r.orderId}</TableCell>
                          <TableCell>{r.journeyType ?? "—"}</TableCell>
                          <TableCell>{statusPill(r.legSequenceStatus)}</TableCell>
                          <TableCell>{statusPill(r.serviceabilityStatus)}</TableCell>
                          <TableCell>{containerPill(r.containerStatus)}</TableCell>
                          <TableCell>{statusPill(r.overallStatus)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </CardContent>
            </Card>
          </div>
        )}
      </main>
    </>
  );
}

interface StatCardProps {
  label: string;
  value: string | null;
  hint?: string | null;
  tone?: "good" | "bad" | "default";
}
function StatCard({ label, value, hint }: StatCardProps) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      {value == null ? (
        <Skeleton className="h-8 w-24 mt-2" />
      ) : (
        <div className="stat-value">{value}</div>
      )}
      {hint && <div className="stat-delta-muted mt-1.5">{hint}</div>}
    </div>
  );
}

interface CheckBarProps {
  label: string;
  pass: number;
  fail: number;
  skipped?: number;
}
function CheckBar({ label, pass, fail, skipped = 0 }: CheckBarProps) {
  const total = pass + fail + skipped;
  const passPct = total === 0 ? 0 : (pass / total) * 100;
  const failPct = total === 0 ? 0 : (fail / total) * 100;
  const skipPct = total === 0 ? 0 : (skipped / total) * 100;
  return (
    <div>
      <div className="flex justify-between text-xs mb-1.5">
        <span>{label}</span>
        <span className="text-muted-foreground">
          {formatPercent(passPct, 1)} pass · {formatNumber(total)} total
        </span>
      </div>
      <div className="h-2 rounded-full bg-muted overflow-hidden flex">
        <div style={{ width: `${passPct}%` }} className="bg-green-500" />
        <div style={{ width: `${failPct}%` }} className="bg-red-500" />
        <div style={{ width: `${skipPct}%` }} className="bg-amber-500/70" />
      </div>
    </div>
  );
}
