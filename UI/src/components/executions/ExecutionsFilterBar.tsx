import { Input } from "@/components/ui/input";

export interface ExecutionFilters {
  skillId: string;
  ruleName: string;
  status: "" | "success" | "failed";
  since: string;
  until: string;
  pageSize: number;
}

interface SkillOption {
  skillId: string;
  skillName: string;
}

interface Props {
  filters: ExecutionFilters;
  skills: SkillOption[];
  onChange: (next: ExecutionFilters) => void;
  onReset: () => void;
}

export function ExecutionsFilterBar({ filters, skills, onChange, onReset }: Props) {
  const set = <K extends keyof ExecutionFilters>(k: K, v: ExecutionFilters[K]) =>
    onChange({ ...filters, [k]: v });

  return (
    <div className="rounded border bg-white p-3">
      <div className="grid grid-cols-1 md:grid-cols-6 gap-2">
        <div className="md:col-span-1">
          <label className="text-[11px] font-semibold text-gray-600 uppercase tracking-wide">Skill</label>
          <select
            value={filters.skillId}
            onChange={(e) => set("skillId", e.target.value)}
            className="w-full mt-1 rounded border bg-white px-2 py-1.5 text-xs"
          >
            <option value="">All skills</option>
            {skills.map((s) => (
              <option key={s.skillId} value={s.skillId}>
                {s.skillName}
              </option>
            ))}
          </select>
        </div>
        <div className="md:col-span-1">
          <label className="text-[11px] font-semibold text-gray-600 uppercase tracking-wide">Rule</label>
          <Input
            value={filters.ruleName}
            onChange={(e) => set("ruleName", e.target.value)}
            placeholder="rule_name or intent"
            className="mt-1 h-8 text-xs"
          />
        </div>
        <div className="md:col-span-1">
          <label className="text-[11px] font-semibold text-gray-600 uppercase tracking-wide">Status</label>
          <select
            value={filters.status}
            onChange={(e) => set("status", e.target.value as ExecutionFilters["status"])}
            className="w-full mt-1 rounded border bg-white px-2 py-1.5 text-xs"
          >
            <option value="">Any</option>
            <option value="success">Success</option>
            <option value="failed">Failed</option>
          </select>
        </div>
        <div className="md:col-span-1">
          <label className="text-[11px] font-semibold text-gray-600 uppercase tracking-wide">Since</label>
          <Input
            type="datetime-local"
            value={filters.since}
            onChange={(e) => set("since", e.target.value)}
            className="mt-1 h-8 text-xs"
          />
        </div>
        <div className="md:col-span-1">
          <label className="text-[11px] font-semibold text-gray-600 uppercase tracking-wide">Until</label>
          <Input
            type="datetime-local"
            value={filters.until}
            onChange={(e) => set("until", e.target.value)}
            className="mt-1 h-8 text-xs"
          />
        </div>
        <div className="md:col-span-1 flex items-end gap-2">
          <select
            value={filters.pageSize}
            onChange={(e) => set("pageSize", Number(e.target.value))}
            className="rounded border bg-white px-2 py-1.5 text-xs"
          >
            <option value={25}>25 / page</option>
            <option value={50}>50 / page</option>
            <option value={100}>100 / page</option>
            <option value={200}>200 / page</option>
          </select>
          <button
            type="button"
            onClick={onReset}
            className="text-xs text-gray-600 hover:text-gray-900"
          >
            Reset
          </button>
        </div>
      </div>
    </div>
  );
}
