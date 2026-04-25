import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "@/services/api";
import type { AgentTool, ToolDomain } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from "sonner";

type ToolActionModal = "none" | "tool-action-options" | "manual" | "openapi" | "curl";
const TOOLS_PAGE_SIZE = 10;

export default function ToolDomainPage() {
  const navigate = useNavigate();
  const { domainId = "" } = useParams();
  const queryClient = useQueryClient();

  const [activeModal, setActiveModal] = useState<ToolActionModal>("none");
  const [search, setSearch] = useState("");
  const [toolsPage, setToolsPage] = useState(1);

  const [newToolName, setNewToolName] = useState("");
  const [newToolMethod, setNewToolMethod] = useState("GET");
  const [newToolHost, setNewToolHost] = useState("");
  const [newToolEndpoint, setNewToolEndpoint] = useState("");
  const [toolError, setToolError] = useState("");

  const [openApiMode, setOpenApiMode] = useState<"json" | "url">("json");
  const [openApiSpec, setOpenApiSpec] = useState("");
  const [openApiSpecUrl, setOpenApiSpecUrl] = useState("");
  const [openApiError, setOpenApiError] = useState("");

  const [curlToolName, setCurlToolName] = useState("");
  const [curlText, setCurlText] = useState("");
  const [curlResponseSample, setCurlResponseSample] = useState("");
  const [curlError, setCurlError] = useState("");

  const { data: domains = [] } = useQuery<ToolDomain[]>({
    queryKey: ["tool-domains"],
    queryFn: () => api.get("/tool-domains"),
  });

  const { data: tools = [] } = useQuery<AgentTool[]>({
    queryKey: ["tools-by-domain", domainId],
    queryFn: () => api.get(`/tool-domains/${domainId}/tools`),
    enabled: !!domainId,
  });

  const domain = useMemo(() => domains.find((d) => d.id === domainId), [domains, domainId]);
  const visibleTools = useMemo(
    () => tools.filter((t) => t.sourceType !== "framework_default" && t.sourceType !== "mcp"),
    [tools]
  );

  const filteredTools = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return visibleTools;
    return visibleTools.filter((t) =>
      [t.name, t.sourceType, t.method, t.host, t.endpoint, t.enabled ? "enabled" : "disabled"]
        .map((v) => String(v || "").toLowerCase())
        .some((v) => v.includes(q))
    );
  }, [visibleTools, search]);

  const totalPages = Math.max(1, Math.ceil(filteredTools.length / TOOLS_PAGE_SIZE));
  const pagedTools = useMemo(() => {
    const start = (toolsPage - 1) * TOOLS_PAGE_SIZE;
    return filteredTools.slice(start, start + TOOLS_PAGE_SIZE);
  }, [filteredTools, toolsPage]);

  useEffect(() => {
    setToolsPage(1);
  }, [domainId, search]);

  useEffect(() => {
    if (toolsPage > totalPages) setToolsPage(totalPages);
  }, [toolsPage, totalPages]);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["tool-domains"] });
    if (domainId) queryClient.invalidateQueries({ queryKey: ["tools-by-domain", domainId] });
  };

  const createTool = useMutation({
    mutationFn: () =>
      api.post(`/tool-domains/${domainId}/tools`, {
        name: newToolName,
        description: "Manual tool",
        sourceType: "manual",
        executionKind: "http_proxy",
        permissionScope: "http_proxy",
        method: newToolMethod,
        host: newToolHost.trim() || undefined,
        endpoint: newToolEndpoint,
        enabled: true,
      }),
    onSuccess: () => {
      refresh();
      closeModal();
      toast.success("Tool created");
    },
    onError: (e: any) => toast.error(e.message || "Failed to create tool"),
  });

  const importOpenApi = useMutation({
    mutationFn: () =>
      api.post("/tool-domains/import/openapi", {
        domainId,
        spec: openApiMode === "json" ? openApiSpec : undefined,
        specUrl: openApiMode === "url" ? openApiSpecUrl : undefined,
        enabled: true,
      }),
    onSuccess: () => {
      refresh();
      closeModal();
      toast.success("OpenAPI tools imported");
    },
    onError: (e: any) => toast.error(e.message || "OpenAPI import failed"),
  });

  const importCurl = useMutation({
    mutationFn: () =>
      api.post("/tool-domains/import/curl", {
        domainId,
        curlCommand: curlText,
        toolName: curlToolName || undefined,
        responseSample: curlResponseSample || undefined,
        enabled: true,
      }),
    onSuccess: () => {
      refresh();
      closeModal();
      toast.success("cURL tool imported");
    },
    onError: (e: any) => toast.error(e.message || "cURL import failed"),
  });

  const toggleTool = async (tool: AgentTool) => {
    try {
      await api.post(`/tool-domains/${tool.domainId}/tools/${tool.id}/${tool.enabled ? "disable" : "enable"}`);
      refresh();
    } catch (e: any) {
      toast.error(e.message || "Failed to update tool");
    }
  };

  const deleteTool = async (tool: AgentTool) => {
    try {
      await api.delete(`/tool-domains/${tool.domainId}/tools/${tool.id}`);
      refresh();
    } catch (e: any) {
      toast.error(e.message || "Failed to delete tool");
    }
  };

  const resetManualToolForm = () => {
    setNewToolName("");
    setNewToolMethod("GET");
    setNewToolHost("");
    setNewToolEndpoint("");
    setToolError("");
  };

  const resetOpenApiForm = () => {
    setOpenApiMode("json");
    setOpenApiSpec("");
    setOpenApiSpecUrl("");
    setOpenApiError("");
  };

  const resetCurlForm = () => {
    setCurlToolName("");
    setCurlText("");
    setCurlResponseSample("");
    setCurlError("");
  };

  const closeModal = () => {
    setActiveModal("none");
    resetManualToolForm();
    resetOpenApiForm();
    resetCurlForm();
  };

  const handleCreateTool = () => {
    setToolError("");
    if (!newToolName.trim()) {
      setToolError("Tool name is required");
      return;
    }
    if (!newToolEndpoint.trim()) {
      setToolError("Endpoint / path is required");
      return;
    }
    if (newToolHost.trim()) {
      try {
        new URL(newToolHost.trim());
      } catch {
        setToolError("Host must be a valid URL (e.g. https://api.example.com)");
        return;
      }
    } else {
      try {
        new URL(newToolEndpoint.trim());
      } catch {
        setToolError("Endpoint must be a full URL when no host is specified");
        return;
      }
    }
    createTool.mutate();
  };

  const handleImportOpenApi = () => {
    setOpenApiError("");
    if (openApiMode === "json") {
      try {
        JSON.parse(openApiSpec);
      } catch {
        setOpenApiError("OpenAPI spec must be valid JSON");
        return;
      }
      importOpenApi.mutate();
      return;
    }
    if (!openApiSpecUrl.trim()) {
      setOpenApiError("OpenAPI spec URL is required");
      return;
    }
    try {
      new URL(openApiSpecUrl.trim());
    } catch {
      setOpenApiError("OpenAPI spec URL must be a valid URL");
      return;
    }
    importOpenApi.mutate();
  };

  const handleImportCurl = () => {
    setCurlError("");
    if (!curlText.trim().toLowerCase().startsWith("curl ")) {
      setCurlError("cURL import must start with 'curl '");
      return;
    }
    importCurl.mutate();
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2">
        <div className="space-y-1">
          <h3 className="text-xl font-semibold text-[#123262]">Tools in Domain</h3>
          <p className="text-sm text-gray-600">{domain ? domain.name : "Loading domain..."}</p>
        </div>
        <Button variant="outline" onClick={() => navigate("/tools")}>Back to Domains</Button>
      </div>

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tools"
            className="max-w-sm"
          />
          <Button size="sm" onClick={() => setActiveModal("tool-action-options")}>
            Add / Import Tool
          </Button>
        </div>

        <Table>
          <TableHeader>
            <TableRow className="h-9">
              <TableHead>Name</TableHead>
              <TableHead>Source</TableHead>
              <TableHead>Method</TableHead>
              <TableHead>Host</TableHead>
              <TableHead>Endpoint</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="w-[180px] text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredTools.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-sm text-gray-500">
                  No tools found in this domain.
                </TableCell>
              </TableRow>
            ) : (
              pagedTools.map((t) => (
                <TableRow key={t.id} className="h-9">
                  <TableCell className="max-w-[180px] truncate font-medium" title={t.name}>{t.name}</TableCell>
                  <TableCell>{t.sourceType}</TableCell>
                  <TableCell>{t.method || "-"}</TableCell>
                  <TableCell className="max-w-[220px] truncate text-xs text-gray-500" title={t.host || "-"}>{t.host || "-"}</TableCell>
                  <TableCell className="max-w-[240px] truncate" title={t.endpoint || "-"}>{t.endpoint || "-"}</TableCell>
                  <TableCell>{t.enabled ? "Enabled" : "Disabled"}</TableCell>
                  <TableCell className="text-right">
                    <div className="inline-flex gap-1">
                      <Button size="sm" variant="outline" onClick={() => toggleTool(t)}>
                        {t.enabled ? "Disable" : "Enable"}
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => deleteTool(t)}>Delete</Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>

        {filteredTools.length > 0 && (
          <div className="flex items-center justify-end gap-2 pt-1">
            <Button
              size="sm"
              variant="outline"
              onClick={() => setToolsPage((p) => Math.max(1, p - 1))}
              disabled={toolsPage <= 1}
            >
              Previous
            </Button>
            <span className="text-xs text-gray-600">Page {toolsPage} of {totalPages}</span>
            <Button
              size="sm"
              variant="outline"
              onClick={() => setToolsPage((p) => Math.min(totalPages, p + 1))}
              disabled={toolsPage >= totalPages}
            >
              Next
            </Button>
          </div>
        )}
      </section>

      <Dialog open={activeModal === "tool-action-options"} onOpenChange={(open) => !open && closeModal()}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Choose Tool Action</DialogTitle>
            <DialogDescription>Pick one action and continue with a focused workflow.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Button variant="outline" className="justify-start" onClick={() => setActiveModal("manual")}>Create Manual HTTP Tool</Button>
            <Button variant="outline" className="justify-start" onClick={() => setActiveModal("openapi")}>Import from OpenAPI</Button>
            <Button variant="outline" className="justify-start" onClick={() => setActiveModal("curl")}>Import from cURL</Button>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeModal}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={activeModal === "manual"} onOpenChange={(open) => !open && closeModal()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Create Manual HTTP Tool</DialogTitle>
            <DialogDescription>Manual tools are HTTP-only in this workspace.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-2 md:grid-cols-12">
            <Input value={newToolName} onChange={(e) => { setNewToolName(e.target.value); setToolError(""); }} placeholder="Tool name (required)" className="md:col-span-3" />
            <select className="h-10 rounded-md border border-input bg-background px-3 text-sm md:col-span-2" value={newToolMethod} onChange={(e) => setNewToolMethod(e.target.value)}>
              <option value="GET">GET</option>
              <option value="POST">POST</option>
              <option value="PUT">PUT</option>
              <option value="PATCH">PATCH</option>
              <option value="DELETE">DELETE</option>
            </select>
            <Input value={newToolHost} onChange={(e) => { setNewToolHost(e.target.value); setToolError(""); }} placeholder="https://api.example.com (optional host)" className="md:col-span-3" />
            <Input value={newToolEndpoint} onChange={(e) => { setNewToolEndpoint(e.target.value); setToolError(""); }} placeholder="/path or full URL if no host" className="md:col-span-4" />
          </div>
          {toolError ? <p className="text-xs text-red-600">{toolError}</p> : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => setActiveModal("tool-action-options")}>Back</Button>
            <Button onClick={handleCreateTool} disabled={createTool.isPending}>{createTool.isPending ? "Creating..." : "Create Tool"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={activeModal === "openapi"} onOpenChange={(open) => !open && closeModal()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Import from OpenAPI</DialogTitle>
            <DialogDescription>Import tools from OpenAPI JSON or URL.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <div className="inline-flex gap-2">
              <Button size="sm" variant={openApiMode === "json" ? "default" : "outline"} onClick={() => setOpenApiMode("json")}>JSON</Button>
              <Button size="sm" variant={openApiMode === "url" ? "default" : "outline"} onClick={() => setOpenApiMode("url")}>URL</Button>
            </div>
            {openApiMode === "json" ? (
              <textarea value={openApiSpec} onChange={(e) => { setOpenApiSpec(e.target.value); setOpenApiError(""); }} className="h-48 w-full rounded-md border border-input p-2 text-xs" placeholder="Paste OpenAPI JSON" />
            ) : (
              <Input value={openApiSpecUrl} onChange={(e) => { setOpenApiSpecUrl(e.target.value); setOpenApiError(""); }} placeholder="https://example.com/openapi.json" />
            )}
            {openApiError ? <p className="text-xs text-red-600">{openApiError}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setActiveModal("tool-action-options")}>Back</Button>
            <Button onClick={handleImportOpenApi} disabled={importOpenApi.isPending || (openApiMode === "json" ? !openApiSpec.trim() : !openApiSpecUrl.trim())}>
              {importOpenApi.isPending ? "Importing..." : "Import OpenAPI"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={activeModal === "curl"} onOpenChange={(open) => !open && closeModal()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Import from cURL</DialogTitle>
            <DialogDescription>Import a single tool from a cURL request and optional response sample.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Input value={curlToolName} onChange={(e) => setCurlToolName(e.target.value)} placeholder="Optional tool name" />
            <textarea value={curlText} onChange={(e) => { setCurlText(e.target.value); setCurlError(""); }} className="h-32 w-full rounded-md border border-input p-2 text-xs" placeholder="curl https://..." />
            <textarea value={curlResponseSample} onChange={(e) => setCurlResponseSample(e.target.value)} className="h-24 w-full rounded-md border border-input p-2 text-xs" placeholder="Optional response sample (JSON or text)" />
            {curlError ? <p className="text-xs text-red-600">{curlError}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setActiveModal("tool-action-options")}>Back</Button>
            <Button onClick={handleImportCurl} disabled={importCurl.isPending || !curlText.trim()}>
              {importCurl.isPending ? "Importing..." : "Import cURL"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
