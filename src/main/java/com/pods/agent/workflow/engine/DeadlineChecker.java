package com.pods.agent.workflow.engine;

import com.pods.agent.repository.SqlQueryLoader;
import com.pods.agent.workflow.engine.domain.ActivityState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps {@code activity_inst} for breached deadlines.
 *
 * <p>Phase 1 had no deadline support; this fixes the half of audit finding #3
 * about runaway activities. The pattern mirrors Joget's {@code DeadlineChecker}
 * (a persistent background thread scanning for {@code LimitStruct} expiry).
 *
 * <p>Implementation:
 * <ul>
 *   <li>Every 30 seconds, find activities with {@code state='running'} and
 *       {@code due_at &lt; now()}.</li>
 *   <li>Mark them {@link ActivityState#DEADLINE_BREACHED}.</li>
 *   <li>Audit per-row.</li>
 * </ul>
 *
 * <p>What this does NOT do (yet): cancel an in-flight thread that's still
 * executing the activity body. Phase 1's executor is synchronous; long-running
 * activities block the executor. Async / cancellable execution is a separate
 * follow-up. Until then, deadline breach is a state-cleanup mechanism for
 * crashed or hung instances rather than a hard kill.
 *
 * <p>Active only when the JDBC persistence is configured; in unit tests with
 * the no-op persistence the bean is not constructed.
 */
@Component
@ConditionalOnBean(JdbcEnginePersistence.class)
@Slf4j
public class DeadlineChecker {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;
    private final AuditTrailManager audit;

    public DeadlineChecker(NamedParameterJdbcTemplate jdbc,
                           SqlQueryLoader sql,
                           AuditTrailManager audit) {
        this.jdbc = jdbc;
        this.sql = sql;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${agent.workflow.deadline.scan-interval-ms:30000}",
               initialDelay = 30_000)
    public void scan() {
        long now = Instant.now().toEpochMilli();
        try {
            List<Map<String, Object>> breached = jdbc.queryForList(
                    sql.getQuery("WORKFLOW_ACTIVITY_INST.FIND_BREACHED_DEADLINES"),
                    new MapSqlParameterSource("now", now));
            if (breached.isEmpty()) {
                return;
            }
            log.info("[DeadlineChecker] {} activities have breached their deadline", breached.size());
            for (Map<String, Object> row : breached) {
                String id = String.valueOf(row.get("id"));
                String instId = String.valueOf(row.get("inst_id"));
                jdbc.update(sql.getQuery("WORKFLOW_ACTIVITY_INST.MARK_DEADLINE_BREACHED"),
                        new MapSqlParameterSource()
                                .addValue("id", id)
                                .addValue("endedAt", now));
                audit.record(instId, id,
                        AuditTrailManager.Action.ACTIVITY_DEADLINE, "deadline-checker",
                        Map.of("dueAt", row.get("due_at"),
                                "now", now));
            }
        } catch (RuntimeException e) {
            log.warn("[DeadlineChecker] sweep failed: {}", e.toString());
        }
    }
}
