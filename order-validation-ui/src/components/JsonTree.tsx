import { memo, useMemo, useState } from "react";
import { ChevronRight, Search } from "lucide-react";
import { cn } from "@/lib/utils";

type Json = unknown;

interface NodeProps {
  value: Json;
  keyName?: string;
  depth: number;
  needle: string;
  openUntilDepth: number;
  isLast?: boolean;
}

function isObj(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function previewSummary(v: Json): string {
  if (Array.isArray(v)) return `Array(${v.length})`;
  if (isObj(v)) {
    const n = Object.keys(v).length;
    return `Object(${n})`;
  }
  return "";
}

function valueClass(v: Json): string {
  if (v === null) return "text-muted-foreground italic";
  if (typeof v === "string") return "text-pods-blue";
  if (typeof v === "number") return "text-pods-orange";
  if (typeof v === "boolean") return "text-status-fail";
  return "text-foreground";
}

function renderPrimitive(v: Json) {
  if (v === null) return "null";
  if (typeof v === "string") return JSON.stringify(v);
  return String(v);
}

function containsMatch(v: Json, needle: string): boolean {
  if (!needle) return true;
  if (v === null || v === undefined) return needle === "null";
  if (typeof v === "string") return v.toLowerCase().includes(needle);
  if (typeof v === "number" || typeof v === "boolean") {
    return String(v).toLowerCase().includes(needle);
  }
  if (Array.isArray(v)) return v.some((c) => containsMatch(c, needle));
  if (isObj(v)) {
    for (const [k, c] of Object.entries(v)) {
      if (k.toLowerCase().includes(needle)) return true;
      if (containsMatch(c, needle)) return true;
    }
  }
  return false;
}

const TreeNode = memo(function TreeNode({
  value,
  keyName,
  depth,
  needle,
  openUntilDepth,
  isLast = true,
}: NodeProps) {
  const isContainer = Array.isArray(value) || isObj(value);
  // When searching, force-open any branch that contains a match.
  const forceOpen = needle.length > 0 && isContainer && containsMatch(value, needle);
  const [open, setOpen] = useState<boolean>(depth < openUntilDepth);
  const effectiveOpen = forceOpen || open;

  const keyMatches = keyName != null && needle && keyName.toLowerCase().includes(needle);

  if (!isContainer) {
    return (
      <div className="flex items-baseline gap-1.5" style={{ paddingLeft: depth * 14 }}>
        {keyName != null && (
          <span
            className={cn(
              "font-medium text-foreground/80",
              keyMatches && "bg-yellow-200 dark:bg-yellow-500/30 rounded px-0.5",
            )}
          >
            {JSON.stringify(keyName)}
          </span>
        )}
        {keyName != null && <span className="text-muted-foreground">:</span>}
        <span
          className={cn(
            valueClass(value),
            "break-all whitespace-pre-wrap",
            !keyMatches &&
              needle &&
              typeof value !== "object" &&
              String(value).toLowerCase().includes(needle) &&
              "bg-yellow-200 dark:bg-yellow-500/30 rounded px-0.5",
          )}
        >
          {renderPrimitive(value)}
        </span>
        {!isLast && <span className="text-muted-foreground">,</span>}
      </div>
    );
  }

  const entries = Array.isArray(value)
    ? value.map((v, i) => [String(i), v] as const)
    : Object.entries(value as Record<string, unknown>);
  const openBracket = Array.isArray(value) ? "[" : "{";
  const closeBracket = Array.isArray(value) ? "]" : "}";

  return (
    <div>
      <div
        className="flex items-baseline gap-1 cursor-pointer hover:bg-accent/30 rounded-sm select-none"
        style={{ paddingLeft: depth * 14 }}
        onClick={() => setOpen((o) => !o)}
      >
        <ChevronRight
          className={cn(
            "size-3 text-muted-foreground shrink-0 transition-transform mt-1",
            effectiveOpen && "rotate-90",
          )}
        />
        {keyName != null && (
          <>
            <span
              className={cn(
                "font-medium text-foreground/80",
                keyMatches && "bg-yellow-200 dark:bg-yellow-500/30 rounded px-0.5",
              )}
            >
              {JSON.stringify(keyName)}
            </span>
            <span className="text-muted-foreground">:</span>
          </>
        )}
        <span className="text-muted-foreground">{openBracket}</span>
        {!effectiveOpen && (
          <>
            <span className="text-xs text-muted-foreground italic">
              {previewSummary(value)}
            </span>
            <span className="text-muted-foreground">{closeBracket}</span>
            {!isLast && <span className="text-muted-foreground">,</span>}
          </>
        )}
      </div>
      {effectiveOpen && (
        <>
          {entries.map(([k, v], i) => (
            <TreeNode
              key={k}
              keyName={Array.isArray(value) ? undefined : k}
              value={v}
              depth={depth + 1}
              needle={needle}
              openUntilDepth={openUntilDepth}
              isLast={i === entries.length - 1}
            />
          ))}
          <div
            className="text-muted-foreground"
            style={{ paddingLeft: depth * 14 + 12 }}
          >
            {closeBracket}
            {!isLast && ","}
          </div>
        </>
      )}
    </div>
  );
});

interface Props {
  value: unknown;
  defaultOpenDepth?: number;
  emptyText?: string;
}

export function JsonTree({ value, defaultOpenDepth = 1, emptyText = "—" }: Props) {
  const [query, setQuery] = useState("");
  const needle = query.trim().toLowerCase();

  const matchCount = useMemo(() => {
    if (!needle) return 0;
    let count = 0;
    const walk = (v: Json) => {
      if (v === null || v === undefined) return;
      if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") {
        if (String(v).toLowerCase().includes(needle)) count++;
        return;
      }
      if (Array.isArray(v)) {
        v.forEach(walk);
        return;
      }
      if (isObj(v)) {
        for (const [k, c] of Object.entries(v)) {
          if (k.toLowerCase().includes(needle)) count++;
          walk(c);
        }
      }
    };
    walk(value);
    return count;
  }, [value, needle]);

  if (value === null || value === undefined) {
    return <div className="text-sm text-muted-foreground">{emptyText}</div>;
  }

  return (
    <div className="space-y-2">
      <div className="relative">
        <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search keys or values…"
          className="h-7 w-full rounded border border-border bg-muted pl-8 pr-16 text-xs outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        />
        {needle && (
          <span className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[10px] text-muted-foreground">
            {matchCount} match{matchCount === 1 ? "" : "es"}
          </span>
        )}
      </div>
      <div className="bg-muted border border-border rounded-md p-3 overflow-auto max-h-[50vh] text-xs font-mono leading-5">
        <TreeNode
          value={value}
          depth={0}
          needle={needle}
          openUntilDepth={defaultOpenDepth}
        />
      </div>
    </div>
  );
}
