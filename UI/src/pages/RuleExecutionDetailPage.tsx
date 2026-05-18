import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { api } from "@/services/api";
import { BpmnDiagram } from "@/components/BpmnDiagram";
import type { BpmnElementRuntimeState, BpmnExecutionDecoration } from "@/components/bpmn/types";
import { ActivityValuesPanel, type ActivityEvent } from "@/components/bpmn/ActivityValuesPanel";
import { ActivityGanttChart } from "@/components/executions/ActivityGanttChart";
import { ArrowLeft, ExternalLink } from "lucide-react";

interface ExecutionDetail {
  id: string;
  domainId: string;
  sessionId?: string | null;
  flowableProcId?: string | null;
  inputsJson?: string | null;
  outputsJson?: string | null;
  success: boolean;
  errorMessage?: string | null;
  latencyMs?: number | null;
  createdAt: number;
  skillId: string;
  skillName: string;
  intentLabel: string;
  ruleName: string;
  resultKey?: string | null;
}

interface TraceEntry {
  activityId: string;
  activityName?: string | null;
  activityType?: string | null;
  startTime?: number | null;
  endTime?: number | null;
  durationMs?: number | null;
  deleteReason?: string | null;
  errored?: boolean;
  status?: BpmnElementRuntimeState | "running";
}

export default function RuleExecutionDetailPage() {
  const { execId } = useParams<{ execId: string }>();

  const { data: execution } = useQuery<ExecutionDetail>({
    queryKey: ["rule-execution", execId],
    queryFn: () => api.get(`/rule-executions/${encodeURIComponent(execId!)}`),
    enabled: !!execId,
  });

  const { data: domain } = useQuery<{ bpmnXml: string }>({
    queryKey: ["rule-domain-for-exec", execution?.domainId],
    queryFn: () => api.ruleDomains.get(execution!.domainId),
    enabled: !!execution?.domainId,
  });

  const { data: activityEvents = [] } = useQuery<ActivityEvent[]>({
    queryKey: ["rule-execution-activity-events", execId],
    queryFn: () => api.ruleExecutions.activityEvents(execId!),
    enabled: !!execId,
  });

  const { data: executionTrace = [] } = useQuery<TraceEntry[]>({
    queryKey: ["rule-execution-trace", execution?.domainId, execId],
    queryFn: () => api.ruleDomains.executionTrace(execution!.domainId, execId!),
    enabled: !!execution?.domainId && !!execId,
  });

  const decorations: BpmnExecutionDecoration = useMemo(() => {
    const stateByElementId: Record<string, BpmnElementRuntimeState> = {};
    const badgeByElementId: Record<string, string> = {};
    for (const t of executionTrace) {
      if (!t.activityId) continue;
      let state: BpmnElementRuntimeState = "completed";
      if (t.errored || t.status === "failed") state = "failed";
      else if (t.status === "running") state = "running";
      stateByElementId[t.activityId] = state;
      if (typeof t.durationMs === "number") {
        badgeByElementId[t.activityId] = `${t.durationMs} ms`;
      }
    }
    return { stateByElementId, badgeByElementId };
  }, [executionTrace]);

  const [selectedActivityId, setSelectedActivityId] = useState<string | null>(null);

  if (!execId) return <div className="text-sm text-gray-500">Missing execution id.</div>;
  if (!execution) return <div className="text-sm text-gray-500">Loading execution…</div>;

  return (
    <div className="space-y-4 max-w-[1500px]">
      <div className="flex items-center justify-between">
        <Link to="/rule-executions" className="inline-flex items-center gap-1 text-xs text-gray-600 hover:text-gray-900">
          <ArrowLeft className="h-3.5 w-3.5" /> Back to executions
        </Link>
        <Link
          to={`/rule-domains/${encodeURIComponent(execution.domainId)}`}
          className="inline-flex items-center gap-1 text-xs text-gray-600 hover:text-gray-900"
        >
          Open rule editor <ExternalLink className="h-3.5 w-3.5" />
        </Link>
      </div>

      <div className="rounded border bg-white p-3">
        <div className="flex flex-wrap items-baseline gap-3">
          <span
            className={`rounded px-2 py-0.5 text-[10px] font-semibold ${
              execution.success ? "bg-green-100 text-green-800" : "bg-red-100 text-red-800"
            }`}
          >
            {execution.success ? "SUCCESS" : "FAILED"}
          </span>
          <span className="text-sm text-gray-700">{execution.skillName}</span>
          <span className="text-sm font-mono text-[#123262]">{execution.ruleName}</span>
          <span className="text-xs text-gray-500">{new Date(execution.createdAt).toLocaleString()}</span>
          <span className="text-xs text-gray-500">
            Total execution time:{" "}
            <span className="font-semibold tabular-nums">{execution.latencyMs ?? "—"}</span>
            {typeof execution.latencyMs === "number" ? " ms" : ""}
          </span>
          {execution.flowableProcId && (
            <span className="text-[10px] font-mono text-gray-400">proc {execution.flowableProcId}</span>
          )}
        </div>
        {execution.errorMessage && (
          <pre className="mt-2 text-[11px] text-red-700 bg-red-50 border border-red-200 rounded p-2 whitespace-pre-wrap break-words">
            {execution.errorMessage}
          </pre>
        )}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[1fr_420px] gap-3">
        <div className="space-y-3">
          {domain?.bpmnXml ? (
            <div className="relative">
              <BpmnDiagram
                xml={domain.bpmnXml}
                className="h-[50vh]"
                executionDecorations={decorations}
                selectedElementId={selectedActivityId}
                onElementClick={(id) => setSelectedActivityId(id)}
              />
            </div>
          ) : (
            <div className="rounded border bg-white p-4 text-sm text-gray-500">Loading BPMN…</div>
          )}

          <ActivityGanttChart
            events={activityEvents}
            selectedActivityId={selectedActivityId}
            onSelect={(id) => setSelectedActivityId(id)}
          />
        </div>

        <div className="space-y-3">
          <div className="rounded border bg-white p-3">
            <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-700 mb-2">
              Performance
            </div>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <SidePanelStat label="Execution" value={`${execution.latencyMs ?? "—"} ms`} />
              <SidePanelStat label="Activities" value={String(activityEvents.length)} />
              <SidePanelStat
                label="Started"
                value={new Date(execution.createdAt).toLocaleTimeString()}
              />
              <SidePanelStat
                label="Status"
                value={execution.success ? "Success" : "Failed"}
                tone={execution.success ? "green" : "red"}
              />
            </div>
          </div>

          {selectedActivityId ? (
            <div className="relative">
              <SelectedActivityPanel
                activityId={selectedActivityId}
                events={activityEvents}
                onClose={() => setSelectedActivityId(null)}
              />
            </div>
          ) : (
            <div className="rounded border bg-white p-3 text-xs text-gray-500">
              Click an activity in the timeline or BPMN diagram to see its input and output.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function SidePanelStat({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "green" | "red";
}) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wide text-gray-500">{label}</div>
      <div
        className={`text-sm font-semibold ${
          tone === "green" ? "text-green-700" : tone === "red" ? "text-red-700" : "text-gray-900"
        }`}
      >
        {value}
      </div>
    </div>
  );
}

function SelectedActivityPanel({
  activityId,
  events,
  onClose,
}: {
  activityId: string;
  events: ActivityEvent[];
  onClose: () => void;
}) {
  // Render the side panel inline (no absolute positioning). The shared
  // ActivityValuesPanel uses `absolute` positioning meant for overlay on
  // the diagram; here we want it as a column. Wrap it in a relative
  // container so its absolute positioning collapses to that container.
  return (
    <div className="relative min-h-[200px]">
      <ActivityValuesPanel activityId={activityId} events={events} onClose={onClose} />
    </div>
  );
}
