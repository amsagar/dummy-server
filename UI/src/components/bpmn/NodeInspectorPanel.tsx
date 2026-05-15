import { X } from "lucide-react";

import type { NodeInspection } from "@/lib/bpmnNodeInspect";

interface Props {
  inspection: NodeInspection;
  onClose: () => void;
}

export function NodeInspectorPanel({ inspection, onClose }: Props) {
  return (
    <div className="absolute right-0 top-0 bottom-0 z-30 flex w-[380px] flex-col border-l border-gray-200 bg-white shadow-2xl">
      <header className="flex items-start justify-between gap-3 border-b border-gray-200 px-4 py-3">
        <div className="min-w-0">
          <div className="text-[10px] font-semibold uppercase tracking-wider text-gray-500">
            {inspection.typeLabel}
            {inspection.delegate && (
              <>
                <span className="mx-1 text-gray-300">·</span>
                <span className="font-mono normal-case text-gray-400">{inspection.delegate}</span>
              </>
            )}
          </div>
          <div className="mt-0.5 truncate text-sm font-semibold text-[#123262]" title={inspection.name}>
            {inspection.name}
          </div>
          <div className="truncate font-mono text-[10px] text-gray-400" title={inspection.id}>
            {inspection.id}
          </div>
        </div>
        <button
          onClick={onClose}
          className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-700"
          title="Close"
          aria-label="Close inspector"
        >
          <X className="h-4 w-4" />
        </button>
      </header>

      <div className="flex-1 space-y-5 overflow-y-auto px-4 py-4 text-sm">
        {inspection.summary && (
          <p
            className="leading-relaxed text-gray-700"
            // Render **bold** segments without pulling in a markdown lib.
            dangerouslySetInnerHTML={{ __html: renderBold(inspection.summary) }}
          />
        )}

        <Section title="Input">
          {inspection.inputs.length === 0 ? (
            <div className="text-xs text-gray-400">No declared inputs.</div>
          ) : (
            <div className="space-y-1.5">
              {inspection.inputs.map((io, idx) => (
                <div key={idx} className="text-xs">
                  <div className="font-medium text-gray-800">{io.label}</div>
                  <code className="mt-0.5 block break-all rounded bg-gray-50 px-1.5 py-1 font-mono text-[11px] text-[#123262]">
                    {io.source}
                  </code>
                </div>
              ))}
            </div>
          )}
        </Section>

        <Section title="Output">
          {inspection.output ? (
            <div className="space-y-1">
              <div className="text-xs">
                <span className="text-gray-500">stored as </span>
                <code className="rounded bg-gray-50 px-1.5 py-0.5 font-mono text-[11px] text-[#123262]">
                  {inspection.output.name}
                </code>
              </div>
              <div className="text-xs text-gray-600">{inspection.output.description}</div>
            </div>
          ) : (
            <div className="text-xs text-gray-400">No declared output.</div>
          )}
        </Section>

        {Object.keys(inspection.rawFields).length > 0 && (
          <details>
            <summary className="cursor-pointer text-[11px] uppercase tracking-wider text-gray-400 hover:text-gray-600">
              Raw fields
            </summary>
            <pre className="mt-2 overflow-x-auto rounded bg-gray-900 px-3 py-2 text-[11px] leading-relaxed text-gray-100">
              {JSON.stringify(inspection.rawFields, null, 2)}
            </pre>
          </details>
        )}
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <div className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-gray-500">{title}</div>
      {children}
    </section>
  );
}

function renderBold(text: string): string {
  // Trivial **bold** → <strong>bold</strong> replacement, escaping anything else.
  const escaped = text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  return escaped.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
}
