import { useEffect, useMemo, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { BpmnDiagram } from "@/components/BpmnDiagram";
import { RevisionPicker } from "@/components/bpmn/RevisionPicker";
import type { BpmnElementRuntimeState, BpmnExecutionDecoration } from "@/components/bpmn/types";

interface RuleDomainDetail {
  id: string;
  skillName: string;
  intentLabel: string;
  status: string;
  version: number;
  flowableProcKey: string;
  bpmnXml: string;
  lastError?: string | null;
  createdAt: number;
  updatedAt: number;
}

interface RuleDomainRevision {
  id: string;
  version: number;
  status: string;
  updatedAt: number;
  compileAttempts: number;
}

interface RuleExecution {
  id: string;
  domainId: string;
  flowableProcId: string;
  inputsJson?: string;
  outputsJson?: string;
  success: boolean;
  errorMessage?: string;
  latencyMs?: number;
  createdAt: number;
}

interface TraceEntry {
  activityId: string;
  activityName?: string;
  activityType?: string;
  startTime?: number | null;
  endTime?: number | null;
  durationMs?: number | null;
  deleteReason?: string | null;
  errored: boolean;
  status: BpmnElementRuntimeState | "running";
}

type Tab = "diagram" | "configuration" | "executions";

export default function RuleDomainEditorPage() {
  const { id = "" } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("diagram");
  const [selectedExecutionId, setSelectedExecutionId] = useState<string | null>(null);

  const { data: domain } = useQuery<RuleDomainDetail>({
    queryKey: ["rule-domain", id],
    queryFn: () => api.ruleDomains.get(id),
    enabled: !!id,
  });

  const { data: revisions = [] } = useQuery<RuleDomainRevision[]>({
    queryKey: ["rule-domain-revisions", id],
    queryFn: () => api.ruleDomains.versions(id),
    enabled: !!id,
  });

  const { data: executions = [] } = useQuery<RuleExecution[]>({
    queryKey: ["rule-domain-executions", id],
    queryFn: () => api.ruleDomains.executions(id, 50),
    enabled: !!id && (tab === "executions" || tab === "diagram"),
  });

  // Default-select the most recent execution when entering the Executions tab.
  useEffect(() => {
    if (tab !== "executions") return;
    if (selectedExecutionId) return;
    if (executions.length > 0) setSelectedExecutionId(executions[0].id);
  }, [tab, executions, selectedExecutionId]);

  // Reset selection when switching to a different domain id.
  useEffect(() => setSelectedExecutionId(null), [id]);

  const selectedExecution = useMemo(
    () => executions.find((e) => e.id === selectedExecutionId) ?? null,
    [executions, selectedExecutionId],
  );

  const { data: executionTrace = [] } = useQuery<TraceEntry[]>({
    queryKey: ["rule-execution-trace", id, selectedExecutionId],
    queryFn: () => api.ruleDomains.executionTrace(id, selectedExecutionId!),
    enabled: !!id && !!selectedExecutionId && tab === "executions",
  });

  // Build BpmnExecutionDecoration from the per-activity trace so the diagram
  // colors completed / failed / running steps in place.
  const traceDecorations: BpmnExecutionDecoration = useMemo(() => {
    const stateByElementId: Record<string, BpmnElementRuntimeState> = {};
    if (!executionTrace || executionTrace.length === 0) {
      return { stateByElementId: {}, badgeByElementId: {} };
    }
    for (const t of executionTrace) {
      if (!t.activityId) continue;
      // Map the trace status to the decoration vocabulary.
      let state: BpmnElementRuntimeState = "completed";
      if (t.status === "failed") state = "failed";
      else if (t.status === "running") state = "running";
      else if (t.status === "completed") state = "completed";
      // "failed" wins over "completed" on the same element id (multi-instance
      // can produce both — show the worst outcome).
      const prev = stateByElementId[t.activityId];
      if (prev === "failed") continue;
      stateByElementId[t.activityId] = state;
    }
    return { stateByElementId, badgeByElementId: {} };
  }, [executionTrace]);

  if (!domain) {
    return <div className="text-gray-400">Loading domain…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <Button variant="link" onClick={() => navigate("/rule-domains")} className="px-0">
            ← Back to all domains
          </Button>
          <h2 className="text-xl font-semibold text-[#123262]">
            {domain.skillName} <span className="text-gray-400">·</span> {domain.intentLabel}
          </h2>
          <div className="text-sm text-gray-500">
            <span className="font-mono">{domain.flowableProcKey}</span>
            <span className="mx-1.5">·</span>
            <span>v{domain.version}</span>
            <span className="mx-1.5">·</span>
            <span>{domain.status}</span>
          </div>
        </div>
        <RevisionPicker revisions={revisions} currentId={domain.id} />
      </div>

      {domain.lastError && (
        <div className="bg-red-50 border border-red-200 rounded p-3 text-sm text-red-800">
          <div className="font-semibold mb-1">Last error</div>
          <pre className="text-xs overflow-x-auto whitespace-pre-wrap">{domain.lastError}</pre>
        </div>
      )}

      <div className="border-b flex gap-4">
        <TabButton active={tab === "diagram"} onClick={() => setTab("diagram")}>
          Diagram
        </TabButton>
        <TabButton active={tab === "configuration"} onClick={() => setTab("configuration")}>
          Configuration
        </TabButton>
        <TabButton active={tab === "executions"} onClick={() => setTab("executions")}>
          Executions
          {executions.length > 0 && (
            <span className="ml-1.5 text-[10px] rounded-full bg-gray-200 text-gray-700 px-1.5 py-0.5 font-semibold">
              {executions.length}
            </span>
          )}
        </TabButton>
      </div>

      {tab === "diagram" && (
        <div className="space-y-2">
          <BpmnDiagram xml={domain.bpmnXml} className="h-[70vh]" />
          <div className="rounded border bg-gray-50 px-3 py-2 text-xs text-gray-600">
            <span>Executions: <span className="font-semibold text-gray-800">{executions.length}</span></span>
            <span className="ml-4">Tip: double-click any non-subprocess node to see its inputs/outputs. Double-click a subprocess to drill in.</span>
          </div>
        </div>
      )}

      {tab === "configuration" && (
        <pre className="bg-gray-900 text-gray-100 rounded p-4 text-xs overflow-x-auto leading-relaxed max-h-[70vh]">
          {domain.bpmnXml}
        </pre>
      )}

      {tab === "executions" && (
        <div className="grid grid-cols-12 gap-3">
          <div className="col-span-12 lg:col-span-3 space-y-1.5 max-h-[78vh] overflow-y-auto pr-1">
            {executions.length === 0 && (
              <div className="text-gray-400 text-sm py-4">No executions recorded yet.</div>
            )}
            {executions.map((e) => (
              <button
                key={e.id}
                onClick={() => setSelectedExecutionId(e.id)}
                className={`w-full text-left border rounded p-3 transition ${
                  selectedExecutionId === e.id
                    ? "border-[#E31837] bg-red-50/40 ring-1 ring-[#E31837]/20"
                    : "border-gray-200 bg-white hover:bg-gray-50"
                }`}
              >
                <div className="flex items-center justify-between text-xs">
                  <span className={e.success ? "text-green-700 font-medium" : "text-red-700 font-medium"}>
                    {e.success ? "✓ success" : "✗ failure"}
                  </span>
                  <span className="text-gray-500">{e.latencyMs ?? "?"}ms</span>
                </div>
                <div className="mt-1 text-[11px] text-gray-500">
                  {new Date(e.createdAt).toLocaleString()}
                </div>
                {e.errorMessage && (
                  <div className="mt-1 text-[11px] text-red-700 line-clamp-2">{e.errorMessage}</div>
                )}
              </button>
            ))}
          </div>

          <div className="col-span-12 lg:col-span-9">
            {selectedExecution ? (
              <ExecutionDetail
                execution={selectedExecution}
                xml={domain.bpmnXml}
                trace={executionTrace}
                decorations={traceDecorations}
              />
            ) : (
              <div className="text-gray-400 text-sm py-8 text-center border rounded">
                Select an execution on the left to inspect it.
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function ExecutionDetail({
  execution,
  xml,
  trace,
  decorations,
}: {
  execution: RuleExecution;
  xml: string;
  trace: TraceEntry[];
  decorations: BpmnExecutionDecoration;
}) {
  const erroredStep = trace.find((t) => t.errored || t.status === "failed");

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3 text-xs">
        <span
          className={`rounded px-2 py-0.5 font-semibold ${
            execution.success
              ? "bg-green-100 text-green-800"
              : "bg-red-100 text-red-800"
          }`}
        >
          {execution.success ? "SUCCESS" : "FAILURE"}
        </span>
        <span className="text-gray-500">{new Date(execution.createdAt).toLocaleString()}</span>
        <span className="text-gray-500">· {execution.latencyMs ?? "?"} ms</span>
        <span className="text-gray-400 font-mono text-[10px]">proc {execution.flowableProcId}</span>
      </div>

      {execution.errorMessage && (
        <div className="bg-red-50 border border-red-200 rounded p-2.5 text-xs text-red-800">
          <div className="font-semibold mb-1">Error</div>
          <div className="font-mono text-[11px] whitespace-pre-wrap break-words">{execution.errorMessage}</div>
          {erroredStep && (
            <div className="mt-1.5 text-[11px]">
              Stopped at <code className="bg-red-100 px-1 rounded">{erroredStep.activityName ?? erroredStep.activityId}</code>
              {erroredStep.deleteReason && <> — {erroredStep.deleteReason}</>}
            </div>
          )}
        </div>
      )}

      <BpmnDiagram xml={xml} className="h-[55vh]" executionDecorations={decorations} />

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
        <CountCard label="Steps" value={trace.length} />
        <CountCard
          label="Completed"
          value={trace.filter((t) => t.status === "completed").length}
          tone="green"
        />
        <CountCard
          label="Failed"
          value={trace.filter((t) => t.status === "failed" || t.errored).length}
          tone="red"
        />
      </div>

      <details className="text-xs" open={false}>
        <summary className="cursor-pointer text-gray-600 hover:text-gray-900">
          Activity trace ({trace.length} entries)
        </summary>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full text-[11px]">
            <thead>
              <tr className="bg-gray-50 text-gray-600">
                <th className="text-left px-2 py-1">Activity</th>
                <th className="text-left px-2 py-1">Type</th>
                <th className="text-left px-2 py-1">Status</th>
                <th className="text-right px-2 py-1">Duration</th>
              </tr>
            </thead>
            <tbody>
              {trace.map((t, idx) => (
                <tr key={`${t.activityId}-${idx}`} className="border-t">
                  <td className="px-2 py-1">
                    <div className="font-medium text-gray-800">{t.activityName ?? t.activityId}</div>
                    <div className="font-mono text-[10px] text-gray-400">{t.activityId}</div>
                  </td>
                  <td className="px-2 py-1 text-gray-600">{t.activityType ?? ""}</td>
                  <td className="px-2 py-1">
                    <span className={statusColor(t.status)}>{t.status}</span>
                  </td>
                  <td className="px-2 py-1 text-right text-gray-600">
                    {t.durationMs != null ? `${t.durationMs}ms` : "—"}
                  </td>
                </tr>
              ))}
              {trace.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-2 py-3 text-center text-gray-400">
                    No activity history available.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </details>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {execution.inputsJson && (
          <details>
            <summary className="cursor-pointer text-xs text-gray-600 hover:text-gray-900">Inputs</summary>
            <pre className="text-[11px] bg-gray-50 p-2 mt-1 rounded overflow-x-auto max-h-72">
              {prettify(execution.inputsJson)}
            </pre>
          </details>
        )}
        {execution.outputsJson && (
          <details open>
            <summary className="cursor-pointer text-xs text-gray-600 hover:text-gray-900">Outputs</summary>
            <pre className="text-[11px] bg-gray-50 p-2 mt-1 rounded overflow-x-auto max-h-72">
              {prettify(execution.outputsJson)}
            </pre>
          </details>
        )}
      </div>
    </div>
  );
}

function CountCard({ label, value, tone }: { label: string; value: number; tone?: "green" | "red" }) {
  const toneClass =
    tone === "green"
      ? "text-green-700"
      : tone === "red"
        ? "text-red-700"
        : "text-gray-800";
  return (
    <div className="border rounded p-2.5 bg-white">
      <div className="text-[10px] uppercase font-semibold text-gray-500 tracking-wider">{label}</div>
      <div className={`text-xl font-bold ${toneClass}`}>{value}</div>
    </div>
  );
}

function TabButton({
  children,
  active,
  onClick,
}: {
  children: React.ReactNode;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={`pb-2 -mb-px border-b-2 text-sm font-medium ${
        active
          ? "border-[#E31837] text-[#E31837]"
          : "border-transparent text-gray-500 hover:text-gray-700"
      }`}
    >
      {children}
    </button>
  );
}

function statusColor(status: string): string {
  switch (status) {
    case "completed":
      return "text-green-700 font-medium";
    case "failed":
      return "text-red-700 font-medium";
    case "running":
      return "text-blue-700 font-medium";
    default:
      return "text-gray-700";
  }
}

function prettify(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}
