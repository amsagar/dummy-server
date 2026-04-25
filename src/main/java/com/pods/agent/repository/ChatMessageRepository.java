package com.pods.agent.repository;

import com.pods.agent.domain.ChatMessage;
import com.pods.agent.repository.mapper.ChatMessageRowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Slf4j
public class ChatMessageRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sqlQueryLoader;
    private final ChatMessageRowMapper rowMapper;

    public ChatMessageRepository(JdbcTemplate jdbc,
                                  NamedParameterJdbcTemplate namedJdbc,
                                  SqlQueryLoader sqlQueryLoader,
                                  ChatMessageRowMapper rowMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sqlQueryLoader = sqlQueryLoader;
        this.rowMapper = rowMapper;
    }

    public ChatMessage save(ChatMessage message) {
        if (message.getId() == null) {
            message.setId(UUID.randomUUID().toString());
        }
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.INSERT");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", message.getId())
                .addValue("sessionId", message.getSessionId())
                .addValue("role", message.getRole())
                .addValue("content", message.getContent())
                .addValue("createdAt", message.getCreatedAt());
        namedJdbc.update(sql, params);
        return message;
    }

    public List<ChatMessage> findBySessionId(String sessionId) {
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.FIND_BY_SESSION");
        return jdbc.query(sql, rowMapper, sessionId);
    }

    public List<ChatMessage> findBySessionId(String sessionId, int limit, int offset) {
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.FIND_BY_SESSION_PAGED");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return namedJdbc.query(sql, params, rowMapper);
    }

    public long countBySessionId(String sessionId) {
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.COUNT_BY_SESSION");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId);
        Long count = namedJdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    public void deleteBySessionId(String sessionId) {
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.DELETE_BY_SESSION");
        int deleted = jdbc.update(sql, sessionId);
        log.info("[ChatMessageRepository] Deleted {} messages for session={}", deleted, sessionId);
    }

    public Optional<ChatMessage> findById(String id) {
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.FIND_BY_ID");
        List<ChatMessage> results = jdbc.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void deleteById(String id) {
        String sql = sqlQueryLoader.getQuery("CHAT_MESSAGE.DELETE_BY_ID");
        jdbc.update(sql, id);
    }
}
