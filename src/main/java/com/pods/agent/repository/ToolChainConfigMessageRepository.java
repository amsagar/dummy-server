package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainConfigMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ToolChainConfigMessageRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainConfigMessageRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainConfigMessage save(ToolChainConfigMessage message) {
        if (message.getId() == null) message.setId(UUID.randomUUID().toString());
        if (message.getCreatedAt() == 0) message.setCreatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_CONFIG_MESSAGE.INSERT"), new MapSqlParameterSource()
                .addValue("id", message.getId())
                .addValue("sessionId", message.getSessionId())
                .addValue("role", message.getRole())
                .addValue("content", message.getContent())
                .addValue("metadataJson", message.getMetadataJson())
                .addValue("createdAt", message.getCreatedAt())
                .addValue("eventType", message.getEventType())
                .addValue("eventPayload", message.getEventPayload())
                .addValue("requestId", message.getRequestId())
                .addValue("hitlStatus", message.getHitlStatus())
                .addValue("hitlResponse", message.getHitlResponse()));
        return message;
    }

    public List<ToolChainConfigMessage> findBySessionId(String sessionId) {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_CONFIG_MESSAGE.FIND_BY_SESSION"), (rs, n) -> ToolChainConfigMessage.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .role(rs.getString("role"))
                .content(rs.getString("content"))
                .metadataJson(rs.getString("metadata_json"))
                .createdAt(rs.getLong("created_at"))
                .eventType(rs.getString("event_type"))
                .eventPayload(rs.getString("event_payload"))
                .requestId(rs.getString("request_id"))
                .hitlStatus(rs.getString("hitl_status"))
                .hitlResponse(rs.getString("hitl_response"))
                .build(), sessionId);
    }

    public int updateHitlByRequestId(String sessionId, String requestId, String hitlStatus, String hitlResponse) {
        return namedJdbc.update(sql.getQuery("TOOL_CHAIN_CONFIG_MESSAGE.UPDATE_HITL_BY_REQUEST"), new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("requestId", requestId)
                .addValue("hitlStatus", hitlStatus)
                .addValue("hitlResponse", hitlResponse));
    }
}
