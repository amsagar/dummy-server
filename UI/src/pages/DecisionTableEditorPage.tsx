import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { RulesGrid } from "@/components/decision/RulesGrid";
import { DecisionTableTestPanel } from "@/components/decision/DecisionTableTestPanel";
import type { DecisionTableDefinition, DecisionTableDetail } from "@/types/decision";
import { toast } from "sonner";

const EMPTY_DEF: DecisionTableDefinition = {
  hitPolicy: "FIRST",
  inputs: [],
  outputs: [],
  rules: [],
};

export default function DecisionTableEditorPage() {
  const { name = "" } = useParams();
  const navigate = useNavigate();
  const [localName, setLocalName] = useState(name);
  const [description, setDescription] = useState("");
  const [hitPolicy, setHitPolicy] = useState<"FIRST" | "UNIQUE" | "COLLECT">("FIRST");
  const [definition, setDefinition] = useState<DecisionTableDefinition>(EMPTY_DEF);

  const { data, refetch } = useQuery<DecisionTableDetail>({
    queryKey: ["decision-table", name],
    queryFn: () => api.decisionTables.get(name),
    enabled: !!name,
  });

  useEffect(() => {
    if (!data) return;
    setLocalName(data.name);
    setDescription(data.description || "");
    setHitPolicy(data.hitPolicy || "FIRST");
    setDefinition((data.dmnJson as DecisionTableDefinition) || EMPTY_DEF);
  }, [data]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      return api.decisionTables.upsert(name, {
        name: localName,
        description,
        hitPolicy,
        dmnJson: { ...definition, hitPolicy },
        metadata: {},
      });
    },
    onSuccess: (saved: any) => {
      toast.success("Decision table saved");
      if (saved?.name && saved.name !== name) {
        navigate(`/decision-tables/${encodeURIComponent(saved.name)}`, { replace: true });
      }
      refetch();
    },
    onError: (e: any) => toast.error(e.message || "Failed to save decision table"),
  });

  const deleteMutation = useMutation({
    mutationFn: () => api.decisionTables.delete(name),
    onSuccess: () => {
      toast.success("Decision table deleted");
      navigate("/decision-tables");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete decision table"),
  });

  if (!name) {
    return <div className="text-sm text-slate-600">Missing decision table name.</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input className="max-w-sm" value={localName} onChange={(e) => setLocalName(e.target.value)} />
        <Input className="max-w-lg" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Description" />
        <select
          className="h-9 rounded border px-2 text-sm"
          value={hitPolicy}
          onChange={(e) => setHitPolicy(e.target.value as "FIRST" | "UNIQUE" | "COLLECT")}
        >
          <option value="FIRST">FIRST</option>
          <option value="UNIQUE">UNIQUE</option>
          <option value="COLLECT">COLLECT</option>
        </select>
        <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
          {saveMutation.isPending ? "Saving..." : "Save"}
        </Button>
        <Button variant="outline" onClick={() => navigate("/decision-tables")}>Back</Button>
        <Button variant="outline" onClick={() => deleteMutation.mutate()} disabled={deleteMutation.isPending}>
          Delete
        </Button>
      </div>

      <RulesGrid value={definition} onChange={setDefinition} />

      <DecisionTableTestPanel
        onEvaluate={(inputs) => api.decisionTables.evaluate(name, inputs)}
      />
    </div>
  );
}
