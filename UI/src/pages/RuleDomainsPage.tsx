import { useEffect, useMemo, useState, type ReactElement } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { useNavigate, Link } from "react-router-dom";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { toast } from "sonner";
import {
  ChevronDown,
  ChevronRight,
  Settings as SettingsIcon,
  Trash2,
  Play,
  CheckCircle2,
} from "lucide-react";
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
  domainGroupId?: string | null;
  domainGroupName?: string | null;
  ruleName?: string | null;
  matchScope?: string | null;
  coverageState?: string | null;
  traceSource?: string | null;
  resultKey?: string | null;
}

interface PageResp {
  items: RuleDomainRow[];
  total: number;
  page: number;
  pageSize: number;
}

interface SkillGroup {
  skillId: string;
  skillName: string;
  rules: RuleDomainRow[];
  activeCount: number;
  draftCount: number;
  failedCount: number;
  latestUpdatedAt: number;
  traceSource: string | null;
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
  const [searchInput, setSearchInput] = useState("");
  const [searchActive, setSearchActive] = useState("");
  const [pendingDelete, setPendingDelete] = useState<RuleDomainRow | null>(null);
  const [pendingSkillDelete, setPendingSkillDelete] = useState<SkillGroup | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  useEffect(() => {
    const t = setTimeout(() => setSearchActive(searchInput.trim()), 300);
    return () => clearTimeout(t);
  }, [searchInput]);

  // Fetch all latest revisions in one shot — the page groups client-side so
  // sub-rules can stay together regardless of pagination boundaries.
  const { data, isLoading, isFetching } = useQuery<PageResp>({
    queryKey: ["rule-domains", searchActive],
    queryFn: () =>
      api.ruleDomains.list({ search: searchActive, page: 0, pageSize: 500, onlyLatest: true }),
    placeholderData: keepPreviousData,
  });

  const { data: cfg } = useQuery<{ enabled: boolean }>({
    queryKey: ["rule-domain-config"],
    queryFn: () => api.ruleDomainConfig.get(),
  });

  const groups: SkillGroup[] = useMemo(() => {
    const rows = data?.items ?? [];
    const bySkill = new Map<string, SkillGroup>();
    for (const r of rows) {
      let g = bySkill.get(r.skillId);
      if (!g) {
        g = {
          skillId: r.skillId,
          skillName: r.skillName,
          rules: [],
          activeCount: 0,
          draftCount: 0,
          failedCount: 0,
          latestUpdatedAt: 0,
          traceSource: r.traceSource ?? null,
        };
        bySkill.set(r.skillId, g);
      }
      g.rules.push(r);
      if (r.status === "ACTIVE") g.activeCount++;
      else if (r.status === "DRAFT") g.draftCount++;
      else if (r.status === "FAILED") g.failedCount++;
      if (r.updatedAt > g.latestUpdatedAt) g.latestUpdatedAt = r.updatedAt;
      if (!g.traceSource && r.traceSource) g.traceSource = r.traceSource;
    }
    // Sort rules within a group by name; sort groups by skill name.
    for (const g of bySkill.values()) {
      g.rules.sort((a, b) => {
        const an = a.ruleName ?? a.intentLabel ?? "";
        const bn = b.ruleName ?? b.intentLabel ?? "";
        return an.localeCompare(bn);
      });
    }
    return [...bySkill.values()].sort((a, b) => a.skillName.localeCompare(b.skillName));
  }, [data]);

  const totalRules = data?.items?.length ?? 0;

  const deprecateMutation = useMutation({
    mutationFn: (id: string) => api.ruleDomains.deprecate(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      toast.success("Rule deprecated");
    },
    onError: (e: any) => toast.error(e?.message || "Failed to deprecate"),
  });

  const activateMutation = useMutation({
    mutationFn: (id: string) => api.ruleDomains.activate(id),
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.deactivatedCount ?? 0);
      toast.success(
        n > 0 ? `Activated · superseded ${n} previous` : "Activated",
      );
    },
    onError: (e: any) => toast.error(e?.message || "Failed to activate"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.ruleDomains.delete(id),
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.deletedRevisions ?? 0);
      toast.success(`Deleted ${n} ${n === 1 ? "revision" : "revisions"}`);
      setPendingDelete(null);
    },
    onError: (e: any) => toast.error(e?.message || "Failed to delete"),
  });

  const activateAllMutation = useMutation({
    mutationFn: (skillId: string) => api.ruleDomains.activateAllForSkill(skillId),
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.activated ?? 0);
      toast.success(n > 0 ? `Activated ${n} rule${n === 1 ? "" : "s"}` : "Nothing to activate");
    },
    onError: (e: any) => toast.error(e?.message || "Failed to activate skill"),
  });

  const deleteSkillMutation = useMutation({
    mutationFn: (skillId: string) => api.ruleDomains.deleteAllForSkill(skillId),
    onSuccess: (resp: any) => {
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      const n = Number(resp?.deletedRevisions ?? 0);
      const u = Number(resp?.undeployedFlowableDefinitions ?? 0);
      toast.success(
        `Deleted ${n} revision${n === 1 ? "" : "s"}` +
          (u > 0 ? ` · undeployed ${u} Flowable def${u === 1 ? "" : "s"}` : ""),
      );
      setPendingSkillDelete(null);
    },
    onError: (e: any) => toast.error(e?.message || "Failed to delete skill"),
  });

  const testSkillMutation = useMutation({
    mutationFn: (skillId: string) => api.ruleDomains.testAllForSkill(skillId, {}),
    onSuccess: (resp: any) => {
      const n = Number(resp?.ruleCount ?? 0);
      const ok = (resp?.results ?? []).filter((r: any) => r.success).length;
      toast.success(`Tested ${n} rule${n === 1 ? "" : "s"} — ${ok} ok / ${n - ok} failed`);
    },
    onError: (e: any) => toast.error(e?.message || "Failed to test skill"),
  });

  const toggleExpand = (skillId: string) =>
    setExpanded((s) => {
      const next = new Set(s);
      if (next.has(skillId)) next.delete(skillId);
      else next.add(skillId);
      return next;
    });

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold text-[#123262]">Compiled Rule Domains</h2>
          <p className="text-sm text-gray-500">
            Skills compiled into rule BPMNs. Click a skill row to see its rules. Test or activate the whole skill at once, or drill into a single rule.
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
          placeholder="Search skill / rule / intent…"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          className="max-w-sm"
        />
        <div className="text-xs text-gray-500">
          {groups.length === 0
            ? "No skills"
            : `${groups.length} skill${groups.length === 1 ? "" : "s"} · ${totalRules} rule${totalRules === 1 ? "" : "s"}`}
          {isFetching && <span className="ml-2 text-gray-400">refreshing…</span>}
        </div>
      </div>

      <div className="bg-white rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-8"></TableHead>
              <TableHead>Skill</TableHead>
              <TableHead>Rules</TableHead>
              <TableHead>Active / Draft / Failed</TableHead>
              <TableHead>Source</TableHead>
              <TableHead>Last updated</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-gray-400 py-8">Loading…</TableCell>
              </TableRow>
            )}
            {!isLoading && groups.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-gray-400 py-8">
                  {searchActive ? "No matches for that search." : "No compiled skills yet."}
                </TableCell>
              </TableRow>
            )}
            {groups.flatMap((g) =>
              renderGroupRows({
                group: g,
                isOpen: expanded.has(g.skillId),
                onToggle: () => toggleExpand(g.skillId),
                onNavigateRule: (ruleId) => navigate(`/rule-domains/${encodeURIComponent(ruleId)}`),
                onTestSkill: () => testSkillMutation.mutate(g.skillId),
                onActivateAll: () => activateAllMutation.mutate(g.skillId),
                onDeleteSkill: () => setPendingSkillDelete(g),
                onActivateRule: (id) => activateMutation.mutate(id),
                onDeprecateRule: (id) => deprecateMutation.mutate(id),
                onDeleteRule: (rule) => setPendingDelete(rule),
                busyActivateAll: activateAllMutation.isPending,
                busyDeleteSkill: deleteSkillMutation.isPending,
                busyTestSkill: testSkillMutation.isPending,
              }),
            )}
          </TableBody>
        </Table>
      </div>

      {/* Per-rule delete */}
      <ConfirmDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null);
        }}
        title="Delete this rule?"
        description="Removes ALL revisions of this rule, every recorded execution, and any orphaned Flowable deployments. Cannot be undone."
        detail={
          pendingDelete && (
            <div className="space-y-0.5">
              <div>
                <span className="font-medium text-gray-900">{pendingDelete.skillName}</span>
                <span className="text-gray-500"> · </span>
                <span className="font-mono text-[11px]">
                  {pendingDelete.ruleName ?? pendingDelete.intentLabel}
                </span>
              </div>
              <div className="text-[11px] text-gray-500">
                Latest revision shown: v{pendingDelete.version} · {pendingDelete.status}
              </div>
            </div>
          )
        }
        confirmLabel="Delete this rule"
        tone="danger"
        busy={deleteMutation.isPending}
        onConfirm={() => {
          if (pendingDelete) deleteMutation.mutate(pendingDelete.id);
        }}
      />

      {/* Skill-level delete (all rules) */}
      <ConfirmDialog
        open={pendingSkillDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingSkillDelete(null);
        }}
        title="Delete the entire skill?"
        description="Removes EVERY rule and every revision of this skill, all execution history, and any orphaned Flowable deployments. The skill's prose definition is untouched — next chat turn will re-compile from scratch."
        detail={
          pendingSkillDelete && (
            <div className="space-y-0.5">
              <div>
                <span className="font-medium text-gray-900">{pendingSkillDelete.skillName}</span>
              </div>
              <div className="text-[11px] text-gray-500">
                {pendingSkillDelete.rules.length} rule
                {pendingSkillDelete.rules.length === 1 ? "" : "s"} will be deleted:
              </div>
              <ul className="text-[11px] text-gray-500 list-disc list-inside ml-2">
                {pendingSkillDelete.rules.map((r) => (
                  <li key={r.id} className="font-mono">
                    {r.ruleName ?? r.intentLabel}
                  </li>
                ))}
              </ul>
            </div>
          )
        }
        confirmLabel="Delete entire skill"
        tone="danger"
        busy={deleteSkillMutation.isPending}
        onConfirm={() => {
          if (pendingSkillDelete) deleteSkillMutation.mutate(pendingSkillDelete.skillId);
        }}
      />
    </div>
  );
}

interface RuleGroupRowsProps {
  group: SkillGroup;
  isOpen: boolean;
  onToggle: () => void;
  onNavigateRule: (id: string) => void;
  onTestSkill: () => void;
  onActivateAll: () => void;
  onDeleteSkill: () => void;
  onActivateRule: (id: string) => void;
  onDeprecateRule: (id: string) => void;
  onDeleteRule: (rule: RuleDomainRow) => void;
  busyActivateAll: boolean;
  busyDeleteSkill: boolean;
  busyTestSkill: boolean;
}

function renderGroupRows({
  group,
  isOpen,
  onToggle,
  onNavigateRule,
  onTestSkill,
  onActivateAll,
  onDeleteSkill,
  onActivateRule,
  onDeprecateRule,
  onDeleteRule,
  busyActivateAll,
  busyDeleteSkill,
  busyTestSkill,
}: RuleGroupRowsProps) {
  const rows: ReactElement[] = [];
  rows.push(
    <TableRow key={`${group.skillId}-head`} className="cursor-pointer hover:bg-gray-50" onClick={onToggle}>
      <TableCell className="w-8">
        {isOpen ? (
          <ChevronDown className="h-4 w-4 text-gray-500" />
        ) : (
          <ChevronRight className="h-4 w-4 text-gray-500" />
        )}
      </TableCell>
      <TableCell className="font-medium">{group.skillName}</TableCell>
      <TableCell>
        <span className="font-mono text-xs">{group.rules.length}</span>
      </TableCell>
      <TableCell className="space-x-1">
        {group.activeCount > 0 && (
          <span className="px-1.5 py-0.5 rounded-full text-[11px] font-medium bg-green-100 text-green-800">
            {group.activeCount} active
          </span>
        )}
        {group.draftCount > 0 && (
          <span className="px-1.5 py-0.5 rounded-full text-[11px] font-medium bg-yellow-100 text-yellow-800">
            {group.draftCount} draft
          </span>
        )}
        {group.failedCount > 0 && (
          <span className="px-1.5 py-0.5 rounded-full text-[11px] font-medium bg-red-100 text-red-800">
            {group.failedCount} failed
          </span>
        )}
      </TableCell>
      <TableCell className="text-xs text-gray-600">
        {group.traceSource === "LLM_TRACE"
          ? "trace"
          : group.traceSource === "LLM_PROSE"
            ? "prose"
            : group.traceSource === "HYBRID"
              ? "hybrid"
              : "—"}
      </TableCell>
      <TableCell className="text-xs text-gray-600">
        {new Date(group.latestUpdatedAt).toLocaleString()}
      </TableCell>
      <TableCell className="text-right space-x-2" onClick={(e) => e.stopPropagation()}>
        <Button
          size="sm"
          variant="outline"
          className="gap-1"
          onClick={onTestSkill}
          disabled={busyTestSkill}
          title="Run every rule in this skill against an empty input map"
        >
          <Play className="h-3.5 w-3.5" />
          Test skill
        </Button>
        <Button
          size="sm"
          variant="outline"
          className="gap-1"
          onClick={onActivateAll}
          disabled={busyActivateAll || group.draftCount + group.failedCount === 0}
          title="Activate every non-active rule in this skill"
        >
          <CheckCircle2 className="h-3.5 w-3.5" />
          Activate all
        </Button>
        <Button
          size="sm"
          variant="outline"
          onClick={onDeleteSkill}
          disabled={busyDeleteSkill}
          title="Delete every rule of this skill"
          className="text-red-700 hover:bg-red-50 hover:border-red-300"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </TableCell>
    </TableRow>,
  );

  if (isOpen) {
    for (const r of group.rules) {
      rows.push(
        <TableRow
          key={r.id}
          className="bg-gray-50/60 cursor-pointer hover:bg-gray-100/70"
          onClick={() => onNavigateRule(r.id)}
        >
          <TableCell className="w-8"></TableCell>
          <TableCell className="pl-8">
            <div className="flex flex-col">
              <span className="font-mono text-xs">
                {r.ruleName ?? r.intentLabel}
              </span>
              {r.resultKey && (
                <span className="text-[11px] text-gray-500">
                  result key: <code>{r.resultKey}</code>
                </span>
              )}
            </div>
          </TableCell>
          <TableCell>
            <span className="font-mono text-xs text-gray-500">v{r.version}</span>
          </TableCell>
          <TableCell>
            <span
              className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                STATUS_COLOR[r.status] ?? ""
              }`}
            >
              {r.status}
            </span>
          </TableCell>
          <TableCell className="text-xs text-gray-500">
            {r.coverageState && r.coverageState !== "COMPLETE" ? r.coverageState : ""}
          </TableCell>
          <TableCell className="text-xs text-gray-500">
            {new Date(r.updatedAt).toLocaleString()}
          </TableCell>
          <TableCell className="text-right space-x-2" onClick={(e) => e.stopPropagation()}>
            <Button
              size="sm"
              variant="outline"
              onClick={() => onNavigateRule(r.id)}
              title="Open editor / run a test on just this rule"
            >
              Open
            </Button>
            {r.status === "ACTIVE" && (
              <Button size="sm" variant="outline" onClick={() => onDeprecateRule(r.id)}>
                Deprecate
              </Button>
            )}
            {(r.status === "DRAFT" || r.status === "DEPRECATED") && (
              <Button size="sm" variant="outline" onClick={() => onActivateRule(r.id)}>
                Activate
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              onClick={() => onDeleteRule(r)}
              title="Delete just this rule"
              className="text-red-700 hover:bg-red-50 hover:border-red-300"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          </TableCell>
        </TableRow>,
      );
    }
  }

  return rows;
}
