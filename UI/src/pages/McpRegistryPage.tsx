import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { MoreHorizontal } from "lucide-react";
import { toast } from "sonner";
import { EmbeddingSetupBanner } from "@/components/EmbeddingSetupBanner";

export default function McpRegistryPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [search, setSearch] = useState("");
  const [connectServer, setConnectServer] = useState<any | null>(null);
  const [authForm, setAuthForm] = useState({
    headerName: "x-api-key",
    apiKey: "",
    token: "",
    username: "",
    password: "",
    clientId: "",
    clientSecret: "",
    redirectUri: "",
    authorizationUrl: "",
    tokenUrl: "",
    scopes: "",
  });

  const { data: servers = [] } = useQuery({
    queryKey: ["mcp-registry"],
    queryFn: () => api.get("/mcp-registry"),
  });

  const filteredServers = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return servers as any[];
    return (servers as any[]).filter((s: any) =>
      [s.name, s.baseUrl || s.endpoint, s.authType, s.lastStatus, s.connectRequired ? "auth required" : "", s.enabled ? "enabled" : "disabled"]
        .map((v) => String(v || "").toLowerCase())
        .some((v) => v.includes(q))
    );
  }, [servers, search]);

  const refresh = () => qc.invalidateQueries({ queryKey: ["mcp-registry"] });

  const createServer = useMutation({
    mutationFn: () =>
      api.post("/mcp-registry", {
        name,
        baseUrl,
        endpoint: baseUrl,
        authType: "none",
        enabled: true,
      }),
    onSuccess: (r: any) => {
      setName("");
      setBaseUrl("");
      refresh();
      const discovered = r?.discover?.discoveredCount;
      if (typeof discovered === "number") {
        toast.success(`MCP server registered and auto-discovered ${discovered} tool(s)`);
      } else {
        toast.success("MCP server registered (auto-discovery attempted)");
      }
    },
    onError: (e: any) => toast.error(e.message || "Failed to register MCP server"),
  });

  const testServer = useMutation({
    mutationFn: (id: string) => api.post(`/mcp-registry/${id}/test`, {}),
    onSuccess: () => {
      refresh();
      toast.success("MCP connectivity test completed");
    },
    onError: (e: any) => toast.error(e.message || "MCP test failed"),
  });

  const connectMutation = useMutation({
    mutationFn: async ({ id, authType }: { id: string; authType: string }) => {
      if (authType === "api_key_header") {
        return api.post(`/mcp-registry/${id}/connect/api-key`, {
          headerName: authForm.headerName || "x-api-key",
          apiKey: authForm.apiKey,
        });
      }
      if (authType === "bearer_token") {
        return api.post(`/mcp-registry/${id}/connect/bearer`, { token: authForm.token });
      }
      if (authType === "basic_auth") {
        return api.post(`/mcp-registry/${id}/connect/basic`, {
          username: authForm.username,
          password: authForm.password,
        });
      }
      if (authType === "oauth_client_credentials") {
        return api.post(`/mcp-registry/${id}/connect/oauth/client-credentials`, {
          clientId: authForm.clientId,
          clientSecret: authForm.clientSecret,
          tokenUrl: authForm.tokenUrl || connectServer?.tokenUrl,
          scopes: authForm.scopes,
        });
      }
      if (authType === "oauth_auth_code") {
        throw new Error("Use Open OAuth Login. Callback is handled by backend.");
      }
      throw new Error("Unsupported auth type");
    },
    onSuccess: (result: any) => {
      const discovered = result?.discover?.discoveredCount;
      toast.success(typeof discovered === "number" ? `Connected and discovered ${discovered} tool(s)` : "Connected successfully");
      setConnectServer(null);
      setAuthForm({
        headerName: "x-api-key",
        apiKey: "",
        token: "",
        username: "",
        password: "",
        clientId: "",
        clientSecret: "",
        redirectUri: "",
        authorizationUrl: "",
        tokenUrl: "",
        scopes: "",
      });
      refresh();
    },
    onError: (e: any) => toast.error(e.message || "Connect failed"),
  });

  const reconnectServer = useMutation({
    mutationFn: (id: string) => api.post(`/mcp-registry/${id}/reconnect`, {}),
    onSuccess: () => {
      refresh();
      toast.success("MCP server reconnected");
    },
    onError: (e: any) => toast.error(e.message || "Reconnect failed"),
  });

  const reauthenticateServer = useMutation({
    mutationFn: (server: any) => api.post(`/mcp-registry/${server.id}/reauthenticate`, {}),
    onSuccess: (result: any, server: any) => {
      if (result?.authorizationUrl) {
        window.open(result.authorizationUrl, "_blank", "noopener,noreferrer");
        toast.success("Reauthentication started in browser");
      } else if (result?.nextAction === "connect") {
        openConnect(server);
        toast.info("Reauthentication needs credentials. Please connect.");
      } else {
        toast.success("Reauthentication started");
      }
      refresh();
    },
    onError: (e: any) => toast.error(e.message || "Reauthenticate failed"),
  });

  const toggleServer = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.patch(`/mcp-registry/${id}`, { enabled }),
    onSuccess: () => {
      refresh();
      toast.success("MCP server updated");
    },
    onError: (e: any) => toast.error(e.message || "Failed to update MCP server"),
  });

  const deleteServer = useMutation({
    mutationFn: (id: string) => api.delete(`/mcp-registry/${id}`),
    onSuccess: () => {
      refresh();
      toast.success("MCP server removed");
    },
    onError: (e: any) => toast.error(e.message || "Failed to remove MCP server"),
  });

  const openConnect = (server: any) => {
    setConnectServer(server);
    setAuthForm((prev) => ({
      ...prev,
      tokenUrl: server?.tokenUrl || "",
      clientId: server?.clientId || "",
      redirectUri: server?.redirectUri || "",
      authorizationUrl: server?.authorizationUrl || "",
      scopes: server?.scopes || "",
    }));
  };

  const triggerOauthAuthCode = async () => {
    if (!connectServer) return;
    try {
      const r: any = await api.post(`/mcp-registry/${connectServer.id}/oauth/authorization-url`, {});
      if (r?.authorizationUrl) {
        window.open(r.authorizationUrl, "_blank", "noopener,noreferrer");
        toast.success("OAuth page opened. Complete login in that tab; backend handles callback automatically.");
      }
    } catch (e: any) {
      toast.error(e.message || "Failed to build authorization URL");
    }
  };

  const renderConnectForm = () => {
    const authType = connectServer?.authType || "none";
    if (authType === "api_key_header") {
      return (
        <div className="space-y-2">
          <Input value={authForm.headerName} onChange={(e) => setAuthForm((s) => ({ ...s, headerName: e.target.value }))} placeholder="Header name (x-api-key)" />
          <Input value={authForm.apiKey} onChange={(e) => setAuthForm((s) => ({ ...s, apiKey: e.target.value }))} placeholder="API key" />
        </div>
      );
    }
    if (authType === "bearer_token") {
      return <Input value={authForm.token} onChange={(e) => setAuthForm((s) => ({ ...s, token: e.target.value }))} placeholder="Bearer token" />;
    }
    if (authType === "basic_auth") {
      return (
        <div className="space-y-2">
          <Input value={authForm.username} onChange={(e) => setAuthForm((s) => ({ ...s, username: e.target.value }))} placeholder="Username" />
          <Input type="password" value={authForm.password} onChange={(e) => setAuthForm((s) => ({ ...s, password: e.target.value }))} placeholder="Password" />
        </div>
      );
    }
    if (authType === "oauth_client_credentials") {
      return (
        <div className="space-y-2">
          <Input value={authForm.clientId} onChange={(e) => setAuthForm((s) => ({ ...s, clientId: e.target.value }))} placeholder="Client ID" />
          <Input type="password" value={authForm.clientSecret} onChange={(e) => setAuthForm((s) => ({ ...s, clientSecret: e.target.value }))} placeholder="Client secret" />
          <Input value={authForm.tokenUrl} onChange={(e) => setAuthForm((s) => ({ ...s, tokenUrl: e.target.value }))} placeholder="Token URL" />
          <Input value={authForm.scopes} onChange={(e) => setAuthForm((s) => ({ ...s, scopes: e.target.value }))} placeholder="Scopes (space separated, optional)" />
        </div>
      );
    }
    if (authType === "oauth_auth_code") {
      return (
        <div className="space-y-2">
          <p className="text-xs text-gray-600">OAuth app details are managed by backend defaults. Open login and complete authorization in the provider tab.</p>
          <Button type="button" variant="outline" onClick={triggerOauthAuthCode}>Open OAuth Login</Button>
        </div>
      );
    }
    return <p className="text-sm text-gray-600">No connect step required for this server.</p>;
  };

  return (
    <div className="space-y-6">
      <EmbeddingSetupBanner />
      <div className="space-y-1">
        <h3 className="text-xl font-semibold text-[#123262]">MCP Registry</h3>
        <p className="text-sm text-gray-600">Register remote MCP servers, auto-detect auth, test connectivity, and manage tools on a dedicated page.</p>
      </div>

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4">
        <h4 className="text-sm font-semibold text-[#123262]">Register MCP Server</h4>
        <div className="grid gap-2 md:grid-cols-12">
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Server name" className="md:col-span-3" />
          <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://mcp.example.com" className="md:col-span-6" />
          <Button onClick={() => createServer.mutate()} disabled={!name.trim() || !baseUrl.trim() || createServer.isPending} className="md:col-span-3">
            {createServer.isPending ? "Registering..." : "Register"}
          </Button>
        </div>
        <p className="text-xs text-gray-500">Auth method is auto-detected, and tool discovery runs automatically on successful registration.</p>
      </section>

      <section className="space-y-3 overflow-hidden rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h4 className="text-sm font-semibold text-[#123262]">Registered Servers</h4>
          <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search servers" className="max-w-sm" />
        </div>

        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[16%] whitespace-normal">Name</TableHead>
              <TableHead className="w-[30%] whitespace-normal">Base URL</TableHead>
              <TableHead className="w-[12%] whitespace-normal">Auth</TableHead>
              <TableHead className="w-[10%] whitespace-normal">Enabled</TableHead>
              <TableHead className="w-[14%] whitespace-normal">Status</TableHead>
              <TableHead className="w-[8%] whitespace-normal">Discovered</TableHead>
              <TableHead className="w-[10%] text-right whitespace-normal">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredServers.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-sm text-gray-500">No MCP servers found.</TableCell>
              </TableRow>
            ) : (
              filteredServers.map((s: any) => (
                <TableRow 
                  key={s.id} 
                  className="transition-colors hover:bg-slate-50"
                >
                  {(() => {
                    const isConnected = !s.connectRequired && String(s.lastStatus || "").toLowerCase() === "connected";
                    return (
                      <>
                  <TableCell className="whitespace-normal break-words">{s.name}</TableCell>
                  <TableCell className="whitespace-normal break-all">{s.baseUrl || s.endpoint}</TableCell>
                  <TableCell className="whitespace-normal break-words">{s.authType || "none"}</TableCell>
                  <TableCell className="whitespace-normal">
                    <span
                      className={`inline-flex rounded border px-2 py-0.5 text-xs ${
                        s.enabled ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-300 bg-slate-50 text-slate-700"
                      }`}
                    >
                      {s.enabled ? "Enabled" : "Disabled"}
                    </span>
                  </TableCell>
                  <TableCell className="whitespace-normal break-words">
                    <span className="inline-flex rounded border border-slate-300 bg-slate-50 px-2 py-0.5 text-xs">
                      {s.connectRequired ? "Auth required" : s.lastStatus || (s.enabled ? "Enabled" : "Disabled")}
                    </span>
                  </TableCell>
                  <TableCell className="whitespace-normal">{s.discoveredToolsCount ?? 0}</TableCell>
                  <TableCell className="text-right" data-no-row-nav="true">
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
                        <Button size="icon" variant="outline" className="h-8 w-8" aria-label="Server actions" title="Server actions">
                          <MoreHorizontal size={14} />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="w-44">
                        <DropdownMenuItem onClick={(e) => { e.stopPropagation(); testServer.mutate(s.id); }} disabled={testServer.isPending}>Test</DropdownMenuItem>
                        {s.authType !== "none" && !isConnected ? <DropdownMenuItem onClick={(e) => { e.stopPropagation(); openConnect(s); }} disabled={connectMutation.isPending}>Connect</DropdownMenuItem> : null}
                        <DropdownMenuItem onClick={(e) => { e.stopPropagation(); reconnectServer.mutate(s.id); }} disabled={reconnectServer.isPending}>Reconnect</DropdownMenuItem>
                        {s.authType !== "none" ? <DropdownMenuItem onClick={(e) => { e.stopPropagation(); reauthenticateServer.mutate(s); }} disabled={reauthenticateServer.isPending}>Reauthenticate</DropdownMenuItem> : null}
                        <DropdownMenuItem onClick={(e) => { e.stopPropagation(); navigate(`/mcp-registry/${s.id}/tools`); }}>View tools</DropdownMenuItem>
                        <DropdownMenuItem onClick={(e) => { e.stopPropagation(); toggleServer.mutate({ id: s.id, enabled: !s.enabled }); }} disabled={toggleServer.isPending}>
                          {s.enabled ? "Disable" : "Enable"}
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={(e) => { e.stopPropagation(); deleteServer.mutate(s.id); }} disabled={deleteServer.isPending} variant="destructive">Delete</DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                      </>
                    );
                  })()}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </section>

      <Dialog open={Boolean(connectServer)} onOpenChange={(open) => !open && setConnectServer(null)}>
        <DialogContent className="bg-white sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Connect MCP Server</DialogTitle>
            <DialogDescription>
              Provide credentials for <span className="font-medium">{connectServer?.name}</span> using <span className="font-medium">{connectServer?.authType || "none"}</span>.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">{renderConnectForm()}</div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConnectServer(null)}>Cancel</Button>
            {connectServer?.authType !== "oauth_auth_code" && (
              <Button onClick={() => connectServer && connectMutation.mutate({ id: connectServer.id, authType: connectServer.authType })} disabled={connectMutation.isPending || !connectServer}>
                {connectMutation.isPending ? "Connecting..." : "Connect"}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
