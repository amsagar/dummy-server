import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { api } from "@/services/api";
import type { ToolDomain } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from "sonner";

export default function ToolsPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const [search, setSearch] = useState("");
  const [createDomainOpen, setCreateDomainOpen] = useState(false);
  const [newDomainName, setNewDomainName] = useState("");
  const [domainError, setDomainError] = useState("");

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

  return (
    <div className="space-y-6">
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
              <TableHead className="w-[240px] text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredDomains.length === 0 ? (
              <TableRow>
                <TableCell colSpan={3} className="py-6 text-center text-sm text-gray-500">
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
                  <TableCell className="text-right">
                    <div className="inline-flex gap-2">
                      <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); navigate(`/tools/${d.id}`); }}>
                        View tools
                      </Button>
                      <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); toggleDomain(d); }}>
                        {d.enabled ? "Disable" : "Enable"}
                      </Button>
                      <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); deleteDomain(d.id); }}>
                        Delete
                      </Button>
                    </div>
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
    </div>
  );
}
