import * as React from "react";
import { useState, useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Layers,
  Plus,
  Loader2,
  KeyRound,
  Eye,
  EyeOff,
  Star,
  Trash2,
  RefreshCw,
} from "lucide-react";
import { api } from "@/services/api";
import { EmbeddingModelConfig } from "@/types";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Skeleton } from "@/components/ui/skeleton";
import { SearchableSelect } from "@/components/ui/searchable-select";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

// ── Provider badge (kept in sync with ModelsPage palette) ─────────────────────

const PROVIDER_COLORS: Record<string, string> = {
  anthropic:    "bg-orange-100 text-orange-700 border-orange-200",
  openai:       "bg-emerald-100 text-emerald-700 border-emerald-200",
  azure_openai: "bg-blue-100 text-blue-700 border-blue-200",
  google:       "bg-red-100 text-red-700 border-red-200",
  ollama:       "bg-purple-100 text-purple-700 border-purple-200",
  groq:         "bg-yellow-100 text-yellow-700 border-yellow-200",
};

function ProviderBadge({ providerID, name }: { providerID: string; name?: string }) {
  const color = PROVIDER_COLORS[providerID?.toLowerCase()] ?? "bg-slate-100 text-slate-600 border-slate-200";
  return (
    <span className={cn("px-2 py-0.5 rounded-full text-xs font-semibold border whitespace-nowrap", color)}>
      {name || providerID}
    </span>
  );
}

// ── Embedding-provider catalog (UI-side; the server validates the actual values) ──

interface EmbeddingProviderOption {
  id: string;
  name: string;
  disabled?: boolean;
  disabledReason?: string;
}

const EMBEDDING_PROVIDERS: EmbeddingProviderOption[] = [
  { id: "openai",       name: "OpenAI" },
  { id: "azure_openai", name: "Azure OpenAI" },
  { id: "google",       name: "Google Vertex" },
  { id: "ollama",       name: "Ollama" },
  { id: "anthropic",    name: "Anthropic", disabled: true, disabledReason: "Anthropic has no embedding API" },
];

/** Suggested vector dimensions for well-known embedding model IDs. */
const KNOWN_DIMENSIONS: Record<string, number> = {
  "text-embedding-3-small":  1536,
  "text-embedding-3-large":  3072,
  "text-embedding-ada-002":  1536,
  "text-embedding-004":      768,
  "nomic-embed-text":        768,
  "mxbai-embed-large":       1024,
};

function suggestDimensions(modelID: string): number | undefined {
  const key = modelID.trim().toLowerCase();
  if (!key) return undefined;
  if (KNOWN_DIMENSIONS[key] !== undefined) return KNOWN_DIMENSIONS[key];
  // Fuzzy contains match — handles vendor prefixes like "openai/text-embedding-3-large".
  for (const k of Object.keys(KNOWN_DIMENSIONS)) {
    if (key.includes(k)) return KNOWN_DIMENSIONS[k];
  }
  return undefined;
}

// ── Form types ────────────────────────────────────────────────────────────────

interface RegisterForm {
  providerID: string;
  modelID: string;
  displayName: string;
  apiKey: string;
  baseUrl: string;
  dimensions: string; // string for input control; parse on submit
  enabled: boolean;
}

const defaultRegisterForm: RegisterForm = {
  providerID: "",
  modelID: "",
  displayName: "",
  apiKey: "",
  baseUrl: "",
  dimensions: "",
  enabled: true,
};

// ── Page ──────────────────────────────────────────────────────────────────────

export default function EmbeddingModelsPage() {
  const [registerOpen, setRegisterOpen] = useState(false);
  const [form, setForm] = useState<RegisterForm>(defaultRegisterForm);
  const [showRegisterKey, setShowRegisterKey] = useState(false);
  const [reindexOpen, setReindexOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<EmbeddingModelConfig | null>(null);

  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["embedding-models"] });

  const { data: models, isLoading } = useQuery<EmbeddingModelConfig[]>({
    queryKey: ["embedding-models", "all"],
    queryFn: () => api.embeddingModels.list(),
  });

  const registerMutation = useMutation({
    mutationFn: (body: any) => api.embeddingModels.upsert(body),
    onSuccess: () => {
      toast.success("Embedding model registered");
      invalidate();
      setRegisterOpen(false);
      setForm(defaultRegisterForm);
      setShowRegisterKey(false);
    },
    onError: (err: any) => toast.error(err.message || "Failed to register embedding model"),
  });

  const setDefaultMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.embeddingModels.setDefault(providerID, modelID),
    onSuccess: () => { toast.success("Default embedding model updated"); invalidate(); },
    onError: (err: any) => toast.error(err.message || "Failed to set default"),
  });

  const enableMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.embeddingModels.setEnabled(providerID, modelID, true),
    onSuccess: () => { toast.success("Model enabled"); invalidate(); },
    onError: (err: any) => toast.error(err.message || "Failed to enable"),
  });

  const disableMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.embeddingModels.setEnabled(providerID, modelID, false),
    onSuccess: () => { toast.success("Model disabled"); invalidate(); },
    onError: (err: any) => toast.error(err.message || "Failed to disable"),
  });

  const deleteMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.embeddingModels.delete(providerID, modelID),
    onSuccess: () => { toast.success("Embedding model removed"); invalidate(); setDeleteTarget(null); },
    onError: (err: any) => toast.error(err.message || "Failed to delete"),
  });

  const reindexMutation = useMutation({
    mutationFn: () => api.tools.reindex(),
    onError: (err: any) => toast.error(err.message || "Reindex failed"),
  });

  const providerOptions = useMemo(
    () => EMBEDDING_PROVIDERS.map((p) => ({
      value: p.id,
      label: p.name,
      sublabel: p.disabled ? (p.disabledReason ?? "Unavailable") : p.id,
    })),
    []
  );

  const handleRegister = () => {
    const dims = form.dimensions.trim() ? parseInt(form.dimensions, 10) : undefined;
    if (!form.providerID || !form.modelID.trim()) {
      toast.error("Provider and model ID are required");
      return;
    }
    if (form.providerID === "anthropic") {
      toast.error("Anthropic has no embedding API");
      return;
    }
    if (dims === undefined || Number.isNaN(dims) || dims <= 0) {
      toast.error("Embedding dimensions must be a positive integer");
      return;
    }
    registerMutation.mutate({
      providerID: form.providerID,
      modelID: form.modelID.trim(),
      displayName: form.displayName.trim() || form.modelID.trim(),
      apiKey: form.apiKey || undefined,
      baseUrl: form.baseUrl || undefined,
      dimensions: dims,
      enabled: form.enabled,
    });
  };

  const handleConfirmReindex = async () => {
    const toastId = toast.loading("Reindexing tool embeddings…");
    try {
      const res: any = await reindexMutation.mutateAsync();
      const count = res?.toolsEmbedded;
      toast.success(
        typeof count === "number"
          ? `Reindexed ${count} tool${count === 1 ? "" : "s"}`
          : "Tool embeddings reindexed",
        { id: toastId }
      );
    } catch (err: any) {
      toast.error(err?.message || "Reindex failed", { id: toastId });
    } finally {
      setReindexOpen(false);
    }
  };

  const displayed = models ?? [];

  return (
    <div className="space-y-4">
      {/* ── Header ──────────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mb-2">
        <div>
          <h1 className="text-xl font-semibold text-[#123262]">Embedding Models</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Vector embedding providers used for semantic tool retrieval
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            className="h-9 px-4 text-sm font-medium"
            disabled={displayed.length === 0 || !displayed.some((m) => m.defaultModel)}
            onClick={() => setReindexOpen(true)}
          >
            <RefreshCw size={14} className="mr-1.5" /> Reindex tools
          </Button>
          <Button
            onClick={() => setRegisterOpen(true)}
            className="h-9 px-4 text-sm font-medium text-white rounded-lg"
            style={{ background: "#005CB9" }}
          >
            <Plus size={15} className="mr-1.5" /> Register Embedding Model
          </Button>
        </div>
      </div>

      {/* ── Table ───────────────────────────────────────────────────────────── */}
      <div className="overflow-hidden border border-gray-200 rounded-xl bg-white">
        {isLoading ? (
          <div className="p-6 space-y-3">
            {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
          </div>
        ) : displayed.length === 0 ? (
          <div className="text-center py-16 text-slate-500">
            <Layers size={32} className="mx-auto mb-3 opacity-30" />
            <p className="text-sm font-medium text-slate-600">No embedding models registered</p>
            <p className="text-xs text-slate-400 mt-1 max-w-sm mx-auto">
              Register an embedding model to enable smart tool retrieval. Until then, the
              agent runs without semantic routing.
            </p>
            <Button
              className="mt-4 text-white"
              style={{ background: "#005CB9" }}
              onClick={() => setRegisterOpen(true)}
            >
              <Plus size={14} className="mr-1.5" /> Register Embedding Model
            </Button>
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-gray-50">
                <TableHead className="w-[40px]" />
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Provider</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Model ID</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Name</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Dimensions</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {displayed.map((m) => {
                const isDefault = !!m.defaultModel;
                return (
                  <TableRow key={`${m.providerID}/${m.modelID}`}>
                    <TableCell className="pr-0">
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <button
                            type="button"
                            className={cn(
                              "p-1 rounded transition-colors",
                              isDefault ? "text-amber-500" : "text-slate-300 hover:text-amber-500"
                            )}
                            disabled={setDefaultMutation.isPending || isDefault}
                            onClick={() =>
                              setDefaultMutation.mutate({ providerID: m.providerID, modelID: m.modelID })
                            }
                          >
                            <Star size={15} fill={isDefault ? "currentColor" : "none"} />
                          </button>
                        </TooltipTrigger>
                        <TooltipContent side="top">
                          <p>{isDefault ? "Current system default" : "Set as system default"}</p>
                        </TooltipContent>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      <ProviderBadge providerID={m.providerID} name={m.providerName} />
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-500 max-w-[220px] truncate">
                      {m.modelID}
                    </TableCell>
                    <TableCell className="text-sm font-medium max-w-[220px]">
                      <div className="flex items-center gap-1.5">
                        <span className="truncate">{m.displayName}</span>
                        {m.hasKey && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="text-green-600 cursor-default">
                                <KeyRound size={11} />
                              </span>
                            </TooltipTrigger>
                            <TooltipContent side="top"><p>API key stored (encrypted)</p></TooltipContent>
                          </Tooltip>
                        )}
                        {isDefault && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-bold border bg-amber-50 text-amber-700 border-amber-200">
                            DEFAULT
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-600">
                      {m.embeddingDimensions ?? <span className="text-slate-400">—</span>}
                    </TableCell>
                    <TableCell>
                      <span className={cn(
                        "px-2 py-0.5 rounded-full text-xs font-semibold border",
                        m.enabled
                          ? "bg-green-100 text-green-700 border-green-200"
                          : "bg-slate-100 text-slate-500 border-slate-200"
                      )}>
                        {m.enabled ? "Enabled" : "Disabled"}
                      </span>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        {m.enabled ? (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                size="sm" variant="outline"
                                className="h-7 text-xs text-red-600 border-red-200 hover:bg-red-50"
                                disabled={disableMutation.isPending}
                                onClick={() => disableMutation.mutate({ providerID: m.providerID, modelID: m.modelID })}
                              >
                                Disable
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent side="top"><p>Disable this embedding model</p></TooltipContent>
                          </Tooltip>
                        ) : (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                size="sm" variant="outline"
                                className="h-7 text-xs text-green-700 border-green-200 hover:bg-green-50"
                                disabled={enableMutation.isPending}
                                onClick={() => enableMutation.mutate({ providerID: m.providerID, modelID: m.modelID })}
                              >
                                Enable
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent side="top"><p>Enable this embedding model</p></TooltipContent>
                          </Tooltip>
                        )}
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="sm" variant="outline"
                              className="h-7 text-xs text-slate-500 hover:text-red-600 hover:border-red-200 hover:bg-red-50"
                              onClick={() => setDeleteTarget(m)}
                            >
                              <Trash2 size={11} />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent side="top"><p>Remove this embedding model</p></TooltipContent>
                        </Tooltip>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {/* ── Register Dialog ──────────────────────────────────────────────────── */}
      <Dialog
        open={registerOpen}
        onOpenChange={(open) => {
          setRegisterOpen(open);
          if (!open) { setForm(defaultRegisterForm); setShowRegisterKey(false); }
        }}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Register Embedding Model</DialogTitle>
            <DialogDescription>
              Embedding models power semantic tool retrieval. The API key is encrypted (AES-256-GCM) before storage.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            {/* Provider */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">Provider *</label>
              <SearchableSelect
                options={providerOptions}
                value={form.providerID}
                onValueChange={(pid) => setForm((f) => ({ ...f, providerID: pid }))}
                placeholder="Select provider…"
                searchPlaceholder="Search providers…"
              />
              {form.providerID === "anthropic" && (
                <p className="mt-1 text-[11px] text-red-600">Anthropic has no embedding API.</p>
              )}
            </div>

            {/* Model ID */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">Model ID *</label>
              <input
                type="text"
                className="w-full h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground font-mono"
                placeholder="e.g. text-embedding-3-large"
                value={form.modelID}
                onChange={(e) => {
                  const next = e.target.value;
                  setForm((f) => {
                    const suggested = suggestDimensions(next);
                    return {
                      ...f,
                      modelID: next,
                      // Only auto-fill dimensions if user hasn't typed anything custom yet.
                      dimensions:
                        suggested && (f.dimensions === "" || KNOWN_DIMENSIONS_VALUES.has(parseInt(f.dimensions, 10)))
                          ? String(suggested)
                          : f.dimensions,
                    };
                  });
                }}
              />
            </div>

            {/* Display Name */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">Display Name</label>
              <input
                type="text"
                className="w-full h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground"
                placeholder={form.modelID || "Human-friendly name"}
                value={form.displayName}
                onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
              />
            </div>

            {/* API Key */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">
                <KeyRound size={12} className="inline mr-1" />
                API Key
                <span className="ml-1 font-normal text-slate-400">(encrypted at rest)</span>
              </label>
              <div className="relative">
                <input
                  type={showRegisterKey ? "text" : "password"}
                  className="w-full h-9 rounded-md border border-input bg-background px-3 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground"
                  placeholder={form.providerID ? `${form.providerID} API key…` : "API key…"}
                  value={form.apiKey}
                  onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))}
                  autoComplete="off"
                />
                <button
                  type="button"
                  tabIndex={-1}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  onClick={() => setShowRegisterKey((v) => !v)}
                >
                  {showRegisterKey ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
            </div>

            {/* Base URL */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">
                Base URL
                <span className="ml-1 font-normal text-slate-400">(optional — Azure, Ollama, self-hosted)</span>
              </label>
              <input
                type="text"
                className="w-full h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground"
                placeholder="https://your-endpoint.openai.azure.com"
                value={form.baseUrl}
                onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))}
              />
            </div>

            {/* Dimensions */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">
                Embedding Dimensions *
                <span className="ml-1 font-normal text-slate-400">
                  (1536, 3072, 768, 1024…)
                </span>
              </label>
              <input
                type="number"
                min={1}
                step={1}
                className="w-full h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground font-mono"
                placeholder="e.g. 1536"
                value={form.dimensions}
                onChange={(e) => setForm((f) => ({ ...f, dimensions: e.target.value }))}
              />
            </div>

            {/* Enabled */}
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <Checkbox
                checked={form.enabled}
                onCheckedChange={(v) => setForm((f) => ({ ...f, enabled: !!v }))}
              />
              <span className="text-slate-700">Enabled</span>
            </label>
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => { setRegisterOpen(false); setForm(defaultRegisterForm); }}
            >
              Cancel
            </Button>
            <Button
              className="text-white"
              style={{ background: "#005CB9" }}
              disabled={
                registerMutation.isPending ||
                !form.providerID ||
                form.providerID === "anthropic" ||
                !form.modelID.trim() ||
                !form.dimensions.trim()
              }
              onClick={handleRegister}
            >
              {registerMutation.isPending && <Loader2 size={14} className="animate-spin mr-2" />}
              Register
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Reindex confirm dialog ──────────────────────────────────────────── */}
      <Dialog open={reindexOpen} onOpenChange={setReindexOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Reindex tool embeddings</DialogTitle>
            <DialogDescription>
              Re-embed all enabled tools using the current default embedding model.
              Tool retrieval may be briefly degraded during reindex. Continue?
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setReindexOpen(false)}>Cancel</Button>
            <Button
              className="text-white"
              style={{ background: "#005CB9" }}
              disabled={reindexMutation.isPending}
              onClick={handleConfirmReindex}
            >
              {reindexMutation.isPending && <Loader2 size={14} className="animate-spin mr-2" />}
              Reindex
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Delete confirm dialog ───────────────────────────────────────────── */}
      <Dialog open={!!deleteTarget} onOpenChange={(open) => { if (!open) setDeleteTarget(null); }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Remove embedding model</DialogTitle>
            <DialogDescription>
              {deleteTarget && (
                <>
                  Permanently remove{" "}
                  <span className="font-mono text-slate-700">
                    {deleteTarget.providerID} / {deleteTarget.modelID}
                  </span>
                  ? Existing tool embeddings created with this model remain in the index until
                  you reindex.
                </>
              )}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>Cancel</Button>
            <Button
              className="text-white bg-red-600 hover:bg-red-700"
              disabled={deleteMutation.isPending}
              onClick={() => {
                if (deleteTarget)
                  deleteMutation.mutate({
                    providerID: deleteTarget.providerID,
                    modelID: deleteTarget.modelID,
                  });
              }}
            >
              {deleteMutation.isPending && <Loader2 size={14} className="animate-spin mr-2" />}
              Remove
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

const KNOWN_DIMENSIONS_VALUES = new Set<number>(Object.values(KNOWN_DIMENSIONS));
