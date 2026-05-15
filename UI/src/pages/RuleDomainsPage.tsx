import { useEffect, useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { useNavigate, Link } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { toast } from "sonner";
import { ChevronLeft, ChevronRight, Settings as SettingsIcon, Trash2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ConfirmDialog";

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

interface PageResp {
  items: RuleDomainRow[];
  total: number;
  page: number;
  pageSize: number;
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-800",
  DRAFT: "bg-yellow-100 text-yellow-800",
  DEPRECATED: "bg-gray-200 text-gray-700",
  FAILED: "bg-red-100 text-red-800",
};

const PAGE_SIZE = 20;

export default function RuleDomainsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [searchInput, setSearchInput] = useState("");
  const [searchActive, setSearchActive] = useState("");
  const [page, setPage] = useState(0);
  const [pendingDelete, setPendingDelete] = useState<RuleDomainRow | null>(null);

  // Debounce the search input → push to the value used in the query.
  useEffect(() => {
    const t = setTimeout(() => {
      setSearchActive(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [searchInput]);

  const { data, isLoading, isFetching } = useQuery<PageResp>({
    queryKey: ["rule-domains", searchActive, page],
    queryFn: () =>
      api.ruleDomains.list({ search: searchActive, page, pageSize: PAGE_SIZE, onlyLatest: true }),
    placeholderData: keepPreviousData,
  });

  const { data: cfg } = useQuery<{ enabled: boolean }>({
    queryKey: ["rule-domain-config"],
    queryFn: () => api.ruleDomainConfig.get(),
  });

  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

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
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.deactivatedCount ?? 0);
      toast.success(
        n > 0
          ? `Activated · superseded ${n} previous ${n === 1 ? "version" : "versions"}`
          : "Activated",
      );
    },
    onError: (e: any) => toast.error(e?.message || "Failed to activate"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.ruleDomains.delete(id),
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.deletedRevisions ?? 0);
      const u = Number(resp?.undeployedFlowableDefinitions ?? 0);
      const revisionsMsg = `Deleted ${n} ${n === 1 ? "revision" : "revisions"}`;
      const undeployMsg = u > 0
        ? ` · undeployed ${u} Flowable ${u === 1 ? "definition" : "definitions"}`
        : "";
      toast.success(revisionsMsg + undeployMsg);
      setPendingDelete(null);
    },
    onError: (e: any) => toast.error(e?.message || "Failed to delete"),
  });

  const pageStart = useMemo(() => (rows.length > 0 ? page * PAGE_SIZE + 1 : 0), [rows.length, page]);
  const pageEnd = useMemo(() => page * PAGE_SIZE + rows.length, [page, rows.length]);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold text-[#123262]">Compiled Rule Domains</h2>
          <p className="text-sm text-gray-500">
            Showing the latest revision of each (skill, intent). Click any row to see all revisions and execution history.
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

      <div className="flex items-center justify-between gap-4">
        <Input
          placeholder="Search skill / intent / status…"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          className="max-w-sm"
        />
        <div className="text-xs text-gray-500">
          {total === 0 ? "No results" : `${pageStart}–${pageEnd} of ${total}`}
          {isFetching && <span className="ml-2 text-gray-400">refreshing…</span>}
        </div>
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
            {!isLoading && rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-gray-400 py-8">
                  {searchActive ? "No matches for that search." : "No compiled domains yet."}
                </TableCell>
              </TableRow>
            )}
            {rows.map((r) => (
              <TableRow
                key={r.id}
                className="cursor-pointer hover:bg-gray-50"
                onClick={() => navigate(`/rule-domains/${encodeURIComponent(r.id)}`)}
              >
                <TableCell className="font-medium">{r.skillName}</TableCell>
                <TableCell className="font-mono text-xs">{r.intentLabel}</TableCell>
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
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setPendingDelete(r)}
                    disabled={deleteMutation.isPending}
                    title="Delete this rule domain"
                    className="text-red-700 hover:bg-red-50 hover:border-red-300"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button
            size="sm"
            variant="outline"
            disabled={page === 0 || isFetching}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className="gap-1"
          >
            <ChevronLeft className="h-4 w-4" /> Previous
          </Button>
          <span className="text-xs text-gray-500 px-2">
            Page {page + 1} of {totalPages}
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={page + 1 >= totalPages || isFetching}
            onClick={() => setPage((p) => p + 1)}
            className="gap-1"
          >
            Next <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      )}

      <ConfirmDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null);
        }}
        title="Delete this rule domain?"
        description="This removes ALL revisions of this rule domain (every version), every execution recorded against them, and any Flowable deployments that no longer have a reference. This cannot be undone."
        detail={
          pendingDelete && (
            <div className="space-y-0.5">
              <div>
                <span className="font-medium text-gray-900">{pendingDelete.skillName}</span>
              </div>
              <div className="font-mono text-[11px]">{pendingDelete.intentLabel}</div>
              <div className="text-[11px] text-gray-500">
                Latest revision shown: v{pendingDelete.version} · {pendingDelete.status}
              </div>
              <div className="text-[11px] text-gray-500">All other revisions of this (skill, intent) will be deleted as well.</div>
            </div>
          )
        }
        confirmLabel="Delete all revisions"
        tone="danger"
        busy={deleteMutation.isPending}
        onConfirm={() => {
          if (pendingDelete) deleteMutation.mutate(pendingDelete.id);
        }}
      />
    </div>
  );
}
