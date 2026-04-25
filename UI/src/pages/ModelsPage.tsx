import * as React from "react";
import { useState, useMemo, useRef, useEffect } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Cpu, Plus, Loader2, KeyRound, Eye, EyeOff, ChevronDown, Pencil, DollarSign } from "lucide-react";
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

  // close & reset search when disabled (provider changed)
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
      {/* Trigger */}
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

      {/* Dropdown panel */}
      {open && (
        <div className="absolute z-50 mt-1 w-full rounded-md border bg-white shadow-lg">
          {/* Search */}
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
          {/* List */}
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

  const providerOptions = useMemo(() =>
    (providers ?? []).map(p => ({ value: p.id, label: p.name, sublabel: p.id })),
    [providers]
  );

  const modelsForProvider = useMemo(() => {
    if (!form.providerID || !providers) return [];
    return providers.find(p => p.id === form.providerID)?.models ?? [];
  }, [form.providerID, providers]);

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

  const displayed = allModels ?? [];

  return (
    <div className="space-y-4">
      {/* ── Header ──────────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mb-2">
        <div>
          <h1 className="text-xl font-semibold text-[#123262]">AI Models</h1>
          <p className="text-sm text-gray-500 mt-0.5">Registered LLM providers and model configurations</p>
        </div>
        <Button onClick={() => setRegisterOpen(true)} className="h-9 px-4 text-sm font-medium text-white rounded-lg" style={{ background: "#005CB9" }}>
          <Plus size={15} className="mr-1.5" /> Register Model
        </Button>
      </div>

      {/* ── Table ───────────────────────────────────────────────────────────── */}
      <div className="overflow-hidden border border-gray-200 rounded-xl bg-white">
          {isLoading ? (
            <div className="p-6 space-y-3">
              {[1,2,3,4,5].map(i => <Skeleton key={i} className="h-10 w-full" />)}
            </div>
          ) : displayed.length === 0 ? (
            <div className="text-center py-16 text-slate-400">
              <Cpu size={32} className="mx-auto mb-3 opacity-30" />
              <p className="text-sm">No models registered. Click "Register Model" to add one.</p>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className="bg-gray-50">
                  <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Provider</TableHead>
                  <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Model ID</TableHead>
                  <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Name</TableHead>
                  <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Cost</TableHead>
                  <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</TableHead>
                  <TableHead className="text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {displayed.map((m) => (
                  <TableRow key={`${m.providerID}/${m.modelID}`}>
                    <TableCell>
                      <ProviderBadge providerID={m.providerID} name={m.providerName} />
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-500 max-w-[180px] truncate">
                      {m.modelID}
                    </TableCell>
                    <TableCell className="text-sm font-medium max-w-[200px] truncate">
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
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
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
              Select a provider and one or more models, then enter your API key. Keys are encrypted (AES-256-GCM) before storage.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            {/* Provider */}
            <div>
              <label className="text-xs font-medium text-slate-700 block mb-1.5">Provider *</label>
              <SearchableSelect
                options={providerOptions}
                value={form.providerID}
                onValueChange={(pid) => setForm(f => ({ ...f, providerID: pid, selectedModelIDs: [] }))}
                placeholder="Search and select provider…"
                searchPlaceholder="Search providers…"
              />
            </div>

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
                <span className="ml-1 font-normal text-slate-400">(encrypted at rest)</span>
              </label>
              <div className="relative">
                <input
                  type={showRegisterKey ? "text" : "password"}
                  className="w-full h-9 rounded-md border border-input bg-background px-3 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-ring placeholder:text-muted-foreground"
                  placeholder={form.providerID ? `${form.providerID} API key…` : "API key…"}
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
    </div>
  );
}
