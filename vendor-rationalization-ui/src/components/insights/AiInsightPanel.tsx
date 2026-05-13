import * as React from "react";
import {
  Sparkles, RefreshCw, Loader2, Info, AlertTriangle, Lightbulb, Layers, Zap,
} from "lucide-react";
import type { AiInsight, AiInsightsResponse } from "@/types/vendorRationalization";

/**
 * Shared "AI Insights" panel used by every dashboard surface
 * (Executive Dashboard, Category Analytics, Vendor Performance, Savings
 * Opportunities, Contracts & Compliance). Renders the LLM-generated cards
 * returned by {@code GET /api/v1/vendor-rationalization/insights?surface=…}
 * with a uniform shell — header, cached badge, Regenerate button, loading
 * / empty / no-model-configured states. The caller passes in the query
 * data + the regenerate mutation; this component is presentation-only.
 */
export function AiInsightPanel({
  title,
  modelConfigured,
  data,
  isLoading,
  onRegenerate,
  isRegenerating,
  emptyHint,
  panelStyle = "blue",
  loadingLabel,
}: {
  title: string;
  modelConfigured: boolean;
  data: AiInsightsResponse | undefined;
  isLoading: boolean;
  onRegenerate: () => void;
  isRegenerating: boolean;
  emptyHint?: string;
  panelStyle?: "blue" | "plain";
  loadingLabel?: string;
}) {
  const bgStyle = panelStyle === "blue"
    ? { background: "#e9effd", border: "1px solid #a8c1f7" }
    : undefined;

  return (
    <div className="content-card p-5" style={bgStyle}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Sparkles size={15} className="text-[#2563eb]" />
          <h3 className="font-semibold text-[#051332] text-sm">{title}</h3>
          {data?.cached && (
            <span className="text-[10px] uppercase text-gray-400 tracking-wide">cached</span>
          )}
        </div>
        <button
          onClick={onRegenerate}
          disabled={!modelConfigured || isRegenerating}
          title={modelConfigured ? "Force a fresh LLM call" : "Pick a chat model in Settings first"}
          className="flex items-center gap-1 px-2 py-1 rounded text-xs text-[#2563eb] hover:bg-blue-100 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {isRegenerating
            ? <Loader2 size={12} className="animate-spin" />
            : <RefreshCw size={12} />}
          Regenerate
        </button>
      </div>
      <div className="space-y-2">
        {!modelConfigured ? (
          <EmptyAiState
            icon={<Info size={14} className="text-amber-600" />}
            title="No chat model configured"
            body={emptyHint ?? "Pick one in Settings → AI Chat Model to enable AI-generated insights."}
          />
        ) : isLoading || isRegenerating ? (
          <div className="flex items-center gap-2 text-xs text-gray-500 px-1 py-3">
            <Loader2 size={14} className="animate-spin text-[#2563eb]" />
            {loadingLabel ?? "Generating insights…"}
          </div>
        ) : (data?.insights?.length ?? 0) === 0 ? (
          <EmptyAiState
            icon={<AlertTriangle size={14} className="text-amber-600" />}
            title="No insights produced"
            body="The model returned nothing parseable. Try Regenerate or pick a different model."
          />
        ) : (
          (data?.insights ?? []).map((ins, i) => <AiInsightCard key={i} ins={ins} />)
        )}
      </div>
    </div>
  );
}

export function AiInsightCard({ ins }: { ins: AiInsight }) {
  const borderClass = ins.severity === "high"
    ? "border-red-200"
    : ins.severity === "medium"
      ? "border-amber-200"
      : "border-blue-100";
  return (
    <div className={`bg-white rounded-lg p-3 border ${borderClass}`}>
      <div className="flex items-center gap-2 mb-1">
        {iconForHint(ins.iconHint, ins.severity)}
        <span className="text-xs font-semibold text-gray-800">{ins.title}</span>
        {ins.severity && (
          <span className={`ml-auto text-[10px] uppercase tracking-wide ${
            ins.severity === "high" ? "text-red-600"
              : ins.severity === "medium" ? "text-amber-600"
                : "text-gray-400"
          }`}>{ins.severity}</span>
        )}
      </div>
      <p className="text-xs text-gray-600 leading-relaxed">{ins.body}</p>
    </div>
  );
}

export function EmptyAiState({
  icon, title, body,
}: { icon: React.ReactNode; title: string; body: string }) {
  return (
    <div className="bg-white rounded-lg p-3 border border-blue-100">
      <div className="flex items-center gap-2 mb-1">
        {icon}
        <span className="text-xs font-semibold text-gray-800">{title}</span>
      </div>
      <p className="text-xs text-gray-500 leading-relaxed">{body}</p>
    </div>
  );
}

export function iconForHint(hint: string | undefined, severity: string | undefined): React.ReactNode {
  const sevColor =
    severity === "high" ? "text-red-600"
      : severity === "medium" ? "text-amber-600"
        : "text-gray-500";
  switch (hint) {
    case "consolidation": return <Layers size={14} className={sevColor} />;
    case "risk":          return <AlertTriangle size={14} className={sevColor} />;
    case "quick-win":     return <Zap size={14} className={sevColor} />;
    default:              return <Lightbulb size={14} className={sevColor} />;
  }
}
