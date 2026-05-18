import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { NoWorkflowSelected } from "@/components/NoWorkflowSelected";
import { ActivityIoPanel } from "@/components/ActivityIoPanel";
import { Pagination } from "@/components/Pagination";
import { useSettings } from "@/hooks/useSettings";
import { orderValidationApi } from "@/services/api";
import { formatNumber, formatPercent } from "@/lib/utils";

interface PanelTarget {
  instId: string;
  orderId: string;
}

const PAGE_SIZE = 25;

export function LegSequencePage() {
  const { workflowId, dateRange } = useSettings();
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [offset, setOffset] = useState(0);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebouncedSearch(search.trim());
      setOffset(0);
    }, 250);
    return () => clearTimeout(t);
  }, [search]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["legSequence", workflowId, dateRange.fromTs, dateRange.toTs, debouncedSearch, offset],
    queryFn: () =>
      orderValidationApi.legSequence({
        workflowId: workflowId!,
        fromTs: dateRange.fromTs,
        toTs: dateRange.toTs,
        search: debouncedSearch || undefined,
        limit: PAGE_SIZE,
        offset,
      }),
    enabled: !!workflowId,
  });

  const [panel, setPanel] = useState<PanelTarget | null>(null);

  return (
    <>
      <TopBar title="Leg Sequence Validation" subtitle="Decision table · Leg Sequences" />
      <main className="flex-1 p-6 overflow-auto">
        {!workflowId ? (
          <NoWorkflowSelected />
        ) : error ? (
          <div className="text-sm text-error">Failed to load: {(error as Error).message}</div>
        ) : (
          <div className="space-y-5">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <Stat label="Sequence checks" value={isLoading ? null : formatNumber(data?.totalChecks)} />
              <Stat label="Pass rate" value={isLoading ? null : formatPercent(data?.passRate)} />
              <Stat label="Failed sequences" value={isLoading ? null : formatNumber(data?.failed)} />
              <Stat
                label="Most common failure"
                value={isLoading ? null : data?.mostCommonFailure ?? "—"}
                small
              />
            </div>

            <Card>
              <CardHeader>
                <CardTitle>Failures by journey type</CardTitle>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <Skeleton className="h-32 w-full" />
                ) : (data?.failuresByJourney.length ?? 0) === 0 ? (
                  <div className="text-sm text-muted-foreground">No failures in this range.</div>
                ) : (
                  <div className="space-y-2">
                    {data!.failuresByJourney.map((f) => (
                      <div
                        key={f.journeyType}
                        className="flex items-center justify-between py-1.5 border-b border-border last:border-0"
                      >
                        <span className="text-sm">{f.journeyType}</span>
                        <span className="text-sm text-muted-foreground">{formatNumber(f.count)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <CardTitle>Recent sequence checks</CardTitle>
                  <div className="relative w-full max-w-xs">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
                    <Input
                      placeholder="Search by order ID…"
                      value={search}
                      onChange={(e) => setSearch(e.target.value)}
                      className="pl-9"
                    />
                  </div>
                </div>
              </CardHeader>
              <CardContent className="!p-0">
                {isLoading ? (
                  <div className="p-5">
                    <Skeleton className="h-40 w-full" />
                  </div>
                ) : (data?.recent.length ?? 0) === 0 ? (
                  <div className="p-5 text-sm text-muted-foreground">
                    {debouncedSearch
                      ? `No orders match "${debouncedSearch}".`
                      : "No recent results."}
                  </div>
                ) : (
                  <>
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Order ID</TableHead>
                          <TableHead>Order type</TableHead>
                          <TableHead>Actual sequence</TableHead>
                          <TableHead>Matched rule</TableHead>
                          <TableHead>Result</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {data!.recent.map((r) => (
                          <TableRow
                            key={r.instId}
                            className="cursor-pointer"
                            onClick={() => setPanel({ instId: r.instId, orderId: r.orderId })}
                          >
                            <TableCell className="font-mono text-xs">{r.orderId}</TableCell>
                            <TableCell className="text-sm">{r.journeyType ?? "—"}</TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1 flex-wrap">
                                {(r.actualSequence ?? []).map((s, i, arr) => (
                                  <span key={i} className="flex items-center gap-1">
                                    <Badge variant="muted">{s}</Badge>
                                    {i < arr.length - 1 && (
                                      <span className="text-muted-foreground">→</span>
                                    )}
                                  </span>
                                ))}
                              </div>
                            </TableCell>
                            <TableCell className="text-sm">
                              {r.matchedRule ?? <span className="text-muted-foreground">No match</span>}
                            </TableCell>
                            <TableCell>
                              <Badge variant={r.valid ? "pass" : "fail"}>{r.valid ? "Pass" : "Fail"}</Badge>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                    <Pagination
                      total={data?.recentTotal ?? 0}
                      limit={PAGE_SIZE}
                      offset={offset}
                      onChange={setOffset}
                      unit="checks"
                    />
                  </>
                )}
              </CardContent>
            </Card>
          </div>
        )}
      </main>
      <ActivityIoPanel
        open={panel != null}
        onClose={() => setPanel(null)}
        instId={panel?.instId ?? null}
        defId="evaluateLegSequence"
        title={panel ? `${panel.orderId} · Leg Sequence · evaluate` : undefined}
      />
    </>
  );
}

function Stat({ label, value, small = false }: { label: string; value: string | number | null; small?: boolean }) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      {value == null ? (
        <Skeleton className="h-8 w-24 mt-2" />
      ) : (
        <div className={small ? "text-base font-semibold mt-1.5" : "stat-value"}>{value}</div>
      )}
    </div>
  );
}
