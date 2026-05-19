import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { toast } from "sonner";

type Persona = {
  id: string;
  name: string;
  systemPrompt: string;
  enabled: boolean;
  kind: string;
  createdAt: number;
  updatedAt: number;
};

const TEXTAREA_ROWS = 12;

export default function PersonasPage() {
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState<Persona | null>(null);
  const [name, setName] = useState("");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<Persona | null>(null);

  const { data: rows = [], isLoading } = useQuery<Persona[]>({
    queryKey: ["personas"],
    queryFn: () => api.responseModes.list(),
  });

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim();
    if (!q) return rows;
    return rows.filter(
      (r) =>
        r.name.toLowerCase().includes(q) ||
        String(r.systemPrompt || "").toLowerCase().includes(q),
    );
  }, [rows, search]);

  const refresh = () => qc.invalidateQueries({ queryKey: ["personas"] });

  const openNew = () => {
    setEditing(null);
    setName("");
    setSystemPrompt("");
    setEditOpen(true);
  };

  const openEdit = (row: Persona) => {
    setEditing(row);
    setName(row.name);
    setSystemPrompt(row.systemPrompt);
    setEditOpen(true);
  };

  const saveMutation = useMutation({
    mutationFn: async () => {
      const trimmedName = name.trim();
      const payload = { name: trimmedName, systemPrompt };
      if (editing) {
        return api.responseModes.update(editing.id, payload);
      }
      return api.responseModes.create(payload);
    },
    onSuccess: () => {
      refresh();
      setEditOpen(false);
      toast.success(editing ? "Persona updated" : "Persona created");
    },
    onError: (e: any) => toast.error(e.message || "Failed to save persona"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.responseModes.delete(id),
    onSuccess: () => {
      refresh();
      setDeleteTarget(null);
      toast.success("Persona deleted");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete persona"),
  });

  const canSave = name.trim().length > 0 && systemPrompt.trim().length > 0 && !saveMutation.isPending;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-xl font-semibold text-[#123262]">Personas</h3>
          <p className="text-sm text-slate-600">
            Voice presets the Order Validation chat applies on top of its base prompt. Pick the active persona in the OV Settings.
          </p>
        </div>
        <Button onClick={openNew}>New Persona</Button>
      </div>

      <Input
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search by name or prompt text"
        className="max-w-sm"
      />

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Updated</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading ? (
            <TableRow>
              <TableCell colSpan={3} className="text-center text-sm text-slate-500">
                Loading…
              </TableCell>
            </TableRow>
          ) : filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={3} className="text-center text-sm text-slate-500">
                No personas yet. Create one to get started.
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((row) => (
              <TableRow key={row.id}>
                <TableCell className="font-medium">{row.name}</TableCell>
                <TableCell className="text-sm text-slate-600">
                  {row.updatedAt ? new Date(row.updatedAt).toLocaleString() : "—"}
                </TableCell>
                <TableCell className="space-x-2 text-right">
                  <Button size="sm" variant="outline" onClick={() => openEdit(row)}>
                    Edit
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setDeleteTarget(row)}>
                    Delete
                  </Button>
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editing ? "Edit Persona" : "New Persona"}</DialogTitle>
            <DialogDescription>
              Free-text voice guidance appended to the OV assistant's base prompt. The base prompt still owns scope, tools, and output rules.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-600">Name</label>
              <Input
                placeholder="Business, Developers, Security, IT…"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-600">System prompt</label>
              <textarea
                className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 font-mono text-xs leading-5 text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
                rows={TEXTAREA_ROWS}
                placeholder="Describe how the assistant should sound for this audience…"
                value={systemPrompt}
                onChange={(e) => setSystemPrompt(e.target.value)}
              />
              <p className="text-[11px] text-slate-500">
                Tip: focus on voice (technical / plain / compliance-flavored). Don't restate the OV scope or output rules — they come from the base prompt.
              </p>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditOpen(false)} disabled={saveMutation.isPending}>
              Cancel
            </Button>
            <Button disabled={!canSave} onClick={() => saveMutation.mutate()}>
              {saveMutation.isPending ? "Saving…" : editing ? "Save" : "Create"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title="Delete persona?"
        tone="danger"
        confirmLabel="Delete"
        busy={deleteMutation.isPending}
        description="The persona is hidden from the OV-UI dropdown. Any settings row still referencing it falls back to the base prompt."
        detail={deleteTarget ? <div><span className="font-semibold">{deleteTarget.name}</span> ({deleteTarget.id})</div> : null}
        onConfirm={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
      />
    </div>
  );
}
