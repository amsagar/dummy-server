import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { AlertTriangle, X } from "lucide-react";
import { api } from "@/services/api";
import type { EmbeddingModelConfig } from "@/types";

const DISMISS_KEY = "embeddingSetupBanner.dismissed";

/**
 * Inline banner shown on tool-related pages when smart tool retrieval is not
 * yet usable — i.e. either no embedding models are registered, or none is
 * marked as the system default. Dismissible per-browser via localStorage.
 */
export function EmbeddingSetupBanner() {
  const [dismissed, setDismissed] = useState<boolean>(
    () => localStorage.getItem(DISMISS_KEY) === "1"
  );

  const { data, isLoading } = useQuery<EmbeddingModelConfig[]>({
    queryKey: ["embedding-models", "all"],
    queryFn: () => api.embeddingModels.list(),
    staleTime: 30_000,
  });

  if (dismissed || isLoading) return null;

  const list = data ?? [];
  const needsSetup = list.length === 0 || !list.some((m) => m.defaultModel);
  if (!needsSetup) return null;

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, "1");
    setDismissed(true);
  };

  return (
    <div className="flex items-start gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-amber-900">
      <AlertTriangle size={16} className="mt-0.5 shrink-0 text-amber-600" />
      <div className="flex-1 text-sm">
        <span className="font-medium">Smart tool retrieval is disabled</span>
        <span className="text-amber-800"> — register an embedding model to enable semantic tool routing. </span>
        <Link
          to="/embedding-models"
          className="font-semibold underline underline-offset-2 hover:text-amber-950"
        >
          Set up &rarr;
        </Link>
      </div>
      <button
        type="button"
        aria-label="Dismiss"
        onClick={dismiss}
        className="shrink-0 rounded p-0.5 text-amber-600 hover:bg-amber-100 hover:text-amber-900"
      >
        <X size={14} />
      </button>
    </div>
  );
}
