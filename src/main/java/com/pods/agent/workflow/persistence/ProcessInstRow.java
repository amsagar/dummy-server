package com.pods.agent.workflow.persistence;

/**
 * Mirrors {@code agent.process_inst}. Field names match column names in
 * snake_case via {@link org.springframework.jdbc.core.DataClassRowMapper}'s
 * default conversion, with a custom mapper for state-string round-tripping.
 */
public record ProcessInstRow(
        String id,
        String defId,
        String state,
        Long startedAt,
        Long endedAt,
        String requesterId,
        String parentInstId,
        Long dueAt,
        String errorClass,
        String errorMessage
) {}
