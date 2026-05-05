import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from "sonner";
import type { DecisionTableSummary } from "@/types/decision";

export default function DecisionTablesPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  const { data: rows = [] } = useQuery<DecisionTableSummary[]>({
    queryKey: ["decision-tables"],
    queryFn: () => api.decisionTables.list(),
  });

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim();
    if (!q) return rows;
    return rows.filter((r) => r.name.toLowerCase().includes(q) || String(r.description || "").toLowerCase().includes(q));
  }, [rows, search]);

  const refresh = () => qc.invalidateQueries({ queryKey: ["decision-tables"] });

  const createMutation = useMutation({
    mutationFn: () =>
      api.decisionTables.create({
        name,
        description,
        hitPolicy: "FIRST",
        dmnJson: { hitPolicy: "FIRST", inputs: [], outputs: [], rules: [] },
        metadata: {},
      }),
    onSuccess: (created: any) => {
      refresh();
      setCreateOpen(false);
      setName("");
      setDescription("");
      navigate(`/decision-tables/${encodeURIComponent(created.name)}`);
      toast.success("Decision table created");
    },
    onError: (e: any) => toast.error(e.message || "Failed to create decision table"),
  });

  const deleteMutation = useMutation({
    mutationFn: (tableName: string) => api.decisionTables.delete(tableName),
    onSuccess: () => {
      refresh();
      toast.success("Decision table deleted");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete decision table"),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-xl font-semibold text-[#123262]">Decision Tables</h3>
          <p className="text-sm text-slate-600">Manage decision rules and test evaluations.</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>New Decision Table</Button>
      </div>

      <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search by name or description" className="max-w-sm" />

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Description</TableHead>
            <TableHead>Updated</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={4} className="text-center text-sm text-slate-500">
                No decision tables found.
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((row) => (
              <TableRow key={row.name}>
                <TableCell>{row.name}</TableCell>
                <TableCell>{row.description || "—"}</TableCell>
                <TableCell>{new Date(row.updatedAt).toLocaleString()}</TableCell>
                <TableCell className="space-x-2 text-right">
                  <Button size="sm" variant="outline" onClick={() => navigate(`/decision-tables/${encodeURIComponent(row.name)}`)}>
                    Edit
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => deleteMutation.mutate(row.name)}>
                    Delete
                  </Button>
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>New Decision Table</DialogTitle>
            <DialogDescription>Create a table and open it in the editor.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} />
            <Input placeholder="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button disabled={!name.trim() || createMutation.isPending} onClick={() => createMutation.mutate()}>
              {createMutation.isPending ? "Creating..." : "Create"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
