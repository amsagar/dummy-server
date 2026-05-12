import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, Link } from "react-router-dom";
import { ArrowLeft, Save, Trash2 } from "lucide-react";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Dialog } from "@/components/ui/dialog";
import { RulesGrid } from "@/components/decision/RulesGrid";
import { DecisionTableTestPanel } from "@/components/decision/DecisionTableTestPanel";
import { decisionTablesApi } from "@/services/api";
import type { DmnJson } from "@/types/decisionTable";

const EMPTY_DMN: DmnJson = { inputs: [], outputs: [], rules: [] };

export function DecisionTableEditorPage() {
  const { name = "" } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [localName, setLocalName] = useState(name);
  const [description, setDescription] = useState("");
  const [hitPolicy, setHitPolicy] = useState<string>("FIRST");
  const [definition, setDefinition] = useState<DmnJson>(EMPTY_DMN);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["decision-table", name],
    queryFn: () => decisionTablesApi.detail(name),
    enabled: !!name,
  });

  useEffect(() => {
    if (!data) return;
    setLocalName(data.name);
    setDescription(data.description ?? "");
    setHitPolicy(data.hitPolicy || "FIRST");
    const dmn = (data.dmnJson as DmnJson) ?? EMPTY_DMN;
    setDefinition({
      inputs: dmn.inputs ?? [],
      outputs: dmn.outputs ?? [],
      rules: dmn.rules ?? [],
    });
  }, [data]);

  const saveMutation = useMutation({
    mutationFn: () =>
      decisionTablesApi.update(name, {
        name: localName.trim(),
        description: description.trim() || null,
        hitPolicy,
        dmnJson: definition,
        metadata: data?.metadata ?? {},
      }),
    onSuccess: (saved) => {
      qc.invalidateQueries({ queryKey: ["decision-tables"] });
      if (saved.name !== name) {
        navigate(`/decision-tables/${encodeURIComponent(saved.name)}`, { replace: true });
      } else {
        refetch();
      }
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => decisionTablesApi.remove(name),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["decision-tables"] });
      navigate("/decision-tables");
    },
  });

  if (!name) {
    return <div className="p-6 text-sm text-error">Missing decision-table name in URL.</div>;
  }

  return (
    <>
      <TopBar title={data ? data.name : "Decision Table"} subtitle={data?.description ?? undefined} />
      <main className="flex-1 p-6 overflow-auto">
        <Link
          to="/decision-tables"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4"
        >
          <ArrowLeft className="size-4" /> Back to tables
        </Link>

        {error ? (
          <div className="text-sm text-error">Failed to load: {(error as Error).message}</div>
        ) : isLoading || !data ? (
          <div className="space-y-4">
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-60 w-full" />
          </div>
        ) : (
          <div className="space-y-4">
            <Card>
              <CardContent className="!pt-5 grid grid-cols-1 lg:grid-cols-[1fr_2fr_auto_auto_auto] gap-3 items-end">
                <label className="flex flex-col gap-1.5">
                  <span className="text-xs font-medium text-foreground/80">Name</span>
                  <Input value={localName} onChange={(e) => setLocalName(e.target.value)} />
                </label>
                <label className="flex flex-col gap-1.5">
                  <span className="text-xs font-medium text-foreground/80">Description</span>
                  <Input value={description} onChange={(e) => setDescription(e.target.value)} />
                </label>
                <label className="flex flex-col gap-1.5">
                  <span className="text-xs font-medium text-foreground/80">Hit policy</span>
                  <Select value={hitPolicy} onChange={(e) => setHitPolicy(e.target.value)}>
                    <option value="FIRST">FIRST</option>
                    <option value="UNIQUE">UNIQUE</option>
                    <option value="COLLECT">COLLECT</option>
                  </Select>
                </label>
                <Button
                  onClick={() => saveMutation.mutate()}
                  disabled={saveMutation.isPending || !localName.trim()}
                  className="btn-primary-text"
                >
                  <Save className="size-4" />
                  {saveMutation.isPending ? "Saving…" : "Save"}
                </Button>
                <Button variant="outline" onClick={() => setConfirmDelete(true)}>
                  <Trash2 className="size-4 icon-error" />
                </Button>
              </CardContent>
            </Card>

            {saveMutation.error && (
              <Card className="error-banner">
                <CardContent className="!pt-4 text-sm">
                  <div className="error-banner-title">Save failed</div>
                  <div className="error-banner-body">{(saveMutation.error as Error).message}</div>
                </CardContent>
              </Card>
            )}

            <RulesGrid value={definition} onChange={setDefinition} />

            <DecisionTableTestPanel
              onEvaluate={(inputs) => decisionTablesApi.evaluate(name, { inputs })}
            />
          </div>
        )}
      </main>

      <Dialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        title="Delete decision table"
        description={`This permanently removes "${name}". Workflows referencing it will fail until you restore.`}
      >
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => setConfirmDelete(false)}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => deleteMutation.mutate()}
            disabled={deleteMutation.isPending}
          >
            {deleteMutation.isPending ? "Deleting…" : "Delete"}
          </Button>
        </div>
      </Dialog>
    </>
  );
}
