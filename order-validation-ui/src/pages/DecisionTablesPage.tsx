import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Trash2, Edit3, Search } from "lucide-react";
import { TopBar } from "@/components/layout/TopBar";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog } from "@/components/ui/dialog";
import { decisionTablesApi } from "@/services/api";

export function DecisionTablesPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["decision-tables"],
    queryFn: () => decisionTablesApi.list(),
  });

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim();
    const rows = data ?? [];
    if (!q) return rows;
    return rows.filter(
      (r) =>
        r.name.toLowerCase().includes(q) ||
        (r.description ?? "").toLowerCase().includes(q),
    );
  }, [data, search]);

  const deleteMutation = useMutation({
    mutationFn: (tableName: string) => decisionTablesApi.remove(tableName),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["decision-tables"] });
      setConfirmDelete(null);
    },
  });

  return (
    <>
      <TopBar
        title="Decision Tables"
        subtitle={data ? `${data.length} table${data.length === 1 ? "" : "s"}` : undefined}
      />
      <main className="flex-1 p-6 overflow-auto">
        <div className="space-y-4">
          <Card>
            <CardContent className="!pt-5 flex items-center gap-3">
              <div className="relative flex-1 max-w-md">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
                <Input
                  placeholder="Search by name or description…"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  className="pl-9"
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="!p-0">
              {error ? (
                <div className="p-5 text-sm text-error">
                  Failed to load: {(error as Error).message}
                </div>
              ) : isLoading ? (
                <div className="p-5">
                  <Skeleton className="h-40 w-full" />
                </div>
              ) : filtered.length === 0 ? (
                <div className="p-10 text-sm text-muted-foreground text-center">
                  {data && data.length === 0
                    ? "No decision tables exist yet."
                    : "No tables match your search."}
                </div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Description</TableHead>
                      <TableHead>Hit policy</TableHead>
                      <TableHead>Updated</TableHead>
                      <TableHead className="w-32 text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {filtered.map((row) => (
                      <TableRow
                        key={row.name}
                        className="cursor-pointer"
                        onClick={() =>
                          navigate(`/decision-tables/${encodeURIComponent(row.name)}`)
                        }
                      >
                        <TableCell className="font-medium">{row.name}</TableCell>
                        <TableCell className="text-sm text-muted-foreground">
                          {row.description || "—"}
                        </TableCell>
                        <TableCell className="text-xs font-mono">{row.hitPolicy}</TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {row.updatedAt
                            ? new Date(row.updatedAt).toLocaleString("en-US", {
                                dateStyle: "medium",
                                timeStyle: "short",
                              })
                            : "—"}
                        </TableCell>
                        <TableCell
                          className="text-right space-x-1"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() =>
                              navigate(`/decision-tables/${encodeURIComponent(row.name)}`)
                            }
                          >
                            <Edit3 className="size-3.5" />
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setConfirmDelete(row.name)}
                          >
                            <Trash2 className="size-3.5 icon-error" />
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </div>
      </main>

      <Dialog
        open={confirmDelete != null}
        onClose={() => setConfirmDelete(null)}
        title="Delete decision table"
        description={`This permanently removes "${confirmDelete}". Workflows referencing it will fail until you restore.`}
      >
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => setConfirmDelete(null)}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => confirmDelete && deleteMutation.mutate(confirmDelete)}
            disabled={deleteMutation.isPending}
          >
            {deleteMutation.isPending ? "Deleting…" : "Delete"}
          </Button>
        </div>
      </Dialog>
    </>
  );
}
