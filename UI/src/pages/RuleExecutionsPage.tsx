import { useMemo, useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { api } from "@/services/api";
import { ExecutionsFilterBar, type ExecutionFilters } from "@/components/executions/ExecutionsFilterBar";
import { ExecutionsTable, type ExecutionRow } from "@/components/executions/ExecutionsTable";
import { ChevronLeft, ChevronRight } from "lucide-react";

interface PageResp {
  items: ExecutionRow[];
  total: number;
  page: number;
  pageSize: number;
}

interface SkillRow {
  skillId: string;
  skillName: string;
}

const EMPTY_FILTERS: ExecutionFilters = {
  skillId: "",
  ruleName: "",
  status: "",
  since: "",
  until: "",
  pageSize: 50,
};

function toEpoch(local: string): number | undefined {
  if (!local) return undefined;
  const t = new Date(local).getTime();
  return Number.isFinite(t) ? t : undefined;
}

export default function RuleExecutionsPage() {
  const [params, setParams] = useSearchParams();
  const [page, setPage] = useState(Number(params.get("page") ?? 0));

  const filters: ExecutionFilters = useMemo(
    () => ({
      skillId: params.get("skillId") ?? "",
      ruleName: params.get("ruleName") ?? "",
      status: (params.get("status") as ExecutionFilters["status"]) ?? "",
      since: params.get("since") ?? "",
      until: params.get("until") ?? "",
      pageSize: Number(params.get("pageSize") ?? 50),
    }),
    [params],
  );

  const setFilters = (next: ExecutionFilters) => {
    const sp = new URLSearchParams();
    if (next.skillId) sp.set("skillId", next.skillId);
    if (next.ruleName) sp.set("ruleName", next.ruleName);
    if (next.status) sp.set("status", next.status);
    if (next.since) sp.set("since", next.since);
    if (next.until) sp.set("until", next.until);
    sp.set("pageSize", String(next.pageSize));
    setParams(sp);
    setPage(0);
  };

  // Reuse the rule-domains list for the skill dropdown — small payload
  // and already exposes skillId + skillName.
  const { data: domainsData } = useQuery<{ items: Array<SkillRow> }>({
    queryKey: ["rule-domains-skills"],
    queryFn: () => api.ruleDomains.list({ page: 0, pageSize: 500, onlyLatest: true }),
  });
  const skills = useMemo(() => {
    const m = new Map<string, SkillRow>();
    for (const r of domainsData?.items ?? []) {
      if (r.skillId && !m.has(r.skillId)) m.set(r.skillId, { skillId: r.skillId, skillName: r.skillName });
    }
    return Array.from(m.values()).sort((a, b) => a.skillName.localeCompare(b.skillName));
  }, [domainsData]);

  const { data, isFetching } = useQuery<PageResp>({
    queryKey: ["rule-executions", filters, page],
    queryFn: () =>
      api.ruleExecutions.list({
        skillId: filters.skillId || undefined,
        ruleName: filters.ruleName || undefined,
        status: (filters.status || undefined) as "success" | "failed" | undefined,
        since: toEpoch(filters.since),
        until: toEpoch(filters.until),
        page,
        pageSize: filters.pageSize,
      }),
    placeholderData: keepPreviousData,
  });

  const total = data?.total ?? 0;
  const pageSize = data?.pageSize ?? filters.pageSize;
  const pageCount = Math.max(Math.ceil(total / pageSize), 1);

  return (
    <div className="space-y-3 w-full">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-[#123262]">Executions</h2>
          <p className="text-xs text-gray-500">
            Every rule-domain BPMN run across all skills. Click a row to inspect the timeline.
          </p>
        </div>
        <div className="text-xs text-gray-500">{total.toLocaleString()} executions</div>
      </div>

      <ExecutionsFilterBar
        filters={filters}
        skills={skills}
        onChange={setFilters}
        onReset={() => setFilters(EMPTY_FILTERS)}
      />

      <ExecutionsTable items={data?.items ?? []} isLoading={isFetching} />

      <div className="flex items-center justify-between text-xs text-gray-600">
        <span>
          Page {page + 1} of {pageCount}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(p - 1, 0))}
            className="inline-flex items-center gap-1 rounded border px-2 py-1 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <ChevronLeft className="h-3.5 w-3.5" /> Prev
          </button>
          <button
            type="button"
            disabled={page + 1 >= pageCount}
            onClick={() => setPage((p) => p + 1)}
            className="inline-flex items-center gap-1 rounded border px-2 py-1 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            Next <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
}
