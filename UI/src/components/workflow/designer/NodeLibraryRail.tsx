// Left rail: searchable, categorized list of plugin descriptors PLUS three
// fixed entries (Start, End, Subflow, loop nodes) that map to non-tool node
// types directly.
//
// Each item is draggable. The drop is handled in WorkflowDesignerPage's
// onDrop handler — we set a custom MIME so the canvas only accepts our
// payload.

import { useMemo, useState } from "react";
import { CircleDot, Flag, Loader2, RefreshCw, Repeat, Search, SquareStack, Waypoints } from "lucide-react";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { usePluginCatalog } from "@/hooks/usePluginCatalog";
import { iconFor } from "./nodes/iconMap";
import type { PluginDescriptor } from "@/types/workflow";

export const DRAG_MIME = "application/x-pods-plugin";

export type LibraryDragPayload =
  | { kind: "plugin"; pluginName: string }
  | { kind: "route"; flag?: "start" | "end" }
  | { kind: "subflow" }
  | { kind: "loop"; loopType: "foreach" | "while" | "batch" };

interface NodeLibraryRailProps {
  /** Click-to-add fallback. Drop also fires this with a default position. */
  onAdd: (payload: LibraryDragPayload) => void;
}

export function NodeLibraryRail({ onAdd }: NodeLibraryRailProps) {
  const { byCategory, isLoading, error } = usePluginCatalog();
  const [q, setQ] = useState("");

  const filteredCategories = useMemo(() => {
    const ql = q.trim().toLowerCase();
    if (!ql) return byCategory;
    const out = new Map<string, PluginDescriptor[]>();
    for (const [cat, list] of byCategory) {
      const sub = list.filter(
        (d) =>
          d.label.toLowerCase().includes(ql) ||
          d.name.toLowerCase().includes(ql) ||
          (d.description ?? "").toLowerCase().includes(ql),
      );
      if (sub.length) out.set(cat, sub);
    }
    return out;
  }, [byCategory, q]);

  return (
    <aside className="w-[220px] border-r flex flex-col bg-background">
      <div className="p-2 border-b">
        <div className="relative">
          <Search className="w-3.5 h-3.5 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search nodes…"
            className="h-8 text-xs pl-7"
          />
        </div>
      </div>
      <ScrollArea className="flex-1">
        <div className="p-2 flex flex-col gap-3">
          <Section title="Flow">
            <FixedItem
              label="Start"
              icon={<CircleDot className="w-3.5 h-3.5" />}
              tone="emerald"
              onAdd={() => onAdd({ kind: "route", flag: "start" })}
              dragPayload={{ kind: "route", flag: "start" }}
            />
            <FixedItem
              label="End"
              icon={<Flag className="w-3.5 h-3.5" />}
              tone="rose"
              onAdd={() => onAdd({ kind: "route", flag: "end" })}
              dragPayload={{ kind: "route", flag: "end" }}
            />
            <FixedItem
              label="Subflow"
              icon={<SquareStack className="w-3.5 h-3.5" />}
              tone="violet"
              onAdd={() => onAdd({ kind: "subflow" })}
              dragPayload={{ kind: "subflow" }}
            />
            <FixedItem
              label="For Each"
              icon={<Repeat className="w-3.5 h-3.5" />}
              tone="amber"
              onAdd={() => onAdd({ kind: "loop", loopType: "foreach" })}
              dragPayload={{ kind: "loop", loopType: "foreach" }}
            />
            <FixedItem
              label="While"
              icon={<RefreshCw className="w-3.5 h-3.5" />}
              tone="amber"
              onAdd={() => onAdd({ kind: "loop", loopType: "while" })}
              dragPayload={{ kind: "loop", loopType: "while" }}
            />
            <FixedItem
              label="Batch"
              icon={<Waypoints className="w-3.5 h-3.5" />}
              tone="amber"
              onAdd={() => onAdd({ kind: "loop", loopType: "batch" })}
              dragPayload={{ kind: "loop", loopType: "batch" }}
            />
          </Section>

          {isLoading && (
            <div className="flex items-center gap-2 text-xs text-muted-foreground p-2">
              <Loader2 className="w-3 h-3 animate-spin" /> Loading plugins…
            </div>
          )}
          {error && (
            <div className="text-xs text-red-600 p-2">
              Failed to load plugins: {error.message}
            </div>
          )}

          {[...filteredCategories.entries()].map(([cat, list]) => (
            <Section key={cat} title={cat}>
              {list.map((d) => (
                <PluginItem
                  key={d.name}
                  descriptor={d}
                  onAdd={() => onAdd({ kind: "plugin", pluginName: d.name })}
                />
              ))}
            </Section>
          ))}
        </div>
      </ScrollArea>
    </aside>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="text-[11px] uppercase tracking-wide text-muted-foreground mb-1 px-1">
        {title}
      </h4>
      <div className="flex flex-col gap-1">{children}</div>
    </div>
  );
}

function PluginItem({
  descriptor,
  onAdd,
}: {
  descriptor: PluginDescriptor;
  onAdd: () => void;
}) {
  const Icon = iconFor(descriptor.icon);
  const payload: LibraryDragPayload = { kind: "plugin", pluginName: descriptor.name };
  return (
    <button
      type="button"
      draggable
      onDragStart={(e) => {
        e.dataTransfer.effectAllowed = "copy";
        e.dataTransfer.setData(DRAG_MIME, JSON.stringify(payload));
      }}
      onClick={onAdd}
      className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-muted text-left"
      title={descriptor.description ?? descriptor.label}
    >
      <Icon className="w-4 h-4 text-blue-700 dark:text-blue-300" />
      <span className="text-xs truncate">{descriptor.label}</span>
    </button>
  );
}

function FixedItem({
  label,
  icon,
  tone,
  onAdd,
  dragPayload,
}: {
  label: string;
  icon: React.ReactNode;
  tone: "emerald" | "rose" | "amber" | "violet";
  onAdd: () => void;
  dragPayload: LibraryDragPayload;
}) {
  const toneClasses: Record<string, string> = {
    emerald: "text-emerald-700 dark:text-emerald-300",
    rose: "text-rose-700 dark:text-rose-300",
    amber: "text-amber-700 dark:text-amber-300",
    violet: "text-violet-700 dark:text-violet-300",
  };
  return (
    <button
      type="button"
      draggable
      onDragStart={(e) => {
        e.dataTransfer.effectAllowed = "copy";
        e.dataTransfer.setData(DRAG_MIME, JSON.stringify(dragPayload));
      }}
      onClick={onAdd}
      className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-muted text-left"
    >
      <span className={toneClasses[tone]}>{icon}</span>
      <span className="text-xs">{label}</span>
    </button>
  );
}
