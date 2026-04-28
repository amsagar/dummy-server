import * as React from "react";
import { useState, useMemo, useRef, useEffect } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Cpu, Plus, Loader2, KeyRound, Eye, EyeOff, ChevronDown, ChevronRight, Pencil, DollarSign, Info, Trash2 } from "lucide-react";
import { api } from "@/services/api";
import { ModelConfig, ProviderEntry } from "@/types";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Skeleton } from "@/components/ui/skeleton";
import { SearchableSelect } from "@/components/ui/searchable-select";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

// ── Provider badge ────────────────────────────────────────────────────────────

const PROVIDER_COLORS: Record<string, string> = {
  anthropic:    "bg-orange-100 text-orange-700 border-orange-200",
  openai:       "bg-emerald-100 text-emerald-700 border-emerald-200",
  azure_openai: "bg-blue-100 text-blue-700 border-blue-200",
  google:       "bg-red-100 text-red-700 border-red-200",
  ollama:       "bg-purple-100 text-purple-700 border-purple-200",
  groq:         "bg-yellow-100 text-yellow-700 border-yellow-200",
};

const PROVIDER_GROUP_COLORS: Record<string, string> = {
  anthropic:    "bg-orange-50/60 border-orange-100",
  openai:       "bg-emerald-50/60 border-emerald-100",
  azure_openai: "bg-blue-50/60 border-blue-100",
  google:       "bg-red-50/60 border-red-100",
  ollama:       "bg-purple-50/60 border-purple-100",
  groq:         "bg-yellow-50/60 border-yellow-100",
};

function ProviderBadge({ providerID, name }: { providerID: string; name?: string }) {
  const color = PROVIDER_COLORS[providerID?.toLowerCase()] ?? "bg-slate-100 text-slate-600 border-slate-200";
  return (
    <span className={cn("px-2 py-0.5 rounded-full text-xs font-semibold border whitespace-nowrap", color)}>
      {name || providerID}
    </span>
  );
}

function CostCell({ input, output }: { input?: number; output?: number }) {
  if (!input && !output) return <span className="text-slate-400 text-xs">—</span>;
  return (
    <div className="text-[10px] font-mono text-slate-500 space-y-0.5">
      {input  != null && <div className="flex items-center gap-0.5"><DollarSign size={8} />{input}/M in</div>}
      {output != null && <div className="flex items-center gap-0.5"><DollarSign size={8} />{output}/M out</div>}
    </div>
  );
}

// ── Model multi-select dropdown ───────────────────────────────────────────────

interface ModelDropdownProps {
  models: { id: string; name: string }[];
  selected: string[];
  onToggle: (id: string) => void;
  onClear: () => void;
  disabled?: boolean;
}

function ModelDropdown({ models, selected, onToggle, onClear, disabled }: ModelDropdownProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  useEffect(() => { if (disabled) { setOpen(false); setSearch(''); } }, [disabled]);

  const filtered = search.trim()
    ? models.filter(m => m.name.toLowerCase().includes(search.toLowerCase()) || m.id.toLowerCase().includes(search.toLowerCase()))
    : models;

  const label = selected.length === 0
    ? (disabled ? "Select a provider first" : "Select models…")
    : selected.length === 1
      ? (models.find(m => m.id === selected[0])?.name ?? selected[0])
      : `${selected.length} models selected`;

  return (
    <div ref={ref} className={cn("relative", disabled && "opacity-50 pointer-events-none")}>
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        className={cn(
          "w-full h-9 flex items-center justify-between gap-2 rounded-md border border-input bg-background px-3 text-sm transition-colors",
          "hover:border-slate-400 focus:outline-none focus:ring-2 focus:ring-ring",
          open && "border-ring ring-2 ring-ring"
        )}
      >
        <span className={cn("truncate", selected.length === 0 ? "text-muted-foreground" : "text-slate-800")}>
          {label}
        </span>
        <ChevronDown size={14} className={cn("shrink-0 text-slate-400 transition-transform", open && "rotate-180")} />
      </button>

      {open && (
        <div className="absolute z-50 mt-1 w-full rounded-md border bg-white shadow-lg">
          <div className="flex items-center gap-2 px-3 py-2 border-b bg-slate-50">
            <svg className="w-3.5 h-3.5 text-slate-400 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              autoFocus
              type="text"
              className="flex-1 text-xs bg-transparent outline-none placeholder:text-slate-400"
              placeholder="Search models…"
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
            {selected.length > 0 && (
              <button
                className="text-xs text-slate-400 hover:text-slate-600 shrink-0"
                onClick={(e) => { e.stopPropagation(); onClear(); }}
              >
                Clear
              </button>
            )}
          </div>
          <div className="max-h-52 overflow-y-auto">
            {filtered.length === 0 ? (
              <div className="text-xs text-slate-400 text-center py-4">No models found</div>
            ) : (
              filtered.map(m => {
                const checked = selected.includes(m.id);
                return (
                  <label
                    key={m.id}
                    className={cn(
                      "flex items-center gap-2.5 px-3 py-2 cursor-pointer hover:bg-slate-50 transition-colors",
                      checked && "bg-blue-50"
                    )}
                  >
                    <Checkbox checked={checked} onCheckedChange={() => onToggle(m.id)} />
                    <span className="flex-1 min-w-0">
                      <span className="text-sm font-medium text-slate-800 block truncate">{m.name}</span>
                      <span className="text-[10px] font-mono text-slate-400">{m.id}</span>
                    </span>
                  </label>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ── Types ─────────────────────────────────────────────────────────────────────

interface RegisterForm {
  providerID: string;
  selectedModelIDs: string[];
  apiKey: string;
  baseUrl: string;
  enabled: boolean;
}

interface EditForm {
  apiKey: string;
  baseUrl: string;
  enabled: boolean;
}

const defaultRegisterForm: RegisterForm = { providerID: '', selectedModelIDs: [], apiKey: '', baseUrl: '', enabled: true };
const defaultEditForm: EditForm = { apiKey: '', baseUrl: '', enabled: true };

// ── Page ──────────────────────────────────────────────────────────────────────

export default function ModelsPage() {
  const [registerOpen, setRegisterOpen] = useState(false);
  const [form, setForm] = useState<RegisterForm>(defaultRegisterForm);
  const [showRegisterKey, setShowRegisterKey] = useState(false);

  const [editTarget, setEditTarget] = useState<ModelConfig | null>(null);
  const [editForm, setEditForm] = useState<EditForm>(defaultEditForm);
  const [showEditKey, setShowEditKey] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<ModelConfig | null>(null);

  // Track which provider groups are collapsed (all expanded by default)
  const [collapsedProviders, setCollapsedProviders] = useState<Set<string>>(new Set());

  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['models'] });

  const { data: allModels, isLoading } = useQuery<ModelConfig[]>({
    queryKey: ['models', 'all'],
    queryFn: () => api.get('/models'),
  });

  const { data: providers } = useQuery<ProviderEntry[]>({
    queryKey: ['providers'],
    queryFn: () => api.get('/providers'),
  });

  const registerMutation = useMutation({
    mutationFn: (body: any) => api.post('/models', body),
    onError: (err: any) => toast.error(err.message || "Failed to register model"),
  });

  const editMutation = useMutation({
    mutationFn: (body: any) => api.post('/models', body),
    onSuccess: () => { toast.success("Model updated"); invalidate(); setEditTarget(null); },
    onError: (err: any) => toast.error(err.message || "Failed to update model"),
  });

  const enableMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.post(`/models/${providerID}/${modelID}/enable`),
    onSuccess: () => { toast.success("Model enabled"); invalidate(); },
    onError: (err: any) => toast.error(err.message || "Failed to enable"),
  });

  const disableMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.post(`/models/${providerID}/${modelID}/disable`),
    onSuccess: () => { toast.success("Model disabled"); invalidate(); },
    onError: (err: any) => toast.error(err.message || "Failed to disable"),
  });

  const deleteMutation = useMutation({
    mutationFn: ({ providerID, modelID }: { providerID: string; modelID: string }) =>
      api.delete(`/models/${providerID}/${modelID}`),
    onSuccess: () => { toast.success("Model removed"); invalidate(); setDeleteTarget(null); },
    onError: (err: any) => toast.error(err.message || "Failed to remove model"),
  });

  const providerOptions = useMemo(() =>
    (providers ?? []).map(p => ({ value: p.id, label: p.name, sublabel: p.id })),
    [providers]
  );

  const modelsForProvider = useMemo(() => {
    if (!form.providerID || !providers) return [];
    return providers.find(p => p.id === form.providerID)?.models ?? [];
  }, [form.providerID, providers]);

  // Per-provider credential status derived from registered models
  const providerCredentials = useMemo(() => {
    const map: Record<string, { hasKey: boolean; baseUrl?: string; providerName?: string }> = {};
    (allModels ?? []).forEach(m => {
      if (!map[m.providerID]) {
        map[m.providerID] = { hasKey: false, providerName: m.providerName };
      }
      if (m.hasKey && !map[m.providerID].hasKey) {
        map[m.providerID].hasKey = true;
        map[m.providerID].baseUrl = m.baseUrl;
      }
    });
    return map;
  }, [allModels]);

  // Credential info for the currently-selected provider in the register form
  const selectedProviderCreds = form.providerID ? providerCredentials[form.providerID] : undefined;
  const willReuseCredentials = !!selectedProviderCreds?.hasKey && !form.apiKey;

  // Group chat models by provider (exclude embeddings — they have their own page)
  const groupedModels = useMemo(() => {
    const groups = new Map<string, ModelConfig[]>();
    (allModels ?? []).filter(m => m.modelKind !== 'embedding').forEach(m => {
      if (!groups.has(m.providerID)) groups.set(m.providerID, []);
      groups.get(m.providerID)!.push(m);
    });
    return groups;
  }, [allModels]);

  const openRegisterForProvider = (pid: string) => {
    const creds = providerCredentials[pid];
    setForm({
      ...defaultRegisterForm,
      providerID: pid,
      baseUrl: creds?.baseUrl ?? '',
    });
    setShowRegisterKey(false);
    setRegisterOpen(true);
  };

  const toggleProviderCollapse = (pid: string) => {
    setCollapsedProviders(prev => {
      const next = new Set(prev);
      next.has(pid) ? next.delete(pid) : next.add(pid);
      return next;
    });
  };

  const handleRegister = async () => {
    const { providerID, selectedModelIDs, apiKey, baseUrl, enabled } = form;
    let count = 0;
    for (const modelID of selectedModelIDs) {
      await registerMutation.mutateAsync({
        providerID, modelID,
        apiKey: apiKey || undefined,
        baseUrl: baseUrl || undefined,
        enabled,
      });
      count++;
    }
    toast.success(`${count} model${count > 1 ? 's' : ''} registered`);
    invalidate();
    setRegisterOpen(false);
    setForm(defaultRegisterForm);
    setShowRegisterKey(false);
  };

  const openEdit = (m: ModelConfig) => {
    setEditTarget(m);
    setEditForm({ apiKey: '', baseUrl: m.baseUrl ?? '', enabled: m.enabled });
    setShowEditKey(false);
  };

  const handleEdit = () => {
    if (!editTarget) return;
    editMutation.mutate({
      providerID: editTarget.providerID,
      modelID: editTarget.modelID,
      apiKey: editForm.apiKey || undefined,
      baseUrl: editForm.baseUrl || undefined,
      enabled: editForm.enabled,
    });
  };

  return (
    <div className="space-y-4">
      {/* ── Header ──────────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mb-2">
        <div>
          <h1 className="text-xl font-semibold text-[#123262]">AI Models</h1>
          <p className="text-sm text-gray-500 mt-0.5">Registered LLM providers and model configurations</p>
        </div>
        <Button onClick={() => { setForm(defaultRegisterForm); setShowRegisterKey(false); setRegisterOpen(true); }} className="h-9 px-4 text-sm font-medium text-white rounded-lg" style={{ background: "#005CB9" }}>
          <Plus size={15} className="mr-1.5" /> Register Model
        </Button>
      </div>

      {/* ── Table ───────────────────────────────────────────────────────────── */}
      <div className="overflow-hidden border border-gray-200 rounded-xl bg-white">
        {isLoading ? (
          <div className="p-6 space-y-3">
            {[1,2,3,4,5].map(i => <Skeleton key={i} className="h-10 w-full" />)}
          </div>
        ) : groupedModels.size === 0 ? (
          <div className="text-center py-16 text-slate-400">
            <Cpu size={32} className="mx-auto mb-3 opacity-30" />
            <p className="text-sm">No models registered. Click "Register Model" to add one.</p>
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-gray-50">
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider w-6 pl-3 pr-0"></TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider w-40">Provider</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Model ID</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Name</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Cost</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</TableHead>
                <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {Array.from(groupedModels.entries()).map(([pid, models]) => {
                const collapsed = collapsedProviders.has(pid);
                const creds = providerCredentials[pid];
                const headerColor = PROVIDER_GROUP_COLORS[pid?.toLowerCase()] ?? "bg-slate-50 border-slate-200";
                const firstModel = models[0];

                return (
                  <React.Fragment key={pid}>
                    {/* Provider group header row */}
                    <TableRow
                      className={cn("border-b cursor-pointer select-none hover:brightness-95 transition-all", headerColor)}
                      onClick={() => toggleProviderCollapse(pid)}
                    >
                      <TableCell className="py-2.5 pl-3 pr-0 w-6">
                        <ChevronRight
                          size={14}
                          className={cn("text-slate-400 transition-transform duration-150", !collapsed && "rotate-90")}
                        />
                      </TableCell>
                      <TableCell className="py-2.5" colSpan={2}>
                        <div className="flex items-center gap-2.5">
                          <ProviderBadge providerID={pid} name={firstModel?.providerName} />
                          <span className="text-xs text-slate-500 font-medium">
                            {models.length} model{models.length !== 1 ? 's' : ''}
                          </span>
                          {creds?.hasKey && (
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <span className="flex items-center gap-1 text-[10px] text-green-700 bg-green-100 border border-green-200 rounded-full px-2 py-0.5 cursor-default">
                                  <KeyRound size={9} /> Credentials stored
                                </span>
                              </TooltipTrigger>
                              <TooltipContent side="right">
                                <p>API key is stored for this provider. New models can be added without re-entering credentials.</p>
                              </TooltipContent>
                            </Tooltip>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="py-2.5" />
                      <TableCell className="py-2.5">
                        <span className="text-xs text-slate-400">
                          {models.filter(m => m.enabled).length}/{models.length} enabled
                        </span>
                      </TableCell>
                      <TableCell className="py-2.5 text-right pr-4" colSpan={2}>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-6 text-[11px] px-2.5 gap-1"
                          onClick={(e) => { e.stopPropagation(); openRegisterForProvider(pid); }}
                        >
                          <Plus size={10} /> Add Model
                        </Button>
                      </TableCell>
                    </TableRow>

                    {/* Model rows */}
                    {!collapsed && models.map((m) => (
                      <TableRow key={`${m.providerID}/${m.modelID}`} className="hover:bg-slate-50/50">
                        <TableCell className="py-2.5 w-6" />
                        <TableCell className="py-2.5 w-40" />
                        <TableCell className="font-mono text-xs text-slate-500 max-w-[180px] truncate pl-2" title={m.modelID}>
                          {m.modelID}
                        </TableCell>
                        <TableCell className="text-sm font-medium max-w-[200px] truncate" title={m.displayName}>
                          <div className="flex items-center gap-1.5">
                            {m.displayName}
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
                          </div>
                        </TableCell>
                        <TableCell>
                          <CostCell input={m.costInput} output={m.costOutput} />
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
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Button
                                  size="sm" variant="outline"
                                  className="h-7 text-xs"
                                  onClick={() => openEdit(m)}
                                >
                                  <Pencil size={11} className="mr-1" /> Edit
                                </Button>
                              </TooltipTrigger>
                              <TooltipContent side="top"><p>Edit model configuration</p></TooltipContent>
                            </Tooltip>
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
                                <TooltipContent side="top"><p>Disable this model</p></TooltipContent>
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
                                <TooltipContent side="top"><p>Enable this model</p></TooltipContent>
                              </Tooltip>
                            )}
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Button
                                  size="sm" variant="outline"
                                  className="h-7 w-7 p-0 text-slate-400 border-slate-200 hover:text-red-600 hover:border-red-200 hover:bg-red-50"
                                  onClick={() => setDeleteTarget(m)}
                                >
                                  <Trash2 size={12} />
                                </Button>
                              </TooltipTrigger>
                              <TooltipContent side="top"><p>Remove model</p></TooltipContent>
                            </Tooltip>
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </React.Fragment>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {/* ── Register Dialog ──────────────────────────────────────────────────── */}
      <Dialog open={registerOpen} onOpenChange={(open) => {
        setRegisterOpen(open);
        if (!open) { setForm(defaultRegisterForm); setShowRegisterKey(false); }
      }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Register Model</DialogTitle>
            <DialogDescription>
              Select a provider and one or more models to register. Keys are encrypted (AES-256-GCM) before storage.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            {/* Provider */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">Provider *</label>
              <SearchableSelect
                options={providerOptions}
                value={form.providerID}
                onValueChange={(pid) => {
                  const creds = providerCredentials[pid];
                  setForm(f => ({
                    ...f,
                    providerID: pid,
                    selectedModelIDs: [],
                    baseUrl: creds?.baseUrl ?? f.baseUrl,
                  }));
                }}
                placeholder="Search and select provider…"
                searchPlaceholder="Search providers…"
              />
            </div>

            {/* Credential reuse banner */}
            {selectedProviderCreds?.hasKey && (
              <div className="flex items-start gap-2.5 rounded-lg border border-green-200 bg-green-50 px-3 py-2.5">
                <Info size={14} className="text-green-600 mt-0.5 shrink-0" />
                <div className="text-xs text-green-800 leading-relaxed">
                  <span className="font-semibold">Credentials already stored</span> for{" "}
                  {selectedProviderCreds.providerName || form.providerID}.{" "}
                  Leave the API key blank to reuse them for all selected models.
                </div>
              </div>
            )}

            {/* Model multi-select dropdown */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">
                Models *
                {form.selectedModelIDs.length > 0 && (
                  <span className="ml-2 text-blue-600 font-semibold">{form.selectedModelIDs.length} selected</span>
                )}
              </label>
              <ModelDropdown
                models={modelsForProvider}
                selected={form.selectedModelIDs}
                onToggle={(id) => setForm(f => ({
                  ...f,
                  selectedModelIDs: f.selectedModelIDs.includes(id)
                    ? f.selectedModelIDs.filter(m => m !== id)
                    : [...f.selectedModelIDs, id],
                }))}
                onClear={() => setForm(f => ({ ...f, selectedModelIDs: [] }))}
                disabled={!form.providerID}
              />
            </div>

            {/* API Key */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">
                <KeyRound size={12} className="inline mr-1" />
                API Key
                {selectedProviderCreds?.hasKey ? (
                  <span className="ml-1 font-normal text-green-600">(optional — existing key will be reused)</span>
                ) : (
                  <span className="ml-1 font-normal text-slate-400">(encrypted at rest)</span>
                )}
              </label>
              <div className="relative">
                <input
                  type={showRegisterKey ? "text" : "password"}
                  className={cn(
                    "w-full h-9 rounded-md border bg-background px-3 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground",
                    willReuseCredentials ? "border-green-300 focus:ring-green-400" : "border-input"
                  )}
                  placeholder={
                    willReuseCredentials
                      ? "Leave blank to reuse existing key…"
                      : form.providerID
                        ? `${form.providerID} API key…`
                        : "API key…"
                  }
                  value={form.apiKey}
                  onChange={(e) => setForm(f => ({ ...f, apiKey: e.target.value }))}
                  autoComplete="off"
                />
                <button type="button" tabIndex={-1}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  onClick={() => setShowRegisterKey(v => !v)}
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
                onChange={(e) => setForm(f => ({ ...f, baseUrl: e.target.value }))}
              />
            </div>

            {/* Enabled */}
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <Checkbox
                checked={form.enabled}
                onCheckedChange={(v) => setForm(f => ({ ...f, enabled: !!v }))}
              />
              <span className="text-slate-700">Enabled</span>
            </label>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => { setRegisterOpen(false); setForm(defaultRegisterForm); }}>Cancel</Button>
            <Button
              className="text-white" style={{ background: "#005CB9" }}
              disabled={registerMutation.isPending || !form.providerID || form.selectedModelIDs.length === 0}
              onClick={handleRegister}
            >
              {registerMutation.isPending && <Loader2 size={14} className="animate-spin mr-2" />}
              Register{form.selectedModelIDs.length > 1 ? ` (${form.selectedModelIDs.length})` : ''}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Edit Dialog ───────────────────────────────────────────────────────── */}
      <Dialog open={!!editTarget} onOpenChange={(open) => { if (!open) setEditTarget(null); }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Edit Model</DialogTitle>
            <DialogDescription>
              {editTarget && <span className="font-mono text-slate-600">{editTarget.providerID} / {editTarget.modelID}</span>}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            {/* API Key */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">
                <KeyRound size={12} className="inline mr-1" />
                {editTarget?.hasKey ? "Replace API Key" : "API Key"}
                <span className="ml-1 font-normal text-slate-400">
                  {editTarget?.hasKey ? "(leave blank to keep existing)" : "(encrypted at rest)"}
                </span>
              </label>
              <div className="relative">
                <input
                  type={showEditKey ? "text" : "password"}
                  className="w-full h-9 rounded-md border border-input bg-background px-3 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground"
                  placeholder={editTarget?.hasKey ? "Enter new key to replace…" : "API key…"}
                  value={editForm.apiKey}
                  onChange={(e) => setEditForm(f => ({ ...f, apiKey: e.target.value }))}
                  autoComplete="off"
                />
                <button type="button" tabIndex={-1}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  onClick={() => setShowEditKey(v => !v)}
                >
                  {showEditKey ? <EyeOff size={14} /> : <Eye size={14} />}
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
                value={editForm.baseUrl}
                onChange={(e) => setEditForm(f => ({ ...f, baseUrl: e.target.value }))}
              />
            </div>

            {/* Enabled */}
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <Checkbox
                checked={editForm.enabled}
                onCheckedChange={(v) => setEditForm(f => ({ ...f, enabled: !!v }))}
              />
              <span className="text-slate-700">Enabled</span>
            </label>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setEditTarget(null)}>Cancel</Button>
            <Button
              className="text-white" style={{ background: "#005CB9" }}
              disabled={editMutation.isPending}
              onClick={handleEdit}
            >
              {editMutation.isPending && <Loader2 size={14} className="animate-spin mr-2" />}
              Save
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Delete Confirmation Dialog ────────────────────────────────────────── */}
      <Dialog open={!!deleteTarget} onOpenChange={(open) => { if (!open) setDeleteTarget(null); }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Remove Model</DialogTitle>
            <DialogDescription>
              This will permanently remove{" "}
              <span className="font-mono font-semibold text-slate-700">
                {deleteTarget?.displayName || deleteTarget?.modelID}
              </span>{" "}
              from the registry. The model can be re-registered at any time.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>Cancel</Button>
            <Button
              variant="outline"
              className="text-red-600 border-red-200 hover:bg-red-50"
              disabled={deleteMutation.isPending}
              onClick={() => deleteTarget && deleteMutation.mutate({ providerID: deleteTarget.providerID, modelID: deleteTarget.modelID })}
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
