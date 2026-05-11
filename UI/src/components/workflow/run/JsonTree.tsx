// Collapsible JSON tree view. Folders default to expanded for the first two
// levels. Search highlights matching keys / leaf values.

import { useState } from "react";
import { ChevronDown, ChevronRight, Copy } from "lucide-react";
import { toast } from "sonner";

interface JsonTreeProps {
  value: unknown;
  search?: string;
  defaultExpandDepth?: number;
}

export function JsonTree({ value, search, defaultExpandDepth = 2 }: JsonTreeProps) {
  return (
    <div className="text-xs font-mono p-2 overflow-x-auto">
      <Node label="" value={value} depth={0} maxOpenDepth={defaultExpandDepth} search={search} root />
    </div>
  );
}

function Node({
  label,
  value,
  depth,
  maxOpenDepth,
  search,
  root,
}: {
  label: string;
  value: unknown;
  depth: number;
  maxOpenDepth: number;
  search?: string;
  root?: boolean;
}) {
  const [open, setOpen] = useState<boolean>(depth < maxOpenDepth);
  const ql = (search ?? "").trim().toLowerCase();
  const isObject = value !== null && typeof value === "object" && !Array.isArray(value);
  const isArray = Array.isArray(value);
  const matches = ql && (label.toLowerCase().includes(ql) || JSON.stringify(value).toLowerCase().includes(ql));
  const labelTone = matches ? "bg-yellow-200/70 dark:bg-yellow-700/40" : "";

  if (!isObject && !isArray) {
    // Leaf
    const display = leafDisplay(value);
    return (
      <div className="flex items-start gap-1.5 group" style={{ paddingLeft: depth * 12 }}>
        {label !== "" && (
          <span className={`text-foreground ${labelTone}`}>{label}:</span>
        )}
        <span className="text-blue-700 dark:text-blue-300 break-all flex-1">{display}</span>
        <button
          type="button"
          onClick={() => {
            navigator.clipboard.writeText(typeof value === "string" ? value : JSON.stringify(value));
            toast.success("Copied");
          }}
          className="opacity-0 group-hover:opacity-100 p-0.5 hover:bg-muted rounded"
          aria-label="Copy"
        >
          <Copy className="w-3 h-3" />
        </button>
      </div>
    );
  }

  const entries = isArray
    ? (value as unknown[]).map((v, i) => [String(i), v] as const)
    : Object.entries(value as Record<string, unknown>);

  return (
    <div style={{ paddingLeft: root ? 0 : depth * 12 }}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="inline-flex items-center gap-1 hover:bg-muted/40 rounded px-0.5"
      >
        {open ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
        {label !== "" && <span className={labelTone}>{label}</span>}
        <span className="text-muted-foreground">
          {label !== "" ? ": " : ""}
          {isArray ? `[${entries.length}]` : `{${entries.length}}`}
        </span>
      </button>
      {open && (
        <div className="ml-3 border-l border-border/60 pl-2">
          {entries.map(([k, v]) => (
            <Node
              key={String(k)}
              label={String(k)}
              value={v}
              depth={depth + 1}
              maxOpenDepth={maxOpenDepth}
              search={search}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function leafDisplay(v: unknown): string {
  if (v === null) return "null";
  if (v === undefined) return "undefined";
  if (typeof v === "string") return JSON.stringify(v);
  return String(v);
}
