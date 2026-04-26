import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import type { ToolDomain } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { MoreHorizontal } from "lucide-react";
import { toast } from "sonner";
import { EmbeddingSetupBanner } from "@/components/EmbeddingSetupBanner";

export default function ToolsPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const [search, setSearch] = useState("");
  const [createDomainOpen, setCreateDomainOpen] = useState(false);
  const [newDomainName, setNewDomainName] = useState("");
  const [domainError, setDomainError] = useState("");
  const [authProfileOpen, setAuthProfileOpen] = useState(false);
  const [selectedDomainForAuth, setSelectedDomainForAuth] = useState<ToolDomain | null>(null);
  const [editingProfileId, setEditingProfileId] = useState<string | null>(null);
  const [authProfileName, setAuthProfileName] = useState("");
  const [authType, setAuthType] = useState("none");
  const [authForm, setAuthForm] = useState({
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
  const [authFormError, setAuthFormError] = useState("");

  const { data: domains = [] } = useQuery<ToolDomain[]>({
    queryKey: ["tool-domains"],
    queryFn: () => api.get("/tool-domains"),
  });

  const domainOptions = useMemo(
    () =>
      domains.filter((d) => {
        const name = d.name.toLowerCase();
        return !name.startsWith("framework ") && !name.startsWith("mcp ");
      }),
    [domains]
  );

  const { data: domainAuthSummary = {} } = useQuery<Record<string, { count: number; authTypes: string[] }>>({
    queryKey: ["tool-domain-auth-summary", domainOptions.map((d) => d.id).join(",")],
    enabled: domainOptions.length > 0,
    queryFn: async () => {
      const entries = await Promise.all(
        domainOptions.map(async (domain) => {
          const profiles = await api.get(`/tool-auth/domains/${domain.id}/profiles`);
          const list = Array.isArray(profiles) ? profiles : [];
          const authTypes = Array.from(
            new Set(
              list
                .map((p: any) => String(p?.authType || "none"))
                .filter((t: string) => t && t !== "none")
            )
          );
          return [domain.id, { count: list.length, authTypes }] as const;
        })
      );
      return Object.fromEntries(entries);
    },
  });

  const { data: selectedDomainProfiles = [] } = useQuery<any[]>({
    queryKey: ["tool-domain-auth-profiles", selectedDomainForAuth?.id],
    enabled: authProfileOpen && !!selectedDomainForAuth?.id,
    queryFn: () => api.get(`/tool-auth/domains/${selectedDomainForAuth?.id}/profiles`),
  });

  const filteredDomains = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return domainOptions;
    return domainOptions.filter(
      (d) =>
        d.name.toLowerCase().includes(q) ||
        String(d.description || "").toLowerCase().includes(q) ||
        (d.enabled ? "enabled" : "disabled").includes(q)
    );
  }, [domainOptions, search]);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["tool-domains"] });
    queryClient.invalidateQueries({ queryKey: ["tool-domain-auth-summary"] });
    queryClient.invalidateQueries({ queryKey: ["tool-domain-auth-profiles"] });
  };

  const createDomain = useMutation({
    mutationFn: () => api.post("/tool-domains", { name: newDomainName, enabled: true }),
    onSuccess: () => {
      refresh();
      setCreateDomainOpen(false);
      setNewDomainName("");
      setDomainError("");
      toast.success("Domain created");
    },
    onError: (e: any) => toast.error(e.message || "Failed to create domain"),
  });

  const saveAuthProfile = useMutation({
    mutationFn: async ({ authenticate }: { authenticate: boolean }) => {
      if (!selectedDomainForAuth) throw new Error("Domain is not selected");
      setAuthFormError("");
      const profile: any = editingProfileId
        ? await api.patch(`/tool-auth/domains/${selectedDomainForAuth.id}/profiles/${editingProfileId}`, {
            name: authProfileName.trim(),
            authType,
            enabled: true,
          })
        : await api.post(`/tool-auth/domains/${selectedDomainForAuth.id}/profiles`, {
            name: authProfileName.trim(),
            authType,
            enabled: true,
          });
      const payload = buildConnectPayload();
      const validation = validateAuthPayload(authType, payload);
      if (authenticate && authType === "oauth_auth_code") {
        if (!validation.valid) {
          throw new Error(validation.message);
        }
        const oauth: any = await api.post(`/tool-auth/profiles/${profile.id}/oauth/authorization-url`, buildConnectPayload());
        if (oauth?.authorizationUrl) {
          window.open(oauth.authorizationUrl, "_blank", "noopener,noreferrer");
        }
      }
      if (authType !== "none" && authType !== "oauth_auth_code") {
        if (!validation.valid) {
          if (!editingProfileId) {
            throw new Error(validation.message);
          }
          return { ...profile, connectSkipped: true, connectSkippedReason: validation.message };
        }
        await api.post(`/tool-auth/profiles/${profile.id}/connect/${authType}`, payload);
      }
      return profile;
    },
    onSuccess: (profile, vars) => {
      setAuthProfileOpen(false);
      resetAuthProfileDialog();
      refresh();
      if (profile?.connectSkipped) {
        toast.success("Profile saved. Existing credentials kept.");
        toast.info(profile?.connectSkippedReason || "Re-enter credential fields to rotate secrets.");
        return;
      }
      toast.success(vars.authenticate ? "Auth profile saved and authenticated" : "Auth profile saved");
    },
    onError: (e: any) => {
      setAuthFormError(e.message || "Failed to create auth profile");
      toast.error(e.message || "Failed to create auth profile");
    },
  });

  const deleteAuthProfile = useMutation({
    mutationFn: async () => {
      if (!selectedDomainForAuth || !editingProfileId) throw new Error("No profile selected");
      await api.delete(`/tool-auth/domains/${selectedDomainForAuth.id}/profiles/${editingProfileId}`);
    },
    onSuccess: () => {
      resetAuthProfileDialog();
      refresh();
      toast.success("Auth profile deleted");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete auth profile"),
  });

  const toggleDomain = async (domain: ToolDomain) => {
    try {
      await api.patch(`/tool-domains/${domain.id}`, {
        name: domain.name,
        description: domain.description,
        enabled: !domain.enabled,
      });
      refresh();
    } catch (e: any) {
      toast.error(e.message || "Failed to update domain");
    }
  };

  const deleteDomain = async (domainId: string) => {
    try {
      await api.delete(`/tool-domains/${domainId}`);
      refresh();
    } catch (e: any) {
      toast.error(e.message || "Failed to delete domain");
    }
  };

  const handleCreateDomain = () => {
    setDomainError("");
    if (!newDomainName.trim()) {
      setDomainError("Domain name is required");
      return;
    }
    createDomain.mutate();
  };

  const openAuthProfileDialog = (domain: ToolDomain) => {
    setSelectedDomainForAuth(domain);
    setAuthProfileOpen(true);
  };

  const resetAuthProfileDialog = () => {
    setAuthProfileOpen(false);
    setSelectedDomainForAuth(null);
    setEditingProfileId(null);
    setAuthProfileName("");
    setAuthType("none");
    setAuthForm({
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
    setAuthFormError("");
  };

  useEffect(() => {
    if (!authProfileOpen) return;
    const existing = Array.isArray(selectedDomainProfiles) ? selectedDomainProfiles[0] : null;
    if (!existing) {
      setEditingProfileId(null);
      setAuthProfileName("");
      setAuthType("none");
      return;
    }
    setEditingProfileId(existing.id);
    setAuthProfileName(existing.name || "");
    setAuthType(existing.authType || "none");
    const cfg = typeof existing.authConfig === "object" && existing.authConfig !== null ? existing.authConfig : {};
    setAuthForm((prev) => ({
      ...prev,
      headerName: String((cfg as any).headerName || prev.headerName),
      paramName: String((cfg as any).paramName || prev.paramName),
      clientId: existing.clientId || prev.clientId,
      tokenUrl: existing.tokenUrl || prev.tokenUrl,
      authorizationUrl: existing.authorizationUrl || prev.authorizationUrl,
      scopes: existing.scopes || prev.scopes,
      requestUrl: String((cfg as any).requestUrl || prev.requestUrl),
      requestMethod: String((cfg as any).requestMethod || prev.requestMethod),
      requestBodyTemplate: String((cfg as any).requestBodyTemplate || prev.requestBodyTemplate),
      templateVars: JSON.stringify((cfg as any).templateVars || {}, null, 0),
      requestHeaders: JSON.stringify((cfg as any).requestHeaders || {}, null, 0),
      responseTokenPath: String((cfg as any).responseTokenPath || prev.responseTokenPath),
      responseExpiresInPath: String((cfg as any).responseExpiresInPath || prev.responseExpiresInPath),
      tokenHeaderName: String((cfg as any).tokenHeaderName || prev.tokenHeaderName),
      tokenPrefix: String((cfg as any).tokenPrefix || prev.tokenPrefix),
      apiKey: "",
      token: "",
      username: String((cfg as any).username && (cfg as any).username !== "***" ? (cfg as any).username : ""),
      password: "",
      clientSecret: "",
    }));
  }, [authProfileOpen, selectedDomainProfiles]);

  const parseJsonInput = (raw: string, fallback: any) => {
    if (!raw.trim()) return fallback;
    try {
      return JSON.parse(raw);
    } catch {
      return fallback;
    }
  };

  const buildConnectPayload = () => {
    if (authType === "api_key_header") return { headerName: authForm.headerName, apiKey: authForm.apiKey };
    if (authType === "api_key_query") return { paramName: authForm.paramName || "api_key", apiKey: authForm.apiKey };
    if (authType === "bearer_token") return { token: authForm.token };
    if (authType === "basic_auth") return { username: authForm.username, password: authForm.password };
    if (authType === "oauth_client_credentials") {
      return {
        clientId: authForm.clientId,
        clientSecret: authForm.clientSecret,
        tokenUrl: authForm.tokenUrl,
        scopes: authForm.scopes || undefined,
      };
    }
    if (authType === "oauth_auth_code") {
      return {
        clientId: authForm.clientId,
        clientSecret: authForm.clientSecret,
        tokenUrl: authForm.tokenUrl,
        authorizationUrl: authForm.authorizationUrl,
        scopes: authForm.scopes || undefined,
      };
    }
    if (authType === "custom_token_api") {
      return {
        requestUrl: authForm.requestUrl,
        requestMethod: authForm.requestMethod,
        requestBodyTemplate: authForm.requestBodyTemplate,
        templateVars: parseJsonInput(authForm.templateVars, {}),
        requestHeaders: parseJsonInput(authForm.requestHeaders, {}),
        responseTokenPath: authForm.responseTokenPath || "access_token",
        responseExpiresInPath: authForm.responseExpiresInPath || "expires_in",
        tokenHeaderName: authForm.tokenHeaderName || "Authorization",
        tokenPrefix: authForm.tokenPrefix || "Bearer",
      };
    }
    return {};
  };

  const validateAuthPayload = (type: string, payload: any): { valid: boolean; message: string } => {
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
    return { valid: true, message: "" };
  };

  return (
    <div className="space-y-6">
      <EmbeddingSetupBanner />
      <div className="space-y-1">
        <h3 className="text-xl font-semibold text-[#123262]">Tools Workspace</h3>
        <p className="text-sm text-gray-600">Manage domains first, then open a domain to manage its tools on a dedicated page.</p>
      </div>

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="space-y-1">
            <h4 className="text-sm font-semibold text-[#123262]">Domains</h4>
            <p className="text-xs text-gray-500">Click View tools to open the tools page for that domain.</p>
          </div>
          <Button size="sm" onClick={() => setCreateDomainOpen(true)}>
            Create Domain
          </Button>
        </div>

        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search domains"
          className="max-w-sm"
        />

        <Table>
          <TableHeader>
            <TableRow className="h-9">
              <TableHead>Name</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Domain Auth</TableHead>
              <TableHead className="w-[240px] text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredDomains.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="py-6 text-center text-sm text-gray-500">
                  No domains found.
                </TableCell>
              </TableRow>
            ) : (
              filteredDomains.map((d) => (
                <TableRow 
                  key={d.id} 
                  className="h-9 cursor-pointer transition-colors hover:bg-slate-50"
                  onClick={() => navigate(`/tools/${d.id}`)}
                >
                  <TableCell className="max-w-[280px] truncate font-medium" title={d.name}>
                    {d.name}
                  </TableCell>
                  <TableCell>{d.enabled ? "Enabled" : "Disabled"}</TableCell>
                  <TableCell>
                    {domainAuthSummary[d.id]?.count ? (
                      <span className="text-xs text-slate-700">
                        {domainAuthSummary[d.id].count} profile{domainAuthSummary[d.id].count > 1 ? "s" : ""}
                        {domainAuthSummary[d.id].authTypes.length
                          ? ` (${domainAuthSummary[d.id].authTypes.join(", ")})`
                          : ""}
                      </span>
                    ) : (
                      <span className="text-xs text-slate-500">No auth profile</span>
                    )}
                  </TableCell>
                  <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button size="icon" variant="outline" className="h-8 w-8">
                          <MoreHorizontal size={14} />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="w-44">
                        <DropdownMenuItem onClick={() => navigate(`/tools/${d.id}`)}>View tools</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => openAuthProfileDialog(d)}>Manage auth profile</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => toggleDomain(d)}>{d.enabled ? "Disable" : "Enable"}</DropdownMenuItem>
                        <DropdownMenuItem variant="destructive" onClick={() => deleteDomain(d.id)}>Delete</DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </section>

      <Dialog open={createDomainOpen} onOpenChange={(open) => !open && setCreateDomainOpen(false)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Create Domain</DialogTitle>
            <DialogDescription>Create a tool domain before adding tools.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Input
              value={newDomainName}
              onChange={(e) => {
                setNewDomainName(e.target.value);
                setDomainError("");
              }}
              placeholder="Domain name (required)"
            />
            {domainError ? <p className="text-xs text-red-600">{domainError}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateDomainOpen(false)}>Cancel</Button>
            <Button onClick={handleCreateDomain} disabled={createDomain.isPending}>
              {createDomain.isPending ? "Creating..." : "Create"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={authProfileOpen} onOpenChange={(open) => !open && resetAuthProfileDialog()}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editingProfileId ? "Manage Auth Profile" : "Add Auth Profile"}</DialogTitle>
            <DialogDescription>
              {selectedDomainForAuth ? `Domain: ${selectedDomainForAuth.name}` : "Create auth profile for domain"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <Input
              value={authProfileName}
              onChange={(e) => setAuthProfileName(e.target.value)}
              placeholder="Profile name"
            />
            <select
              value={authType}
              onChange={(e) => setAuthType(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
            >
              <option value="none">No auth</option>
              <option value="api_key_header">API Key Header</option>
              <option value="api_key_query">API Key Query Param</option>
              <option value="bearer_token">Bearer Token</option>
              <option value="basic_auth">Basic Auth</option>
              <option value="oauth_client_credentials">OAuth Client Credentials</option>
              <option value="oauth_auth_code">OAuth Authorization Code</option>
              <option value="custom_token_api">Custom Token API</option>
            </select>
            {authType === "api_key_header" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={authForm.headerName} onChange={(e) => setAuthForm((s) => ({ ...s, headerName: e.target.value }))} placeholder="Header name" />
                <Input value={authForm.apiKey} onChange={(e) => setAuthForm((s) => ({ ...s, apiKey: e.target.value }))} placeholder="API key" />
              </div>
            )}
            {authType === "api_key_query" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={authForm.paramName} onChange={(e) => setAuthForm((s) => ({ ...s, paramName: e.target.value }))} placeholder="Query param name" />
                <Input value={authForm.apiKey} onChange={(e) => setAuthForm((s) => ({ ...s, apiKey: e.target.value }))} placeholder="API key" />
              </div>
            )}
            {authType === "bearer_token" && (
              <div className="space-y-2">
                <Input value={authForm.token} onChange={(e) => setAuthForm((s) => ({ ...s, token: e.target.value }))} placeholder="Bearer token" />
                {editingProfileId && !authForm.token.trim() ? (
                  <p className="text-xs text-amber-600">Token is hidden for existing profile. Leave blank to keep current token, or enter a new token to rotate.</p>
                ) : null}
              </div>
            )}
            {authType === "basic_auth" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={authForm.username} onChange={(e) => setAuthForm((s) => ({ ...s, username: e.target.value }))} placeholder="Username" />
                <Input type="password" value={authForm.password} onChange={(e) => setAuthForm((s) => ({ ...s, password: e.target.value }))} placeholder="Password" />
                {editingProfileId && !authForm.password.trim() ? (
                  <p className="col-span-2 text-xs text-amber-600">Password is hidden for existing profile. Leave blank to keep current password, or enter a new password to rotate.</p>
                ) : null}
              </div>
            )}
            {authType === "oauth_client_credentials" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={authForm.clientId} onChange={(e) => setAuthForm((s) => ({ ...s, clientId: e.target.value }))} placeholder="Client ID" />
                <Input type="password" value={authForm.clientSecret} onChange={(e) => setAuthForm((s) => ({ ...s, clientSecret: e.target.value }))} placeholder="Client secret" />
                <Input value={authForm.tokenUrl} onChange={(e) => setAuthForm((s) => ({ ...s, tokenUrl: e.target.value }))} placeholder="Token URL" />
                <Input value={authForm.scopes} onChange={(e) => setAuthForm((s) => ({ ...s, scopes: e.target.value }))} placeholder="Scopes (optional)" />
                {editingProfileId && !authForm.clientSecret.trim() ? (
                  <p className="col-span-2 text-xs text-amber-600">Client secret is hidden for existing profile. Leave blank to keep current secret, or enter a new one to rotate.</p>
                ) : null}
              </div>
            )}
            {authType === "oauth_auth_code" && (
              <div className="grid gap-2 md:grid-cols-2">
                <Input value={authForm.clientId} onChange={(e) => setAuthForm((s) => ({ ...s, clientId: e.target.value }))} placeholder="Client ID" />
                <Input type="password" value={authForm.clientSecret} onChange={(e) => setAuthForm((s) => ({ ...s, clientSecret: e.target.value }))} placeholder="Client secret" />
                <Input value={authForm.authorizationUrl} onChange={(e) => setAuthForm((s) => ({ ...s, authorizationUrl: e.target.value }))} placeholder="Authorization URL" />
                <Input value={authForm.tokenUrl} onChange={(e) => setAuthForm((s) => ({ ...s, tokenUrl: e.target.value }))} placeholder="Token URL" />
                <Input value={authForm.scopes} onChange={(e) => setAuthForm((s) => ({ ...s, scopes: e.target.value }))} placeholder="Scopes (optional)" />
                {editingProfileId && !authForm.clientSecret.trim() ? (
                  <p className="col-span-2 text-xs text-amber-600">Client secret is hidden for existing profile. Leave blank to keep current secret, or enter a new one before Authenticate.</p>
                ) : null}
              </div>
            )}
            {authType === "custom_token_api" && (
              <div className="grid gap-2">
                <div className="grid gap-2 md:grid-cols-2">
                  <Input value={authForm.requestUrl} onChange={(e) => setAuthForm((s) => ({ ...s, requestUrl: e.target.value }))} placeholder="Token API URL" />
                  <Input value={authForm.requestMethod} onChange={(e) => setAuthForm((s) => ({ ...s, requestMethod: e.target.value }))} placeholder="Request method (POST/GET)" />
                  <Input value={authForm.tokenHeaderName} onChange={(e) => setAuthForm((s) => ({ ...s, tokenHeaderName: e.target.value }))} placeholder="Target header name" />
                  <Input value={authForm.tokenPrefix} onChange={(e) => setAuthForm((s) => ({ ...s, tokenPrefix: e.target.value }))} placeholder="Token prefix (Bearer)" />
                  <Input value={authForm.responseTokenPath} onChange={(e) => setAuthForm((s) => ({ ...s, responseTokenPath: e.target.value }))} placeholder="Response token path" />
                  <Input value={authForm.responseExpiresInPath} onChange={(e) => setAuthForm((s) => ({ ...s, responseExpiresInPath: e.target.value }))} placeholder="Response expires path" />
                </div>
                <textarea value={authForm.requestBodyTemplate} onChange={(e) => setAuthForm((s) => ({ ...s, requestBodyTemplate: e.target.value }))} className="h-20 w-full rounded-md border border-input p-2 text-xs" placeholder='Request body template, supports ${var}' />
                <textarea value={authForm.templateVars} onChange={(e) => setAuthForm((s) => ({ ...s, templateVars: e.target.value }))} className="h-16 w-full rounded-md border border-input p-2 text-xs" placeholder='Template vars JSON: {"clientId":"..."}' />
                <textarea value={authForm.requestHeaders} onChange={(e) => setAuthForm((s) => ({ ...s, requestHeaders: e.target.value }))} className="h-16 w-full rounded-md border border-input p-2 text-xs" placeholder='Request headers JSON: {"Content-Type":"application/json"}' />
              </div>
            )}
            {authFormError ? <p className="text-xs text-red-600">{authFormError}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={resetAuthProfileDialog}>Cancel</Button>
            {editingProfileId && (
              <Button
                variant="outline"
                onClick={() => deleteAuthProfile.mutate()}
                disabled={deleteAuthProfile.isPending}
              >
                {deleteAuthProfile.isPending ? "Deleting..." : "Delete"}
              </Button>
            )}
            <Button
              variant="outline"
              onClick={() => saveAuthProfile.mutate({ authenticate: false })}
              disabled={!authProfileName.trim() || saveAuthProfile.isPending}
            >
              Save
            </Button>
            {authType === "oauth_auth_code" && (
              <Button
                onClick={() => saveAuthProfile.mutate({ authenticate: true })}
                disabled={!authProfileName.trim() || saveAuthProfile.isPending}
              >
                Authenticate & Save
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
