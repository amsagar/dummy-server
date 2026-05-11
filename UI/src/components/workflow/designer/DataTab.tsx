// Data tab — empty in Phase 1. Phase 3 wires this to the most recent run's
// activity_inst row for this node, with a Table/JSON/Schema viewer on the
// input/output snapshots and an error block. For now we show a hint so the
// user knows what to expect.

import { Database } from "lucide-react";

export function DataTab() {
  return (
    <div className="flex flex-col items-center gap-2 text-muted-foreground py-10 text-xs px-4 text-center">
      <Database className="w-6 h-6" />
      <p className="font-medium text-foreground">No run data yet</p>
      <p>
        Once this workflow runs, the input and output of this node will appear here. A Gantt
        timeline and per-step IO viewer ship in the next phase.
      </p>
    </div>
  );
}
