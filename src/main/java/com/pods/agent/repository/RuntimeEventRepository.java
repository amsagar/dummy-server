package com.pods.agent.repository;

import com.pods.agent.domain.RuntimeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
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
        namedJdbc.update(sql.getQuery("RUNTIME_EVENT.INSERT"), new MapSqlParameterSource()
                .addValue("id", event.getId())
                .addValue("sessionId", event.getSessionId())
                .addValue("turnId", event.getTurnId())
                .addValue("eventType", event.getEventType())
                .addValue("payload", event.getPayload())
                .addValue("createdAt", event.getCreatedAt()));
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

    public int deleteFromTime(String sessionId, long createdAt) {
        return namedJdbc.update(sql.getQuery("RUNTIME_EVENT.DELETE_FROM_TIME"), new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("createdAt", createdAt));
    }
}
