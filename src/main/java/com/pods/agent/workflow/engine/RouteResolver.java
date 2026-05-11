package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ActivityResult;
import com.pods.agent.workflow.engine.domain.ErrorClass;
import com.pods.agent.workflow.engine.domain.TransitionDef;
import com.pods.agent.workflow.engine.domain.TransitionTrigger;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;

import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Picks which outgoing transition(s) to follow after an activity ends.
 *
 * <p>Transition-only routing: all branching decisions live on edges.
 */
@Component
@Slf4j
public class RouteResolver {

    private final AuditTrailManager audit;

    public RouteResolver(AuditTrailManager audit) {
        this.audit = audit;
    }

    /**
     * Pick targets after an activity completed (success or failure).
     *
     * <p>Loop activities (foreach/while/batch) get a small extra rule: when the
     * loop is "exhausted" (the dispatcher wrote {@code __loop_continue_<id> ==
     * false}), unconditional success edges are skipped so that the
     * no-match/default edge — typically the path to the end node — fires
     * instead. This matches the natural pattern an LLM produces:
     * <pre>
     *   loopActivity --ON_SUCCESS--> body          (taken every iteration)
     *   loopActivity --ON_NO_MATCH-> exit (default) (taken once, when done)
     * </pre>
     * without forcing the LLM to also remember the {@code
     * #__loop_continue_&lt;id&gt; == true} guard expression. Conditioned edges
     * are still honoured on exhaustion so explicit guards keep working.
     */
    public List<TransitionDef> resolve(ExecutionContext ctx,
                                       ActivityDef from,
                                       ActivityResult result) {
        List<TransitionDef> outgoing = ctx.definition().outgoing(from.id());
        if (outgoing.isEmpty()) {
            return List.of();
        }
        List<TransitionDef> matched = new ArrayList<>();
        List<TransitionDef> noMatchEdges = new ArrayList<>();
        Map<String, Object> bindings = errorAwareBindings(ctx, result);
        boolean loopExhausted = isLoopExhausted(ctx, from);

        for (TransitionDef tr : outgoing) {
            if (!triggerMatches(result, tr)) {
                continue;
            }
            if (tr.isErrorEdge() && tr.matchesErrorClass() != null
                    && tr.matchesErrorClass() != result.errorClass()) {
                continue;
            }
            if (tr.trigger() == TransitionTrigger.ON_NO_MATCH || tr.isDefault()) {
                noMatchEdges.add(tr);
                continue;
            }
            if (tr.condition() == null || tr.condition().isBlank()) {
                if (loopExhausted) {
                    // Body edge of an exhausted loop — skip so no-match wins.
                    continue;
                }
                matched.add(tr);
                continue;
            }
            SecureSpelEvaluator.Result evalResult =
                    SecureSpelEvaluator.evaluateBoolean(tr.condition(), bindings);
            if (!evalResult.ok()) {
                // Don't silently drop; record the failure and skip the edge.
                // The activity is already in its terminal state — we can't
                // re-route it on EXPRESSION error, so we just log + audit.
                log.warn("[RouteResolver] transition {} condition failed: {}",
                        tr.id(), evalResult.error());
                audit.record(ctx.processInstanceId(), null,
                        AuditTrailManager.Action.EXPRESSION_FAILED, null,
                        Map.of("transitionId", tr.id(),
                                "condition", tr.condition(),
                                "error", evalResult.error()));
                continue;
            }
            if (Boolean.TRUE.equals(evalResult.value())) {
                matched.add(tr);
            }
        }
        if (matched.isEmpty() && result.isSuccess()) {
            matched.addAll(noMatchEdges);
        }
        matched = applyPrioritySemantics(matched);
        return matched;
    }

    /**
     * True when {@code from} is a loop activity (foreach/while/batch) whose
     * dispatcher most recently wrote {@code __loop_continue_<id> == false}
     * into the variable scope. Used to bypass unconditional body edges so the
     * loop's natural exit edge wins.
     */
    private boolean isLoopExhausted(ExecutionContext ctx, ActivityDef from) {
        if (from == null || from.type() == null) return false;
        String t = from.type();
        if (!"foreach".equals(t) && !"while".equals(t) && !"batch".equals(t)) {
            return false;
        }
        Object cont = ctx.scope().effectiveSnapshot().get("__loop_continue_" + from.id());
        return Boolean.FALSE.equals(cont);
    }

    private Map<String, Object> errorAwareBindings(ExecutionContext ctx, ActivityResult result) {
        Map<String, Object> bindings = new LinkedHashMap<>(ctx.scope().effectiveSnapshot());
        // Expose error.* for error-edge conditions.
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("class", result.errorClass() == null ? null : result.errorClass().name());
        err.put("message", result.errorMessage());
        bindings.put("error", err);
        return bindings;
    }

    private boolean triggerMatches(ActivityResult result, TransitionDef tr) {
        TransitionTrigger trigger = tr.trigger();
        if (result.isSuccess()) {
            if (tr.isErrorEdge()) return false;
            return trigger == null
                    || trigger == TransitionTrigger.ON_SUCCESS
                    || trigger == TransitionTrigger.ON_NO_MATCH;
        }
        if (!tr.isErrorEdge()) return false;
        if (trigger == null || trigger == TransitionTrigger.ON_ERROR) return true;
        if (result.errorClass() == ErrorClass.TIMEOUT && trigger == TransitionTrigger.ON_TIMEOUT) return true;
        return result.errorClass() == ErrorClass.VALIDATION
                && trigger == TransitionTrigger.ON_VALIDATION_ERROR;
    }

    private List<TransitionDef> applyPrioritySemantics(List<TransitionDef> matched) {
        if (matched.isEmpty()) return matched;
        boolean hasPriority = matched.stream().anyMatch(t -> t.priority() != null);
        if (!hasPriority) return matched;
        matched.sort(Comparator.comparing(
                t -> t.priority() == null ? Integer.MAX_VALUE : t.priority()));
        int best = matched.get(0).priority() == null ? Integer.MAX_VALUE : matched.get(0).priority();
        return matched.stream()
                .filter(t -> {
                    int p = t.priority() == null ? Integer.MAX_VALUE : t.priority();
                    return p == best;
                })
                .toList();
    }
}
