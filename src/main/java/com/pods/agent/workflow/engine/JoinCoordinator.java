package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.engine.domain.TransitionDef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-process join bookkeeping. Two semantics, picked per-target-activity:
 *
 * <ul>
 *   <li><b>OR-join (default)</b> — fire on the first arrival; suppress
 *       subsequent arrivals at the same convergence point. Correct for XOR
 *       routing where only one branch fires.</li>
 *   <li><b>AND-join</b> — when {@link ActivityDef#andJoin()} is true, the
 *       activity fires only after EVERY incoming transition has arrived.
 *       Correct for AND-split fan-outs that need full synchronization at the
 *       merge.</li>
 * </ul>
 *
 * <p>Single-process scope, in-memory: one instance per
 * {@link ExecutionContext}. Not thread-safe — Phase 1's executor is
 * single-threaded.
 */
public final class JoinCoordinator {

    private final ProcessDefinition def;
    /** OR-join: target activityId already dispatched (fire-once). */
    private final Set<String> orJoinDispatched = new HashSet<>();
    /** AND-join: target activityId → remaining incoming arrivals. */
    private final Map<String, Integer> andJoinPending = new HashMap<>();

    public JoinCoordinator(ProcessDefinition def) {
        this.def = def;
        for (ActivityDef a : def.activities()) {
            if (a.andJoin()) {
                int incoming = def.incoming(a.id()).size();
                if (incoming > 1) {
                    andJoinPending.put(a.id(), incoming);
                }
            }
        }
    }

    /** Snapshot for checkpoint persistence. */
    public Snapshot snapshot() {
        return new Snapshot(new HashSet<>(orJoinDispatched), new HashMap<>(andJoinPending));
    }

    /** Restore from a previously persisted snapshot. */
    public void restore(Snapshot s) {
        orJoinDispatched.clear();
        andJoinPending.clear();
        if (s == null) {
            return;
        }
        if (s.orJoinDispatched != null) {
            orJoinDispatched.addAll(s.orJoinDispatched);
        }
        if (s.andJoinPending != null) {
            andJoinPending.putAll(s.andJoinPending);
        }
    }

    public record Snapshot(Set<String> orJoinDispatched, Map<String, Integer> andJoinPending) {}

    /**
     * Forget every OR-join arrival recorded so far. Called by the executor at
     * the start of each new loop iteration so the loop body's activities can
     * fire again — without this, the second iteration's body would be
     * suppressed as "already dispatched once".
     *
     * <p>AND-join pending counts are intentionally not reset: AND-joins inside
     * a single iteration must still complete their incoming-arrival count.
     * (AND-joins that span iterations are not currently supported.)
     */
    public void resetOrJoinDispatched() {
        orJoinDispatched.clear();
    }

    /**
     * Notify the coordinator that a transition fired into the target
     * activity. Returns {@code true} if the target should run now.
     *
     * <p>For AND-join targets, returns {@code true} only on the LAST arrival.
     * For OR-join targets, returns {@code true} only on the FIRST arrival.
     */
    public boolean notifyArrival(TransitionDef tr) {
        String targetId = tr.toActivityId();
        ActivityDef target = def.activitiesById().get(targetId);
        if (target != null && target.andJoin()) {
            Integer remaining = andJoinPending.get(targetId);
            if (remaining == null) {
                return true; // single incoming, fire now
            }
            int next = remaining - 1;
            if (next <= 0) {
                andJoinPending.remove(targetId);
                return true;
            }
            andJoinPending.put(targetId, next);
            return false;
        }
        // Loop activities (foreach/while/batch) are inherently re-entrant —
        // the body's back-edge MUST be allowed to re-fire them every iteration.
        // OR-join's fire-once rule would otherwise drain the worklist after
        // the first iteration. The loop's own __loop_continue_<id> flag plus
        // RouteResolver.isLoopExhausted() handle clean exit.
        if (target != null && isLoopType(target.type())) {
            return true;
        }
        // OR-join (default): first arrival fires, subsequent arrivals suppressed.
        return orJoinDispatched.add(targetId);
    }

    private static boolean isLoopType(String type) {
        return "foreach".equals(type) || "while".equals(type) || "batch".equals(type);
    }
}
