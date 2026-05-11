package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.engine.domain.ActivityDef;
import com.pods.agent.workflow.engine.domain.ActivityResult;
import com.pods.agent.workflow.engine.domain.ErrorClass;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.engine.domain.ProcessState;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;
import com.pods.agent.workflow.persistence.ProcessInstRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Runs a sub-flow with an <em>isolated</em> {@link VariableScope} so the
 * parent context is untouched until declared outputs are mapped back.
 *
 * <p>This is the fix for audit finding #5 ("context corruption on partial
 * failure"). Mid-flow failures inside the child cannot pollute the parent
 * scope; only declared outputs cross the boundary, and only on success.
 *
 * <p>Lookup is by {@link ActivityDef#subflowDefId()} → resolved through
 * {@link ProcessDefService}. {@link ActivityDef#subflowInputs()} maps a
 * SecureSpel expression evaluated in the parent scope to a child-variable
 * name; {@link ActivityDef#subflowOutputs()} maps a child-variable name to a
 * parent-variable name (no expression — the child value is stored verbatim).
 */
@Component
@Slf4j
public class SubFlowExecutor {

    private final ProcessDefService defService;
    private final ProcessExecutor executor;
    private final EnginePersistence persistence;
    private final AuditTrailManager audit;

    public SubFlowExecutor(ProcessDefService defService,
                           @Lazy ProcessExecutor executor,
                           EnginePersistence persistence,
                           AuditTrailManager audit) {
        this.defService = defService;
        this.executor = executor;
        this.persistence = persistence;
        this.audit = audit;
    }

    public ActivityResult dispatch(ExecutionContext parentCtx, ActivityDef activity) {
        if (activity.subflowDefId() == null || activity.subflowDefId().isBlank()) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "subflow activity " + activity.id() + " has no subflowDefId");
        }
        ProcessDefinition childDef = defService.loadDomainById(activity.subflowDefId()).orElse(null);
        if (childDef == null) {
            return ActivityResult.failure(ErrorClass.VALIDATION,
                    "subflow defId " + activity.subflowDefId() + " not found");
        }

        // Resolve inputs against the parent scope, fail fast on expression errors.
        Map<String, Object> childInitial = new LinkedHashMap<>();
        Map<String, Object> parentBindings = parentCtx.scope().effectiveSnapshot();
        for (Map.Entry<String, String> e : activity.subflowInputs().entrySet()) {
            SecureSpelEvaluator.Result r = SecureSpelEvaluator.evaluate(e.getValue(), parentBindings);
            if (!r.ok()) {
                return ActivityResult.failure(ErrorClass.EXPRESSION,
                        "subflow input '" + e.getKey() + "' failed: " + r.error());
            }
            childInitial.put(e.getKey(), r.value());
        }

        // Build child instance with isolated scope.
        String childInstanceId = UUID.randomUUID().toString();
        long startedAt = Instant.now().toEpochMilli();
        VariableScope childScope = new VariableScope(childInstanceId, parentCtx.scope());
        childScope.setAll(childInitial);

        persistence.persistProcessStart(new ProcessInstRow(
                childInstanceId,
                childDef.id(),
                ProcessState.OPEN_RUNNING.wire(),
                startedAt,
                null,
                parentCtx.requesterId(),
                parentCtx.processInstanceId(),
                null,
                null,
                null));

        ExecutionContext childCtx = new ExecutionContext(
                childInstanceId, childDef, childScope, parentCtx.requesterId());

        ProcessState terminal;
        try {
            terminal = executor.run(childCtx);
        } catch (RuntimeException e) {
            log.error("[SubFlowExecutor] uncaught error in subflow {}", childInstanceId, e);
            persistence.persistProcessEnd(childInstanceId,
                    ProcessState.CLOSED_TERMINATED.wire(),
                    Instant.now().toEpochMilli(),
                    "UNCAUGHT", e.getMessage());
            return ActivityResult.failure(ErrorClass.SUBFLOW,
                    "subflow threw: " + e.getMessage());
        }

        persistence.persistProcessEnd(childInstanceId,
                terminal.wire(),
                Instant.now().toEpochMilli(),
                null, null);

        if (terminal != ProcessState.CLOSED_COMPLETED) {
            return ActivityResult.failure(ErrorClass.SUBFLOW,
                    "subflow terminated in state " + terminal.wire());
        }

        // Map declared child outputs back to parent — atomic, only on success.
        Map<String, Object> parentUpdates = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : activity.subflowOutputs().entrySet()) {
            // key = child variable name, value = parent variable name
            parentUpdates.put(e.getValue(), childScope.get(e.getKey()));
        }
        return ActivityResult.success(childInstanceId, parentUpdates);
    }
}
