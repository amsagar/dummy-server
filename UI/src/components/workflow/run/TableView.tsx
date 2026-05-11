// Flatten an arbitrary JSON value into dotted-path key / leaf-value rows.
//
//   { user: { id: 7, tags: ["a", "b"] } }
//   →
//   user.id          7
//   user.tags[0]     "a"
//   user.tags[1]     "b"
//
// Rows are filterable by the search query (matches either path or value).

import { Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";

interface TableViewProps {
  value: unknown;
  search?: string;
}

interface Row {
  path: string;
  value: unknown;
}

function flatten(v: unknown, prefix: string, out: Row[]): void {
  if (v === null || v === undefined) {
    out.push({ path: prefix || "(root)", value: v });
    return;
  }
  if (Array.isArray(v)) {
    if (v.length === 0) {
      out.push({ path: prefix || "(root)", value: [] });
      return;
    }
    v.forEach((child, i) => flatten(child, `${prefix}[${i}]`, out));
    return;
  }
  if (typeof v === "object") {
    const entries = Object.entries(v as Record<string, unknown>);
    if (entries.length === 0) {
      out.push({ path: prefix || "(root)", value: {} });
      return;
    }
    for (const [k, child] of entries) {
      flatten(child, prefix ? `${prefix}.${k}` : k, out);
    }
    return;
  }
  out.push({ path: prefix || "(root)", value: v });
}

function formatLeaf(v: unknown): string {
  if (v === null) return "null";
  if (v === undefined) return "undefined";
  if (typeof v === "string") return v;
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

export function TableView({ value, search }: TableViewProps) {
  const rows: Row[] = [];
  flatten(value, "", rows);

  const ql = (search ?? "").trim().toLowerCase();
  const filtered = ql
    ? rows.filter((r) => r.path.toLowerCase().includes(ql) || formatLeaf(r.value).toLowerCase().includes(ql))
    : rows;

  if (filtered.length === 0) {
    return <p className="text-xs text-muted-foreground p-3">No rows {ql ? "match the filter" : ""}.</p>;
  }

  return (
    <table className="w-full text-xs font-mono">
      <thead className="text-left text-muted-foreground">
        <tr className="border-b">
          <th className="py-1 pl-2 pr-3 w-1/3">Path</th>
          <th className="py-1 pr-3">Value</th>
          <th className="py-1 pr-2 w-8"></th>
        </tr>
      </thead>
      <tbody>
        {filtered.map((r, i) => {
          const display = formatLeaf(r.value);
          return (
            <tr key={i} className="border-b hover:bg-muted/40 group">
              <td className="py-1 pl-2 pr-3 text-muted-foreground truncate max-w-0">{r.path}</td>
              <td className="py-1 pr-3 break-all">{display}</td>
              <td className="py-1 pr-2 text-right">
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-6 w-6 p-0 opacity-0 group-hover:opacity-100"
                  onClick={() => {
                    navigator.clipboard.writeText(display);
                    toast.success("Copied");
                  }}
                  aria-label="Copy value"
                >
                  <Copy className="w-3 h-3" />
                </Button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
