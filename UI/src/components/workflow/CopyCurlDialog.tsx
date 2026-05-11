import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Copy, Check, KeyRound } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { workflowApi, type ApiKeySummary } from "@/services/workflowApi";
import type { ProcessDef } from "@/types/workflow";

// Same base URL the in-app api client points at. Kept literal so the copied
// curl is self-contained and doesn't depend on the user's shell env.
const API_BASE = "http://localhost:8080/api/v1";

interface CopyCurlDialogProps {
  processDef: ProcessDef | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

// Pre-fill variables JSON with the workflow's required variables so the user
// has scaffolding to edit. Runtime-filled variables are omitted — they'd just
// be noise.
function buildInitialVariables(def: ProcessDef): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const v of def.variables ?? []) {
    if (!v.required) continue;
    out[v.name] = inferTypePlaceholder(v.javaClass ?? null);
  }
  return out;
}

function inferTypePlaceholder(javaClass: string | null): unknown {
  if (!javaClass) return "";
  if (javaClass.endsWith("Long") || javaClass.endsWith("Integer")) return 0;
  if (javaClass.endsWith("Boolean")) return false;
  if (javaClass.endsWith("Double") || javaClass.endsWith("Float")) return 0;
  if (javaClass.endsWith("Map")) return {};
  if (javaClass.endsWith("List")) return [];
  return "";
}

// sh-safe single-quoted body: any literal single quote becomes '\''.
function shellEscapeSingleQuoted(s: string): string {
  return s.replace(/'/g, "'\\''");
}

function buildCurl(args: {
  processDefId: string;
  initialVariables: unknown;
  asyncMode: boolean;
  apiKey: string;
}): string {
  const { processDefId, initialVariables, asyncMode, apiKey } = args;
  const url = `${API_BASE}/workflow/runs${asyncMode ? "?async=true" : ""}`;
  const body = shellEscapeSingleQuoted(
    JSON.stringify({ processDefId, initialVariables }),
  );
  return [
    `curl -X POST '${url}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -H 'X-API-Key: ${apiKey}' \\`,
    `  -d '${body}'`,
  ].join("\n");
}

export function CopyCurlDialog({ processDef, open, onOpenChange }: CopyCurlDialogProps) {
  // Only fetch keys when the dialog is open — avoids a request on every
  // workflow list render.
  const keysQuery = useQuery<ApiKeySummary[]>({
    queryKey: ["workflow-api-keys"],
    queryFn: () => workflowApi.apiKeys.list(),
    enabled: open,
  });

  const eligibleKeys = useMemo(() => {
    if (!processDef?.id) return [];
    return (keysQuery.data ?? []).filter(
      (k) => !k.revokedAt && k.processDefIds.includes(processDef.id as string),
    );
  }, [keysQuery.data, processDef?.id]);

  const initialJson = useMemo(
    () => (processDef ? JSON.stringify(buildInitialVariables(processDef), null, 2) : "{}"),
    [processDef?.id],
  );

  const [varsText, setVarsText] = useState(initialJson);
  const [asyncMode, setAsyncMode] = useState(true);
  const [selectedKeyId, setSelectedKeyId] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // Reset state when a different workflow opens the dialog.
  useEffect(() => {
    setVarsText(initialJson);
    setSelectedKeyId(null);
  }, [initialJson]);

  // Auto-select the first eligible key once they load, so the user doesn't
  // have to click a dropdown to see a working curl.
  useEffect(() => {
    if (selectedKeyId === null && eligibleKeys.length > 0) {
      setSelectedKeyId(eligibleKeys[0].id);
    }
  }, [eligibleKeys, selectedKeyId]);

  const parsed = useMemo(() => {
    try {
      return { ok: true as const, value: JSON.parse(varsText) };
    } catch (e) {
      return { ok: false as const, error: (e as Error).message };
    }
  }, [varsText]);

  // Pin the curl preview to the last-valid JSON so it doesn't flicker to
  // empty while the user is mid-edit.
  const [lastValid, setLastValid] = useState<unknown>({});
  useEffect(() => {
    if (parsed.ok) setLastValid(parsed.value);
  }, [parsed]);
  const effectiveVars = parsed.ok ? parsed.value : lastValid;

  // The plaintext key is shown only at creation time — we never have it in
  // the browser later. The curl uses a $WORKFLOW_API_KEY shell var that the
  // user sets before running. The selected-key dropdown is purely a UX
  // confirmation ("this curl will work with that key").
  const curl = useMemo(
    () =>
      processDef?.id
        ? buildCurl({
            processDefId: processDef.id,
            initialVariables: effectiveVars,
            asyncMode,
            apiKey: "$WORKFLOW_API_KEY",
          })
        : "",
    [processDef?.id, effectiveVars, asyncMode],
  );

  const handleCopy = () => {
    if (!parsed.ok) return;
    navigator.clipboard.writeText(curl);
    toast.success("curl copied");
    setCopied(true);
    setTimeout(() => setCopied(false), 1200);
  };

  const noEligibleKeys = !keysQuery.isLoading && eligibleKeys.length === 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Run via curl</DialogTitle>
          <DialogDescription>
            {processDef?.name
              ? `Trigger "${processDef.name}" from the command line, Postman, or a script using an API key.`
              : "Trigger this workflow from the command line."}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div>
            <label className="text-xs font-medium text-muted-foreground">
              API key
            </label>
            {keysQuery.isLoading ? (
              <div className="mt-1 text-xs text-muted-foreground">Loading keys…</div>
            ) : noEligibleKeys ? (
              <div className="mt-1 rounded-md border border-dashed p-3 text-sm flex items-center gap-2">
                <KeyRound className="w-4 h-4 text-muted-foreground" />
                <span className="text-muted-foreground flex-1">
                  No active API key has access to this workflow.
                </span>
                <Link
                  to="/api-keys"
                  className="text-xs underline underline-offset-2 hover:text-foreground"
                  onClick={() => onOpenChange(false)}
                >
                  Create one →
                </Link>
              </div>
            ) : (
              <select
                value={selectedKeyId ?? ""}
                onChange={(e) => setSelectedKeyId(e.target.value || null)}
                className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm outline-none focus:ring-2 focus:ring-ring/40"
              >
                {eligibleKeys.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.name} ({k.keyPrefix}…)
                  </option>
                ))}
              </select>
            )}
            <div className="mt-1 text-xs text-muted-foreground">
              The plaintext key was shown only at creation time. The curl below uses{" "}
              <code className="bg-muted px-1 py-0.5 rounded">$WORKFLOW_API_KEY</code> — set it in
              your shell before running:{" "}
              <code className="bg-muted px-1 py-0.5 rounded">export WORKFLOW_API_KEY=pak_…</code>
            </div>
          </div>

          <div>
            <label className="text-xs font-medium text-muted-foreground">
              Initial variables (JSON)
            </label>
            <textarea
              value={varsText}
              onChange={(e) => setVarsText(e.target.value)}
              spellCheck={false}
              className="mt-1 w-full min-h-24 rounded-md border bg-background px-2 py-1.5 font-mono text-xs outline-none focus:ring-2 focus:ring-ring/40"
            />
            {!parsed.ok && (
              <div className="mt-1 text-xs text-destructive">Invalid JSON: {parsed.error}</div>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-4 text-sm">
            <label className="inline-flex items-center gap-1.5 cursor-pointer">
              <input
                type="checkbox"
                checked={asyncMode}
                onChange={(e) => setAsyncMode(e.target.checked)}
                className="h-3.5 w-3.5 accent-primary"
              />
              <span>Async (returns immediately)</span>
            </label>
          </div>

          <div>
            <label className="text-xs font-medium text-muted-foreground">curl</label>
            <pre className="mt-1 max-h-64 overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs whitespace-pre-wrap break-all">
              {curl}
            </pre>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            onClick={handleCopy}
            disabled={!parsed.ok || !processDef?.id || noEligibleKeys}
          >
            {copied ? (
              <>
                <Check className="w-3.5 h-3.5" /> Copied
              </>
            ) : (
              <>
                <Copy className="w-3.5 h-3.5" /> Copy
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
