import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Check, ChevronDown, History } from "lucide-react";

interface Revision {
  id: string;
  version: number;
  status: string;
  updatedAt: number;
  compileAttempts?: number;
}

interface Props {
  revisions: Revision[];
  currentId: string;
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: "text-green-700",
  DRAFT: "text-amber-700",
  DEPRECATED: "text-gray-500",
  FAILED: "text-red-700",
};

export function RevisionPicker({ revisions, currentId }: Props) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const current = revisions.find((r) => r.id === currentId);

  if (revisions.length <= 1) {
    return (
      <div className="inline-flex items-center gap-1.5 rounded border bg-gray-50 px-2.5 py-1 text-xs text-gray-600">
        <History className="h-3 w-3" />
        Only one revision
      </div>
    );
  }

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        className="inline-flex items-center gap-2 rounded border bg-white px-3 py-1.5 text-xs shadow-sm hover:bg-gray-50"
      >
        <History className="h-3.5 w-3.5 text-gray-400" />
        <span className="text-gray-500">Revision</span>
        <span className="font-semibold text-gray-900">v{current?.version ?? "?"}</span>
        {current && (
          <span className={`text-[10px] uppercase font-semibold ${STATUS_COLOR[current.status] ?? ""}`}>
            {current.status}
          </span>
        )}
        <ChevronDown className="h-3.5 w-3.5 text-gray-400" />
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-1 z-40 w-72 rounded border bg-white shadow-lg">
          <div className="max-h-80 overflow-y-auto py-1">
            {revisions.map((rev) => {
              const isCurrent = rev.id === currentId;
              return (
                <button
                  key={rev.id}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    if (!isCurrent) navigate(`/rule-domains/${encodeURIComponent(rev.id)}`);
                    setOpen(false);
                  }}
                  className={`flex w-full items-start justify-between gap-2 px-3 py-2 text-left text-xs hover:bg-gray-50 ${
                    isCurrent ? "bg-gray-50" : ""
                  }`}
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-gray-900">v{rev.version}</span>
                      <span className={`text-[10px] uppercase font-semibold ${STATUS_COLOR[rev.status] ?? ""}`}>
                        {rev.status}
                      </span>
                    </div>
                    <div className="text-[10px] text-gray-500 mt-0.5">
                      Updated {new Date(rev.updatedAt).toLocaleString()}
                    </div>
                  </div>
                  {isCurrent && <Check className="h-3.5 w-3.5 text-[#E31837] mt-0.5 shrink-0" />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
