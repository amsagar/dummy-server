package com.pods.agent.workflow.engine;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ActivityErrorPolicy;
import com.pods.agent.workflow.engine.domain.ActivityResult;
import com.pods.agent.workflow.engine.domain.ActivityState;
import com.pods.agent.workflow.engine.domain.ProcessState;
import com.pods.agent.workflow.engine.domain.TransitionDef;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;
import tools.jackson.core.type.TypeReference;
import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Walks the activity graph synchronously for a given process instance.
 *
 * <p>Phase 1 design: single-threaded, in-memory worklist. AND-split runs
 * sequentially in worklist order; AND-join coordination, true parallel
 * execution, sub-flow execution, and resume-from-checkpoint come in Phase 5.
 *
 * <p>Persistence (via {@link EnginePersistence}) is engaged at every state
 * transition: activity start writes an {@code activity_inst} row, activity
 * end updates it, every variable update writes/updates the
 * {@code workflow_variable} row.
 */
@Component
@Slf4j
public class ProcessExecutor {

    private final ActivityDispatcher dispatcher;
    private final RouteResolver router;
    private final AuditTrailManager audit;
    private final EnginePersistence persistence;
    private final WorkflowSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final com.pods.agent.workflow.persistence.PendingApprovalRepository pendingApprovalRepo;

    @Autowired
    public ProcessExecutor(ActivityDispatcher dispatcher,
                           RouteResolver router,
                           AuditTrailManager audit,
                           EnginePersistence persistence,
                           WorkflowSchemaValidator schemaValidator,
                           ObjectMapper objectMapper,
                           com.pods.agent.workflow.persistence.PendingApprovalRepository pendingApprovalRepo) {
        this.dispatcher = dispatcher;
        this.router = router;
        this.audit = audit;
        this.persistence = persistence;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.pendingApprovalRepo = pendingApprovalRepo;
    }

    ProcessExecutor(ActivityDispatcher dispatcher,
                    RouteResolver router,
                    AuditTrailManager audit,
                    EnginePersistence persistence,
                    ObjectMapper objectMapper) {
        this(dispatcher, router, audit, persistence,
                new WorkflowSchemaValidator(),
                objectMapper,
                null);
    }

    public ProcessState run(ExecutionContext ctx) {
        return run(ctx, null, null, /* fresh */ true);
    }

    /**
     * Resume entry point: continue from a previously persisted worklist +
     * join state. The caller supplies the pre-loaded variable scope on
     * {@code ctx}; this method takes responsibility for everything else.
     */
    public ProcessState resume(ExecutionContext ctx,
                               List<String> worklistActivityIds,
                               JoinCoordinator.Snapshot joinSnapshot) {
        return run(ctx, worklistActivityIds, joinSnapshot, /* fresh */ false);
    }

    private ProcessState run(ExecutionContext ctx,
                             List<String> initialWorklistIds,
                             JoinCoordinator.Snapshot initialJoinSnapshot,
                             boolean fresh) {
        Deque<ActivityDef> worklist = new ArrayDeque<>();
        JoinCoordinator join = new JoinCoordinator(ctx.definition());
        if (fresh) {
            worklist.add(ctx.definition().startActivity());
            audit.record(ctx.processInstanceId(),
                    AuditTrailManager.Action.PROCESS_STARTED,
                    Map.of("definitionId", ctx.definition().id(),
                            "version", String.valueOf(ctx.definition().version())));
        } else {
            if (initialWorklistIds != null) {
                for (String id : initialWorklistIds) {
                    worklist.add(ctx.definition().requireActivity(id));
                }
            }
            join.restore(initialJoinSnapshot);
            audit.record(ctx.processInstanceId(),
                    AuditTrailManager.Action.PROCESS_STARTED,
                    Map.of("definitionId", ctx.definition().id(),
                            "version", String.valueOf(ctx.definition().version()),
                            "resumed", true,
                            "resumeWorklist", initialWorklistIds == null
                                    ? List.of() : initialWorklistIds));
        }

        try {
        while (!worklist.isEmpty()) {
            ActivityDef activity = worklist.poll();
            ActivityRun run = startActivity(ctx, activity);

            ActivityResult result = executeWithPolicy(ctx, activity);

            // Suspend short-circuit: end this activity-instance row in
            // SUSPENDED, write a pending_approval row, re-add the activity
            // to the worklist front, persist the checkpoint, and return.
            if (result.isSuspended()) {
                endActivity(ctx, run, result);
                if (pendingApprovalRepo != null) {
                    pendingApprovalRepo.insert(new com.pods.agent.workflow.persistence.PendingApprovalRow(
                            UUID.randomUUID().toString(),
                            ctx.processInstanceId(),
                            run.id,
                            activity.id(),
                            ctx.requesterId(),
                            Instant.now().toEpochMilli(),
                            result.errorMessage() == null ? "approval required" : result.errorMessage(),
                            null, null, null, null, null, null));
                }
                audit.record(ctx.processInstanceId(), run.id,
                        AuditTrailManager.Action.PROCESS_SUSPENDED, ctx.requesterId(),
                        Map.of("activityId", activity.id(),
                                "reason", String.valueOf(result.errorMessage())));
                Deque<ActivityDef> remainder = new ArrayDeque<>();
                remainder.add(activity);
                while (!worklist.isEmpty()) remainder.add(worklist.poll());
                persistence.persistCheckpoint(
                        ctx.processInstanceId(),
                        serializeWorklist(remainder),
                        serializeJoin(join.snapshot()),
                        Instant.now().toEpochMilli());
                return ProcessState.OPEN_SUSPENDED;
            }

            applyVariableUpdates(ctx, run, activity, result);
            endActivity(ctx, run, result);

            // Loop activities dispatched with __loop_continue=true are starting
            // a new iteration. Clear OR-join arrival markers so the loop body's
            // activities can fire again — otherwise they'd be suppressed as
            // "already dispatched" on iteration 2+.
            if (isLoopActivity(activity) && result.isSuccess() && isLoopContinuing(activity, result)) {
                join.resetOrJoinDispatched();
            }

            List<TransitionDef> successors = pickSuccessors(ctx, activity, result);

            if (successors.isEmpty()) {
                audit.record(ctx.processInstanceId(), run.id,
                        AuditTrailManager.Action.DECISION_ROUTED, ctx.requesterId(),
                        Map.of("from", activity.id(),
                                "transitions", List.of(),
                                "outcome", result.isSuccess() ? "no-match" : "error"));
                if (result.isSuccess() && activity.isEnd()) {
                    persistEndResult(ctx, activity);
                    audit.record(ctx.processInstanceId(),
                            AuditTrailManager.Action.PROCESS_COMPLETED,
                            Map.of("definitionId", ctx.definition().id()));
                    return ProcessState.CLOSED_COMPLETED;
                }
                if (!result.isSuccess()) {
                    audit.record(ctx.processInstanceId(),
                            AuditTrailManager.Action.PROCESS_TERMINATED,
                            Map.of("reason", "no error-edge match",
                                    "errorClass", String.valueOf(result.errorClass()),
                                    "errorMessage", String.valueOf(result.errorMessage())));
                    return ProcessState.CLOSED_TERMINATED;
                }
                log.warn("[ProcessExecutor] activity {} has no successors and is not isEnd",
                        activity.id());
                audit.record(ctx.processInstanceId(),
                        AuditTrailManager.Action.PROCESS_COMPLETED,
                        Map.of("definitionId", ctx.definition().id(),
                                "warning", "graph dead-end, no successors"));
                return ProcessState.CLOSED_COMPLETED;
            }

            audit.record(ctx.processInstanceId(), run.id,
                    AuditTrailManager.Action.DECISION_ROUTED, ctx.requesterId(),
                    Map.of("from", activity.id(),
                            "outcome", result.isSuccess() ? "success" : "error",
                            "transitions", successors.stream().map(TransitionDef::id).toList()));

            for (TransitionDef tr : successors) {
                if (join.notifyArrival(tr)) {
                    worklist.add(ctx.definition().requireActivity(tr.toActivityId()));
                }
            }

            // Mid-flow checkpoint after each activity transition. If the JVM
            // crashes here, /runs/{id}/resume can pick up from this point.
            persistence.persistCheckpoint(
                    ctx.processInstanceId(),
                    serializeWorklist(worklist),
                    serializeJoin(join.snapshot()),
                    Instant.now().toEpochMilli());
        }
        } finally {
            // Any path out of the run loop — natural drain, early return,
            // or thrown exception — clears the checkpoint so the next
            // resume request sees a quiescent state.
            persistence.persistCheckpoint(ctx.processInstanceId(), null, null, null);
        }

        log.warn("[ProcessExecutor] process {} drained worklist with no end activity",
                ctx.processInstanceId());
        audit.record(ctx.processInstanceId(),
                AuditTrailManager.Action.PROCESS_COMPLETED,
                Map.of("warning", "drained without explicit end"));
        return ProcessState.CLOSED_COMPLETED;
    }

    private ActivityRun startActivity(ExecutionContext ctx, ActivityDef activity) {
        ActivityRun run = new ActivityRun();
        run.id = UUID.randomUUID().toString();
        run.startedAt = Instant.now().toEpochMilli();
        persistence.persistActivityStart(new ActivityInstRow(
                run.id,
                ctx.processInstanceId(),
                activity.id(),
                activity.type(),
                ActivityState.RUNNING.wire(),
                run.startedAt,
                null,
                null,
                null,
                0,
                activity.pluginName(),
                snapshotJson(ctx.scope().localSnapshot()),
                null,
                null,
                null));
        audit.record(ctx.processInstanceId(), run.id,
                AuditTrailManager.Action.ACTIVITY_STARTED, ctx.requesterId(),
                Map.of("activityId", activity.id(),
                        "type", activity.type(),
                        "pluginName", String.valueOf(activity.pluginName())));
        return run;
    }

    private void applyVariableUpdates(ExecutionContext ctx, ActivityRun run, ActivityDef activity, ActivityResult result) {
        if (result.variableUpdates().isEmpty()) {
            return;
        }
        // Validate against the same target the dispatcher chose — by default
        // that's the activity's raw output, not the {varName -> value} wrapper.
        // Without this picker, any non-"object" outputSchema would fail here
        // because variableUpdates is always a Map.
        Object schemaTarget = ActivityDispatcher.pickOutputSchemaTarget(
                activity, result.output(), result.variableUpdates());
        List<String> schemaErrors = schemaValidator.validate(activity.outputSchema(), schemaTarget);
        if (!schemaErrors.isEmpty()) {
            throw new IllegalStateException("outputSchema violation before variable writes: "
                    + String.join("; ", schemaErrors));
        }
        long now = Instant.now().toEpochMilli();
        ctx.scope().setAll(result.variableUpdates());
        for (Map.Entry<String, Object> e : result.variableUpdates().entrySet()) {
            audit.record(ctx.processInstanceId(), run.id,
                    AuditTrailManager.Action.VARIABLE_UPDATED, ctx.requesterId(),
                    Map.of("name", e.getKey(),
                            "value", String.valueOf(e.getValue())));
            persistence.persistVariable(new WorkflowVariableRow(
                    UUID.randomUUID().toString(),
                    ctx.processInstanceId(),
                    ctx.scope().scopeId(),
                    e.getKey(),
                    e.getValue() == null ? null : e.getValue().getClass().getName(),
                    snapshotJson(e.getValue()),
                    now));
        }
    }

    private void endActivity(ExecutionContext ctx, ActivityRun run, ActivityResult result) {
        long now = Instant.now().toEpochMilli();
        persistence.persistActivityEnd(
                run.id,
                result.state().wire(),
                now,
                snapshotJson(result.output()),
                result.errorClass() == null ? null : result.errorClass().name(),
                result.errorMessage());
        if (result.isSuccess()) {
            audit.record(ctx.processInstanceId(), run.id,
                    AuditTrailManager.Action.ACTIVITY_COMPLETED, ctx.requesterId(),
                    Map.of("activityId", run.id,
                            "output", String.valueOf(result.output())));
        } else {
            audit.record(ctx.processInstanceId(), run.id,
                    AuditTrailManager.Action.ACTIVITY_FAILED, ctx.requesterId(),
                    Map.of("activityId", run.id,
                            "errorClass", String.valueOf(result.errorClass()),
                            "errorMessage", String.valueOf(result.errorMessage())));
        }
    }

    private List<TransitionDef> pickSuccessors(ExecutionContext ctx,
                                               ActivityDef activity,
                                               ActivityResult result) {
        return router.resolve(ctx, activity, result);
    }

    /**
     * On PROCESS_COMPLETED, evaluate the closing activity's
     * {@code properties.result} SecureSpel expression (if any) against the
     * run's final variable scope and persist the JSON-serialized value on
     * the {@code process_inst} row. The result is later surfaced in the
     * run-summary API response so external callers don't need a second
     * round-trip to dig the answer out of activity outputs.
     *
     * <p>This is best-effort: any failure (missing expression, eval error,
     * serialization issue) is logged and swallowed — the workflow has
     * already succeeded; surfacing a result is a convenience, not a
     * correctness requirement.
     */
    private void persistEndResult(ExecutionContext ctx, ActivityDef activity) {
        Object expr = activity.properties().get("result");
        if (!(expr instanceof String exprStr) || exprStr.isBlank()) {
            return;
        }
        try {
            String unwrapped = exprStr.startsWith("#{") && exprStr.endsWith("}")
                    ? exprStr.substring(2, exprStr.length() - 1)
                    : exprStr;
            SecureSpelEvaluator.Result eval = SecureSpelEvaluator.evaluate(
                    unwrapped, ctx.scope().effectiveSnapshot());
            if (!eval.ok()) {
                audit.record(ctx.processInstanceId(), null,
                        AuditTrailManager.Action.EXPRESSION_FAILED, ctx.requesterId(),
                        Map.of("phase", "endResult",
                                "activityId", activity.id(),
                                "error", String.valueOf(eval.error())));
                return;
            }
            String json = objectMapper.writeValueAsString(eval.value());
            persistence.persistProcessResult(ctx.processInstanceId(), json);
        } catch (Exception e) {
            audit.record(ctx.processInstanceId(), null,
                    AuditTrailManager.Action.EXPRESSION_FAILED, ctx.requesterId(),
                    Map.of("phase", "endResult",
                            "activityId", activity.id(),
                            "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static boolean isLoopActivity(ActivityDef activity) {
        String t = activity.type();
        return "foreach".equals(t) || "while".equals(t) || "batch".equals(t);
    }

    private static boolean isLoopContinuing(ActivityDef activity, ActivityResult result) {
        // Loop dispatchers signal continuation via output.continue=true. When
        // false (loop exhausted) we leave the OR-join state alone so the
        // post-loop section still gets its single-fire semantics.
        if (!(result.output() instanceof Map<?, ?> out)) return false;
        Object cont = out.get("continue");
        return Boolean.TRUE.equals(cont);
    }

    private ActivityResult executeWithPolicy(ExecutionContext ctx, ActivityDef activity) {
        ActivityErrorPolicy policy = activity.errorPolicy() == null
                ? ActivityErrorPolicy.defaults()
                : activity.errorPolicy();
        int maxAttempts = Math.max(1, policy.retryCount() + 1);
        ActivityResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            last = dispatcher.dispatch(ctx, activity);
            long elapsed = System.currentTimeMillis() - startedAt;
            if (policy.timeoutMs() != null && policy.timeoutMs() > 0 && elapsed > policy.timeoutMs()) {
                last = ActivityResult.failure(
                        com.pods.agent.workflow.engine.domain.ErrorClass.TIMEOUT,
                        "activity exceeded timeoutMs=" + policy.timeoutMs());
            }
            if (last.isSuccess() || last.isSuspended()) {
                return last;
            }
            if (policy.failFast()) {
                return last;
            }
            if (attempt < maxAttempts) {
                audit.record(ctx.processInstanceId(), null,
                        AuditTrailManager.Action.ACTIVITY_FAILED, ctx.requesterId(),
                        Map.of("activityId", activity.id(),
                                "attempt", attempt,
                                "retrying", true,
                                "errorClass", String.valueOf(last.errorClass()),
                                "errorMessage", String.valueOf(last.errorMessage())));
                if (policy.backoffMs() > 0) {
                    try {
                        Thread.sleep(policy.backoffMs() * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return last;
                    }
                }
                continue;
            }
            if (policy.continueOnError()) {
                return ActivityResult.success(Map.of(
                        "continuedOnError", true,
                        "errorClass", String.valueOf(last.errorClass()),
                        "errorMessage", String.valueOf(last.errorMessage())));
            }
            return last;
        }
        return last == null ? ActivityResult.failure(
                com.pods.agent.workflow.engine.domain.ErrorClass.UNCAUGHT,
                "activity execution produced no result") : last;
    }

    private String serializeWorklist(Deque<ActivityDef> worklist) {
        List<String> ids = new ArrayList<>(worklist.size());
        for (ActivityDef a : worklist) {
            ids.add(a.id());
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeJoin(JoinCoordinator.Snapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Deserialize a previously persisted worklist into a list of activity
     * ids. Used by {@link WorkflowManager#resumeProcess}.
     */
    public List<String> deserializeWorklist(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[ProcessExecutor] failed to deserialize worklist: {}", e.toString());
            return List.of();
        }
    }

    /** Deserialize a persisted join snapshot. */
    public JoinCoordinator.Snapshot deserializeJoin(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JoinCoordinator.Snapshot.class);
        } catch (Exception e) {
            log.warn("[ProcessExecutor] failed to deserialize join state: {}", e.toString());
            return null;
        }
    }

    private String snapshotJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            return "\"<unserializable: " + value.getClass().getSimpleName() + ">\"";
        }
    }

    /**
     * Minimal mutable holder for the bookkeeping the executor needs about an
     * activity in flight. Not exported.
     */
    private static final class ActivityRun {
        String id;
        long startedAt;

        @SuppressWarnings("unused")
        Map<String, Object> startSnapshot = new LinkedHashMap<>();
    }
}
