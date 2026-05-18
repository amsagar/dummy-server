import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { TopBar } from "@/components/layout/TopBar";
import {
  orderValidationApi,
  orderValidationScopeApi,
  orderValidationSettingsApi,
} from "@/services/api";
import { chatApi } from "@/services/chatApi";
import { useSettings } from "@/hooks/useSettings";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertCircle, ArrowRight, Check, ChevronDown, Lock, Sparkles, X } from "lucide-react";

export function SettingsPage() {
  const qc = useQueryClient();
  const { workflowId, setWorkflowId } = useSettings();

  const { data: workflows, isLoading, error } = useQuery({
    queryKey: ["workflows"],
    queryFn: () => orderValidationApi.listWorkflows(),
  });
  const { data: settings, isLoading: settingsLoading } = useQuery({
    queryKey: ["ov-settings"],
    queryFn: () => orderValidationSettingsApi.get(),
  });
  const { data: models, isLoading: modelsLoading, error: modelsError } = useQuery({
    queryKey: ["chat-models"],
    queryFn: () => chatApi.listModels(),
  });

  const selected = workflows?.find((w) => w.id === workflowId);

  const updateSettings = useMutation({
    mutationFn: (next: {
      chatModelRef?: string | null;
      responseMode?: "basic" | "detailed";
      workflowId?: string | null;
      allowedSkillIds?: string[] | null;
      allowedRuleDomainIds?: string[] | null;
      allowedDecisionTables?: string[] | null;
    }) =>
      orderValidationSettingsApi.update({
        chatModelRef: next.chatModelRef ?? settings?.chatModelRef ?? null,
        responseMode: next.responseMode ?? settings?.responseMode ?? "basic",
        workflowId: next.workflowId ?? settings?.workflowId ?? null,
        allowedSkillIds:
          next.allowedSkillIds !== undefined ? next.allowedSkillIds : settings?.allowedSkillIds ?? null,
        allowedRuleDomainIds:
          next.allowedRuleDomainIds !== undefined
            ? next.allowedRuleDomainIds
            : settings?.allowedRuleDomainIds ?? null,
        allowedDecisionTables:
          next.allowedDecisionTables !== undefined
            ? next.allowedDecisionTables
            : settings?.allowedDecisionTables ?? null,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ov-settings"] }),
  });

  // The workflow's skill + all its rule_domains are auto-allowed by
  // the backend. The UI lets the user OPTIONALLY expose extra skills
  // (other than the workflow's) and restrict decision tables.
  const { data: scopeSkills } = useQuery({
    queryKey: ["ov-scope-skills", settings?.workflowId],
    queryFn: () => orderValidationScopeApi.listSkills(),
  });
  const { data: scopeDecisionTables } = useQuery({
    queryKey: ["ov-scope-decision-tables"],
    queryFn: () => orderValidationScopeApi.listDecisionTables(),
  });

  const toggleAllow = (kind: "skills" | "tables", id: string) => {
    const current =
      kind === "skills" ? settings?.allowedSkillIds : settings?.allowedDecisionTables;
    const next = current == null ? [id] : current.includes(id) ? current.filter((x) => x !== id) : [...current, id];
    updateSettings.mutate(
      kind === "skills" ? { allowedSkillIds: next } : { allowedDecisionTables: next },
    );
  };

  const clearAllow = (kind: "skills" | "tables") => {
    updateSettings.mutate(
      kind === "skills" ? { allowedSkillIds: null } : { allowedDecisionTables: null },
    );
  };

  // Reconcile server-stored workflowId into local state on first load —
  // the AI agent tools resolve the workflow from settings, so the two must
  // agree.
  useEffect(() => {
    if (!settings) return;
    if (settings.workflowId && settings.workflowId !== workflowId) {
      setWorkflowId(settings.workflowId);
    } else if (!settings.workflowId && workflowId) {
      // Push local choice up if server has nothing yet.
      updateSettings.mutate({ workflowId });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings?.workflowId]);

  const onWorkflowChange = (id: string | null) => {
    setWorkflowId(id);
    updateSettings.mutate({ workflowId: id });
  };

  const onModelChange = (ref: string) => {
    updateSettings.mutate({ chatModelRef: ref || null });
  };

  const onResponseModeChange = (mode: string) => {
    updateSettings.mutate({ responseMode: (mode as "basic" | "detailed") || "basic" });
  };

  return (
    <>
      <TopBar title="Settings" subtitle="Workflow + AI configuration" />
      <main className="flex-1 p-6 overflow-auto">
        <div className="w-full grid grid-cols-1 xl:grid-cols-12 gap-5 items-start">
          <Card className="xl:col-span-6">
            <CardHeader>
              <CardTitle>Validation Workflow</CardTitle>
              <CardDescription>
                Pick the workflow whose completed runs feed the analytics. The AI assistant also
                uses this id when it triggers new validation runs on your behalf.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {isLoading ? (
                <Skeleton className="h-9 w-full" />
              ) : error ? (
                <div className="flex items-center gap-2 text-sm text-error">
                  <AlertCircle className="size-4" />
                  Failed to load workflows: {(error as Error).message}
                </div>
              ) : (
                <div className="space-y-3">
                  <Select
                    value={workflowId ?? ""}
                    onChange={(e) => onWorkflowChange(e.target.value || null)}
                    className="w-full"
                  >
                    <option value="">— Select a workflow —</option>
                    {workflows?.map((w) => (
                      <option key={w.id} value={w.id}>
                        {w.name}
                        {formatVersionLabel(w.version)}
                      </option>
                    ))}
                  </Select>
                  {selected && (
                    <div className="rounded-md border border-border/70 bg-muted/30 p-2.5">
                      <div className="flex items-start gap-2 text-xs text-muted-foreground">
                      <Check className="size-3.5 mt-0.5 icon-success shrink-0" />
                        <div className="min-w-0">
                          <div className="text-foreground font-medium">
                            {selected.name}
                            {formatVersionLabel(selected.version)}
                          </div>
                          {cleanWorkflowDescription(selected.description) && (
                            <div className="mt-0.5 text-muted-foreground">
                              {cleanWorkflowDescription(selected.description)}
                            </div>
                          )}
                          <div className="mt-1 font-mono text-[10px] text-muted-foreground/90 break-all">
                            {selected.id}
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="xl:col-span-6">
            <CardHeader>
              <div className="flex items-center gap-2">
                <Sparkles className="size-4 text-pods-blue" />
                <CardTitle>AI Assistant</CardTitle>
              </div>
              <CardDescription>
                Pick the chat model and response style for the in-app AI assistant. These values
                are stored server-side and apply to all viewers of the dashboard.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {settingsLoading || modelsLoading ? (
                <Skeleton className="h-20 w-full" />
              ) : modelsError ? (
                <div className="flex items-center gap-2 text-sm text-error">
                  <AlertCircle className="size-4" />
                  Failed to load models: {(modelsError as Error).message}
                </div>
              ) : (
                <div className="space-y-4">
                  <label className="flex flex-col gap-1.5">
                    <span className="text-xs font-medium text-foreground/80">Chat model</span>
                    <Select
                      value={settings?.chatModelRef ?? ""}
                      onChange={(e) => onModelChange(e.target.value)}
                      className="w-full"
                    >
                      <option value="">— Select a model —</option>
                      {(models ?? [])
                        .filter((m) => m.modelKind !== "embedding")
                        .map((m) => {
                          const ref = `${m.providerID}/${m.modelID}`;
                          return (
                            <option key={ref} value={ref}>
                              {m.displayName || m.modelID} · {m.providerID}
                            </option>
                          );
                        })}
                    </Select>
                  </label>
                  <label className="flex flex-col gap-1.5">
                    <span className="text-xs font-medium text-foreground/80">Response mode</span>
                    <Select
                      value={settings?.responseMode ?? "basic"}
                      onChange={(e) => onResponseModeChange(e.target.value)}
                      className="w-full"
                    >
                      <option value="basic">Basic — one-paragraph headline summary</option>
                      <option value="detailed">Detailed — per-check breakdown with bullets</option>
                    </Select>
                  </label>
                  {updateSettings.error && (
                    <div className="text-xs text-error">
                      Failed to save: {(updateSettings.error as Error).message}
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="xl:col-span-12">
            <CardHeader>
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-3 min-w-0">
                  <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-pods-blue/10 text-pods-blue">
                    <Lock className="size-4" />
                  </div>
                  <div className="min-w-0">
                    <CardTitle>Assistant scope</CardTitle>
                    <CardDescription className="mt-0.5">
                      The selected workflow's skill and rule-domains are always in scope.
                    </CardDescription>
                  </div>
                </div>
                <Link
                  to="/decision-tables"
                  className="shrink-0 inline-flex items-center gap-1 text-xs text-pods-blue hover:underline"
                >
                  Manage decision tables <ArrowRight className="size-3" />
                </Link>
              </div>
            </CardHeader>
            <CardContent className="pt-0">
              <div className="border-t border-border/70 pt-4">
                <ScopeSection
                  title="Additional skills"
                  helper="Optional — other skills the assistant may invoke alongside the workflow."
                  itemNoun="skill"
                  itemNounPlural="skills"
                  items={(scopeSkills ?? []).map((s) => ({ id: s.id, label: s.name }))}
                  selected={settings?.allowedSkillIds ?? null}
                  onToggle={(id) => toggleAllow("skills", id)}
                  onClear={() => clearAllow("skills")}
                  emptyMessage="No other enabled skills available"
                />
              </div>
              <div className="border-t border-border/70 mt-5 pt-4">
                <ScopeSection
                  title="Allowed decision tables"
                  helper="Restrict which DTs the assistant may evaluate. None selected = any."
                  itemNoun="table"
                  itemNounPlural="tables"
                  items={(scopeDecisionTables ?? []).map((t) => ({ id: t.name, label: t.name }))}
                  selected={settings?.allowedDecisionTables ?? null}
                  onToggle={(id) => toggleAllow("tables", id)}
                  onClear={() => clearAllow("tables")}
                />
              </div>
            </CardContent>
          </Card>
        </div>
      </main>
    </>
  );
}

function formatVersionLabel(version?: string | number | null): string {
  if (version == null) return "";
  const trimmed = String(version).trim();
  if (!trimmed) return "";
  return ` · ${/^v/i.test(trimmed) ? trimmed : `v${trimmed}`}`;
}

function cleanWorkflowDescription(raw?: string | null): string {
  if (!raw) return "";
  const trimmed = raw.trim();
  if (trimmed.length <= 1) return "";
  if (/^[>|-]+$/.test(trimmed)) return "";
  return trimmed;
}

function ScopeSection({
  title,
  helper,
  itemNoun,
  itemNounPlural,
  items,
  selected,
  onToggle,
  onClear,
  emptyMessage,
}: {
  title: string;
  helper: string;
  itemNoun: string;
  itemNounPlural: string;
  items: Array<{ id: string; label: string }>;
  selected: string[] | null;
  onToggle: (id: string) => void;
  onClear: () => void;
  emptyMessage?: string;
}) {
  const isUnrestricted = selected == null;
  const selectedCount = selected?.length ?? 0;
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const isEmpty = items.length === 0;

  // Click-outside / Escape closes the dropdown.
  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const selectedItems = isUnrestricted
    ? []
    : items.filter((it) => selected!.includes(it.id));

  const summary = isEmpty
    ? emptyMessage ?? "No items available"
    : isUnrestricted
    ? `Any ${itemNounPlural} allowed`
    : selectedCount === 0
    ? `No ${itemNounPlural} selected`
    : `${selectedCount} ${selectedCount === 1 ? itemNoun : itemNounPlural} selected`;

  return (
    <div ref={ref}>
      <div className="flex items-center justify-between gap-3 mb-1">
        <h4 className="text-sm font-semibold text-foreground">{title}</h4>
        <div className="flex items-center gap-2">
          <span
            className={
              "rounded-full px-2 py-0.5 text-[10px] font-medium " +
              (isUnrestricted
                ? "bg-muted text-muted-foreground"
                : "bg-pods-blue/10 text-pods-blue")
            }
          >
            {isUnrestricted ? "any" : `${selectedCount} selected`}
          </span>
          {!isUnrestricted && (
            <button
              type="button"
              onClick={onClear}
              className="rounded-md border border-border px-2 py-0.5 text-[11px] text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors"
            >
              Reset
            </button>
          )}
        </div>
      </div>
      <p className="text-xs text-muted-foreground mb-2">{helper}</p>

      <div className="relative">
        <button
          type="button"
          onClick={() => !isEmpty && setOpen((v) => !v)}
          aria-disabled={isEmpty}
          className={
            "w-full flex items-center justify-between gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm " +
            (isEmpty
              ? "cursor-not-allowed text-muted-foreground italic"
              : "hover:bg-muted/40 transition-colors")
          }
        >
          <span className="truncate">{summary}</span>
          {!isEmpty && (
            <ChevronDown
              className={
                "size-4 text-muted-foreground shrink-0 transition-transform " +
                (open ? "rotate-180" : "")
              }
            />
          )}
        </button>
        {open && !isEmpty && (
          <div className="absolute z-30 mt-1 w-full max-h-64 overflow-auto rounded-md border border-border bg-card text-foreground shadow-xl">
            {items.map((it) => {
              const isSel = !isUnrestricted && selected!.includes(it.id);
              return (
                <button
                  key={it.id}
                  type="button"
                  onClick={() => onToggle(it.id)}
                  className="w-full flex items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted/60 transition-colors"
                  title={it.id}
                >
                  <span
                    className={
                      "flex size-4 shrink-0 items-center justify-center rounded border " +
                      (isSel
                        ? "border-pods-blue bg-pods-blue text-white"
                        : "border-border bg-background")
                    }
                  >
                    {isSel && <Check className="size-3" />}
                  </span>
                  <span className="truncate">{it.label}</span>
                </button>
              );
            })}
          </div>
        )}
      </div>

      {!open && selectedItems.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {selectedItems.map((it) => (
            <span
              key={it.id}
              className="inline-flex items-center gap-1 rounded-md bg-pods-blue/10 text-pods-blue text-xs pl-2 pr-1 py-0.5"
              title={it.id}
            >
              <span className="truncate max-w-[200px]">{it.label}</span>
              <button
                type="button"
                onClick={() => onToggle(it.id)}
                className="flex size-4 items-center justify-center rounded hover:bg-pods-blue/20"
                aria-label={`Remove ${it.label}`}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
