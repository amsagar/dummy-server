// Provides per-activity execution status to the read-only canvas. The same
// custom node components (TaskNode/RouteNode/SubflowNode) are used in the
// designer (no context — generic icons) and in the execution browser
// (context present — status icons + colored ring + tooltips).
//
// Where multiple attempts exist (e.g. suspended → resumed → completed), we
// keep the LAST attempt — that's the one whose state matters for the
// overlay. The AND-join progress map is precomputed by the parent canvas
// (it's the only thing that knows the graph topology).

import { createContext, useContext, useMemo } from "react";
import type { ActivityInst } from "@/types/workflow";

export interface JoinProgress {
  arrived: number;
  total: number;
}

interface ExecutionStatusValue {
  byActivityDefId: Map<string, ActivityInst>;
  /** activityDefId set — used by ExecutionCanvas to dim/bold edges. */
  ranActivityIds: Set<string>;
  /** Per-AND-join precomputed progress, keyed by the join activity id. */
  joinProgressByActivityId: Map<string, JoinProgress>;
}

const Ctx = createContext<ExecutionStatusValue | null>(null);

export function ExecutionStatusProvider({
  activities,
  joinProgressByActivityId,
  children,
}: {
  activities: ActivityInst[];
  joinProgressByActivityId: Map<string, JoinProgress>;
  children: React.ReactNode;
}) {
  const value = useMemo<ExecutionStatusValue>(() => {
    const map = new Map<string, ActivityInst>();
    const ran = new Set<string>();
    for (const a of activities) {
      const prev = map.get(a.activityDefId);
      if (!prev || (a.startedAt ?? 0) >= (prev.startedAt ?? 0)) {
        map.set(a.activityDefId, a);
      }
      ran.add(a.activityDefId);
    }
    return { byActivityDefId: map, ranActivityIds: ran, joinProgressByActivityId };
  }, [activities, joinProgressByActivityId]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useExecutionStatus(activityDefId: string): ActivityInst | null {
  const v = useContext(Ctx);
  return v?.byActivityDefId.get(activityDefId) ?? null;
}

export function useInExecutionView(): boolean {
  return useContext(Ctx) != null;
}

export function useJoinProgress(activityDefId: string): JoinProgress | null {
  const v = useContext(Ctx);
  return v?.joinProgressByActivityId.get(activityDefId) ?? null;
}
