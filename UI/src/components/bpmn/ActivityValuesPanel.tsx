import { useMemo, useState } from "react";

export interface ActivityEvent {
  id: string;
  executionId: string;
  processInstanceId: string;
  activityId: string;
  activityName?: string | null;
  activityType?: string | null;
  delegateBean?: string | null;
  iterationIndex?: number | null;
  inputJson?: string | null;
  outputJson?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  startTs: number;
  endTs?: number | null;
  durationMs?: number | null;
  createdAt: number;
}

interface Props {
  activityId: string | null;
  events: ActivityEvent[];
  onClose: () => void;
}

/**
 * Side panel that shows the recorded input/output for a clicked BPMN
 * node. When the activity is multi-instance (one row per iteration), a
 * tab strip lets the user flip between iterations.
 *
 * <p>Renders nothing when {@code activityId} is null or no events match.
 * Sticks to the right side of the diagram container; the parent decides
 * the layout.
 */
export function ActivityValuesPanel({ activityId, events, onClose }: Props) {
  const matched = useMemo(
    () =>
      events
        .filter((e) => e.activityId === activityId)
        .sort((a, b) => {
          const ai = a.iterationIndex ?? -1;
          const bi = b.iterationIndex ?? -1;
          if (ai !== bi) return ai - bi;
          return a.startTs - b.startTs;
        }),
    [events, activityId],
  );

  const [tabIdx, setTabIdx] = useState(0);
  const event = matched[tabIdx] ?? null;

  if (!activityId) return null;
  if (matched.length === 0) {
    return (
      <div className="absolute right-3 top-14 z-30 w-80 rounded-lg border bg-white p-3 shadow-lg">
        <div className="flex items-center justify-between">
          <span className="text-xs font-mono">{activityId}</span>
          <button onClick={onClose} className="text-xs text-gray-500 hover:text-gray-800">
            ✕
          </button>
        </div>
        <p className="mt-2 text-xs text-gray-500">
          No recorded I/O for this activity in this execution.
        </p>
      </div>
    );
  }

  return (
    <div className="absolute right-3 top-14 z-30 w-[420px] max-h-[75vh] overflow-hidden rounded-lg border bg-white shadow-lg flex flex-col">
      <div className="flex items-start justify-between gap-2 border-b p-3">
        <div className="min-w-0">
          <div className="text-[11px] uppercase tracking-wide text-gray-500">
            {event?.delegateBean ?? "activity"}
          </div>
          <div className="font-mono text-sm truncate">{activityId}</div>
          {event?.activityName && (
            <div className="text-xs text-gray-600 truncate">{event.activityName}</div>
          )}
        </div>
        <button
          onClick={onClose}
          className="text-gray-500 hover:text-gray-800 p-1 -mt-1 -mr-1"
        >
          ✕
        </button>
      </div>

      {matched.length > 1 && (
        <div className="flex items-center gap-1 px-3 py-1.5 border-b bg-gray-50 overflow-x-auto">
          <span className="text-[11px] text-gray-500 mr-1">iter:</span>
          {matched.map((e, i) => (
            <button
              key={e.id}
              onClick={() => setTabIdx(i)}
              className={`text-[11px] px-1.5 py-0.5 rounded ${
                i === tabIdx
                  ? "bg-blue-100 text-blue-900 font-semibold"
                  : "text-gray-600 hover:bg-gray-100"
              }`}
            >
              {e.iterationIndex ?? i}
            </button>
          ))}
        </div>
      )}

      <div className="overflow-y-auto flex-1 p-3 space-y-2">
        {event && (
          <>
            <div className="flex items-center gap-2 text-[11px] text-gray-600">
              {typeof event.durationMs === "number" && (
                <span>{event.durationMs} ms</span>
              )}
              {event.errorCode && (
                <span className="rounded bg-red-100 text-red-800 px-1.5 py-0.5 font-semibold">
                  {event.errorCode}
                </span>
              )}
            </div>
            {event.errorMessage && (
              <pre className="text-[11px] text-red-700 bg-red-50 border border-red-200 rounded p-2 whitespace-pre-wrap break-words">
                {event.errorMessage}
              </pre>
            )}
            {event.inputJson && (
              <div>
                <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
                  Input
                </div>
                <pre className="text-[11px] bg-gray-50 border rounded p-2 overflow-x-auto whitespace-pre-wrap break-words">
                  {prettyOrRaw(event.inputJson)}
                </pre>
              </div>
            )}
            {event.outputJson && (
              <div>
                <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-700">
                  Output
                </div>
                <pre className="text-[11px] bg-gray-50 border rounded p-2 overflow-x-auto whitespace-pre-wrap break-words">
                  {prettyOrRaw(event.outputJson)}
                </pre>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function prettyOrRaw(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
