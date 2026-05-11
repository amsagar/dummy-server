// Manage scoped API keys that let external callers (curl, CI, scripts)
// trigger workflow runs without leaking a JWT. Each key is restricted to
// a fixed allowlist of workflows chosen at creation time.

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Copy, Check, Key, Loader2, Pencil, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
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
import { Input } from "@/components/ui/input";
import {
  workflowApi,
  type ApiKeyCreateResponse,
  type ApiKeySummary,
} from "@/services/workflowApi";
import type { ProcessDef } from "@/types/workflow";

// `null` = closed. `{ mode: "create" }` = new key. `{ mode: "edit", key }` =
// editing an existing key's name + scope (secret stays put).
type EditorState =
  | { mode: "create" }
  | { mode: "edit"; key: ApiKeySummary };

/**
 * Page size for the per-section pagination. List filtering happens
 * client-side over the full per-owner list; if your usage outgrows this
 * (thousands of keys per owner) we'd move to server-side filter+page.
 */
const PAGE_SIZE = 10;

export default function ApiKeysPage() {
  const qc = useQueryClient();
  const [editor, setEditor] = useState<EditorState | null>(null);
  // The plaintext-key reveal dialog is shared by create AND regenerate —
  // both need to show a "store this now, you won't see it again" prompt.
  const [issued, setIssued] = useState<ApiKeyCreateResponse | null>(null);
  const [search, setSearch] = useState("");
  // Each section paginates independently so navigating Active doesn't
  // jolt your spot in Revoked and vice versa.
  const [activePage, setActivePage] = useState(1);
  const [revokedPage, setRevokedPage] = useState(1);

  const keys = useQuery<ApiKeySummary[]>({
    queryKey: ["workflow-api-keys"],
    queryFn: () => workflowApi.apiKeys.list(),
  });

  const workflows = useQuery<ProcessDef[]>({
    queryKey: ["workflows"],
    queryFn: () => workflowApi.processes.list(),
  });

  const workflowsById = useMemo(() => {
    const m = new Map<string, ProcessDef>();
    for (const wf of workflows.data ?? []) {
      if (wf.id) m.set(wf.id, wf);
    }
    return m;
  }, [workflows.data]);

  const revoke = useMutation({
    mutationFn: (id: string) => workflowApi.apiKeys.revoke(id),
    onSuccess: () => {
      toast.success("API key revoked");
      qc.invalidateQueries({ queryKey: ["workflow-api-keys"] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to revoke"),
  });

  const regenerate = useMutation({
    mutationFn: (id: string) => workflowApi.apiKeys.regenerate(id),
    onSuccess: (r) => {
      toast.success("API key regenerated");
      qc.invalidateQueries({ queryKey: ["workflow-api-keys"] });
      setIssued(r);
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to regenerate"),
  });

  const purge = useMutation({
    mutationFn: (id: string) => workflowApi.apiKeys.purge(id),
    onSuccess: () => {
      toast.success("API key permanently deleted");
      qc.invalidateQueries({ queryKey: ["workflow-api-keys"] });
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to delete"),
  });

  // Filter by search term first, then split by revocation state. Search
  // matches name, key prefix, or any workflow name in the key's scope —
  // case-insensitive substring across all three.
  const { active, revoked } = useMemo(() => {
    const all = keys.data ?? [];
    const q = search.trim().toLowerCase();
    const filtered = q
      ? all.filter((k) => {
          if (k.name.toLowerCase().includes(q)) return true;
          if (k.keyPrefix.toLowerCase().includes(q)) return true;
          return k.processDefIds.some((id) => {
            const name = workflowsById.get(id)?.name ?? id;
            return name.toLowerCase().includes(q);
          });
        })
      : all;
    return {
      active: filtered.filter((k) => !k.revokedAt),
      revoked: filtered.filter((k) => k.revokedAt),
    };
  }, [keys.data, search, workflowsById]);

  // Reset both pages whenever search changes — staying on page 5 of a
  // filtered set that's only 1 page long is confusing.
  useEffect(() => {
    setActivePage(1);
    setRevokedPage(1);
  }, [search]);

  // Clamp pages when the underlying list shrinks (revoke / purge / delete
  // a workflow narrowing scope can trim the last page out of existence).
  const activeTotalPages = Math.max(1, Math.ceil(active.length / PAGE_SIZE));
  const revokedTotalPages = Math.max(1, Math.ceil(revoked.length / PAGE_SIZE));
  useEffect(() => {
    if (activePage > activeTotalPages) setActivePage(activeTotalPages);
  }, [activePage, activeTotalPages]);
  useEffect(() => {
    if (revokedPage > revokedTotalPages) setRevokedPage(revokedTotalPages);
  }, [revokedPage, revokedTotalPages]);

  const activeSlice = active.slice((activePage - 1) * PAGE_SIZE, activePage * PAGE_SIZE);
  const revokedSlice = revoked.slice((revokedPage - 1) * PAGE_SIZE, revokedPage * PAGE_SIZE);

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">API Keys</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Scoped credentials for triggering workflow runs from outside the app.
            Each key only works for the workflows you choose.
          </p>
        </div>
        <Button onClick={() => setEditor({ mode: "create" })}>
          <Plus className="w-4 h-4 mr-1" /> New API key
        </Button>
      </div>

      {/* Search input — filters across name, prefix, and scope workflow
          names. Only render when there's something to search; on an empty
          page the input would just be visual noise. */}
      {(keys.data ?? []).length > 0 && (
        <div className="relative mb-4">
          <Search className="w-4 h-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground pointer-events-none" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by name, prefix, or workflow…"
            className="pl-8"
          />
        </div>
      )}

      {keys.isLoading ? (
        <div className="text-muted-foreground">Loading…</div>
      ) : keys.error ? (
        <div className="text-red-600">Error: {(keys.error as Error).message}</div>
      ) : (keys.data ?? []).length === 0 ? (
        <div className="border rounded-md p-8 text-center text-muted-foreground">
          <Key className="w-8 h-8 mx-auto mb-2 opacity-50" />
          <div className="font-medium text-foreground">No API keys yet</div>
          <div className="text-sm mt-1">
            Create one to start a workflow from a script or terminal.
          </div>
        </div>
      ) : active.length === 0 && revoked.length === 0 ? (
        // Non-empty list but the search filtered everything out.
        <div className="border border-dashed rounded-md p-8 text-center text-sm text-muted-foreground">
          No keys match "{search}".
        </div>
      ) : (
        <div className="flex flex-col gap-6">
          {/* Active section — header is suppressed when there's nothing in
              the revoked section so single-state pages stay uncluttered. */}
          {active.length > 0 && (
            <section>
              {revoked.length > 0 && (
                <h2 className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-2">
                  Active ({active.length})
                </h2>
              )}
              <div className="border rounded-md divide-y">
                {activeSlice.map((k) => (
                  <ApiKeyRow
                    key={k.id}
                    k={k}
                    workflowsById={workflowsById}
                    onEdit={() => setEditor({ mode: "edit", key: k })}
                    onRegenerate={() => {
                      if (
                        window.confirm(
                          `Regenerate "${k.name}"? The current key will stop working immediately.`,
                        )
                      ) {
                        regenerate.mutate(k.id);
                      }
                    }}
                    onRevoke={() => {
                      if (window.confirm(`Revoke "${k.name}"? This cannot be undone.`)) {
                        revoke.mutate(k.id);
                      }
                    }}
                    regeneratePending={regenerate.isPending}
                  />
                ))}
              </div>
              <Pager
                page={activePage}
                totalPages={activeTotalPages}
                totalItems={active.length}
                pageSize={PAGE_SIZE}
                onChange={setActivePage}
              />
            </section>
          )}

          {revoked.length > 0 && (
            <section>
              <h2 className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-2">
                Revoked ({revoked.length})
              </h2>
              <div className="border rounded-md divide-y bg-muted/30">
                {revokedSlice.map((k) => (
                  <ApiKeyRow
                    key={k.id}
                    k={k}
                    workflowsById={workflowsById}
                    onPurge={() => {
                      if (
                        window.confirm(
                          `Permanently delete "${k.name}"? This removes the record entirely and cannot be undone.`,
                        )
                      ) {
                        purge.mutate(k.id);
                      }
                    }}
                    purgePending={purge.isPending}
                  />
                ))}
              </div>
              <Pager
                page={revokedPage}
                totalPages={revokedTotalPages}
                totalItems={revoked.length}
                pageSize={PAGE_SIZE}
                onChange={setRevokedPage}
              />
            </section>
          )}
        </div>
      )}

      <ApiKeyEditorDialog
        state={editor}
        onClose={() => setEditor(null)}
        workflows={workflows.data ?? []}
        onCreated={(r) => {
          qc.invalidateQueries({ queryKey: ["workflow-api-keys"] });
          setIssued(r);
        }}
        onUpdated={() => {
          qc.invalidateQueries({ queryKey: ["workflow-api-keys"] });
        }}
      />

      <KeyRevealDialog
        issued={issued}
        onClose={() => setIssued(null)}
      />
    </div>
  );
}

function formatTs(ms: number): string {
  return new Date(ms).toLocaleString();
}

interface PagerProps {
  page: number;
  totalPages: number;
  totalItems: number;
  pageSize: number;
  onChange: (page: number) => void;
}

/**
 * Per-section pagination footer. Hides itself when there's only one page so
 * tiny lists stay uncluttered. Shows a "X–Y of N" range readout so the user
 * always knows what slice they're looking at.
 */
function Pager({ page, totalPages, totalItems, pageSize, onChange }: PagerProps) {
  if (totalPages <= 1) return null;
  const start = (page - 1) * pageSize + 1;
  const end = Math.min(page * pageSize, totalItems);
  return (
    <div className="flex items-center justify-between mt-3 text-xs text-muted-foreground">
      <span>
        {start}–{end} of {totalItems}
      </span>
      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="xs"
          onClick={() => onChange(page - 1)}
          disabled={page <= 1}
          aria-label="Previous page"
        >
          <ChevronLeft className="w-3.5 h-3.5" />
        </Button>
        <span className="px-2">
          Page {page} of {totalPages}
        </span>
        <Button
          variant="outline"
          size="xs"
          onClick={() => onChange(page + 1)}
          disabled={page >= totalPages}
          aria-label="Next page"
        >
          <ChevronRight className="w-3.5 h-3.5" />
        </Button>
      </div>
    </div>
  );
}

interface ApiKeyRowProps {
  k: ApiKeySummary;
  workflowsById: Map<string, ProcessDef>;
  // Active-state actions
  onEdit?: () => void;
  onRegenerate?: () => void;
  onRevoke?: () => void;
  regeneratePending?: boolean;
  // Revoked-state action
  onPurge?: () => void;
  purgePending?: boolean;
}

/**
 * One row in the API-keys list. Body is shared across active and revoked
 * keys — only the action buttons differ. Pass the action callbacks that
 * apply to the row's state; the others stay undefined and render nothing.
 */
function ApiKeyRow({
  k,
  workflowsById,
  onEdit,
  onRegenerate,
  onRevoke,
  regeneratePending,
  onPurge,
  purgePending,
}: ApiKeyRowProps) {
  return (
    <div className="p-4 flex items-start justify-between gap-4">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <div className="font-medium truncate">{k.name}</div>
          {k.revokedAt ? (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-destructive/10 text-destructive">
              revoked
            </span>
          ) : (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-700 dark:text-emerald-400">
              active
            </span>
          )}
        </div>
        <div className="flex items-center gap-1.5 mt-1">
          <span className="font-mono text-xs text-muted-foreground">{k.keyPrefix}…</span>
          <button
            type="button"
            onClick={() => {
              navigator.clipboard.writeText(k.keyPrefix);
              toast.success("Prefix copied (the full key was shown only at creation)");
            }}
            className="inline-flex items-center justify-center h-5 w-5 rounded text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            title="Copy prefix. The full key was shown only at creation — use Regenerate to mint a new one."
            aria-label="Copy key prefix"
          >
            <Copy className="w-3 h-3" />
          </button>
        </div>
        <div className="text-xs text-muted-foreground mt-2">
          Scope:{" "}
          {k.processDefIds.length === 0 ? (
            <span className="italic">no workflows</span>
          ) : (
            k.processDefIds.map((id, i) => (
              <span key={id}>
                {i > 0 && ", "}
                <span className="text-foreground">
                  {workflowsById.get(id)?.name ?? id}
                </span>
              </span>
            ))
          )}
        </div>
        <div className="text-xs text-muted-foreground mt-1">
          Created {formatTs(k.createdAt)}
          {k.lastUsedAt ? ` · last used ${formatTs(k.lastUsedAt)}` : " · never used"}
          {k.revokedAt ? ` · revoked ${formatTs(k.revokedAt)}` : ""}
        </div>
      </div>

      {/* Action buttons: shape depends on whether this is an active or revoked row. */}
      {!k.revokedAt && onEdit && onRegenerate && onRevoke && (
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={onEdit}>
            <Pencil className="w-3.5 h-3.5 mr-1" /> Edit
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={onRegenerate}
            disabled={regeneratePending}
          >
            <RefreshCw className="w-3.5 h-3.5 mr-1" /> Regenerate
          </Button>
          <Button variant="outline" size="sm" onClick={onRevoke}>
            <Trash2 className="w-3.5 h-3.5 mr-1" /> Revoke
          </Button>
        </div>
      )}
      {k.revokedAt && onPurge && (
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={onPurge}
            disabled={purgePending}
          >
            <Trash2 className="w-3.5 h-3.5 mr-1" /> Delete
          </Button>
        </div>
      )}
    </div>
  );
}

interface EditorProps {
  state: EditorState | null;
  onClose: () => void;
  workflows: ProcessDef[];
  onCreated: (r: ApiKeyCreateResponse) => void;
  onUpdated: () => void;
}

/**
 * Dual-mode dialog for creating a new API key and editing the name + scope
 * of an existing one. The form is identical between the two modes — only
 * the title, submit label, and mutation differ. The secret material is
 * never editable from this dialog (use Regenerate for that).
 */
function ApiKeyEditorDialog({ state, onClose, workflows, onCreated, onUpdated }: EditorProps) {
  const isEdit = state?.mode === "edit";
  const initialName = state?.mode === "edit" ? state.key.name : "";
  const initialSelectedIds = state?.mode === "edit" ? state.key.processDefIds : [];

  const [name, setName] = useState(initialName);
  const [selectedIds, setSelectedIds] = useState<string[]>(initialSelectedIds);

  // Re-seed inputs each time the dialog opens for a different row (or for
  // create after an edit, etc.). Keyed off the mode + edited-key id.
  const editKeyId = state?.mode === "edit" ? state.key.id : null;
  useEffect(() => {
    setName(initialName);
    setSelectedIds(initialSelectedIds);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state?.mode, editKeyId]);

  const create = useMutation({
    mutationFn: () => workflowApi.apiKeys.create(name.trim(), selectedIds),
    onSuccess: (r) => {
      onClose();
      onCreated(r);
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to create key"),
  });

  const update = useMutation({
    mutationFn: () => {
      if (state?.mode !== "edit") {
        return Promise.reject(new Error("no key to update"));
      }
      return workflowApi.apiKeys.update(state.key.id, name.trim(), selectedIds);
    },
    onSuccess: () => {
      toast.success("API key updated");
      onClose();
      onUpdated();
    },
    onError: (e: Error) => toast.error(e.message ?? "Failed to update key"),
  });

  const toggle = (id: string) =>
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );

  const pending = create.isPending || update.isPending;
  const canSubmit = name.trim().length > 0 && selectedIds.length > 0 && !pending;

  return (
    <Dialog open={state !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{isEdit ? "Edit API key" : "Create API key"}</DialogTitle>
          <DialogDescription>
            {isEdit
              ? "Change the name or which workflows this key is allowed to trigger. The secret itself isn't touched — use Regenerate to rotate it."
              : "The key will only be able to start runs of the workflows you select. You'll see the key once — copy it now."}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div>
            <label className="text-xs font-medium text-muted-foreground">Name</label>
            <Input
              className="mt-1"
              placeholder="e.g. CI deploy bot, dashboard-cron"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">
              Allowed workflows
            </label>
            <div className="mt-1 max-h-56 overflow-auto border rounded-md p-2 flex flex-col gap-1">
              {workflows.length === 0 ? (
                <div className="text-sm text-muted-foreground p-2">
                  No workflows exist yet.
                </div>
              ) : (
                workflows.map((wf) =>
                  wf.id ? (
                    <label
                      key={wf.id}
                      className="flex items-center gap-2 px-2 py-1 rounded hover:bg-muted/40 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(wf.id)}
                        onChange={() => toggle(wf.id as string)}
                        className="h-3.5 w-3.5 accent-primary"
                      />
                      <span className="text-sm truncate">{wf.name}</span>
                      <span className="text-xs text-muted-foreground ml-auto">
                        v{wf.version}
                      </span>
                    </label>
                  ) : null,
                )
              )}
            </div>
            <div className="text-xs text-muted-foreground mt-1">
              {selectedIds.length} selected
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={() => (isEdit ? update.mutate() : create.mutate())}
            disabled={!canSubmit}
          >
            {pending ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : isEdit ? (
              "Save"
            ) : (
              "Create"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface KeyRevealProps {
  issued: ApiKeyCreateResponse | null;
  onClose: () => void;
}

/**
 * One-shot key reveal — shown after both create AND regenerate. The full
 * plaintext key only lives in this dialog's render scope; once closed we
 * never have access to it again.
 */
function KeyRevealDialog({ issued, onClose }: KeyRevealProps) {
  const [copied, setCopied] = useState(false);
  return (
    <Dialog
      open={issued !== null}
      onOpenChange={(o) => {
        if (!o) {
          setCopied(false);
          onClose();
        }
      }}
    >
      <DialogContent className="max-w-lg sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Copy your API key</DialogTitle>
          <DialogDescription>
            This is the only time the full key will be shown. Store it in a secret manager or
            paste it where you need it now.
          </DialogDescription>
        </DialogHeader>

        {issued && (
          <div>
            <label className="text-xs font-medium text-muted-foreground">
              Key for "{issued.name}"
            </label>
            <div className="mt-1 flex items-stretch gap-2">
              <pre className="flex-1 overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs whitespace-pre-wrap break-all">
                {issued.key}
              </pre>
              <Button
                variant="outline"
                onClick={() => {
                  navigator.clipboard.writeText(issued.key);
                  toast.success("Key copied");
                  setCopied(true);
                  setTimeout(() => setCopied(false), 1200);
                }}
              >
                {copied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
              </Button>
            </div>
          </div>
        )}

        <DialogFooter>
          <Button
            onClick={() => {
              setCopied(false);
              onClose();
            }}
          >
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
