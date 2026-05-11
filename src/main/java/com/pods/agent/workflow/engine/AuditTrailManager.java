package com.pods.agent.workflow.engine;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.persistence.AuditTrailRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Writes per-event audit rows.
 *
 * <p>Two destinations: slf4j (always) and {@link EnginePersistence}
 * (driven by Spring config — JDBC in production, no-op in unit tests). The
 * Joget pattern (see {@code AuditTrailManager} in wflow-core) stores actor +
 * action + payload per significant state change; we replicate that.
 *
 * <p>Side-effect-only: callers never read back. For querying history, use
 * {@link com.pods.agent.workflow.persistence.AuditTrailRepository} directly.
 */
@Component
@Slf4j
public class AuditTrailManager {

    public enum Action {
        PROCESS_STARTED("process.started"),
        PROCESS_COMPLETED("process.completed"),
        PROCESS_TERMINATED("process.terminated"),
        PROCESS_ABORTED("process.aborted"),
        PROCESS_SUSPENDED("process.suspended"),
        PROCESS_RESUMED("process.resumed"),
        APPROVAL_DECIDED("approval.decided"),
        ACTIVITY_STARTED("activity.started"),
        ACTIVITY_COMPLETED("activity.completed"),
        ACTIVITY_FAILED("activity.failed"),
        ACTIVITY_DEADLINE("activity.deadline_breached"),
        ACTIVITY_CANCELLED("activity.cancelled"),
        DECISION_ROUTED("decision.routed"),
        VARIABLE_UPDATED("variable.updated"),
        EXPRESSION_FAILED("expression.failed");

        public final String wire;
        Action(String wire) { this.wire = wire; }
    }

    private final EnginePersistence persistence;
    private final ObjectMapper objectMapper;

    public AuditTrailManager(EnginePersistence persistence, ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.objectMapper = objectMapper;
    }

    public void record(String processInstanceId,
                       String activityInstanceId,
                       Action action,
                       String actor,
                       Map<String, Object> payload) {
        long ts = Instant.now().toEpochMilli();
        Map<String, Object> safe = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        log.info("[audit] inst={} act={} action={} actor={} ts={} payload={}",
                processInstanceId, activityInstanceId, action.wire, actor, ts, safe);
        try {
            persistence.persistAudit(new AuditTrailRow(
                    UUID.randomUUID().toString(),
                    processInstanceId,
                    activityInstanceId,
                    action.wire,
                    actor,
                    ts,
                    toJson(safe)));
        } catch (RuntimeException e) {
            // Persistence failures must never derail engine progress; the
            // slf4j line above is the durable record of last resort.
            log.warn("[audit] persist failed: {}", e.toString());
        }
    }

    public void record(String processInstanceId, Action action, Map<String, Object> payload) {
        record(processInstanceId, null, action, null, payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            return "{\"audit_serialization_error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
