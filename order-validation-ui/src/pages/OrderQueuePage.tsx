import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Plus, Search } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { NoWorkflowSelected } from "@/components/NoWorkflowSelected";
import { SubmitOrderDialog } from "@/components/SubmitOrderDialog";
import { Pagination } from "@/components/Pagination";
import { useSettings } from "@/hooks/useSettings";
import { orderValidationApi } from "@/services/api";
import { formatNumber, formatStatus } from "@/lib/utils";
import { cn } from "@/lib/utils";
import type { OverallStatus, ResultStatus } from "@/types/orderValidation";

const filters: { key: string; label: string; status?: string }[] = [
  { key: "all", label: "All" },
  { key: "passed", label: "Passed", status: "passed" },
  { key: "review", label: "Review", status: "review" },
  { key: "failed", label: "Failed", status: "failed" },
  { key: "running", label: "Running", status: "running" },
];

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

const QUEUE_PAGE_SIZE = 25;

export function OrderQueuePage() {
  const { workflowId, dateRange } = useSettings();
  const [filter, setFilter] = useState<string>("all");
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [offset, setOffset] = useState(0);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [startedToast, setStartedToast] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    const t = setTimeout(() => {
      setDebouncedSearch(search.trim());
      setOffset(0);
    }, 250);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    setOffset(0);
  }, [filter]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["orderQueue", workflowId, dateRange.fromTs, dateRange.toTs, filter, debouncedSearch, offset],
    queryFn: () =>
      orderValidationApi.orderQueue({
        workflowId: workflowId!,
        fromTs: dateRange.fromTs,
        toTs: dateRange.toTs,
        status: filters.find((f) => f.key === filter)?.status,
        search: debouncedSearch || undefined,
        limit: QUEUE_PAGE_SIZE,
        offset,
      }),
    enabled: !!workflowId,
    refetchInterval: startedToast ? 3000 : false,
  });

  useEffect(() => {
    if (!startedToast) return;
    const t = setTimeout(() => setStartedToast(null), 8000);
    return () => clearTimeout(t);
  }, [startedToast]);

  return (
    <>
      <TopBar
        title="Order Queue"
        subtitle={data ? `${formatNumber(data.total)} runs in range` : undefined}
      />
      <main className="flex-1 p-6 overflow-auto">
        {!workflowId ? (
          <NoWorkflowSelected />
        ) : (
          <div className="space-y-4">
            <Card>
              <CardContent className="!pt-5 flex items-center gap-3">
                <div className="relative flex-1 max-w-md">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
                  <Input
                    placeholder="Search by order ID, journey type…"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    className="pl-9"
                  />
                </div>
                <div className="flex gap-1.5">
                  {filters.map((f) => {
                    const count =
                      f.key === "all"
                        ? data?.total
                        : f.key === "passed"
                          ? data?.passed
                          : f.key === "review"
                            ? data?.review
                            : f.key === "failed"
                              ? data?.failed
                              : undefined;
                    return (
                      <button
                        key={f.key}
                        onClick={() => setFilter(f.key)}
                        className={cn(
                          "px-3 py-1.5 rounded-md text-xs font-medium border transition-colors",
                          filter === f.key
                            ? "bg-primary text-white btn-primary-text border-primary shadow-sm"
                            : "border-border bg-muted text-foreground hover:bg-accent",
                        )}
                      >
                        {f.label}
                        {count != null && (
                          <span
                            className={cn(
                              "ml-1.5",
                              filter === f.key ? "opacity-90" : "text-foreground/70",
                            )}
                          >
                            ({formatNumber(count)})
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
                <Button
                  className="ml-auto btn-primary-text"
                  onClick={() => setSubmitOpen(true)}
                >
                  <Plus className="size-4" /> Submit order
                </Button>
              </CardContent>
            </Card>

            <Card>
              <CardContent className="!p-0">
                {error ? (
                  <div className="p-5 text-sm text-error">
                    Failed to load: {(error as Error).message}
                  </div>
                ) : isLoading ? (
                  <div className="p-5">
                    <Skeleton className="h-64 w-full" />
                  </div>
                ) : (data?.rows.length ?? 0) === 0 ? (
                  <div className="p-10 text-sm text-muted-foreground text-center">
                    No runs match the current filters.
                  </div>
                ) : (
                  <>
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Order ID</TableHead>
                          <TableHead>Journey type</TableHead>
                          <TableHead className="text-right">Leg lines</TableHead>
                          <TableHead>Leg seq.</TableHead>
                          <TableHead>Serviceability</TableHead>
                          <TableHead>Container</TableHead>
                          <TableHead>Overall</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {data!.rows.map((r) => (
                          <TableRow
                            key={r.instId}
                            className="cursor-pointer"
                            onClick={() => navigate(`/runs/${r.instId}`)}
                          >
                            <TableCell className="font-mono text-xs">{r.orderId}</TableCell>
                            <TableCell>{r.journeyType ?? "—"}</TableCell>
                            <TableCell className="text-right">{r.legLines ?? "—"}</TableCell>
                            <TableCell>{statusPill(r.legSequenceStatus)}</TableCell>
                            <TableCell>{statusPill(r.serviceabilityStatus)}</TableCell>
                            <TableCell>{containerPill(r.containerStatus)}</TableCell>
                            <TableCell>{statusPill(r.overallStatus)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                    <Pagination
                      total={data?.total ?? 0}
                      limit={QUEUE_PAGE_SIZE}
                      offset={offset}
                      onChange={setOffset}
                      unit="runs"
                    />
                  </>
                )}
              </CardContent>
            </Card>
            {startedToast && (
              <div className="fixed bottom-6 right-6 z-40 panel-card text-sm shadow-xl flex items-center gap-3">
                <span className="size-2 rounded-full bg-status-pass animate-pulse" />
                <span>
                  Started run{" "}
                  <button
                    className="font-mono text-xs underline-offset-2 hover:underline"
                    onClick={() => navigate(`/runs/${startedToast}`)}
                  >
                    {startedToast.slice(0, 8)}…
                  </button>
                  {" "}— refreshing queue
                </span>
              </div>
            )}
          </div>
        )}
      </main>
      {workflowId && (
        <SubmitOrderDialog
          open={submitOpen}
          onClose={() => setSubmitOpen(false)}
          workflowId={workflowId}
          onStarted={(instId) => setStartedToast(instId)}
        />
      )}
    </>
  );
}
