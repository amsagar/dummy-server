import { useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate, Link } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { toast } from "sonner";
import { Settings as SettingsIcon } from "lucide-react";

interface RuleDomainRow {
  id: string;
  skillId: string;
  skillName: string;
  intentLabel: string;
  status: "DRAFT" | "ACTIVE" | "DEPRECATED" | "FAILED";
  version: number;
  flowableProcKey: string;
  compileAttempts: number;
  createdAt: number;
  updatedAt: number;
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-800",
  DRAFT: "bg-yellow-100 text-yellow-800",
  DEPRECATED: "bg-gray-200 text-gray-700",
  FAILED: "bg-red-100 text-red-800",
};

export default function RuleDomainsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");

  const { data: rows = [], isLoading } = useQuery<RuleDomainRow[]>({
    queryKey: ["rule-domains"],
    queryFn: () => api.ruleDomains.list(),
  });

  const { data: cfg } = useQuery<{ enabled: boolean; enabledSkills: string[] }>({
    queryKey: ["rule-domain-config"],
    queryFn: () => api.ruleDomainConfig.get(),
  });

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim();
    if (!q) return rows;
    return rows.filter(
      (r) =>
        r.skillName.toLowerCase().includes(q) ||
        r.intentLabel.toLowerCase().includes(q) ||
        r.status.toLowerCase().includes(q)
    );
  }, [rows, search]);

  const deprecateMutation = useMutation({
    mutationFn: (id: string) => api.ruleDomains.deprecate(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      toast.success("Domain deprecated");
    },
    onError: (e: any) => toast.error(e?.message || "Failed to deprecate"),
  });

  const activateMutation = useMutation({
    mutationFn: (id: string) => api.ruleDomains.activate(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      toast.success("Domain activated");
    },
    onError: (e: any) => toast.error(e?.message || "Failed to activate"),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold text-[#123262]">Compiled Rule Domains</h2>
          <p className="text-sm text-gray-500">
            BPMN workflows compiled from skills + intents. Cache hits skip the LLM entirely.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {cfg && (
            <span
              className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                cfg.enabled ? "bg-green-100 text-green-800" : "bg-gray-200 text-gray-600"
              }`}
            >
              {cfg.enabled ? "Feature enabled" : "Feature disabled"}
            </span>
          )}
          <Link to="/settings/rule-domain">
            <Button variant="outline" size="sm" className="gap-2">
              <SettingsIcon className="h-4 w-4" />
              Configure
            </Button>
          </Link>
        </div>
      </div>

      <div className="flex items-center justify-end">
        <Input
          placeholder="Filter by skill, intent, status…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-xs"
        />
      </div>

      <div className="bg-white rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Skill</TableHead>
              <TableHead>Intent</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>Compile attempts</TableHead>
              <TableHead>Updated</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-gray-400 py-8">Loading…</TableCell>
              </TableRow>
            )}
            {!isLoading && filtered.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-gray-400 py-8">No compiled domains yet</TableCell>
              </TableRow>
            )}
            {filtered.map((r) => (
              <TableRow
                key={r.id}
                className="cursor-pointer hover:bg-gray-50"
                onClick={() => navigate(`/rule-domains/${encodeURIComponent(r.id)}`)}
              >
                <TableCell className="font-medium">{r.skillName}</TableCell>
                <TableCell>{r.intentLabel}</TableCell>
                <TableCell>
                  <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_COLOR[r.status] ?? ""}`}>
                    {r.status}
                  </span>
                </TableCell>
                <TableCell>v{r.version}</TableCell>
                <TableCell>{r.compileAttempts}</TableCell>
                <TableCell>{new Date(r.updatedAt).toLocaleString()}</TableCell>
                <TableCell className="text-right space-x-2" onClick={(e) => e.stopPropagation()}>
                  {r.status === "ACTIVE" && (
                    <Button size="sm" variant="outline" onClick={() => deprecateMutation.mutate(r.id)}>
                      Deprecate
                    </Button>
                  )}
                  {(r.status === "DRAFT" || r.status === "DEPRECATED") && (
                    <Button size="sm" variant="outline" onClick={() => activateMutation.mutate(r.id)}>
                      Activate
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
