package com.pods.agent.workflow.persistence;

public record ProcessDefRow(
        String id,
        String name,
        String version,
        String packageId,
        String description,
        String xpdlJson,
        Long createdAt,
        Long updatedAt
) {}
