import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Archive, ArchiveRestore, Pencil, Search, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";

type SessionRow = {
  id: string;
  title?: string;
  status?: string;
  updatedAt?: number;
  archivedAt?: number | null;
};

type Props = {
  sessions: SessionRow[];
  activeSessionId: string | null;
  onSelect: (sessionId: string) => void;
  onNewSession: () => void;
  onRenameSession: (sessionId: string, title: string) => void;
  onArchiveSession: (sessionId: string) => void;
  onRestoreSession: (sessionId: string) => void;
  onDeleteSession: (sessionId: string) => void;
};

export default function ToolChainSessionList({
  sessions,
  activeSessionId,
  onSelect,
  onNewSession,
  onRenameSession,
  onArchiveSession,
  onRestoreSession,
  onDeleteSession,
}: Props) {
  const [search, setSearch] = useState("");
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [showArchived, setShowArchived] = useState(false);

  const filtered = useMemo(
    () =>
      sessions.filter((s) => {
        const query = search.trim().toLowerCase();
        if (!query) return true;
        return (s.title || "").toLowerCase().includes(query);
      }),
    [sessions, search]
  );
  const active = filtered.filter((s) => !s.archivedAt);
  const archived = filtered.filter((s) => !!s.archivedAt);

  return (
    <div className="space-y-2 rounded-lg border border-slate-200 bg-white p-2.5 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold text-[#123262]">Sessions</h3>
        <Button size="sm" variant="outline" className="h-7 px-2.5 text-xs" onClick={onNewSession}>
          New
        </Button>
      </div>

      <div className="relative">
        <Search size={12} className="pointer-events-none absolute left-2 top-2.5 text-slate-400" />
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search sessions..."
          className="h-8 w-full rounded-md border border-slate-200 bg-white pl-7 pr-2 text-xs text-slate-700 outline-none"
        />
      </div>

      <div className="max-h-[240px] space-y-1.5 overflow-auto pr-1">
        {active.map((session) => (
          <button
            key={session.id}
            onClick={() => onSelect(session.id)}
            className={cn(
              "w-full rounded-md border px-2.5 py-1.5 text-left transition-colors",
              activeSessionId === session.id
                ? "border-[#BFDBFE] bg-[#EFF6FF]"
                : "border-slate-200 bg-white hover:bg-slate-50"
            )}
          >
            {renamingId === session.id ? (
              <div onClick={(e) => e.stopPropagation()} className="flex items-center gap-1">
                <input
                  value={renameValue}
                  onChange={(e) => setRenameValue(e.target.value)}
                  className="h-7 flex-1 rounded border border-slate-200 px-2 text-xs"
                  autoFocus
                />
                <Button
                  size="sm"
                  variant="outline"
                  className="h-7 px-2 text-[11px]"
                  onClick={() => {
                    onRenameSession(session.id, renameValue.trim());
                    setRenamingId(null);
                    setRenameValue("");
                  }}
                >
                  Save
                </Button>
              </div>
            ) : (
              <>
                <p className="truncate text-xs font-medium text-slate-800">{session.title || "Untitled session"}</p>
                <div className="mt-1 flex items-center justify-between gap-2 text-[11px] text-slate-500">
                  <span>{session.status || "draft"}</span>
                  <span className="truncate">{formatTs(session.updatedAt)}</span>
                </div>
                <div className="mt-1 flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                  <button
                    className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
                    onClick={() => {
                      setRenamingId(session.id);
                      setRenameValue(session.title || "");
                    }}
                  >
                    <Pencil size={11} />
                  </button>
                  <button
                    className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
                    onClick={() => onArchiveSession(session.id)}
                  >
                    <Archive size={11} />
                  </button>
                  <button
                    className="rounded p-1 text-slate-400 hover:bg-red-50 hover:text-red-600"
                    onClick={() => onDeleteSession(session.id)}
                  >
                    <Trash2 size={11} />
                  </button>
                </div>
              </>
            )}
          </button>
        ))}

        {archived.length > 0 ? (
          <div className="pt-1">
            <button
              className="text-[11px] font-medium text-slate-500"
              onClick={() => setShowArchived((v) => !v)}
            >
              {showArchived ? "Hide archived" : `Show archived (${archived.length})`}
            </button>
            {showArchived
              ? archived.map((session) => (
                  <div
                    key={session.id}
                    className="mt-1 flex items-center justify-between rounded border border-slate-200 px-2 py-1"
                  >
                    <p className="truncate text-xs text-slate-600">{session.title || "Untitled session"}</p>
                    <button
                      className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
                      onClick={() => onRestoreSession(session.id)}
                    >
                      <ArchiveRestore size={11} />
                    </button>
                  </div>
                ))
              : null}
          </div>
        ) : null}

        {active.length === 0 && archived.length === 0 ? (
          <p className="rounded-md border border-dashed border-slate-200 py-4 text-center text-xs text-slate-500">
            No sessions yet
          </p>
        ) : null}
      </div>
    </div>
  );
}

function formatTs(ts?: number | null): string {
  if (!ts) return "just now";
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return "just now";
  return date.toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
