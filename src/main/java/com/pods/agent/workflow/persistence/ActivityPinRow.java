package com.pods.agent.workflow.persistence;

/**
 * One pinned output for a (process_def, activity_def_id) pair. The
 * dispatcher consults this before executing a `tool` activity; if a row
 * exists, the pinned output is returned and the plugin is NOT invoked.
 *
 * Used by the "re-run from this node" UX: the run controller copies a
 * source run's activity outputs into pinned rows for the upstream
 * activities, then starts a new run that fast-replays them.
 *
 * `pinnedOutput` is a JSON string (column type is JSONB).
 */
public record ActivityPinRow(
        String id,
        String defId,
        String activityDefId,
        String pinnedOutput,
        long createdAt,
        long updatedAt,
        String createdBy
) {}
