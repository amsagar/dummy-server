import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/services/api";
import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { toast } from "sonner";

export default function AdminPage() {
  const qc = useQueryClient();
  const [policyName, setPolicyName] = useState("");
  const [policyRule, setPolicyRule] = useState("");
  const [mcpName, setMcpName] = useState("");
  const [mcpEndpoint, setMcpEndpoint] = useState("");

  const { data: policies = [] } = useQuery({
    queryKey: ["admin-policies"],
    queryFn: () => api.get("/platform/policies"),
  });
  const { data: mcpEntries = [] } = useQuery({
    queryKey: ["admin-mcp"],
    queryFn: () => api.get("/platform/mcp"),
  });
  const { data: hooks = {} } = useQuery({
    queryKey: ["admin-hooks"],
    queryFn: () => api.get("/platform/hooks"),
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["admin-policies"] });
    qc.invalidateQueries({ queryKey: ["admin-mcp"] });
    qc.invalidateQueries({ queryKey: ["admin-hooks"] });
  };

  const addPolicy = useMutation({
    mutationFn: () => api.post("/platform/policies", {
      name: policyName,
      scope: "tool",
      ruleType: "name",
      ruleValue: policyRule,
      decision: "ask",
      enabled: true,
    }),
    onSuccess: () => { setPolicyName(""); setPolicyRule(""); refresh(); toast.success("Policy added"); },
  });

  const addMcp = useMutation({
    mutationFn: () => api.post("/platform/mcp", {
      name: mcpName,
      endpoint: mcpEndpoint,
      authType: "none",
      enabled: true,
    }),
    onSuccess: () => { setMcpName(""); setMcpEndpoint(""); refresh(); toast.success("MCP added"); },
    onError: (e: any) => toast.error(e.message || "Failed to add MCP"),
  });

  const addHook = useMutation({
    mutationFn: () => api.post("/platform/hooks/pre-tool", { hookName: "default-pre-tool", enabled: true }),
    onSuccess: () => { refresh(); toast.success("Hook registered"); },
  });

  const handleAddMcp = () => {
    try {
      const url = new URL(mcpEndpoint);
      if (!["http:", "https:"].includes(url.protocol)) {
        toast.error("MCP endpoint must use http/https");
        return;
      }
      addMcp.mutate();
    } catch {
      toast.error("Invalid MCP endpoint URL");
    }
  };

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <h3 className="font-semibold text-[#123262]">Guardrail Policies</h3>
        <div className="flex gap-2">
          <Input value={policyName} onChange={(e) => setPolicyName(e.target.value)} placeholder="Policy name" />
          <Input value={policyRule} onChange={(e) => setPolicyRule(e.target.value)} placeholder="Tool name rule" />
          <Button onClick={() => addPolicy.mutate()} disabled={!policyName || !policyRule}>Add</Button>
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Decision</TableHead>
              <TableHead>Rule</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(policies as any[]).map((p: any) => (
              <TableRow key={p.id}>
                <TableCell>{p.name}</TableCell>
                <TableCell>{p.decision}</TableCell>
                <TableCell>{p.ruleValue}</TableCell>
                <TableCell className="text-right">
                  <Button size="sm" variant="outline" onClick={async () => { await api.delete(`/platform/policies/${p.id}`); refresh(); }}>Delete</Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <div className="space-y-3">
        <h3 className="font-semibold text-[#123262]">MCP Registry</h3>
        <div className="flex gap-2">
          <Input value={mcpName} onChange={(e) => setMcpName(e.target.value)} placeholder="MCP name" />
          <Input value={mcpEndpoint} onChange={(e) => setMcpEndpoint(e.target.value)} placeholder="MCP endpoint" />
          <Button onClick={handleAddMcp} disabled={!mcpName || !mcpEndpoint}>Add</Button>
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Endpoint</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(mcpEntries as any[]).map((m: any) => (
              <TableRow key={m.id}>
                <TableCell>{m.name}</TableCell>
                <TableCell className="max-w-[420px] truncate">{m.endpoint}</TableCell>
                <TableCell className="text-right">
                  <Button size="sm" variant="outline" onClick={async () => { await api.delete(`/platform/mcp/${m.id}`); refresh(); }}>Delete</Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <div className="space-y-3">
        <h3 className="font-semibold text-[#123262]">Hook Registry Map</h3>
        <Button onClick={() => addHook.mutate()}>Register Default pre-tool Hook</Button>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Hook Point</TableHead>
              <TableHead>Mapped Hooks</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {Object.entries(hooks as Record<string, string[]>).map(([point, values]) => (
              <TableRow key={point}>
                <TableCell>{point}</TableCell>
                <TableCell>{(values || []).join(", ") || "-"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
