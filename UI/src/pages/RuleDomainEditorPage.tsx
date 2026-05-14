import { useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";

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

type Tab = "diagram" | "xml" | "executions";

export default function RuleDomainEditorPage() {
  const { id = "" } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("diagram");

  const { data: domain } = useQuery<RuleDomainDetail>({
    queryKey: ["rule-domain", id],
    queryFn: () => api.ruleDomains.get(id),
    enabled: !!id,
  });

  const { data: executions = [] } = useQuery<RuleExecution[]>({
    queryKey: ["rule-domain-executions", id],
    queryFn: () => api.ruleDomains.executions(id, 50),
    enabled: !!id && tab === "executions",
  });

  if (!domain) {
    return <div className="text-gray-400">Loading domain…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <Button variant="link" onClick={() => navigate("/rule-domains")} className="px-0">
            ← Back to all domains
          </Button>
          <h2 className="text-xl font-semibold text-[#123262]">
            {domain.skillName} <span className="text-gray-400">·</span> {domain.intentLabel}
          </h2>
          <div className="text-sm text-gray-500">
            <span className="font-mono">{domain.flowableProcKey}</span> · v{domain.version} · {domain.status}
          </div>
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
        <TabButton active={tab === "xml"} onClick={() => setTab("xml")}>
          BPMN XML
        </TabButton>
        <TabButton active={tab === "executions"} onClick={() => setTab("executions")}>
          Executions
        </TabButton>
      </div>

      {tab === "diagram" && (
        <div className="bg-white rounded border p-6 text-center text-sm text-gray-500">
          <p>BPMN visualization placeholder.</p>
          <p className="text-xs mt-2">
            Install <code className="bg-gray-100 px-1 rounded">bpmn-js</code> in <code>UI/package.json</code> to
            render the workflow diagram here. The XML tab shows the raw definition.
          </p>
        </div>
      )}

      {tab === "xml" && (
        <pre className="bg-gray-900 text-gray-100 rounded p-4 text-xs overflow-x-auto leading-relaxed max-h-[60vh]">
          {domain.bpmnXml}
        </pre>
      )}

      {tab === "executions" && (
        <div className="space-y-2">
          {executions.length === 0 && (
            <div className="text-gray-400 text-sm">No executions recorded yet.</div>
          )}
          {executions.map((e) => (
            <div key={e.id} className="bg-white border rounded p-3">
              <div className="flex items-center justify-between text-sm">
                <span className={e.success ? "text-green-700 font-medium" : "text-red-700 font-medium"}>
                  {e.success ? "✓ success" : "✗ failure"}
                </span>
                <span className="text-gray-500">
                  {new Date(e.createdAt).toLocaleString()} · {e.latencyMs}ms
                </span>
              </div>
              {e.errorMessage && <div className="mt-1 text-xs text-red-700">{e.errorMessage}</div>}
              {e.inputsJson && (
                <details className="mt-2">
                  <summary className="text-xs text-gray-500 cursor-pointer">inputs</summary>
                  <pre className="text-xs bg-gray-50 p-2 mt-1 rounded overflow-x-auto">
                    {prettify(e.inputsJson)}
                  </pre>
                </details>
              )}
              {e.outputsJson && (
                <details className="mt-2">
                  <summary className="text-xs text-gray-500 cursor-pointer">outputs</summary>
                  <pre className="text-xs bg-gray-50 p-2 mt-1 rounded overflow-x-auto">
                    {prettify(e.outputsJson)}
                  </pre>
                </details>
              )}
            </div>
          ))}
        </div>
      )}
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

function prettify(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}
