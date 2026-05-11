package com.pods.agent.workflow.persistence;

/**
 * Mirrors {@code agent.activity_inst}. {@code inputSnapshot}/{@code
 * outputSnapshot} are JSON strings (the column type is JSONB but Spring JDBC
 * binds via {@code String} / {@code PGobject}).
 */
public record ActivityInstRow(
        String id,
        String instId,
        String activityDefId,
        String type,
        String state,
        Long startedAt,
        Long endedAt,
        Long dueAt,
        String assignee,
        Integer attempt,
        String pluginName,
        String inputSnapshot,
        String outputSnapshot,
        String errorClass,
        String errorMessage
) {}
