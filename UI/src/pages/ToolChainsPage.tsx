import { useMemo, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { MoreHorizontal } from "lucide-react";
import { toast } from "sonner";
import ToolChainRunInputDialog from "@/components/toolchain/ToolChainRunInputDialog";
import { SearchableSelect } from "@/components/ui/searchable-select";
import { modelRefKey, parseModelRefKey } from "@/types";

function toTitleCase(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function parseMetadata(raw: any): Record<string, any> {
  if (!raw) return {};
  if (typeof raw === "object") return raw as Record<string, any>;
  if (typeof raw !== "string") return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? (parsed as Record<string, any>) : {};
  } catch {
    return {};
  }
}

export default function ToolChainsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const [runToolChainId, setRunToolChainId] = useState<string>("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [runtimeDialogOpen, setRuntimeDialogOpen] = useState(false);
  const [runtimeToolChainId, setRuntimeToolChainId] = useState("");
  const [runtimeProvider, setRuntimeProvider] = useState("");
  const [runtimeModel, setRuntimeModel] = useState("");

  const { data: toolchains = [] } = useQuery<any[]>({
    queryKey: ["toolchains"],
    queryFn: () => api.toolchains.list(),
  });

  const createMutation = useMutation({
    mutationFn: () => api.toolchains.create({ name, description, enabled: true }),
    onSuccess: () => {
      setCreateOpen(false);
      setName("");
      setDescription("");
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success("ToolChain created");
    },
    onError: (e: any) => toast.error(e.message || "Failed to create ToolChain"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.toolchains.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success("ToolChain deleted");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete ToolChain"),
  });

  const { data: modelsData = [] } = useQuery<any[]>({
    queryKey: ["models-enabled"],
    queryFn: () => api.get("/models/enabled"),
  });

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return toolchains;
    return toolchains.filter((row) =>
      String(row.name || "").toLowerCase().includes(q) ||
      String(row.description || "").toLowerCase().includes(q) ||
      String(row.status || "").toLowerCase().includes(q)
    );
  }, [search, toolchains]);

  const versionsQueries = useQueries({
    queries: filtered.map((row: any) => ({
      queryKey: ["toolchain-versions", row.id, "table-response-mode"],
      queryFn: () => api.toolchains.versions(row.id),
      staleTime: 30_000,
    })),
  });
  const responseModeByToolChainId = useMemo(() => {
    const map = new Map<string, string>();
    filtered.forEach((row: any, idx: number) => {
      const versions = versionsQueries[idx]?.data || [];
      const latest = Array.isArray(versions) ? versions[0] : null;
      if (latest?.responseMode) map.set(row.id, String(latest.responseMode));
    });
    return map;
  }, [filtered, versionsQueries]);

  const runtimeSupportedByToolChainId = useMemo(() => {
    const map = new Map<string, boolean>();
    filtered.forEach((row: any) => {
      const mode = String(responseModeByToolChainId.get(row.id) || "").toLowerCase();
      map.set(row.id, mode === "hybrid" || mode === "synthesized_text");
    });
    return map;
  }, [filtered, responseModeByToolChainId]);

  const modelsByProvider = useMemo(() => {
    const map = new Map<string, { providerName?: string; models: any[] }>();
    for (const m of modelsData || []) {
      if (m.modelKind === "embedding") continue;
      const pid = m.providerID as string;
      if (!map.has(pid)) map.set(pid, { providerName: m.providerName, models: [] });
      map.get(pid)!.models.push(m);
    }
    return map;
  }, [modelsData]);
  const providerList = useMemo(() => Array.from(modelsByProvider.keys()), [modelsByProvider]);

  const saveRuntimeModelMutation = useMutation({
    mutationFn: async () => {
      const row = toolchains.find((item: any) => item.id === runtimeToolChainId);
      if (!row) throw new Error("ToolChain not found");
      const metadata = parseMetadata(row.metadataJson);
      const parsed = parseModelRefKey(runtimeModel);
      if (parsed) {
        metadata.runtimeModelRef = { providerID: parsed.providerID, modelID: parsed.modelID };
      } else {
        delete metadata.runtimeModelRef;
      }
      return api.toolchains.update(runtimeToolChainId, {
        name: row.name,
        description: row.description,
        enabled: row.enabled,
        metadata,
      });
    },
    onSuccess: () => {
      setRuntimeDialogOpen(false);
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success("Runtime model updated");
    },
    onError: (e: any) => toast.error(e.message || "Failed to update runtime model"),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-[#123262]">ToolChains</h2>
          <p className="text-sm text-slate-600">Design visually, execute deterministically, observe historically.</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => navigate("/toolchains/approvals")}>Approvals</Button>
          <Button variant="outline" onClick={() => navigate("/toolchains/designer")}>AI Create</Button>
          <Button onClick={() => setCreateOpen(true)}>New ToolChain</Button>
        </div>
      </div>

      <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search ToolChains" className="max-w-sm" />

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Current Version</TableHead>
            <TableHead>Response Mode</TableHead>
            <TableHead>Description</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.map((row: any) => (
            <TableRow
              key={row.id}
              className="cursor-pointer"
              onClick={() => navigate(`/toolchains/${row.id}/designer`)}
            >
              <TableCell className="font-medium">{row.name}</TableCell>
              <TableCell>{toTitleCase(String(row.status || "—"))}</TableCell>
              <TableCell>{row.currentVersion || "—"}</TableCell>
              <TableCell>{toTitleCase(responseModeByToolChainId.get(row.id) || "—")}</TableCell>
              <TableCell className="max-w-[420px] truncate">{row.description || "—"}</TableCell>
              <TableCell className="text-right">
                <DropdownMenu>
                  <DropdownMenuTrigger
                    className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-input bg-background text-slate-600 shadow-xs hover:bg-slate-50"
                    onClick={(event) => event.stopPropagation()}
                    aria-label="Open ToolChain actions"
                  >
                    <MoreHorizontal className="h-4 w-4" />
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem
                      onClick={(event) => {
                        event.stopPropagation();
                        navigate(`/toolchains/${row.id}/designer`);
                      }}
                    >
                      Designer
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      onClick={(event) => {
                        event.stopPropagation();
                        navigate(`/toolchains/${row.id}/runs`);
                      }}
                    >
                      Runs
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      onClick={(event) => {
                        event.stopPropagation();
                        setRunToolChainId(row.id);
                        setRunDialogOpen(true);
                      }}
                    >
                      Run
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      disabled={!runtimeSupportedByToolChainId.get(row.id)}
                      onClick={(event) => {
                        event.stopPropagation();
                        const metadata = parseMetadata(row.metadataJson);
                        const runtimeRef = metadata.runtimeModelRef || metadata.defaultModelRef || null;
                        const nextProvider = String(runtimeRef?.providerID || providerList[0] || "");
                        const providerModels = modelsByProvider.get(nextProvider)?.models || [];
                        const nextModel =
                          runtimeRef?.providerID && runtimeRef?.modelID
                            ? modelRefKey({ providerID: runtimeRef.providerID, modelID: runtimeRef.modelID })
                            : providerModels[0]
                            ? modelRefKey({ providerID: providerModels[0].providerID, modelID: providerModels[0].modelID })
                            : "";
                        setRuntimeToolChainId(row.id);
                        setRuntimeProvider(nextProvider);
                        setRuntimeModel(nextModel);
                        setRuntimeDialogOpen(true);
                      }}
                    >
                      Configure Runtime Llm
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      variant="destructive"
                      onClick={(event) => {
                        event.stopPropagation();
                        const confirmed = window.confirm(
                          `Delete "${row.name}"? This will permanently remove all versions, runs, approvals, and config sessions.`
                        );
                        if (!confirmed) return;
                        deleteMutation.mutate(row.id);
                      }}
                    >
                      Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </TableCell>
            </TableRow>
          ))}
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-slate-500">No ToolChains Found</TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create ToolChain</DialogTitle>
            <DialogDescription>Start with a draft and add a version in the designer.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="ToolChain name" />
            <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Description" />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button onClick={() => createMutation.mutate()} disabled={!name.trim() || createMutation.isPending}>
              {createMutation.isPending ? "Creating..." : "Create"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ToolChainRunInputDialog
        open={runDialogOpen}
        onOpenChange={setRunDialogOpen}
        toolChainId={runToolChainId}
        triggerSource="ui"
        onExecuted={(result) => {
          if (result?.runId) {
            navigate(`/toolchains/runs/${result.runId}`);
          }
        }}
      />

      <Dialog open={runtimeDialogOpen} onOpenChange={setRuntimeDialogOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>Configure Runtime Llm Model</DialogTitle>
            <DialogDescription>
              Choose the model used at runtime for hybrid/AI text synthesis.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <SearchableSelect
              options={providerList.map((pid) => ({
                value: pid,
                label: modelsByProvider.get(pid)?.providerName || pid,
                sublabel: pid,
              }))}
              value={runtimeProvider}
              onValueChange={(pid) => {
                setRuntimeProvider(pid);
                const first = modelsByProvider.get(pid)?.models?.[0];
                if (first) {
                  setRuntimeModel(modelRefKey({ providerID: first.providerID, modelID: first.modelID }));
                } else {
                  setRuntimeModel("");
                }
              }}
              placeholder="Provider"
              searchPlaceholder="Search providers..."
            />
            <SearchableSelect
              options={(modelsByProvider.get(runtimeProvider)?.models || []).map((m: any) => ({
                value: modelRefKey({ providerID: m.providerID, modelID: m.modelID }),
                label: m.displayName || m.modelID,
                sublabel: m.modelID,
              }))}
              value={runtimeModel}
              onValueChange={(value) => setRuntimeModel(value)}
              placeholder="Model"
              searchPlaceholder="Search models..."
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRuntimeDialogOpen(false)}>Cancel</Button>
            <Button onClick={() => saveRuntimeModelMutation.mutate()} disabled={!runtimeModel || saveRuntimeModelMutation.isPending}>
              {saveRuntimeModelMutation.isPending ? "Saving..." : "Save"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
