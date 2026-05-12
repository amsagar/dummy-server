import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { TopBar } from "@/components/layout/TopBar";
import { decisionTablesApi, orderValidationApi } from "@/services/api";
import { useSettings } from "@/hooks/useSettings";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertCircle, ArrowRight, Check, Table2 } from "lucide-react";

export function SettingsPage() {
  const { workflowId, setWorkflowId } = useSettings();
  const { data: workflows, isLoading, error } = useQuery({
    queryKey: ["workflows"],
    queryFn: () => orderValidationApi.listWorkflows(),
  });
  const { data: tables, isLoading: tablesLoading } = useQuery({
    queryKey: ["decision-tables"],
    queryFn: () => decisionTablesApi.list(),
  });

  const selected = workflows?.find((w) => w.id === workflowId);

  return (
    <>
      <TopBar title="Settings" subtitle="Workflow configuration" />
      <main className="flex-1 p-6 overflow-auto">
        <div className="max-w-2xl space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Validation Workflow</CardTitle>
              <CardDescription>
                Pick the workflow whose completed runs feed the analytics. Runs are read from the
                workflow engine's <code className="text-pods-blue">process_inst</code> table; their
                <code className="text-pods-blue"> resultJson</code> must follow the
                <code className="text-pods-blue"> pods-order-validation</code> Step 7 output shape
                ({"{ orderId, legSequence, serviceability[], containerAvailability[] }"}).
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
                    onChange={(e) => setWorkflowId(e.target.value || null)}
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
                      <span className="text-xs text-muted-foreground">
                        {t.hitPolicy}
                      </span>
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
                Read-only analytics over completed order-validation runs. The workflow definition is
                never modified from this dashboard; decision tables can be edited under the Decision
                Tables section above.
              </CardDescription>
            </CardHeader>
          </Card>
        </div>
      </main>
    </>
  );
}
