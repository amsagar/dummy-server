import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { ChevronRight, ExternalLink, Search } from "lucide-react";
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
import type { ServiceabilityResult } from "@/types/orderValidation";

const PAGE_SIZE = 10;

interface PanelTarget {
  instId: string;
  index: number;
  title: string;
}

interface OrderGroup {
  instId: string;
  orderId: string;
  legs: ServiceabilityResult[];
}

function groupByOrder(rows: ServiceabilityResult[]): OrderGroup[] {
  const out: OrderGroup[] = [];
  const idx = new Map<string, OrderGroup>();
  for (const r of rows) {
    const key = r.instId;
    let g = idx.get(key);
    if (!g) {
      g = { instId: r.instId, orderId: r.orderId, legs: [] };
      idx.set(key, g);
      out.push(g);
    }
    g.legs.push(r);
  }
  return out;
}

export function ServiceabilityPage() {
  const { workflowId, dateRange } = useSettings();
  const navigate = useNavigate();
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
    queryKey: ["serviceability", workflowId, dateRange.fromTs, dateRange.toTs, debouncedSearch, offset],
    queryFn: () =>
      orderValidationApi.serviceability({
        workflowId: workflowId!,
        fromTs: dateRange.fromTs,
        toTs: dateRange.toTs,
        search: debouncedSearch || undefined,
        limit: PAGE_SIZE,
        offset,
      }),
    enabled: !!workflowId,
  });

  const groups = useMemo(() => groupByOrder(data?.recent ?? []), [data]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [panel, setPanel] = useState<PanelTarget | null>(null);

  const toggle = (instId: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(instId)) next.delete(instId);
      else next.add(instId);
      return next;
    });
  };

  return (
    <>
      <TopBar title="Serviceability Check" subtitle="Per-leg-line route validation" />
      <main className="flex-1 p-6 overflow-auto">
        {!workflowId ? (
          <NoWorkflowSelected />
        ) : error ? (
          <div className="text-sm text-error">Failed to load: {(error as Error).message}</div>
        ) : (
          <div className="space-y-5">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <Stat label="Route checks" value={isLoading ? null : formatNumber(data?.totalChecks)} />
              <Stat label="Serviceable rate" value={isLoading ? null : formatPercent(data?.serviceableRate)} />
              <Stat label="Exceptions raised" value={isLoading ? null : formatNumber(data?.exceptions)} />
              <Stat label="Skipped (no address)" value={isLoading ? null : formatNumber(data?.skipped)} />
            </div>

            <Card>
              <CardHeader>
                <CardTitle>Exception breakdown</CardTitle>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <Skeleton className="h-32 w-full" />
                ) : (data?.exceptionBreakdown.length ?? 0) === 0 ? (
                  <div className="text-sm text-muted-foreground">No exceptions in this range.</div>
                ) : (
                  <div className="space-y-2">
                    {data!.exceptionBreakdown.map((e) => (
                      <div
                        key={e.exceptionType}
                        className="flex items-center justify-between py-1.5 border-b border-border last:border-0"
                      >
                        <span className="text-sm">{e.exceptionType}</span>
                        <span className="text-sm text-muted-foreground">{formatNumber(e.count)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <CardTitle>Per-order serviceability results</CardTitle>
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
                ) : groups.length === 0 ? (
                  <div className="p-5 text-sm text-muted-foreground">
                    {debouncedSearch
                      ? `No orders match "${debouncedSearch}".`
                      : "No results yet."}
                  </div>
                ) : (
                  <>
                    <div className="divide-y divide-border">
                      {groups.map((g) => (
                        <OrderGroupRow
                          key={g.instId}
                          group={g}
                          expanded={expanded.has(g.instId)}
                          onToggle={() => toggle(g.instId)}
                          onOpenRun={() => navigate(`/runs/${g.instId}`)}
                          onOpenLeg={(i) =>
                            setPanel({
                              instId: g.instId,
                              index: i,
                              title: `${g.orderId} · Serviceability · leg ${i + 1}`,
                            })
                          }
                        />
                      ))}
                    </div>
                    <Pagination
                      total={data?.recentTotal ?? 0}
                      limit={PAGE_SIZE}
                      offset={offset}
                      onChange={setOffset}
                      unit="orders"
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
        defId="callServiceability"
        index={panel?.index}
        title={panel?.title}
      />
    </>
  );
}

function OrderGroupRow({
  group,
  expanded,
  onToggle,
  onOpenRun,
  onOpenLeg,
}: {
  group: OrderGroup;
  expanded: boolean;
  onToggle: () => void;
  onOpenRun: () => void;
  onOpenLeg: (i: number) => void;
}) {
  const serviceable = group.legs.filter((l) => l.isServiceable === true).length;
  const exceptions = group.legs.filter((l) => l.isServiceable === false && l.status !== "skipped").length;
  const skipped = group.legs.filter((l) => l.status === "skipped").length;
  return (
    <div>
      <button
        onClick={onToggle}
        className="w-full flex items-center gap-3 px-5 py-3 hover:bg-accent/30 transition-colors text-left"
      >
        <ChevronRight
          className={"size-4 text-muted-foreground transition-transform " + (expanded ? "rotate-90" : "")}
        />
        <div className="font-mono text-sm">{group.orderId}</div>
        <div className="text-xs text-muted-foreground">
          {group.legs.length} leg{group.legs.length === 1 ? "" : "s"}
        </div>
        <div className="ml-auto flex items-center gap-2">
          {serviceable > 0 && <Badge variant="pass">{serviceable} serviceable</Badge>}
          {exceptions > 0 && <Badge variant="warn">{exceptions} exception{exceptions === 1 ? "" : "s"}</Badge>}
          {skipped > 0 && <Badge variant="muted">{skipped} skipped</Badge>}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onOpenRun();
            }}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            Open run <ExternalLink className="size-3" />
          </button>
        </div>
      </button>
      {expanded && (
        <div className="px-5 pb-4">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Line ID</TableHead>
                <TableHead>Item code</TableHead>
                <TableHead>Origin zip</TableHead>
                <TableHead>Destination zip</TableHead>
                <TableHead>Result</TableHead>
                <TableHead>Exception</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {group.legs.map((r, i) => (
                <TableRow
                  key={`${r.lineId}-${i}`}
                  className="cursor-pointer"
                  onClick={() => onOpenLeg(i)}
                >
                  <TableCell className="font-mono text-xs">{r.lineId ?? "—"}</TableCell>
                  <TableCell>
                    <Badge variant="muted">{r.itemCode ?? "—"}</Badge>
                  </TableCell>
                  <TableCell>{r.originZip ?? "—"}</TableCell>
                  <TableCell>{r.destinationZip ?? "—"}</TableCell>
                  <TableCell>
                    {r.status === "skipped" ? (
                      <Badge variant="muted">Skipped</Badge>
                    ) : r.isServiceable ? (
                      <Badge variant="pass">Serviceable</Badge>
                    ) : (
                      <Badge variant="warn">Exception</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    {r.exceptionType ? (
                      <Badge variant="warn">{r.exceptionType}</Badge>
                    ) : (
                      <span className="text-muted-foreground">—</span>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string | number | null }) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      {value == null ? <Skeleton className="h-8 w-24 mt-2" /> : <div className="stat-value">{value}</div>}
    </div>
  );
}
