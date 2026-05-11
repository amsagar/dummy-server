package com.pods.agent.workflow.persistence;

public record WorkflowVariableRow(
        String id,
        String instId,
        String scope,
        String name,
        String javaClass,
        String valueJson,
        Long updatedAt
) {}
