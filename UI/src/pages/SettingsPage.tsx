import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SearchableSelect, type SearchableSelectOption } from "@/components/ui/searchable-select";
import { toast } from "sonner";
import { ArrowLeft, AlertTriangle } from "lucide-react";

interface RuleDomainConfig {
  enabled: boolean;
  enabledSkills: string[];
  matchThreshold: number;
  maxCompileAttempts: number;
  promoteAfterSuccessfulRuns: number;
  shadowMode: boolean;
  autoDeprecateErrorRate: number;
  compilerProviderId: string;
  compilerModelId: string;
  summarizerProviderId: string;
  summarizerModelId: string;
  embeddingProviderId: string;
  embeddingModelId: string;
  updatedAt?: number;
}

interface ModelRow {
  providerID: string;
  modelID: string;
  displayName?: string;
  enabled?: boolean;
  hasKey?: boolean;
  modelKind?: string;
}

interface SkillRow {
  id: string;
  name: string;
  description?: string;
  isDefault?: boolean;
}

const SYSTEM_DEFAULT = "__default__";

export default function SettingsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();

  const { data: cfg, isLoading } = useQuery<RuleDomainConfig>({
    queryKey: ["rule-domain-config"],
    queryFn: () => api.ruleDomainConfig.get(),
  });

  const { data: allModels = [] } = useQuery<ModelRow[]>({
    queryKey: ["models"],
    queryFn: () => api.models.list(),
  });

  const { data: embeddingModels = [] } = useQuery<ModelRow[]>({
    queryKey: ["embedding-models"],
    queryFn: () => api.embeddingModels.list(),
  });

  const { data: skills = [] } = useQuery<SkillRow[]>({
    queryKey: ["skills-registry"],
    queryFn: () => api.skills.registry(),
  });

  // Chat-only registered models. The /models endpoint returns every model row
  // including embeddings; we filter out everything that isn't a chat model and
  // ensure it's enabled.
  const chatOptions = useMemo<SearchableSelectOption[]>(
    () =>
      allModels
        .filter((m) => (m.modelKind ?? "chat") !== "embedding")
        .filter((m) => m.enabled !== false)
        // "Configured" means creds are present for runtime routing.
        .filter((m) => m.hasKey !== false)
        .map(modelToOption),
    [allModels]
  );

  const embeddingOptions = useMemo<SearchableSelectOption[]>(
    () =>
      embeddingModels
        .filter((m) => m.enabled !== false)
        .map(modelToOption),
    [embeddingModels]
  );

  const [draft, setDraft] = useState<RuleDomainConfig | null>(null);
  useEffect(() => {
    if (cfg) setDraft({ ...cfg });
  }, [cfg]);

  const saveMutation = useMutation({
    mutationFn: (payload: RuleDomainConfig) => api.ruleDomainConfig.update(payload),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rule-domain-config"] });
      qc.invalidateQueries({ queryKey: ["rule-domains"] });
      toast.success("Configuration saved");
    },
    onError: (e: any) => toast.error(e?.message || "Failed to save"),
  });

  if (isLoading || !draft) {
    return <div className="text-gray-400">Loading settings…</div>;
  }

  const dirty = cfg ? JSON.stringify(cfg) !== JSON.stringify(draft) : false;

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <Button
            variant="link"
            size="sm"
            className="px-0 text-gray-500 gap-1"
            onClick={() => navigate("/rule-domains")}
          >
            <ArrowLeft className="h-4 w-4" />
            Back to Rule Domains
          </Button>
          <h2 className="text-xl font-semibold text-[#123262]">Rule Domain Settings</h2>
          <p className="text-sm text-gray-500">
            Stored in <code className="text-xs">agent.rule_domain_config</code>. Changes apply immediately.
          </p>
        </div>
        <div className="flex gap-2 pt-4">
          <Button
            variant="ghost"
            onClick={() => cfg && setDraft({ ...cfg })}
            disabled={!dirty || saveMutation.isPending}
          >
            Reset
          </Button>
          <Button
            onClick={() => saveMutation.mutate(draft)}
            disabled={!dirty || saveMutation.isPending}
          >
            {saveMutation.isPending ? "Saving…" : "Save changes"}
          </Button>
        </div>
      </div>

      <Section title="Feature toggles">
        <ToggleRow
          label="Master switch"
          help="When off, every chat request goes through the existing LLM loop. Required for any compiled-domain behavior."
          value={draft.enabled}
          onChange={(v) => setDraft({ ...draft, enabled: v })}
        />
        <ToggleRow
          label="Shadow mode"
          help="Compile + execute even on cache hits and log a diff against the LLM-loop output. Useful for building confidence before fully relying on a domain."
          value={draft.shadowMode}
          onChange={(v) => setDraft({ ...draft, shadowMode: v })}
        />
      </Section>

      <Section
        title="Allowlisted skills"
        help="Only these skills are eligible for compilation. A skill must also be enabled in the Skills page."
      >
        <SkillMultiSelect
          all={skills}
          selected={draft.enabledSkills}
          onChange={(next) => setDraft({ ...draft, enabledSkills: next })}
        />
      </Section>

      <Section title="Matching & lifecycle">
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-x-6 gap-y-4">
          <NumberField
            label="Match threshold (cosine)"
            help="Higher = stricter intent matching. Start at 0.92."
            value={draft.matchThreshold}
            step={0.01}
            min={0}
            max={1}
            onChange={(v) => setDraft({ ...draft, matchThreshold: v })}
          />
          <NumberField
            label="Max compile attempts"
            help="Initial + repair retries before marking FAILED."
            value={draft.maxCompileAttempts}
            step={1}
            min={1}
            onChange={(v) => setDraft({ ...draft, maxCompileAttempts: v })}
          />
          <NumberField
            label="Promote to ACTIVE after N successful runs"
            value={draft.promoteAfterSuccessfulRuns}
            step={1}
            min={1}
            onChange={(v) => setDraft({ ...draft, promoteAfterSuccessfulRuns: v })}
          />
          <NumberField
            label="Auto-deprecate error rate"
            help="Fraction (0–1). Domains exceeding this in the last 1h auto-deprecate."
            value={draft.autoDeprecateErrorRate}
            step={0.05}
            min={0}
            max={1}
            onChange={(v) => setDraft({ ...draft, autoDeprecateErrorRate: v })}
          />
        </div>
      </Section>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Section
          title="Rule generation model"
          help="Selected model is used for rule generation (manifest derivation + BPMN compile)."
        >
          <ModelPicker
            options={chatOptions}
            providerId={draft.compilerProviderId}
            modelId={draft.compilerModelId}
            onChange={(p, m) =>
              setDraft({ ...draft, compilerProviderId: p, compilerModelId: m })
            }
            emptyAction={
              <a className="underline text-[#123262] hover:text-[#E31837]" href="/models">
                Register a chat model →
              </a>
            }
          />
        </Section>

        <Section
          title="Summarizer model"
          help="LLM that turns the structured BPMN output into the user-facing reply (hot path)."
        >
          <ModelPicker
            options={chatOptions}
            providerId={draft.summarizerProviderId}
            modelId={draft.summarizerModelId}
            onChange={(p, m) =>
              setDraft({ ...draft, summarizerProviderId: p, summarizerModelId: m })
            }
            emptyAction={
              <a className="underline text-[#123262] hover:text-[#E31837]" href="/models">
                Register a chat model →
              </a>
            }
          />
        </Section>

        <Section
          title="Embedding model"
          help="Used for intent matching. Pick 'system default' to use the embedding model marked as default in the registry."
        >
          <ModelPicker
            options={embeddingOptions}
            providerId={draft.embeddingProviderId}
            modelId={draft.embeddingModelId}
            allowSystemDefault
            onChange={(p, m) =>
              setDraft({ ...draft, embeddingProviderId: p, embeddingModelId: m })
            }
            emptyAction={
              <a className="underline text-[#123262] hover:text-[#E31837]" href="/embedding-models">
                Register an embedding model →
              </a>
            }
          />
        </Section>
      </div>
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────────────

function modelToOption(m: ModelRow): SearchableSelectOption {
  const value = `${m.providerID}/${m.modelID}`;
  return {
    value,
    label: m.displayName ? m.displayName : m.modelID,
    sublabel: value,
  };
}

function Section({
  title,
  help,
  children,
}: {
  title: string;
  help?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-white border rounded-lg p-5 space-y-3">
      <div>
        <h3 className="font-semibold text-[#123262]">{title}</h3>
        {help && <p className="text-xs text-gray-500 mt-0.5">{help}</p>}
      </div>
      {children}
    </div>
  );
}

function ToggleRow({
  label,
  help,
  value,
  onChange,
}: {
  label: string;
  help?: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="flex items-start justify-between gap-4 cursor-pointer py-1">
      <div>
        <div className="font-medium text-gray-700">{label}</div>
        {help && <div className="text-xs text-gray-500 mt-0.5">{help}</div>}
      </div>
      <input
        type="checkbox"
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
        className="h-5 w-5 mt-1 accent-[#E31837]"
      />
    </label>
  );
}

function NumberField({
  label,
  help,
  value,
  step,
  min,
  max,
  onChange,
}: {
  label: string;
  help?: string;
  value: number;
  step: number;
  min?: number;
  max?: number;
  onChange: (v: number) => void;
}) {
  return (
    <div>
      <div className="font-medium text-gray-700 text-sm">{label}</div>
      {help && <div className="text-xs text-gray-500 mb-1">{help}</div>}
      <Input
        type="number"
        step={step}
        min={min}
        max={max}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
      />
    </div>
  );
}

function ModelPicker({
  options,
  providerId,
  modelId,
  onChange,
  allowSystemDefault = false,
  emptyAction,
}: {
  options: SearchableSelectOption[];
  providerId: string;
  modelId: string;
  onChange: (providerId: string, modelId: string) => void;
  allowSystemDefault?: boolean;
  emptyAction?: React.ReactNode;
}) {
  const currentValue =
    !providerId && !modelId && allowSystemDefault
      ? SYSTEM_DEFAULT
      : `${providerId}/${modelId}`;

  const decoratedOptions: SearchableSelectOption[] = useMemo(() => {
    const out: SearchableSelectOption[] = [];
    if (allowSystemDefault) {
      out.push({
        value: SYSTEM_DEFAULT,
        label: "System default",
        sublabel: "Uses the embedding model marked default in the registry",
      });
    }
    out.push(...options);
    return out;
  }, [options, allowSystemDefault]);

  const isStoredButUnregistered =
    providerId && modelId && !options.some((o) => o.value === `${providerId}/${modelId}`);

  // If the stored model isn't in the registered set, we still want the user to
  // be able to see it AND clearly understand it's unregistered. Surface a
  // warning above the dropdown rather than mixing it into the options.
  if (options.length === 0 && !allowSystemDefault) {
    return (
      <div className="rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 flex items-start gap-2">
        <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
        <div className="flex-1">
          <div>No registered models match this slot.</div>
          {emptyAction && <div className="mt-1 text-xs">{emptyAction}</div>}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {isStoredButUnregistered && (
        <div className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800 flex items-start gap-2">
          <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
          <div>
            Currently saved as{" "}
            <code className="font-mono text-[11px]">
              {providerId}/{modelId}
            </code>{" "}
            — that model isn't registered. Pick a registered model below.
          </div>
        </div>
      )}
      <SearchableSelect
        options={decoratedOptions}
        value={currentValue}
        placeholder="Select a model…"
        searchPlaceholder="Search models…"
        onValueChange={(v) => {
          if (v === SYSTEM_DEFAULT) {
            onChange("", "");
            return;
          }
          const idx = v.indexOf("/");
          if (idx < 0) return;
          onChange(v.substring(0, idx), v.substring(idx + 1));
        }}
      />
    </div>
  );
}

function SkillMultiSelect({
  all,
  selected,
  onChange,
}: {
  all: SkillRow[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  if (all.length === 0) {
    return <div className="text-sm text-gray-500">No registered skills.</div>;
  }
  const selectedSet = new Set(selected.map((s) => s.toLowerCase()));
  const toggle = (name: string) => {
    const lc = name.toLowerCase();
    if (selectedSet.has(lc)) {
      onChange(selected.filter((s) => s.toLowerCase() !== lc));
    } else {
      onChange([...selected, name]);
    }
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-2">
      {all.map((s) => {
        const checked = selectedSet.has(s.name.toLowerCase());
        const desc = cleanDescription(s.description);
        return (
          <label
            key={s.id}
            className={`flex items-start gap-3 border rounded-lg p-3 cursor-pointer text-sm transition ${
              checked
                ? "border-[#E31837] bg-red-50/50 ring-1 ring-[#E31837]/20"
                : "border-gray-200 hover:bg-gray-50"
            }`}
          >
            <input
              type="checkbox"
              checked={checked}
              onChange={() => toggle(s.name)}
              className="mt-0.5 h-4 w-4 accent-[#E31837]"
            />
            <div className="min-w-0 flex-1">
              <div className="font-medium text-gray-800 flex items-center gap-2">
                <span className="truncate">{s.name}</span>
                {s.isDefault && (
                  <span className="shrink-0 text-[10px] uppercase font-semibold tracking-wide bg-gray-200 text-gray-700 rounded px-1.5 py-0.5">
                    Built-in
                  </span>
                )}
              </div>
              {desc && (
                <div className="text-xs text-gray-500 mt-0.5 line-clamp-2">{desc}</div>
              )}
            </div>
          </label>
        );
      })}
    </div>
  );
}

/** Skill descriptions sometimes come back as the literal YAML block-scalar
 *  indicator ('>' or '|') if the frontmatter parser didn't fold the value.
 *  Treat those — and any single non-alphanumeric character — as empty. */
function cleanDescription(raw?: string): string {
  if (!raw) return "";
  const trimmed = raw.trim();
  if (trimmed.length <= 1) return "";
  if (/^[>|\-]+$/.test(trimmed)) return "";
  return trimmed;
}
