// Plugin catalog hook. Fetches the list of describable plugins from the
// backend (GET /workflow/plugins) and indexes them by name. The result is
// kept hot for the lifetime of the app — descriptors only change on backend
// deploy — so we use a 5-minute staleTime.

import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { workflowApi } from "@/services/workflowApi";
import type { PluginDescriptor } from "@/types/workflow";

export function usePluginCatalog() {
  const query = useQuery<{ plugins: PluginDescriptor[] }>({
    queryKey: ["plugin-catalog"],
    queryFn: () => workflowApi.plugins.list(),
    staleTime: 5 * 60 * 1000,
  });

  const byName = useMemo(() => {
    const map = new Map<string, PluginDescriptor>();
    for (const d of query.data?.plugins ?? []) map.set(d.name, d);
    return map;
  }, [query.data]);

  const byCategory = useMemo(() => {
    const map = new Map<string, PluginDescriptor[]>();
    for (const d of query.data?.plugins ?? []) {
      const cat = d.category ?? "Other";
      const list = map.get(cat) ?? [];
      list.push(d);
      map.set(cat, list);
    }
    // Sort each category by label.
    for (const [, list] of map) list.sort((a, b) => a.label.localeCompare(b.label));
    return map;
  }, [query.data]);

  return {
    plugins: query.data?.plugins ?? [],
    byName,
    byCategory,
    isLoading: query.isLoading,
    error: query.error as Error | null,
    get: (name: string | null | undefined): PluginDescriptor | undefined =>
      name ? byName.get(name) : undefined,
  };
}
