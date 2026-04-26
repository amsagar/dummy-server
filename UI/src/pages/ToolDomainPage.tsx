import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "@/services/api";
import type { AgentTool, ToolAuthProfile, ToolDomain } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { MoreHorizontal } from "lucide-react";
import { toast } from "sonner";
import yaml from "js-yaml";

type ToolActionModal = "none" | "tool-action-options" | "openapi" | "curl" | "auth" | "override-auth";
const TOOLS_PAGE_SIZE = 10;

export default function ToolDomainPage() {
  const navigate = useNavigate();
  const { domainId = "" } = useParams();
  const queryClient = useQueryClient();

  const [activeModal, setActiveModal] = useState<ToolActionModal>("none");
  const [search, setSearch] = useState("");
  const [toolsPage, setToolsPage] = useState(1);

  const [openApiMode, setOpenApiMode] = useState<"file" | "url">("file");
  const [openApiFile, setOpenApiFile] = useState<File | null>(null);
  const [openApiFileText, setOpenApiFileText] = useState("");
  const [openApiSpecUrl, setOpenApiSpecUrl] = useState("");
  const [openApiError, setOpenApiError] = useState("");

  const [curlToolName, setCurlToolName] = useState("");
  const [curlText, setCurlText] = useState("");
  const [curlResponseSample, setCurlResponseSample] = useState("");
  const [curlError, setCurlError] = useState("");
  const [selectedTool, setSelectedTool] = useState<AgentTool | null>(null);
  const [overrideEnabled, setOverrideEnabled] = useState(false);
  const [overrideAuthType, setOverrideAuthType] = useState("bearer_token");
  const [overrideError, setOverrideError] = useState("");
  const [overrideForm, setOverrideForm] = useState({
    headerName: "x-api-key",
    paramName: "api_key",
    apiKey: "",
    token: "",
    username: "",
    password: "",
    clientId: "",
    clientSecret: "",
    tokenUrl: "",
    authorizationUrl: "",
    scopes: "",
    requestUrl: "",
    requestMethod: "POST",
    requestBodyTemplate: "",
    templateVars: "{}",
    requestHeaders: "{}",
    responseTokenPath: "access_token",
    responseExpiresInPath: "expires_in",
    tokenHeaderName: "Authorization",
    tokenPrefix: "Bearer",
  });

  const { data: domains = [] } = useQuery<ToolDomain[]>({
    queryKey: ["tool-domains"],
    queryFn: () => api.get("/tool-domains"),
  });

  const { data: tools = [] } = useQuery<AgentTool[]>({
    queryKey: ["tools-by-domain", domainId],
    queryFn: () => api.get(`/tool-domains/${domainId}/tools`),
    enabled: !!domainId,
  });
  const { data: authProfiles = [] } = useQuery<ToolAuthProfile[]>({
    queryKey: ["tool-auth-profiles", domainId],
    queryFn: () => api.get(`/tool-auth/domains/${domainId}/profiles`),
    enabled: !!domainId,
  });

  const domain = useMemo(() => domains.find((d) => d.id === domainId), [domains, domainId]);
  const domainProfile = useMemo(() => authProfiles[0] ?? null, [authProfiles]);
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
    if (domainId) queryClient.invalidateQueries({ queryKey: ["tool-auth-profiles", domainId] });
  };

  const importOpenApi = useMutation({
    mutationFn: () =>
      api.post("/tool-domains/import/openapi", {
        domainId,
        spec: openApiMode === "file" ? openApiFileText : undefined,
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

  const clearToolOverride = useMutation({
    mutationFn: () =>
      api.patch(`/tool-auth/domains/${domainId}/tools/${selectedTool?.id}/binding`, {
        authProfileId: null,
        authOverrideEnabled: false,
      }),
    onSuccess: () => {
      refresh();
      setOverrideEnabled(false);
      toast.success("Tool override cleared. Domain profile is now inherited.");
    },
    onError: (e: any) => toast.error(e.message || "Failed to clear tool override"),
  });

  const connectToolOverride = useMutation({
    mutationFn: async ({ oauthAuthCode }: { oauthAuthCode: boolean }) => {
      if (!selectedTool) throw new Error("Select a tool first");
      const payload = buildOverridePayload();
      const validation = validateOverridePayload(overrideAuthType, payload);
      if (!validation.valid) throw new Error(validation.message);
      await api.patch(`/tool-auth/domains/${domainId}/tools/${selectedTool.id}/binding`, {
        authProfileId: null,
        authOverrideEnabled: true,
      });
      if (oauthAuthCode) {
        const oauth: any = await api.post(`/tool-auth/tools/${selectedTool.id}/oauth/authorization-url`, payload);
        if (oauth?.authorizationUrl) {
          window.open(oauth.authorizationUrl, "_blank", "noopener,noreferrer");
        }
      } else {
        await api.post(`/tool-auth/tools/${selectedTool.id}/connect/${overrideAuthType}`, payload);
      }
      return { oauthAuthCode };
    },
    onSuccess: (result) => {
      refresh();
      setOverrideEnabled(true);
      setActiveModal("auth");
      toast.success(result.oauthAuthCode ? "Override auth initiated" : "Override auth connected");
    },
    onError: (e: any) => {
      setOverrideError(e.message || "Failed to connect override");
      toast.error(e.message || "Failed to connect override");
    },
  });

  const reauthenticateOverride = useMutation({
    mutationFn: async () => {
      if (!selectedTool) throw new Error("Select a tool first");
      await api.post(`/tool-auth/tools/${selectedTool.id}/reauthenticate`);
    },
    onSuccess: () => {
      refresh();
      toast.success("Override reauthenticated");
    },
    onError: (e: any) => toast.error(e.message || "Failed to reauthenticate override"),
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

  const resetOpenApiForm = () => {
    setOpenApiMode("file");
    setOpenApiFile(null);
    setOpenApiFileText("");
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
    resetOpenApiForm();
    resetCurlForm();
    setSelectedTool(null);
    setOverrideError("");
  };

  const openAuthModal = (tool: AgentTool) => {
    setSelectedTool(tool);
    setOverrideEnabled(Boolean(tool.authOverrideEnabled));
    setOverrideAuthType(tool.authType || "bearer_token");
    const cfg = typeof tool.authConfig === "object" && tool.authConfig !== null ? tool.authConfig as any : {};
    setOverrideForm((prev) => ({
      ...prev,
      headerName: String(cfg.headerName || prev.headerName),
      paramName: String(cfg.paramName || prev.paramName),
      token: "",
      apiKey: "",
      username: String(cfg.username && cfg.username !== "***" ? cfg.username : ""),
      password: "",
      clientId: tool.clientId || prev.clientId,
      clientSecret: "",
      tokenUrl: tool.tokenUrl || prev.tokenUrl,
      authorizationUrl: tool.authorizationUrl || prev.authorizationUrl,
      scopes: tool.scopes || prev.scopes,
      requestUrl: String(cfg.requestUrl || prev.requestUrl),
      requestMethod: String(cfg.requestMethod || prev.requestMethod),
      requestBodyTemplate: String(cfg.requestBodyTemplate || prev.requestBodyTemplate),
      templateVars: JSON.stringify(cfg.templateVars || {}, null, 0),
      requestHeaders: JSON.stringify(cfg.requestHeaders || {}, null, 0),
      responseTokenPath: String(cfg.responseTokenPath || prev.responseTokenPath),
      responseExpiresInPath: String(cfg.responseExpiresInPath || prev.responseExpiresInPath),
      tokenHeaderName: String(cfg.tokenHeaderName || prev.tokenHeaderName),
      tokenPrefix: String(cfg.tokenPrefix || prev.tokenPrefix),
    }));
    setOverrideError("");
    setActiveModal("auth");
  };

  const handleOpenApiFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    setOpenApiError("");
    setOpenApiFileText("");
    const file = e.target.files?.[0] ?? null;
    setOpenApiFile(file);
    if (!file) return;
    try {
      const raw = await file.text();
      const isYaml = /\.(ya?ml)$/i.test(file.name);
      const parsed = isYaml ? yaml.load(raw) : JSON.parse(raw);
      if (!parsed || typeof parsed !== "object") throw new Error("Spec must be an object");
      setOpenApiFileText(JSON.stringify(parsed));
    } catch (err: any) {
      setOpenApiError(`Could not parse file: ${err?.message ?? "invalid spec"}`);
      setOpenApiFileText("");
    }
  };

  const handleImportOpenApi = () => {
    setOpenApiError("");
    if (openApiMode === "file") {
      if (!openApiFileText) {
        setOpenApiError("Choose a valid OpenAPI file (YAML or JSON)");
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

  const parseJsonInput = (raw: string, fallback: any) => {
    if (!raw.trim()) return fallback;
    try {
      return JSON.parse(raw);
    } catch {
      return fallback;
    }
  };

  const buildOverridePayload = () => {
    if (overrideAuthType === "none") return {};
    if (overrideAuthType === "api_key_header") return { headerName: overrideForm.headerName, apiKey: overrideForm.apiKey };
    if (overrideAuthType === "api_key_query") return { paramName: overrideForm.paramName || "api_key", apiKey: overrideForm.apiKey };
    if (overrideAuthType === "bearer_token") return { token: overrideForm.token };
    if (overrideAuthType === "basic_auth") return { username: overrideForm.username, password: overrideForm.password };
    if (overrideAuthType === "oauth_client_credentials") {
      return {
        clientId: overrideForm.clientId,
        clientSecret: overrideForm.clientSecret,
        tokenUrl: overrideForm.tokenUrl,
        scopes: overrideForm.scopes || undefined,
      };
    }
    if (overrideAuthType === "oauth_auth_code") {
      return {
        clientId: overrideForm.clientId,
        clientSecret: overrideForm.clientSecret,
        tokenUrl: overrideForm.tokenUrl,
        authorizationUrl: overrideForm.authorizationUrl,
        scopes: overrideForm.scopes || undefined,
      };
    }
    if (overrideAuthType === "custom_token_api") {
      return {
        requestUrl: overrideForm.requestUrl,
        requestMethod: overrideForm.requestMethod,
        requestBodyTemplate: overrideForm.requestBodyTemplate,
        templateVars: parseJsonInput(overrideForm.templateVars, {}),
        requestHeaders: parseJsonInput(overrideForm.requestHeaders, {}),
        responseTokenPath: overrideForm.responseTokenPath || "access_token",
        responseExpiresInPath: overrideForm.responseExpiresInPath || "expires_in",
        tokenHeaderName: overrideForm.tokenHeaderName || "Authorization",
        tokenPrefix: overrideForm.tokenPrefix || "Bearer",
      };
    }
    return {};
  };

  const validateOverridePayload = (type: string, payload: any): { valid: boolean; message: string } => {
    if (type === "none") return { valid: true, message: "" };
    if (type === "api_key_header") {
      if (!String(payload?.headerName || "").trim()) return { valid: false, message: "Header name is required." };
      if (!String(payload?.apiKey || "").trim()) return { valid: false, message: "API key is required." };
      return { valid: true, message: "" };
    }
    if (type === "api_key_query") {
      if (!String(payload?.paramName || "").trim()) return { valid: false, message: "Query param name is required." };
      if (!String(payload?.apiKey || "").trim()) return { valid: false, message: "API key is required." };
      return { valid: true, message: "" };
    }
    if (type === "bearer_token") {
      if (!String(payload?.token || "").trim()) return { valid: false, message: "Bearer token is required." };
      return { valid: true, message: "" };
    }
    if (type === "basic_auth") {
      if (!String(payload?.username || "").trim()) return { valid: false, message: "Username is required." };
      if (!String(payload?.password || "").trim()) return { valid: false, message: "Password is required." };
      return { valid: true, message: "" };
    }
    if (type === "oauth_client_credentials") {
      if (!String(payload?.clientId || "").trim()) return { valid: false, message: "Client ID is required." };
      if (!String(payload?.clientSecret || "").trim()) return { valid: false, message: "Client secret is required." };
      if (!String(payload?.tokenUrl || "").trim()) return { valid: false, message: "Token URL is required." };
      return { valid: true, message: "" };
    }
    if (type === "oauth_auth_code") {
      if (!String(payload?.clientId || "").trim()) return { valid: false, message: "Client ID is required." };
      if (!String(payload?.clientSecret || "").trim()) return { valid: false, message: "Client secret is required." };
      if (!String(payload?.tokenUrl || "").trim()) return { valid: false, message: "Token URL is required." };
      if (!String(payload?.authorizationUrl || "").trim()) return { valid: false, message: "Authorization URL is required." };
      return { valid: true, message: "" };
    }
    if (type === "custom_token_api") {
      if (!String(payload?.requestUrl || "").trim()) return { valid: false, message: "Token API URL is required." };
      if (!String(payload?.responseTokenPath || "").trim()) return { valid: false, message: "Response token path is required." };
      return { valid: true, message: "" };
    }
    return { valid: false, message: "Select a valid auth type." };
  };

  const hasActiveDomainProfile = Boolean(domainProfile?.enabled);
  const activeAuthMode = overrideEnabled ? "tool_override" : "domain_inherited";
  const activeAuthLabel = activeAuthMode === "tool_override" ? "Tool Override Active" : "Domain Inherited Active";
  const activeAuthDescription = activeAuthMode === "tool_override"
    ? "This tool now uses its own override credentials. Domain profile is bypassed."
    : hasActiveDomainProfile
      ? "This tool is currently using the domain auth profile."
      : "No enabled domain profile found. Configure one from the Domains page.";

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
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button size="icon" variant="outline" className="h-8 w-8">
                          <MoreHorizontal size={14} />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="w-40">
                        <DropdownMenuItem onClick={() => openAuthModal(t)}>Auth</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => toggleTool(t)}>{t.enabled ? "Disable" : "Enable"}</DropdownMenuItem>
                        <DropdownMenuItem variant="destructive" onClick={() => deleteTool(t)}>Delete</DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
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
            <Button variant="outline" className="justify-start" onClick={() => setActiveModal("openapi")}>Import from OpenAPI</Button>
            <Button variant="outline" className="justify-start" onClick={() => setActiveModal("curl")}>Import from cURL</Button>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeModal}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={activeModal === "openapi"} onOpenChange={(open) => !open && closeModal()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Import from OpenAPI</DialogTitle>
            <DialogDescription>Upload an OpenAPI spec file (YAML or JSON) or provide a URL.</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="inline-flex gap-2">
              <Button size="sm" variant={openApiMode === "file" ? "default" : "outline"} onClick={() => setOpenApiMode("file")}>File</Button>
              <Button size="sm" variant={openApiMode === "url" ? "default" : "outline"} onClick={() => setOpenApiMode("url")}>URL</Button>
            </div>
            {openApiMode === "file" ? (
              <div className="space-y-2">
                <input
                  type="file"
                  accept=".json,.yaml,.yml,application/json,application/x-yaml,text/yaml"
                  onChange={handleOpenApiFileChange}
                  className="block w-full text-sm"
                />
                {openApiFile ? (
                  <p className="text-xs text-gray-600">Loaded: {openApiFile.name} ({openApiFile.size} bytes)</p>
                ) : null}
              </div>
            ) : (
              <Input value={openApiSpecUrl} onChange={(e) => { setOpenApiSpecUrl(e.target.value); setOpenApiError(""); }} placeholder="https://example.com/openapi.json" />
            )}
            {openApiError ? <p className="text-xs text-red-600">{openApiError}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setActiveModal("tool-action-options")}>Back</Button>
            <Button
              onClick={handleImportOpenApi}
              disabled={importOpenApi.isPending || (openApiMode === "file" ? !openApiFileText : !openApiSpecUrl.trim())}
            >
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

      <Dialog open={activeModal === "auth"} onOpenChange={(open) => !open && closeModal()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Tool Authentication</DialogTitle>
            <DialogDescription>
              Configure auth behavior for <span className="font-medium">{selectedTool?.name}</span>.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="rounded border border-slate-200 bg-slate-50 p-3">
              <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Active Mode</p>
              <p className="mt-1 text-sm font-semibold text-[#123262]">{activeAuthLabel}</p>
              <p className="mt-1 text-xs text-slate-600">{activeAuthDescription}</p>
            </div>

            <div className="space-y-3 rounded border p-3">
              <p className="text-sm font-semibold text-[#123262]">Inherited Domain Profile</p>
              <p className="text-xs text-slate-500">
                Domain profile applies by default to every tool in this domain unless tool override is enabled.
              </p>
              <div className="grid gap-1 text-xs text-slate-700">
                <p>Profile: <span className="font-medium">{domainProfile?.name || "Not configured"}</span></p>
                <p>Auth type: <span className="font-medium">{domainProfile?.authType || "none"}</span></p>
                <p>Status: <span className="font-medium">{domainProfile?.enabled ? "Enabled" : "Disabled / Missing"}</span></p>
              </div>
            </div>

            <div className="space-y-3 rounded border p-3">
              <p className="text-sm font-semibold text-[#123262]">Tool Override</p>
              <p className="text-xs text-slate-500">Enable this only when this tool needs dedicated credentials.</p>
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  variant={overrideEnabled ? "default" : "outline"}
                  onClick={() => {
                    setOverrideEnabled(true);
                    setActiveModal("override-auth");
                  }}
                >
                  Use Tool Override
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setActiveModal("override-auth")}
                  disabled={!selectedTool || !overrideEnabled}
                >
                  Connect Override
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => reauthenticateOverride.mutate()}
                  disabled={!selectedTool || reauthenticateOverride.isPending || !overrideEnabled}
                >
                  {reauthenticateOverride.isPending ? "Reauth..." : "Reauthenticate"}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setOverrideEnabled(false);
                    clearToolOverride.mutate();
                  }}
                  disabled={clearToolOverride.isPending || !selectedTool || !overrideEnabled}
                >
                  Clear Override
                </Button>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeModal}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={activeModal === "override-auth"} onOpenChange={(open) => !open && setActiveModal("auth")}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Connect Tool Override</DialogTitle>
            <DialogDescription>Configure per-tool auth for <span className="font-medium">{selectedTool?.name}</span>.</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <select
              value={overrideAuthType}
              onChange={(e) => setOverrideAuthType(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
            >
              <option value="none">No Auth</option>
              <option value="api_key_header">API Key Header</option>
              <option value="api_key_query">API Key Query Param</option>
              <option value="bearer_token">Bearer Token</option>
              <option value="basic_auth">Basic Auth</option>
              <option value="oauth_client_credentials">OAuth Client Credentials</option>
              <option value="oauth_auth_code">OAuth Authorization Code</option>
              <option value="custom_token_api">Custom Token API</option>
            </select>

            {overrideAuthType === "api_key_header" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={overrideForm.headerName} onChange={(e) => setOverrideForm((s) => ({ ...s, headerName: e.target.value }))} placeholder="Header name" />
                <Input value={overrideForm.apiKey} onChange={(e) => setOverrideForm((s) => ({ ...s, apiKey: e.target.value }))} placeholder="API key" />
              </div>
            )}
            {overrideAuthType === "api_key_query" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={overrideForm.paramName} onChange={(e) => setOverrideForm((s) => ({ ...s, paramName: e.target.value }))} placeholder="Query param name" />
                <Input value={overrideForm.apiKey} onChange={(e) => setOverrideForm((s) => ({ ...s, apiKey: e.target.value }))} placeholder="API key" />
              </div>
            )}
            {overrideAuthType === "bearer_token" && (
              <Input value={overrideForm.token} onChange={(e) => setOverrideForm((s) => ({ ...s, token: e.target.value }))} placeholder="Bearer token" />
            )}
            {overrideAuthType === "basic_auth" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={overrideForm.username} onChange={(e) => setOverrideForm((s) => ({ ...s, username: e.target.value }))} placeholder="Username" />
                <Input type="password" value={overrideForm.password} onChange={(e) => setOverrideForm((s) => ({ ...s, password: e.target.value }))} placeholder="Password" />
              </div>
            )}
            {overrideAuthType === "oauth_client_credentials" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={overrideForm.clientId} onChange={(e) => setOverrideForm((s) => ({ ...s, clientId: e.target.value }))} placeholder="Client ID" />
                <Input type="password" value={overrideForm.clientSecret} onChange={(e) => setOverrideForm((s) => ({ ...s, clientSecret: e.target.value }))} placeholder="Client secret" />
                <Input value={overrideForm.tokenUrl} onChange={(e) => setOverrideForm((s) => ({ ...s, tokenUrl: e.target.value }))} placeholder="Token URL" />
                <Input value={overrideForm.scopes} onChange={(e) => setOverrideForm((s) => ({ ...s, scopes: e.target.value }))} placeholder="Scopes (optional)" />
              </div>
            )}
            {overrideAuthType === "oauth_auth_code" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={overrideForm.clientId} onChange={(e) => setOverrideForm((s) => ({ ...s, clientId: e.target.value }))} placeholder="Client ID" />
                <Input type="password" value={overrideForm.clientSecret} onChange={(e) => setOverrideForm((s) => ({ ...s, clientSecret: e.target.value }))} placeholder="Client secret" />
                <Input value={overrideForm.authorizationUrl} onChange={(e) => setOverrideForm((s) => ({ ...s, authorizationUrl: e.target.value }))} placeholder="Authorization URL" />
                <Input value={overrideForm.tokenUrl} onChange={(e) => setOverrideForm((s) => ({ ...s, tokenUrl: e.target.value }))} placeholder="Token URL" />
                <Input value={overrideForm.scopes} onChange={(e) => setOverrideForm((s) => ({ ...s, scopes: e.target.value }))} placeholder="Scopes (optional)" />
              </div>
            )}
            {overrideAuthType === "custom_token_api" && (
              <div className="grid gap-2">
                <div className="grid gap-2 md:grid-cols-2">
                  <Input value={overrideForm.requestUrl} onChange={(e) => setOverrideForm((s) => ({ ...s, requestUrl: e.target.value }))} placeholder="Token API URL" />
                  <Input value={overrideForm.requestMethod} onChange={(e) => setOverrideForm((s) => ({ ...s, requestMethod: e.target.value }))} placeholder="Request method (POST/GET)" />
                  <Input value={overrideForm.tokenHeaderName} onChange={(e) => setOverrideForm((s) => ({ ...s, tokenHeaderName: e.target.value }))} placeholder="Target header name" />
                  <Input value={overrideForm.tokenPrefix} onChange={(e) => setOverrideForm((s) => ({ ...s, tokenPrefix: e.target.value }))} placeholder="Token prefix (Bearer)" />
                  <Input value={overrideForm.responseTokenPath} onChange={(e) => setOverrideForm((s) => ({ ...s, responseTokenPath: e.target.value }))} placeholder="Response token path" />
                  <Input value={overrideForm.responseExpiresInPath} onChange={(e) => setOverrideForm((s) => ({ ...s, responseExpiresInPath: e.target.value }))} placeholder="Response expires path" />
                </div>
                <textarea value={overrideForm.requestBodyTemplate} onChange={(e) => setOverrideForm((s) => ({ ...s, requestBodyTemplate: e.target.value }))} className="h-20 w-full rounded-md border border-input p-2 text-xs" placeholder='Request body template, supports ${var}' />
                <textarea value={overrideForm.templateVars} onChange={(e) => setOverrideForm((s) => ({ ...s, templateVars: e.target.value }))} className="h-16 w-full rounded-md border border-input p-2 text-xs" placeholder='Template vars JSON: {"clientId":"..."}' />
                <textarea value={overrideForm.requestHeaders} onChange={(e) => setOverrideForm((s) => ({ ...s, requestHeaders: e.target.value }))} className="h-16 w-full rounded-md border border-input p-2 text-xs" placeholder='Request headers JSON: {"Content-Type":"application/json"}' />
              </div>
            )}
            {overrideError ? <p className="text-xs text-red-600">{overrideError}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setActiveModal("auth")}>Back</Button>
            <Button
              variant="outline"
              onClick={() => connectToolOverride.mutate({ oauthAuthCode: false })}
              disabled={connectToolOverride.isPending}
            >
              Save Override
            </Button>
            {overrideAuthType === "oauth_auth_code" && (
              <Button
                onClick={() => connectToolOverride.mutate({ oauthAuthCode: true })}
                disabled={connectToolOverride.isPending}
              >
                Authenticate
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
