// Audit trail. Each entry shows ts, action, actor, and a collapsible
// payload_json (parsed when valid).

import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { ScrollArea } from "@/components/ui/scroll-area";
import { JsonTree } from "./JsonTree";
import type { AuditTrailEntry } from "@/types/workflow";

interface AuditTabProps {
  entries: AuditTrailEntry[];
}

export function AuditTab({ entries }: AuditTabProps) {
  if (entries.length === 0) {
    return <p className="text-xs text-muted-foreground italic">No audit entries.</p>;
  }
  return (
    <ScrollArea className="h-[600px]">
      <ul className="divide-y">
        {entries.map((e) => (
          <AuditRow key={e.id} entry={e} />
        ))}
      </ul>
    </ScrollArea>
  );
}

function AuditRow({ entry }: { entry: AuditTrailEntry }) {
  const [open, setOpen] = useState(false);
  const parsed = (() => {
    if (!entry.payloadJson) return null;
    try {
      return JSON.parse(entry.payloadJson);
    } catch {
      return entry.payloadJson;
    }
  })();
  const tone = actionTone(entry.action);

  return (
    <li className="py-2">
      <button
        type="button"
        onClick={() => parsed != null && setOpen((o) => !o)}
        className="w-full flex items-start gap-2 text-left text-xs hover:bg-muted/40 px-1 rounded"
      >
        <span className="text-muted-foreground font-mono whitespace-nowrap">
          {new Date(entry.ts).toLocaleTimeString()}
        </span>
        <span className={`font-medium px-1.5 rounded ${tone}`}>{entry.action}</span>
        <span className="text-muted-foreground truncate flex-1">{entry.actor ?? "—"}</span>
        {parsed != null &&
          (open ? <ChevronDown className="w-3 h-3 mt-0.5" /> : <ChevronRight className="w-3 h-3 mt-0.5" />)}
      </button>
      {open && parsed != null && (
        <div className="mt-1 ml-6 border-l border-border/60 pl-2">
          {typeof parsed === "string" ? (
            <pre className="text-xs font-mono whitespace-pre-wrap break-words">{parsed}</pre>
          ) : (
            <JsonTree value={parsed} defaultExpandDepth={1} />
          )}
        </div>
      )}
    </li>
  );
}

function actionTone(action: string): string {
  if (action.includes("failed") || action.includes("error"))
    return "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300";
  if (action.includes("completed"))
    return "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300";
  if (action.includes("started"))
    return "bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-300";
  return "bg-muted text-foreground";
}
