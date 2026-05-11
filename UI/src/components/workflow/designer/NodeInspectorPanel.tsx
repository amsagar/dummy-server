// Right-rail inspector for a selected node. Three tabs:
//   - Parameters: descriptor-driven form (FormRenderer)
//   - Settings:   manual fields (SettingsTab)
//   - Data:       run-time IO snapshots (Phase 3)
//
// The panel is "always wired" — when no node is selected the parent simply
// doesn't mount this component.

import { Loader2, Play, Sparkles, Trash2, X } from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { FormRenderer } from "../inspector/FormRenderer";
import { JsonTree } from "../run/JsonTree";
import { SettingsTab } from "./SettingsTab";
import { DataTab } from "./DataTab";
import { usePluginCatalog } from "@/hooks/usePluginCatalog";
import { iconFor } from "./nodes/iconMap";
import { workflowApi } from "@/services/workflowApi";
import { toast } from "sonner";
import type { BoardNodeData } from "@/lib/workflowSerializer";

interface NodeInspectorPanelProps {
  nodeId: string;
  data: BoardNodeData;
  onChange: (next: BoardNodeData) => void;
  onClose: () => void;
  onDelete: () => void;
}

export function NodeInspectorPanel({ nodeId, data, onChange, onClose, onDelete }: NodeInspectorPanelProps) {
  const { get } = usePluginCatalog();
  const descriptor = get(data.pluginName);
  const Icon = iconFor(descriptor?.icon);

  const test = useMutation({
    mutationFn: () =>
      workflowApi.plugins.preview(data.pluginName!, data.properties ?? {}),
    onSuccess: (r) => {
      if (r.success) toast.success(`Test ok in ${r.durationMs}ms`);
      else toast.error(`Test failed: ${r.error ?? "unknown"}`);
    },
    onError: (e: Error) => toast.error(e.message ?? "Test failed"),
  });

  return (
    <aside className="w-[380px] border-l flex flex-col bg-background">
      <header className="border-b p-3 flex items-center gap-2">
        <div className="rounded bg-blue-100 dark:bg-blue-950 text-blue-700 dark:text-blue-300 p-1.5">
          <Icon className="w-4 h-4" />
        </div>
        <div className="flex flex-col leading-tight overflow-hidden flex-1">
          <span className="text-sm font-medium truncate">{data.label ?? nodeId}</span>
          <span className="text-[11px] text-muted-foreground truncate">
            {descriptor?.label ?? data.pluginName ?? data.activityType ?? "node"}
          </span>
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            if (confirm(`Delete node "${data.label ?? nodeId}"?`)) onDelete();
          }}
          aria-label="Delete node"
          title="Delete node"
          className="text-rose-600 hover:text-rose-700"
        >
          <Trash2 className="w-4 h-4" />
        </Button>
        <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close inspector">
          <X className="w-4 h-4" />
        </Button>
      </header>

      <Tabs defaultValue={descriptor ? "parameters" : "settings"} className="flex-1 flex flex-col overflow-hidden">
        <TabsList className="m-2 grid grid-cols-3">
          <TabsTrigger value="parameters" disabled={!descriptor}>
            Parameters
          </TabsTrigger>
          <TabsTrigger value="settings">Settings</TabsTrigger>
          <TabsTrigger value="data">Data</TabsTrigger>
        </TabsList>

        <ScrollArea className="flex-1">
          <div className="p-3">
            <TabsContent value="parameters" className="m-0">
              {descriptor ? (
                <>
                  <FormRenderer
                    properties={descriptor.properties}
                    values={data.properties ?? {}}
                    onChange={(next) => onChange({ ...data, properties: next })}
                  />
                  <div className="mt-4 flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => test.mutate()}
                      disabled={!data.pluginName || test.isPending}
                    >
                      {test.isPending ? (
                        <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" />
                      ) : (
                        <Play className="w-3.5 h-3.5 mr-1" />
                      )}
                      Test step
                    </Button>
                    {test.data && !test.isPending && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-xs text-muted-foreground"
                        onClick={() => test.reset()}
                      >
                        Clear result
                      </Button>
                    )}
                    {descriptor.description && (
                      <span className="text-[11px] text-muted-foreground self-center">
                        <Sparkles className="w-3 h-3 inline mr-0.5" /> {descriptor.description}
                      </span>
                    )}
                  </div>

                  {test.data && (
                    <div className="mt-3 border rounded-md overflow-hidden">
                      <div
                        className={
                          "px-3 py-2 text-xs font-medium border-b flex items-center justify-between " +
                          (test.data.success
                            ? "bg-emerald-50 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200"
                            : "bg-rose-50 text-rose-800 dark:bg-rose-950/40 dark:text-rose-200")
                        }
                      >
                        <span>
                          {test.data.success ? "✓ ok" : "✗ failed"} · {test.data.durationMs}ms
                        </span>
                      </div>
                      {test.data.success ? (
                        <div className="bg-background max-h-[280px] overflow-auto">
                          <JsonTree value={test.data.output} defaultExpandDepth={2} />
                        </div>
                      ) : (
                        <pre className="text-xs font-mono p-3 whitespace-pre-wrap break-words">
                          {test.data.error ?? "unknown error"}
                        </pre>
                      )}
                    </div>
                  )}
                </>
              ) : (
                <p className="text-xs text-muted-foreground italic">
                  This node has no plugin parameters. Use the Settings tab.
                </p>
              )}
            </TabsContent>

            <TabsContent value="settings" className="m-0">
              <SettingsTab data={data} onChange={onChange} />
            </TabsContent>

            <TabsContent value="data" className="m-0">
              <DataTab />
            </TabsContent>
          </div>
        </ScrollArea>
      </Tabs>
    </aside>
  );
}
