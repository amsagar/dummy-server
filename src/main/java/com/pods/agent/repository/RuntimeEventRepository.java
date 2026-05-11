package com.pods.agent.repository;

import com.pods.agent.domain.RuntimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@Slf4j
public class RuntimeEventRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public RuntimeEventRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public RuntimeEvent save(RuntimeEvent event) {
        if (event.getId() == null) event.setId(UUID.randomUUID().toString());
        if (event.getCreatedAt() == 0) event.setCreatedAt(System.currentTimeMillis());
        try {
            namedJdbc.update(sql.getQuery("RUNTIME_EVENT.INSERT"), new MapSqlParameterSource()
                    .addValue("id", event.getId())
                    .addValue("sessionId", event.getSessionId())
                    .addValue("turnId", event.getTurnId())
                    .addValue("eventType", event.getEventType())
                    .addValue("payload", event.getPayload())
                    .addValue("createdAt", event.getCreatedAt()));
        } catch (DataIntegrityViolationException e) {
            // runtime_events.session_id has a FK to chat_sessions; tolerate a
            // missing parent row (dev DB resets, manual cleanups, races). The
            // chat turn must still complete and downstream async work — e.g.
            // workflow proposal generation — must still get scheduled.
            log.warn("[RuntimeEventRepository] dropping event for session={} turn={} ({}): {}",
                    event.getSessionId(), event.getTurnId(), event.getEventType(),
                    e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
        }
        return event;
    }

    public List<RuntimeEvent> findBySessionId(String sessionId) {
        return jdbc.query(sql.getQuery("RUNTIME_EVENT.FIND_BY_SESSION"), (rs, n) -> RuntimeEvent.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .eventType(rs.getString("event_type"))
                .payload(rs.getString("payload"))
                .createdAt(rs.getLong("created_at"))
                .build(), sessionId);
    }

    public List<RuntimeEvent> findByTurnId(String turnId) {
        return jdbc.query(sql.getQuery("RUNTIME_EVENT.FIND_BY_TURN"), (rs, n) -> RuntimeEvent.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .eventType(rs.getString("event_type"))
                .payload(rs.getString("payload"))
                .createdAt(rs.getLong("created_at"))
                .build(), turnId);
    }

    public List<RuntimeEvent> findByTurnIdAfter(String turnId, long afterCreatedAt, int limit) {
        return namedJdbc.query(sql.getQuery("RUNTIME_EVENT.FIND_BY_TURN_AFTER"),
                new MapSqlParameterSource()
                        .addValue("turnId", turnId)
                        .addValue("afterCreatedAt", afterCreatedAt)
                        .addValue("limit", limit),
                (rs, n) -> RuntimeEvent.builder()
                        .id(rs.getString("id"))
                        .sessionId(rs.getString("session_id"))
                        .turnId(rs.getString("turn_id"))
                        .eventType(rs.getString("event_type"))
                        .payload(rs.getString("payload"))
                        .createdAt(rs.getLong("created_at"))
                        .build());
    }

    public int deleteFromTime(String sessionId, long createdAt) {
        return namedJdbc.update(sql.getQuery("RUNTIME_EVENT.DELETE_FROM_TIME"), new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("createdAt", createdAt));
    }
}
