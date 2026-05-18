import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, AlertCircle, ChevronRight } from "lucide-react";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ActivityIoPanel } from "@/components/ActivityIoPanel";
import { orderValidationApi } from "@/services/api";
import { formatNumber, formatStatus } from "@/lib/utils";
import type { OverallStatus, ResultStatus, RunDetail } from "@/types/orderValidation";

function statusPill(status: ResultStatus | OverallStatus | string | null | undefined) {
  if (!status) return <Badge variant="muted">—</Badge>;
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
    completed: "pass",
  };
  return <Badge variant={map[status] ?? "muted"}>{formatStatus(status)}</Badge>;
}

interface PanelTarget {
  defId: string;
  index?: number;
  title: string;
}

export function RunDetailPage() {
  const { instId } = useParams<{ instId: string }>();
  const { data, isLoading, error } = useQuery({
    queryKey: ["runDetail", instId],
    queryFn: () => orderValidationApi.runDetail(instId!),
    enabled: !!instId,
  });

  const [panel, setPanel] = useState<PanelTarget | null>(null);

  return (
    <>
      <TopBar
        title={data ? `Run · ${data.orderId}` : "Run detail"}
        subtitle={data ? `${data.journeyType ?? "—"} · ${data.state}` : undefined}
      />
      <main className="flex-1 p-6 overflow-auto">
        <Link to="/queue" className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4">
          <ArrowLeft className="size-4" /> Back to queue
        </Link>

        {error ? (
          <div className="text-sm text-error">Failed to load: {(error as Error).message}</div>
        ) : isLoading || !data ? (
          <div className="space-y-4">
            <Skeleton className="h-32 w-full" />
            <Skeleton className="h-48 w-full" />
          </div>
        ) : (
          <RunDetailBody detail={data} onOpen={setPanel} />
        )}
      </main>
      <ActivityIoPanel
        open={panel != null}
        onClose={() => setPanel(null)}
        instId={instId ?? null}
        defId={panel?.defId ?? null}
        index={panel?.index}
        title={panel?.title}
      />
    </>
  );
}

function RunDetailBody({ detail, onOpen }: { detail: RunDetail; onOpen: (t: PanelTarget) => void }) {
  return (
    <div className="space-y-5">
      <Card>
        <CardContent className="!pt-5 grid grid-cols-2 lg:grid-cols-4 gap-4">
          <Meta label="Order ID" value={detail.orderId} mono />
          <Meta label="Order type" value={detail.journeyType ?? "—"} />
          <Meta label="Workflow state" value={<Badge variant="muted">{detail.state}</Badge>} />
          <Meta label="Overall" value={statusPill(detail.overallStatus)} />
          <Meta
            label="Started"
            value={new Date(detail.startedAt).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" })}
          />
          <Meta
            label="Ended"
            value={
              detail.endedAt
                ? new Date(detail.endedAt).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" })
                : "—"
            }
          />
          <Meta
            label="Duration"
            value={detail.durationMs != null ? `${(detail.durationMs / 1000).toFixed(2)}s` : "—"}
          />
          <Meta label="Instance ID" value={detail.instId} mono />
        </CardContent>
      </Card>

      {detail.errorMessage && (
        <Card className="error-banner">
          <CardContent className="!pt-5 flex items-start gap-3">
            <AlertCircle className="size-5 icon-error mt-0.5 shrink-0" />
            <div>
              <div className="text-sm error-banner-title">
                {detail.errorClass ?? "Error"}
              </div>
              <div className="text-sm error-banner-body mt-0.5">{detail.errorMessage}</div>
            </div>
          </CardContent>
        </Card>
      )}

      <Card
        className="cursor-pointer hover:bg-accent/30 transition-colors"
        onClick={() => onOpen({ defId: "evaluateLegSequence", title: "Leg Sequence · evaluate" })}
      >
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>Leg Sequence</CardTitle>
            <span className="inline-flex items-center text-xs text-muted-foreground">
              View input / output <ChevronRight className="size-3.5" />
            </span>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-xs text-muted-foreground">Result</span>
            {detail.legSequence.valid ? (
              <Badge variant="pass">Matched</Badge>
            ) : (
              <Badge variant="fail">No match</Badge>
            )}
          </div>
          <div>
            <div className="text-xs text-muted-foreground mb-1.5">Actual sequence</div>
            <div className="flex items-center gap-1 flex-wrap">
              {(detail.legSequence.actualSequence ?? []).map((s, i, arr) => (
                <span key={i} className="flex items-center gap-1">
                  <Badge variant="muted">{s}</Badge>
                  {i < arr.length - 1 && <span className="text-muted-foreground">→</span>}
                </span>
              ))}
              {(detail.legSequence.actualSequence ?? []).length === 0 && (
                <span className="text-sm text-muted-foreground">—</span>
              )}
            </div>
          </div>
          {detail.legSequence.matchedRule && (
            <div>
              <div className="text-xs text-muted-foreground mb-1">Matched rule</div>
              <div className="text-sm">{detail.legSequence.matchedRule}</div>
            </div>
          )}
          {detail.legSequence.message && (
            <div>
              <div className="text-xs text-muted-foreground mb-1">Message</div>
              <div className="text-sm">{detail.legSequence.message}</div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Serviceability ({formatNumber(detail.serviceability.length)})</CardTitle>
        </CardHeader>
        <CardContent>
          {detail.serviceability.length === 0 ? (
            <div className="text-sm text-muted-foreground">No serviceability records.</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Line ID</TableHead>
                  <TableHead>Item code</TableHead>
                  <TableHead>Origin → Destination</TableHead>
                  <TableHead>Result</TableHead>
                  <TableHead>Exception</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {detail.serviceability.map((s, i) => (
                  <TableRow
                    key={i}
                    className="cursor-pointer"
                    onClick={() =>
                      onOpen({
                        defId: "callServiceability",
                        index: i,
                        title: `Serviceability · leg ${i + 1}`,
                      })
                    }
                  >
                    <TableCell className="font-mono text-xs">{s.lineId ?? "—"}</TableCell>
                    <TableCell>
                      <Badge variant="muted">{s.itemCode ?? "—"}</Badge>
                    </TableCell>
                    <TableCell className="text-sm">
                      {s.originZip ?? "—"} → {s.destinationZip ?? "—"}
                    </TableCell>
                    <TableCell>
                      {s.status === "skipped" ? (
                        <Badge variant="muted">Skipped</Badge>
                      ) : s.isServiceable ? (
                        <Badge variant="pass">Serviceable</Badge>
                      ) : (
                        <Badge variant="warn">Exception</Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      {s.exceptionType ? <Badge variant="warn">{s.exceptionType}</Badge> : <span className="text-muted-foreground">—</span>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Container Availability ({formatNumber(detail.containerAvailability.length)})</CardTitle>
        </CardHeader>
        <CardContent>
          {detail.containerAvailability.length === 0 ? (
            <div className="text-sm text-muted-foreground">No IDEL lines required a container check.</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Line ID</TableHead>
                  <TableHead>Item code</TableHead>
                  <TableHead>Skip reason</TableHead>
                  <TableHead>Available dates</TableHead>
                  <TableHead>Result</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {detail.containerAvailability.map((c, i) => (
                  <TableRow
                    key={i}
                    className="cursor-pointer"
                    onClick={() =>
                      onOpen({
                        defId: "callContainerAvailability",
                        index: i,
                        title: `Container · line ${i + 1}`,
                      })
                    }
                  >
                    <TableCell className="font-mono text-xs">{c.lineId ?? "—"}</TableCell>
                    <TableCell>
                      <Badge variant="muted">{c.itemCode ?? "—"}</Badge>
                    </TableCell>
                    <TableCell className="text-sm">{c.skipReason ?? "—"}</TableCell>
                    <TableCell className="text-sm">
                      {c.availableDates.length > 0
                        ? c.availableDates.slice(0, 3).map((d) => new Date(d).toLocaleDateString("en-US", { month: "short", day: "numeric" })).join(", ") + (c.availableDates.length > 3 ? ` +${c.availableDates.length - 3}` : "")
                        : "—"}
                    </TableCell>
                    <TableCell>
                      {!c.checked ? (
                        <Badge variant="muted">Skipped</Badge>
                      ) : c.availableDates.length > 0 ? (
                        <Badge variant="pass">Dates available</Badge>
                      ) : (
                        <Badge variant="warn">Need Rescheduling</Badge>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function Meta({ label, value, mono = false }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wider text-muted-foreground mb-1">{label}</div>
      <div className={mono ? "font-mono text-sm break-all" : "text-sm"}>{value}</div>
    </div>
  );
}
