import { useMemo, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { MoreHorizontal } from "lucide-react";
import { toast } from "sonner";
import ToolChainRunInputDialog from "@/components/toolchain/ToolChainRunInputDialog";
import { MappingEditorDialog } from "@/components/toolchain/MappingEditorDialog";
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
  const [templateOpen, setTemplateOpen] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState("");
  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const [runToolChainId, setRunToolChainId] = useState<string>("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [runtimeDialogOpen, setRuntimeDialogOpen] = useState(false);
  const [runtimeToolChainId, setRuntimeToolChainId] = useState("");
  const [runtimeProvider, setRuntimeProvider] = useState("");
  const [runtimeModel, setRuntimeModel] = useState("");
  const [activeTab, setActiveTab] = useState<"user" | "system">("user");
  const [mappingEditorOpen, setMappingEditorOpen] = useState(false);
  const [mappingEditorChain, setMappingEditorChain] = useState<{ id: string; name: string } | null>(null);

  const { data: userToolchains = [] } = useQuery<any[]>({
    queryKey: ["toolchains", "user"],
    queryFn: () => api.toolchains.list("user"),
  });
  const { data: systemToolchains = [] } = useQuery<any[]>({
    queryKey: ["toolchains", "system"],
    queryFn: () => api.toolchains.list("system_suggested"),
  });
  const { data: pendingSystemApprovalsData } = useQuery<any>({
    queryKey: ["toolchains", "pending-system-proposals"],
    queryFn: () => api.toolchains.pendingSystemProposals(),
  });
  const { data: templatesData } = useQuery<any>({
    queryKey: ["toolchain-templates"],
    queryFn: () => api.toolchains.templates(),
  });
  const templates = useMemo(
    () => (Array.isArray(templatesData?.templates) ? templatesData.templates : []),
    [templatesData]
  );
  const pendingSystemApprovals = useMemo(
    () => (Array.isArray(pendingSystemApprovalsData?.proposals) ? pendingSystemApprovalsData.proposals : []),
    [pendingSystemApprovalsData]
  );
  const toolchains = useMemo(
    () => (activeTab === "system" ? systemToolchains : userToolchains),
    [activeTab, systemToolchains, userToolchains]
  );
  const allToolchains = useMemo(() => [...userToolchains, ...systemToolchains], [systemToolchains, userToolchains]);

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
  const createFromTemplateMutation = useMutation({
    mutationFn: () => api.toolchains.createFromTemplate({ templateId: selectedTemplateId }),
    onSuccess: (result: any) => {
      setTemplateOpen(false);
      setSelectedTemplateId("");
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success("ToolChain created from template");
      if (result?.toolChainId) navigate(`/toolchains/${result.toolChainId}/designer`);
    },
    onError: (e: any) => toast.error(e.message || "Failed to create from template"),
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
      const row = allToolchains.find((item: any) => item.id === runtimeToolChainId);
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

  const approveToolChainMutation = useMutation({
    mutationFn: (id: string) => api.toolchains.approve(id, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success("System toolchain approved");
    },
    onError: (e: any) => toast.error(e.message || "Failed to approve toolchain"),
  });

  const rejectToolChainMutation = useMutation({
    mutationFn: (id: string) => api.toolchains.reject(id, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success("System toolchain rejected");
    },
    onError: (e: any) => toast.error(e.message || "Failed to reject toolchain"),
  });

  const approvePendingSystemProposalMutation = useMutation({
    mutationFn: (proposalId: string) => api.toolchains.approveSystemProposal(proposalId, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchains", "pending-system-proposals"] });
      queryClient.invalidateQueries({ queryKey: ["toolchains", "system"] });
      toast.success("System toolchain proposal approved");
    },
    onError: (e: any) => toast.error(e.message || "Failed to approve system proposal"),
  });

  const rejectPendingSystemProposalMutation = useMutation({
    mutationFn: (proposalId: string) => api.toolchains.rejectSystemProposal(proposalId, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchains", "pending-system-proposals"] });
      toast.success("System toolchain proposal rejected");
    },
    onError: (e: any) => toast.error(e.message || "Failed to reject system proposal"),
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
          <Button variant="outline" onClick={() => setTemplateOpen(true)}>From Template</Button>
          <Button onClick={() => setCreateOpen(true)}>New ToolChain</Button>
        </div>
      </div>

      <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search ToolChains" className="max-w-sm" />
      <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as "user" | "system")}>
        <TabsList variant="line">
          <TabsTrigger value="user">User Toolchains</TabsTrigger>
          <TabsTrigger value="system">System Toolchains</TabsTrigger>
        </TabsList>
      </Tabs>

      {activeTab === "system" && pendingSystemApprovals.length > 0 ? (
        <div className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 p-3">
          <div className="text-sm font-semibold text-amber-800">Pending System Toolchain Approvals</div>
          {pendingSystemApprovals.map((approval: any) => (
            <div key={String(approval.id)} className="flex items-center justify-between gap-3 rounded-md border border-amber-200 bg-white px-3 py-2">
              <div className="min-w-0">
                <div className="truncate text-sm text-slate-800">
                  {String(approval.reason || "System toolchain approval required.")}
                </div>
                <div className="text-xs text-slate-500">
                  Session {String(approval.sessionId || "").slice(0, 8)} • Turn {String(approval.turnId || "").slice(0, 8)} • Confidence {String(approval.confidence || "low")}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => rejectPendingSystemProposalMutation.mutate(String(approval.id))}
                  disabled={rejectPendingSystemProposalMutation.isPending}
                >
                  Reject
                </Button>
                <Button
                  size="sm"
                  onClick={() => approvePendingSystemProposalMutation.mutate(String(approval.id))}
                  disabled={approvePendingSystemProposalMutation.isPending}
                >
                  Approve
                </Button>
              </div>
            </div>
          ))}
        </div>
      ) : null}

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
              <TableCell className="font-medium">
                <div className="flex items-center gap-2">
                  <span>{row.name}</span>
                </div>
              </TableCell>
              <TableCell>
                {activeTab === "system" ? (
                  <span
                    className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${
                      String(row.approvalStatus || "").toLowerCase() === "approved"
                        ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                        : String(row.approvalStatus || "").toLowerCase() === "rejected"
                        ? "border-rose-200 bg-rose-50 text-rose-700"
                        : "border-amber-200 bg-amber-50 text-amber-700"
                    }`}
                  >
                    {toTitleCase(String(row.approvalStatus || "pending"))}
                  </span>
                ) : row.publishedVersion ? (
                  <span className="inline-flex items-center gap-1 rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">
                    Published v{row.publishedVersion}
                  </span>
                ) : (
                  <span className="inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs font-medium text-slate-600">
                    {toTitleCase(String(row.status || "Draft"))}
                  </span>
                )}
              </TableCell>
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
                      disabled={activeTab === "system" && String(row.approvalStatus || "").toLowerCase() !== "approved"}
                      onClick={(event) => {
                        event.stopPropagation();
                        if (activeTab === "system" && String(row.approvalStatus || "").toLowerCase() !== "approved") {
                          toast.error("Approve this system toolchain once before running it.");
                          return;
                        }
                        setRunToolChainId(row.id);
                        setRunDialogOpen(true);
                      }}
                    >
                      Run
                    </DropdownMenuItem>
                    {activeTab === "system" ? (
                      <>
                        <DropdownMenuItem
                          disabled={String(row.approvalStatus || "").toLowerCase() === "approved"}
                          onClick={(event) => {
                            event.stopPropagation();
                            approveToolChainMutation.mutate(row.id);
                          }}
                        >
                          Approve
                        </DropdownMenuItem>
                        <DropdownMenuItem
                          disabled={String(row.approvalStatus || "").toLowerCase() === "rejected"}
                          onClick={(event) => {
                            event.stopPropagation();
                            rejectToolChainMutation.mutate(row.id);
                          }}
                        >
                          Reject
                        </DropdownMenuItem>
                      </>
                    ) : null}
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
                          `Delete "${row.name}"? This will permanently remove this toolchain and all related versions, runs, approvals, and config sessions.`
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

      <Dialog open={templateOpen} onOpenChange={setTemplateOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create From Template</DialogTitle>
            <DialogDescription>Bootstrap a chain from curated starter flows.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <SearchableSelect
              options={templates.map((tpl: any) => ({
                value: String(tpl.id),
                label: String(tpl.name || tpl.id),
                sublabel: String(tpl.description || ""),
              }))}
              value={selectedTemplateId}
              onValueChange={(v) => setSelectedTemplateId(v)}
              placeholder="Select template"
              searchPlaceholder="Search templates..."
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setTemplateOpen(false)}>Cancel</Button>
            <Button
              onClick={() => createFromTemplateMutation.mutate()}
              disabled={!selectedTemplateId || createFromTemplateMutation.isPending}
            >
              {createFromTemplateMutation.isPending ? "Creating..." : "Create"}
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

      <MappingEditorDialog
        open={mappingEditorOpen}
        onOpenChange={(value) => {
          setMappingEditorOpen(value);
          if (!value) setMappingEditorChain(null);
        }}
        toolChainId={mappingEditorChain?.id || null}
        toolChainName={mappingEditorChain?.name}
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
