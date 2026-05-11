// Per-activity IO panel. Shows Input / Output / Error in three sub-tabs,
// each with a Table / JSON / Schema view toggle, a search box, and a copy
// button. Snapshots arrive from the backend as JSON strings — we parse them
// best-effort and fall back to raw text if they don't parse.

import { useMemo, useState } from "react";
import { Copy, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { toast } from "sonner";
import { JsonTree } from "./JsonTree";
import { SchemaView } from "./SchemaView";
import { TableView } from "./TableView";
import type { ActivityInst } from "@/types/workflow";

type ViewMode = "table" | "json" | "schema";

interface IOPanelProps {
  activity: ActivityInst;
}

function parseSnapshot(s: string | null): { value: unknown; rawFallback: string | null } {
  if (s == null) return { value: null, rawFallback: null };
  const trimmed = s.trim();
  if (!trimmed) return { value: null, rawFallback: null };
  try {
    return { value: JSON.parse(trimmed), rawFallback: null };
  } catch {
    return { value: null, rawFallback: s };
  }
}

export function IOPanel({ activity }: IOPanelProps) {
  const input = useMemo(() => parseSnapshot(activity.inputSnapshot), [activity.inputSnapshot]);
  const output = useMemo(() => parseSnapshot(activity.outputSnapshot), [activity.outputSnapshot]);
  const hasError = !!activity.errorClass || !!activity.errorMessage;

  const [view, setView] = useState<ViewMode>("json");
  const [search, setSearch] = useState("");

  return (
    <div className="flex flex-col h-full">
      <div className="border-b p-2 flex items-center gap-2 bg-background">
        <div className="inline-flex rounded-md border bg-muted p-0.5 text-xs">
          {(["table", "json", "schema"] as const).map((m) => (
            <Button
              key={m}
              type="button"
              size="sm"
              variant={view === m ? "default" : "ghost"}
              className="h-6 px-2 text-xs capitalize"
              onClick={() => setView(m)}
            >
              {m}
            </Button>
          ))}
        </div>
        <div className="relative flex-1">
          <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search keys / values…"
            className="h-7 text-xs pl-6"
          />
        </div>
      </div>

      <Tabs defaultValue={hasError ? "error" : "output"} className="flex-1 flex flex-col">
        <TabsList className="m-2 grid grid-cols-3">
          <TabsTrigger value="input">Input</TabsTrigger>
          <TabsTrigger value="output">Output</TabsTrigger>
          <TabsTrigger value="error" disabled={!hasError} className={hasError ? "text-red-600 data-[state=active]:text-red-600" : undefined}>
            Error
          </TabsTrigger>
        </TabsList>
        <div className="flex-1 overflow-auto">
          <TabsContent value="input" className="m-0 p-0">
            <SnapshotView parsed={input} view={view} search={search} />
          </TabsContent>
          <TabsContent value="output" className="m-0 p-0">
            <SnapshotView parsed={output} view={view} search={search} />
          </TabsContent>
          <TabsContent value="error" className="m-0 p-3">
            {hasError ? (
              <div className="space-y-2 text-sm">
                <div>
                  <span className="text-xs font-medium text-muted-foreground">Class</span>
                  <div className="font-mono text-red-700 dark:text-red-400">{activity.errorClass}</div>
                </div>
                <div>
                  <span className="text-xs font-medium text-muted-foreground">Message</span>
                  <div className="font-mono break-words">{activity.errorMessage}</div>
                </div>
              </div>
            ) : (
              <p className="text-xs text-muted-foreground italic">No error.</p>
            )}
          </TabsContent>
        </div>
      </Tabs>
    </div>
  );
}

function SnapshotView({
  parsed,
  view,
  search,
}: {
  parsed: { value: unknown; rawFallback: string | null };
  view: ViewMode;
  search: string;
}) {
  if (parsed.value === null && parsed.rawFallback == null) {
    return <p className="text-xs text-muted-foreground italic p-3">No data.</p>;
  }
  if (parsed.rawFallback != null) {
    // Couldn't parse — show raw with a copy button.
    return (
      <div className="p-2">
        <div className="flex justify-end mb-1">
          <Button
            size="sm"
            variant="ghost"
            className="h-6 px-2 text-xs"
            onClick={() => {
              navigator.clipboard.writeText(parsed.rawFallback ?? "");
              toast.success("Copied raw");
            }}
          >
            <Copy className="w-3 h-3 mr-1" /> Copy raw
          </Button>
        </div>
        <pre className="text-xs font-mono bg-muted/40 rounded p-2 overflow-x-auto whitespace-pre-wrap break-words">
          {parsed.rawFallback}
        </pre>
      </div>
    );
  }
  const v = parsed.value;
  if (view === "table") return <TableView value={v} search={search} />;
  if (view === "schema") return <SchemaView value={v} />;
  return <JsonTree value={v} search={search} />;
}
