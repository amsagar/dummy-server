import { useEffect, useMemo, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { BpmnDiagram } from "@/components/BpmnDiagram";
import { RevisionPicker } from "@/components/bpmn/RevisionPicker";
import type { BpmnElementRuntimeState, BpmnExecutionDecoration } from "@/components/bpmn/types";
import { Play, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { extractBpmnVariables, partitionVariables, type BpmnVarRef } from "@/lib/bpmnVariables";

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

type Tab = "diagram" | "configuration" | "executions" | "test";

interface TestRunResult {
  success: boolean;
  error?: string | null;
  outputs?: Record<string, unknown> | null;
  latencyMs?: number;
  flowableProcId?: string | null;
  executionId?: string | null;
}

export default function RuleDomainEditorPage() {
  const { id = "" } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>("diagram");
  const [selectedExecutionId, setSelectedExecutionId] = useState<string | null>(null);
  const [confirmDeleteOpen, setConfirmDeleteOpen] = useState(false);

  const deleteMutation = useMutation({
    mutationFn: (rid: string) => api.ruleDomains.delete(rid),
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.deletedRevisions ?? 0);
      const u = Number(resp?.undeployedFlowableDefinitions ?? 0);
      const revisionsMsg = `Deleted ${n} ${n === 1 ? "revision" : "revisions"}`;
      const undeployMsg = u > 0
        ? ` · undeployed ${u} Flowable ${u === 1 ? "definition" : "definitions"}`
        : "";
      toast.success(revisionsMsg + undeployMsg);
      navigate("/rule-domains");
    },
    onError: (e: any) => toast.error(e?.message || "Failed to delete"),
  });

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
        <div className="flex items-center gap-2">
          <RevisionPicker revisions={revisions} currentId={domain.id} />
          <Button
            variant="outline"
            size="sm"
            onClick={() => setConfirmDeleteOpen(true)}
            disabled={deleteMutation.isPending}
            className="gap-1.5 text-red-700 hover:bg-red-50 hover:border-red-300"
            title="Delete this rule domain"
          >
            <Trash2 className="h-3.5 w-3.5" />
            Delete
          </Button>
        </div>
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
        <TabButton active={tab === "test"} onClick={() => setTab("test")}>
          Test
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

      {tab === "test" && (
        <TestPanel domainId={domain.id} bpmnXml={domain.bpmnXml} />
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

      <ConfirmDialog
        open={confirmDeleteOpen}
        onOpenChange={setConfirmDeleteOpen}
        title="Delete this rule domain?"
        description="This removes ALL revisions of this rule domain (every version), every execution recorded against them, and any Flowable deployments that no longer have a reference. This cannot be undone."
        detail={
          <div className="space-y-0.5">
            <div>
              <span className="font-medium text-gray-900">{domain.skillName}</span>
            </div>
            <div className="font-mono text-[11px]">{domain.intentLabel}</div>
            <div className="text-[11px] text-gray-500">
              Currently viewing v{domain.version} · {domain.status}
              {revisions.length > 1 && ` · ${revisions.length} total revisions`}
            </div>
            {revisions.length > 1 && (
              <div className="text-[11px] text-gray-500">
                All revisions ({revisions.map((r) => `v${r.version}`).join(", ")}) will be deleted.
              </div>
            )}
          </div>
        }
        confirmLabel={
          revisions.length > 1
            ? `Delete all ${revisions.length} revisions`
            : "Delete"
        }
        tone="danger"
        busy={deleteMutation.isPending}
        onConfirm={() => deleteMutation.mutate(domain.id)}
      />
    </div>
  );
}

function TestPanel({ domainId, bpmnXml }: { domainId: string; bpmnXml: string }) {
  const qc = useQueryClient();
  const allVars = useMemo(() => extractBpmnVariables(bpmnXml), [bpmnXml]);
  const { inputs: inputVars } = useMemo(() => partitionVariables(allVars), [allVars]);

  const [mode, setMode] = useState<"form" | "json">(inputVars.length > 0 ? "form" : "json");
  // Form state — one string per discovered input variable. The user types
  // raw text; we attempt JSON.parse per-field at submit time so they can
  // enter scalars or JSON objects/arrays interchangeably.
  const [formValues, setFormValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(inputVars.map((v) => [v.name, defaultForVar(v)])),
  );
  // Reset form when the rule (and therefore its variables) changes.
  useEffect(() => {
    setFormValues(Object.fromEntries(inputVars.map((v) => [v.name, defaultForVar(v)])));
  }, [inputVars]);

  const [inputsText, setInputsText] = useState<string>(
    JSON.stringify({ userMessage: "Validate order 600030447", orderId: "600030447" }, null, 2),
  );
  const [parseError, setParseError] = useState<string | null>(null);
  const [result, setResult] = useState<TestRunResult | null>(null);

  const runMutation = useMutation({
    mutationFn: (inputs: Record<string, unknown>) => api.ruleDomains.test(domainId, inputs),
    onSuccess: (r: any) => {
      setResult(r);
      qc.invalidateQueries({ queryKey: ["rule-domain-executions", domainId] });
      toast.success(r?.success ? "Test passed" : "Test failed — see details");
    },
    onError: (e: any) => {
      toast.error(e?.message || "Test run failed");
      setResult({ success: false, error: e?.message ?? "Request failed" });
    },
  });

  const { data: trace = [] } = useQuery<TraceEntry[]>({
    queryKey: ["rule-execution-trace", domainId, result?.executionId],
    queryFn: () => api.ruleDomains.executionTrace(domainId, result!.executionId!),
    enabled: !!result?.executionId,
  });

  const traceDecorations: BpmnExecutionDecoration = useMemo(() => {
    const stateByElementId: Record<string, BpmnElementRuntimeState> = {};
    for (const t of trace) {
      if (!t.activityId) continue;
      const prev = stateByElementId[t.activityId];
      if (prev === "failed") continue;
      if (t.status === "failed" || t.errored) stateByElementId[t.activityId] = "failed";
      else if (t.status === "running") stateByElementId[t.activityId] = "running";
      else if (t.status === "completed") stateByElementId[t.activityId] = "completed";
    }
    return { stateByElementId, badgeByElementId: {} };
  }, [trace]);

  const runTest = () => {
    setParseError(null);
    let parsed: Record<string, unknown>;
    if (mode === "form") {
      parsed = {};
      for (const k of Object.keys(formValues)) {
        const raw = formValues[k];
        if (raw === "") continue; // skip empty fields — BPMN may have a default
        parsed[k] = coerce(raw);
      }
    } else {
      try {
        parsed = JSON.parse(inputsText);
      } catch (e: any) {
        setParseError(`Invalid JSON: ${e?.message ?? e}`);
        return;
      }
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        setParseError("Inputs must be a JSON object.");
        return;
      }
    }
    setResult(null);
    runMutation.mutate(parsed);
  };

  const erroredStep = trace.find((t) => t.errored || t.status === "failed");

  return (
    <div className="space-y-4">
      <div className="bg-blue-50 border border-blue-200 rounded p-3 text-xs text-blue-900">
        <div className="font-semibold mb-1">Test this rule domain without going through chat</div>
        <p>
          Provide the input variables the BPMN expects. The orchestrator normally sets{" "}
          <code className="bg-blue-100 px-1 rounded">userMessage</code> and{" "}
          <code className="bg-blue-100 px-1 rounded">orderId</code> from the user message; you can supply
          any others your BPMN references. Each run is persisted to the execution history with a{" "}
          <code className="bg-blue-100 px-1 rounded">test-…</code> session id.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <div className="flex items-center justify-between mb-1.5 gap-2">
            <div className="flex items-center gap-2">
              <label className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
                Inputs
              </label>
              <div className="inline-flex rounded border bg-white">
                <button
                  type="button"
                  className={`text-[11px] px-2 py-0.5 ${
                    mode === "form" ? "bg-gray-100 font-semibold" : "text-gray-600"
                  }`}
                  onClick={() => setMode("form")}
                  disabled={inputVars.length === 0}
                  title={
                    inputVars.length === 0
                      ? "No variables detected in this BPMN — use JSON mode"
                      : "Form view"
                  }
                >
                  Form
                </button>
                <button
                  type="button"
                  className={`text-[11px] px-2 py-0.5 border-l ${
                    mode === "json" ? "bg-gray-100 font-semibold" : "text-gray-600"
                  }`}
                  onClick={() => setMode("json")}
                >
                  JSON
                </button>
              </div>
            </div>
            <Button
              size="sm"
              onClick={runTest}
              disabled={runMutation.isPending}
              className="gap-1.5 bg-[#E31837] hover:bg-[#c41530]"
            >
              <Play className="h-3.5 w-3.5" />
              {runMutation.isPending ? "Running…" : "Run test"}
            </Button>
          </div>

          {mode === "form" ? (
            <div className="border rounded bg-gray-50 p-3 h-72 overflow-y-auto space-y-3">
              {inputVars.length === 0 && (
                <div className="text-xs text-gray-500">
                  No input variables were detected in this BPMN. Switch to JSON mode if you want
                  to provide variables manually.
                </div>
              )}
              {inputVars.map((v) => (
                <div key={v.name}>
                  {renderFormField({
                    variable: v,
                    value: formValues[v.name] ?? "",
                    onChange: (val: string) =>
                      setFormValues((prev) => ({ ...prev, [v.name]: val })),
                  })}
                </div>
              ))}
            </div>
          ) : (
            <textarea
              value={inputsText}
              onChange={(e) => setInputsText(e.target.value)}
              spellCheck={false}
              className="w-full h-72 rounded border bg-gray-50 p-3 font-mono text-xs"
            />
          )}
          {parseError && (
            <div className="mt-2 text-xs text-red-700 bg-red-50 border border-red-200 rounded p-2">
              {parseError}
            </div>
          )}
        </div>

        <div>
          <label className="text-xs font-semibold text-gray-700 uppercase tracking-wide block mb-1.5">
            Outputs
          </label>
          <div className="border rounded bg-gray-50 p-3 min-h-72 text-xs">
            {result ? (
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <span
                    className={`rounded px-2 py-0.5 text-[10px] font-semibold ${
                      result.success
                        ? "bg-green-100 text-green-800"
                        : "bg-red-100 text-red-800"
                    }`}
                  >
                    {result.success ? "SUCCESS" : "FAILURE"}
                  </span>
                  {typeof result.latencyMs === "number" && (
                    <span className="text-gray-500">{result.latencyMs} ms</span>
                  )}
                </div>
                {result.error && (
                  <pre className="text-[11px] text-red-700 bg-red-50 border border-red-200 rounded p-2 whitespace-pre-wrap break-words">
                    {result.error}
                  </pre>
                )}
                {result.outputs && (
                  <pre className="text-[11px] bg-white border rounded p-2 overflow-x-auto max-h-56">
                    {JSON.stringify(result.outputs, null, 2)}
                  </pre>
                )}
              </div>
            ) : (
              <div className="text-gray-400">Run a test to see outputs here.</div>
            )}
          </div>
        </div>
      </div>

      {result && (
        <>
          {erroredStep && (
            <div className="bg-red-50 border border-red-200 rounded p-2.5 text-xs text-red-800">
              Stopped at{" "}
              <code className="bg-red-100 px-1 rounded">
                {erroredStep.activityName ?? erroredStep.activityId}
              </code>
              {erroredStep.deleteReason && <> — {erroredStep.deleteReason}</>}
            </div>
          )}
          <BpmnDiagram xml={bpmnXml} className="h-[55vh]" executionDecorations={traceDecorations} />
          {trace.length > 0 && (
            <details>
              <summary className="cursor-pointer text-xs text-gray-600 hover:text-gray-900">
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
                  </tbody>
                </table>
              </div>
            </details>
          )}
        </>
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

// ── Test panel: per-variable form field ────────────────────────────────────

function renderFormField({
  variable,
  value,
  onChange,
}: {
  variable: BpmnVarRef;
  value: string;
  onChange: (v: string) => void;
}) {
  const isMultiline = looksMultiline(variable.usages);
  return (
    <div>
      <div className="flex items-baseline justify-between mb-0.5">
        <label className="text-xs font-mono text-gray-800">{variable.name}</label>
        {variable.usages.length > 0 && (
          <span
            className="text-[10px] text-gray-500 font-mono truncate ml-2 max-w-[60%]"
            title={variable.usages.join("\n")}
          >
            used as: {variable.usages[0]}
          </span>
        )}
      </div>
      {isMultiline ? (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          spellCheck={false}
          rows={3}
          className="w-full rounded border bg-white p-2 text-xs font-mono"
          placeholder="Scalar, JSON object, or JSON array"
        />
      ) : (
        <input
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          spellCheck={false}
          className="w-full rounded border bg-white p-2 text-xs font-mono"
          placeholder="Scalar, or JSON for objects/arrays"
        />
      )}
    </div>
  );
}

// Pick a reasonable starting value for a freshly-discovered variable.
function defaultForVar(v: BpmnVarRef): string {
  const n = v.name.toLowerCase();
  if (n === "ordreid" || n === "orderid" || n === "ord_id" || n === "id") return "600030447";
  if (n === "usermessage") return "Validate this order";
  return "";
}

function looksMultiline(usages: string[]): boolean {
  // If the variable is accessed with `.field` or `[i]`, the user probably
  // wants to paste a JSON blob, not a single scalar.
  return usages.some((u) => /[.\[]/.test(u));
}

// Try JSON.parse first; fall through to the raw string so the user can
// type plain values (e.g. `600030447`, `true`) without quoting them.
function coerce(raw: string): unknown {
  const trimmed = raw.trim();
  if (trimmed === "") return "";
  if (trimmed === "true") return true;
  if (trimmed === "false") return false;
  if (trimmed === "null") return null;
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith('"')) {
    try {
      return JSON.parse(trimmed);
    } catch {
      return raw;
    }
  }
  return raw;
}
