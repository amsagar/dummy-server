package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.engine.domain.ProcessDefinition;

/**
 * Per-run execution context. Bundles the things every engine component
 * needs without forcing each method signature to drag five arguments around.
 *
 * <p>Lifecycle: created at process start by {@link WorkflowManager}, used
 * throughout {@link ProcessExecutor}/{@link ActivityDispatcher}/{@link
 * RouteResolver}, and discarded when the process closes.
 */
public final class ExecutionContext {

    private final String processInstanceId;
    private final ProcessDefinition definition;
    private final VariableScope scope;
    private final String requesterId;

    public ExecutionContext(String processInstanceId,
                            ProcessDefinition definition,
                            VariableScope scope,
                            String requesterId) {
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId required");
        }
        if (definition == null) {
            throw new IllegalArgumentException("definition required");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope required");
        }
        this.processInstanceId = processInstanceId;
        this.definition = definition;
        this.scope = scope;
        this.requesterId = requesterId;
    }

    public String processInstanceId() {
        return processInstanceId;
    }

    public ProcessDefinition definition() {
        return definition;
    }

    public VariableScope scope() {
        return scope;
    }

    public String requesterId() {
        return requesterId;
    }
}
