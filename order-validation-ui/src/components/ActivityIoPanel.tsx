import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Sheet } from "@/components/ui/sheet";
import { JsonTree } from "@/components/JsonTree";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import { orderValidationApi } from "@/services/api";
import { formatStatus } from "@/lib/utils";
import type { ActivityInvocation } from "@/types/orderValidation";

interface Props {
  open: boolean;
  onClose: () => void;
  instId: string | null;
  defId: string | null;
  index?: number;
  title?: string;
}

function durationMs(a: ActivityInvocation): string {
  if (a.startedAt == null || a.endedAt == null) return "—";
  const ms = Math.max(0, a.endedAt - a.startedAt);
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function badgeVariant(state: string | null): "pass" | "fail" | "warn" | "info" | "muted" {
  if (!state) return "muted";
  if (state.startsWith("closed.completed")) return "pass";
  if (state.startsWith("closed.failed") || state.startsWith("failed")) return "fail";
  if (state.startsWith("open.running") || state === "running") return "info";
  return "muted";
}

export function ActivityIoPanel({ open, onClose, instId, defId, index, title }: Props) {
  const [selected, setSelected] = useState<number>(index ?? 0);

  const { data, isLoading, error } = useQuery({
    queryKey: ["activityIo", instId, defId],
    queryFn: () => orderValidationApi.runActivities(instId!, defId!),
    enabled: open && !!instId && !!defId,
  });

  const effectiveIndex = index != null ? index : selected;
  const current = data?.[effectiveIndex];

  const heading = title ?? defId ?? "Activity";
  const subtitle =
    data && data.length > 1
      ? `Iteration ${effectiveIndex + 1} of ${data.length}`
      : defId ?? undefined;

  return (
    <Sheet open={open} onClose={onClose} title={heading} description={subtitle}>
      {error ? (
        <div className="text-sm text-error">Failed to load: {(error as Error).message}</div>
      ) : isLoading || !data ? (
        <div className="space-y-3">
          <Skeleton className="h-6 w-40" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : data.length === 0 ? (
        <div className="text-sm text-muted-foreground">
          No invocations recorded for <span className="font-mono">{defId}</span>.
        </div>
      ) : (
        <div className="space-y-5">
          {data.length > 1 && index == null && (
            <div className="flex flex-wrap gap-1.5">
              {data.map((a, i) => (
                <button
                  key={a.activityInstId}
                  onClick={() => setSelected(i)}
                  className={
                    "px-2.5 py-1 rounded-md text-xs font-medium border transition-colors " +
                    (i === effectiveIndex
                      ? "bg-primary text-white btn-primary-text border-primary"
                      : "border-border bg-muted text-foreground hover:bg-accent")
                  }
                >
                  #{i + 1}
                </button>
              ))}
            </div>
          )}
          {current && (
            <>
              <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
                <Badge variant={badgeVariant(current.state)}>{formatStatus(current.state)}</Badge>
                <span>Duration: {durationMs(current)}</span>
                {current.attempt != null && <span>Attempt: {current.attempt}</span>}
                {current.startedAt != null && (
                  <span>Started: {new Date(current.startedAt).toLocaleTimeString()}</span>
                )}
              </div>
              {current.errorMessage && (
                <div className="error-banner rounded-md px-3 py-2 text-xs">
                  <div className="error-banner-title mb-0.5">Error</div>
                  <div className="error-banner-body">{current.errorMessage}</div>
                </div>
              )}
              <div className="space-y-1.5">
                <div className="text-xs font-medium text-foreground/80">Input</div>
                <JsonTree value={current.input} defaultOpenDepth={2} />
              </div>
              <div className="space-y-1.5">
                <div className="text-xs font-medium text-foreground/80">Output</div>
                <JsonTree value={current.output} defaultOpenDepth={2} />
              </div>
            </>
          )}
        </div>
      )}
    </Sheet>
  );
}
