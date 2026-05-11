package com.pods.agent.workflow.engine.domain;

/**
 * Outgoing edge from one activity to another.
 *
 * <p>Routing semantics (see {@link com.pods.agent.workflow.engine.RouteResolver}):
 * <ul>
 *   <li>If the source activity completed successfully:
 *     <ol>
 *       <li>Skip transitions where {@code isErrorEdge=true}.</li>
 *       <li>Take all transitions whose {@code condition} evaluates to true,
 *           or have no condition.</li>
 *       <li>If multiple match, this is an AND-split (every matched target
 *           starts) — see audit finding #6 resolution: matching transitions
 *           are deterministic, no string-substring on error.message.</li>
 *     </ol>
 *   </li>
 *   <li>If the source activity failed:
 *     <ol>
 *       <li>Take only transitions where {@code isErrorEdge=true}.</li>
 *       <li>Filter further by {@code matchesErrorClass} if set.</li>
 *       <li>Apply the same condition evaluation. The activity's
 *           {@code error.class} and {@code error.message} are available as
 *           variables for the condition.</li>
 *     </ol>
 *   </li>
 * </ul>
 */
public record TransitionDef(
        String id,
        String fromActivityId,
        String toActivityId,
        String condition,
        boolean isErrorEdge,
        ErrorClass matchesErrorClass,
        TransitionTrigger trigger,
        Integer priority,
        boolean isDefault
) {
    public TransitionDef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TransitionDef.id must be non-blank");
        }
        if (fromActivityId == null || toActivityId == null) {
            throw new IllegalArgumentException("TransitionDef must have both endpoints");
        }
    }
}
