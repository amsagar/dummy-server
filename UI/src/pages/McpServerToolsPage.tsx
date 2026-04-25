import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { toast } from "sonner";

const PAGE_SIZE = 10;

export default function McpServerToolsPage() {
  const navigate = useNavigate();
  const { serverId = "" } = useParams();
  const qc = useQueryClient();

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);

  const { data: servers = [] } = useQuery({
    queryKey: ["mcp-registry"],
    queryFn: () => api.get("/mcp-registry"),
  });

  const server = useMemo(
    () => (servers as any[]).find((s: any) => s.id === serverId),
    [servers, serverId]
  );

  const { data: toolsResponse } = useQuery({
    queryKey: ["mcp-tools", serverId],
    queryFn: () => api.get(`/mcp-registry/${serverId}/tools`),
    enabled: !!serverId,
  });

  const tools = useMemo(
    () => (Array.isArray((toolsResponse as any)?.tools) ? (toolsResponse as any).tools : []),
    [toolsResponse]
  );

  const filteredTools = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return tools;
    return tools.filter((tool: any) =>
      [tool?.name, tool?.description, tool?.enabled ? "enabled" : "disabled"]
        .map((v) => String(v || "").toLowerCase())
        .some((v) => v.includes(q))
    );
  }, [tools, search]);

  const totalPages = Math.max(1, Math.ceil(filteredTools.length / PAGE_SIZE));
  const pagedTools = useMemo(() => {
    const start = (page - 1) * PAGE_SIZE;
    return filteredTools.slice(start, start + PAGE_SIZE);
  }, [filteredTools, page]);

  useEffect(() => {
    setPage(1);
  }, [serverId, search]);

  useEffect(() => {
    if (page > totalPages) {
      setPage(totalPages);
    }
  }, [page, totalPages]);

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["mcp-registry"] });
    qc.invalidateQueries({ queryKey: ["mcp-tools", serverId] });
  };

  const toggleSingleTool = useMutation({
    mutationFn: ({ toolName, enabled }: { toolName: string; enabled: boolean }) =>
      api.patch(`/mcp-registry/${serverId}/tools/${encodeURIComponent(toolName)}`, { enabled }),
    onSuccess: () => {
      refresh();
      toast.success("Tool state updated");
    },
    onError: (e: any) => toast.error(e.message || "Failed to update tool state"),
  });

  const toggleAllTools = useMutation({
    mutationFn: ({ enabled }: { enabled: boolean }) =>
      api.post(`/mcp-registry/${serverId}/tools/${enabled ? "enable-all" : "disable-all"}`, {}),
    onSuccess: () => {
      refresh();
      toast.success("Tool states updated");
    },
    onError: (e: any) => toast.error(e.message || "Failed to update all tools"),
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2">
        <div className="space-y-1">
          <h3 className="text-xl font-semibold text-[#123262]">Discovered Tools</h3>
          <p className="text-sm text-gray-600">{server ? `${server.name} (${tools.length})` : "Loading server..."}</p>
        </div>
        <Button variant="outline" onClick={() => navigate("/mcp-registry")}>
          Back to MCP Registry
        </Button>
      </div>

      <section className="space-y-3 overflow-hidden rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tools"
            className="max-w-sm"
          />
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => toggleAllTools.mutate({ enabled: false })}
              disabled={toggleAllTools.isPending}
            >
              Disable All Tools
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => toggleAllTools.mutate({ enabled: true })}
              disabled={toggleAllTools.isPending}
            >
              Enable All Tools
            </Button>
          </div>
        </div>

        {filteredTools.length === 0 ? (
          <p className="py-8 text-center text-sm text-gray-500">No tools found.</p>
        ) : (
          <div className="space-y-2 text-sm">
            {pagedTools.map((tool: any, idx: number) => (
              <div key={`${serverId}-tool-${idx}`} className="flex items-start gap-2 rounded border border-slate-200 bg-slate-50 px-2 py-1">
                <div className="min-w-0 flex-1 whitespace-normal break-words">
                  <span className="font-medium break-all">{tool?.name || "unnamed_tool"}</span>
                  {tool?.description ? <span className="text-gray-500 break-words"> - {tool.description}</span> : null}
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  className="shrink-0"
                  onClick={() => toggleSingleTool.mutate({ toolName: tool?.name || "", enabled: !Boolean(tool?.enabled) })}
                  disabled={toggleSingleTool.isPending || !tool?.name}
                >
                  {Boolean(tool?.enabled) ? "Disable" : "Enable"}
                </Button>
              </div>
            ))}
          </div>
        )}

        {filteredTools.length > 0 ? (
          <div className="flex items-center justify-end gap-2 pt-1">
            <Button
              size="sm"
              variant="outline"
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={page <= 1}
            >
              Previous
            </Button>
            <span className="text-xs text-gray-600">
              Page {page} of {totalPages}
            </span>
            <Button
              size="sm"
              variant="outline"
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              disabled={page >= totalPages}
            >
              Next
            </Button>
          </div>
        ) : null}
      </section>
    </div>
  );
}
