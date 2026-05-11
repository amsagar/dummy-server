// OPTIONS field — single-select. Static options come from the descriptor;
// dynamic options come from GET /workflow/plugins/options/{loaderId}.

import { useQuery } from "@tanstack/react-query";
import { workflowApi } from "@/services/workflowApi";
import { SearchableSelect } from "@/components/ui/searchable-select";
import type { PluginPropertyOption } from "@/types/workflow";
import type { FieldProps } from "./StringField";

export function OptionsField({ prop, value, onChange }: FieldProps) {
  const dynamic = useQuery<{ loaderId: string; options: PluginPropertyOption[] }>({
    queryKey: ["plugin-options", prop.optionsLoader],
    queryFn: () => workflowApi.plugins.options(prop.optionsLoader!),
    enabled: !!prop.optionsLoader,
    staleTime: 60 * 1000,
  });

  const options: PluginPropertyOption[] = prop.optionsLoader
    ? dynamic.data?.options ?? []
    : prop.options ?? [];

  // Surface the options to the user even if the dynamic loader is still
  // pending — we render an empty list which the searchable-select handles
  // gracefully by showing "No results".

  return (
    <SearchableSelect
      options={options.map((o) => ({ value: o.value, label: o.label }))}
      value={value == null ? "" : String(value)}
      onValueChange={(v) => onChange(v === "" ? null : v)}
      placeholder={prop.placeholder ?? "Select…"}
      disabled={prop.optionsLoader ? dynamic.isLoading : false}
    />
  );
}
