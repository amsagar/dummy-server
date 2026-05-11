// Overview cards: state, duration, requester, activity counts, error.

import { useState } from "react";
import { Link } from "react-router-dom";
import { CheckCircle2, Clock, Copy, Check, Loader2, XCircle } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { StateBadge } from "../StateBadge";
import type { ActivityInst, RunSummary } from "@/types/workflow";

interface OverviewTabProps {
  summary: RunSummary;
  activities: ActivityInst[];
}

function formatDuration(start: number | null | undefined, end: number | null | undefined): string {
  if (!start) return "—";
  const e = end ?? Date.now();
  const ms = e - start;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

export function OverviewTab({ summary, activities }: OverviewTabProps) {
  const total = activities.length;
  const completed = activities.filter((a) => a.state === "completed").length;
  const failed = activities.filter(
    (a) => a.state === "failed" || a.state === "deadline_breached" || a.state === "cancelled",
  ).length;
  const running = activities.filter((a) => a.state === "running").length;

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
      <Card label="State">
        <StateBadge state={summary.state} className="text-xs" />
      </Card>
      <Card label="Duration">
        <span className="text-lg font-medium">{formatDuration(summary.startedAt, summary.endedAt)}</span>
      </Card>
      <Card label="Started">
        <span className="text-sm">
          {summary.startedAt ? new Date(summary.startedAt).toLocaleString() : "—"}
        </span>
      </Card>
      <Card label="Requester">
        <span className="text-sm font-mono break-all">{summary.requesterId ?? "—"}</span>
      </Card>

      <Card label="Activities">
        <span className="text-lg font-medium">{total}</span>
      </Card>
      <Card label="Completed">
        <span className="text-lg font-medium flex items-center gap-1">
          <CheckCircle2 className="w-4 h-4 text-emerald-600" /> {completed}
        </span>
      </Card>
      <Card label="Running">
        <span className="text-lg font-medium flex items-center gap-1">
          <Loader2 className={cn("w-4 h-4", running > 0 ? "text-blue-600 animate-spin" : "text-muted-foreground")} />
          {running}
        </span>
      </Card>
      <Card label="Failed">
        <span className="text-lg font-medium flex items-center gap-1">
          {failed > 0 ? <XCircle className="w-4 h-4 text-rose-600" /> : <Clock className="w-4 h-4 text-muted-foreground" />}
          {failed}
        </span>
      </Card>

      {summary.errorMessage && (
        <div className="col-span-full border border-rose-300 bg-rose-50 dark:bg-rose-950/40 rounded-md p-3 text-sm">
          <div className="text-xs font-medium text-rose-700 dark:text-rose-400 mb-1">
            {summary.errorClass ?? "Error"}
          </div>
          <div className="font-mono text-rose-900 dark:text-rose-200 break-words">
            {summary.errorMessage}
          </div>
        </div>
      )}

      {summary.state === "closed.completed" && (
        <div className="col-span-full">
          <ResultBlock result={summary.result} />
        </div>
      )}

      {summary.defId && (
        <div className="col-span-full text-xs text-muted-foreground">
          <Link className="underline" to={`/workflows/${summary.defId}/designer`}>
            Open workflow definition
          </Link>
        </div>
      )}
    </div>
  );
}

/**
 * Renders the run's declared result (the JSON-decoded value of the closing
 * activity's `properties.result` SecureSpel expression). When the workflow
 * didn't declare one, show a hint pointing at where to add it.
 */
function ResultBlock({ result }: { result: unknown }) {
  const [copied, setCopied] = useState(false);
  if (result === undefined || result === null) {
    return (
      <div className="border border-dashed rounded-md p-3 text-xs text-muted-foreground">
        This workflow doesn't declare a <code className="font-mono">result</code> expression
        on its end activity, so the run-summary API response has no payload. Add{" "}
        <code className="font-mono">properties.result</code> to the end activity (e.g.{" "}
        <code className="font-mono">"#{`{#details?.output}`}"</code>) to surface the
        workflow's output here and over the API.
      </div>
    );
  }
  const pretty =
    typeof result === "string" ? result : JSON.stringify(result, null, 2);
  return (
    <div className="border rounded-md p-3 bg-background">
      <div className="flex items-center justify-between mb-2">
        <div className="text-xs font-medium text-muted-foreground">Result</div>
        <Button
          variant="outline"
          size="xs"
          onClick={() => {
            navigator.clipboard.writeText(pretty);
            toast.success("Result copied");
            setCopied(true);
            setTimeout(() => setCopied(false), 1200);
          }}
        >
          {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />}
          <span className="ml-1">{copied ? "Copied" : "Copy"}</span>
        </Button>
      </div>
      <pre className="max-h-96 overflow-auto rounded bg-muted/40 p-2 font-mono text-xs whitespace-pre-wrap break-all">
        {pretty}
      </pre>
    </div>
  );
}

function Card({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="border rounded-md p-3 bg-background">
      <div className="text-xs text-muted-foreground mb-1">{label}</div>
      {children}
    </div>
  );
}
