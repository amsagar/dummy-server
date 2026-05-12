import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { TopBar } from "@/components/layout/TopBar";
import {
  decisionTablesApi,
  orderValidationApi,
  orderValidationSettingsApi,
} from "@/services/api";
import { chatApi } from "@/services/chatApi";
import { useSettings } from "@/hooks/useSettings";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertCircle, ArrowRight, Check, Table2, Sparkles } from "lucide-react";

export function SettingsPage() {
  const qc = useQueryClient();
  const { workflowId, setWorkflowId } = useSettings();

  const { data: workflows, isLoading, error } = useQuery({
    queryKey: ["workflows"],
    queryFn: () => orderValidationApi.listWorkflows(),
  });
  const { data: tables, isLoading: tablesLoading } = useQuery({
    queryKey: ["decision-tables"],
    queryFn: () => decisionTablesApi.list(),
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
    }) =>
      orderValidationSettingsApi.update({
        chatModelRef: next.chatModelRef ?? settings?.chatModelRef ?? null,
        responseMode: next.responseMode ?? settings?.responseMode ?? "basic",
        workflowId: next.workflowId ?? settings?.workflowId ?? null,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ov-settings"] }),
  });

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
        <div className="max-w-2xl space-y-6">
          <Card>
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
                        {w.name} {w.version ? `· v${w.version}` : ""}
                      </option>
                    ))}
                  </Select>
                  {selected && (
                    <div className="flex items-start gap-2 text-xs text-muted-foreground">
                      <Check className="size-3.5 mt-0.5 icon-success shrink-0" />
                      <div>
                        <div className="text-foreground">
                          {selected.name}
                          {selected.version && (
                            <span className="text-muted-foreground"> · v{selected.version}</span>
                          )}
                        </div>
                        {selected.description && <div className="mt-0.5">{selected.description}</div>}
                        <div className="mt-1 font-mono text-[10px]">{selected.id}</div>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
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

          <Card>
            <CardHeader>
              <CardTitle>Decision Tables</CardTitle>
              <CardDescription>
                Decision tables drive leg-sequence matching (and other rule-based steps). Edit them
                to change which sequences pass validation, and test edits against sample inputs.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {tablesLoading ? (
                <Skeleton className="h-9 w-full" />
              ) : (tables?.length ?? 0) === 0 ? (
                <div className="text-sm text-muted-foreground">
                  No decision tables yet.{" "}
                  <Link to="/decision-tables" className="text-pods-blue hover:underline">
                    Create one →
                  </Link>
                </div>
              ) : (
                <div className="space-y-2">
                  {(tables ?? []).slice(0, 5).map((t) => (
                    <Link
                      key={t.name}
                      to={`/decision-tables/${encodeURIComponent(t.name)}`}
                      className="flex items-center justify-between gap-2 py-2 px-3 rounded-md border border-border bg-muted hover:bg-accent transition-colors text-sm"
                    >
                      <span className="flex items-center gap-2">
                        <Table2 className="size-3.5 text-muted-foreground" />
                        {t.name}
                      </span>
                      <span className="text-xs text-muted-foreground">{t.hitPolicy}</span>
                    </Link>
                  ))}
                  <Link
                    to="/decision-tables"
                    className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors mt-1"
                  >
                    Manage all <ArrowRight className="size-3" />
                  </Link>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>About</CardTitle>
              <CardDescription>
                Analytics over completed order-validation runs, plus an order-validation-scoped AI
                assistant. The workflow definition is never modified from this dashboard; decision
                tables and AI settings can be edited above.
              </CardDescription>
            </CardHeader>
          </Card>
        </div>
      </main>
    </>
  );
}
