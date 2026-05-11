package com.pods.agent.repository;

import com.pods.agent.domain.RuntimeTrace;
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
public class RuntimeTraceRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public RuntimeTraceRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public RuntimeTrace save(RuntimeTrace trace) {
        if (trace.getId() == null) trace.setId(UUID.randomUUID().toString());
        if (trace.getCreatedAt() == 0) trace.setCreatedAt(System.currentTimeMillis());
        try {
            namedJdbc.update(sql.getQuery("TRACE.INSERT"), new MapSqlParameterSource()
                    .addValue("id", trace.getId())
                    .addValue("sessionId", trace.getSessionId())
                    .addValue("turnId", trace.getTurnId())
                    .addValue("traceType", trace.getTraceType())
                    .addValue("correlationId", trace.getCorrelationId())
                    .addValue("payload", trace.getPayload())
                    .addValue("createdAt", trace.getCreatedAt()));
        } catch (DataIntegrityViolationException e) {
            // runtime_traces.session_id has a FK to chat_sessions. Most often
            // this fails because the session row was wiped between turns
            // (dev DB reset, race, manual cleanup). Traces are observability;
            // never let a missing-session FK violation crash the chat turn or
            // block downstream work like workflow proposal generation.
            log.warn("[RuntimeTraceRepository] dropping trace for session={} turn={} ({}): {}",
                    trace.getSessionId(), trace.getTurnId(), trace.getTraceType(),
                    e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
        }
        return trace;
    }

    public List<RuntimeTrace> findBySession(String sessionId) {
        return jdbc.query(sql.getQuery("TRACE.FIND_BY_SESSION"), (rs, n) -> RuntimeTrace.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .traceType(rs.getString("trace_type"))
                .correlationId(rs.getString("correlation_id"))
                .payload(rs.getString("payload"))
                .createdAt(rs.getLong("created_at"))
                .build(), sessionId);
    }

    public int deleteFromTime(String sessionId, long createdAt) {
        return namedJdbc.update(sql.getQuery("TRACE.DELETE_FROM_TIME"), new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("createdAt", createdAt));
    }
}
