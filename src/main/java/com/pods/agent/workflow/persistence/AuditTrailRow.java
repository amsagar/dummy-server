package com.pods.agent.workflow.persistence;

public record AuditTrailRow(
        String id,
        String instId,
        String activityInstId,
        String action,
        String actor,
        Long ts,
        String payloadJson
) {}
