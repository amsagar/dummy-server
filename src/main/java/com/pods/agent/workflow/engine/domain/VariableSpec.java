package com.pods.agent.workflow.engine.domain;

/**
 * Declared process variable. {@code javaClass} is a type hint matching the
 * Joget {@link com.pods.agent.workflow.joget.model.WorkflowVariable#getJavaClass()}
 * field. {@code defaultExpression} is a SecureSpel expression evaluated when
 * the variable is initialized; {@code null} means leave unset until written.
 *
 * Resolves audit finding #2 ("untyped Map context") by giving each declared
 * variable a known type and a fail-fast presence check.
 */
public record VariableSpec(
        String name,
        String javaClass,
        String defaultExpression,
        boolean required
) {
    public VariableSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("VariableSpec.name must be non-blank");
        }
    }
}
