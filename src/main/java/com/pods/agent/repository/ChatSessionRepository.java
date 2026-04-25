package com.pods.agent.repository;

import com.pods.agent.domain.ChatSession;
import com.pods.agent.repository.mapper.ChatSessionRowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Slf4j
public class ChatSessionRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sqlQueryLoader;
    private final ChatSessionRowMapper rowMapper;

    public ChatSessionRepository(JdbcTemplate jdbc,
                                  NamedParameterJdbcTemplate namedJdbc,
                                  SqlQueryLoader sqlQueryLoader,
                                  ChatSessionRowMapper rowMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sqlQueryLoader = sqlQueryLoader;
        this.rowMapper = rowMapper;
    }

    public ChatSession save(ChatSession session) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.INSERT");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", session.getSessionId())
                .addValue("userId", session.getUserId())
                .addValue("createdAt", session.getCreatedAt())
                .addValue("lastActive", session.getLastActive())
                .addValue("timezone", session.getTimezone())
                .addValue("title", session.getTitle());
        namedJdbc.update(sql, params);
        return session;
    }

    public void renameTitle(String sessionId, String userId, String title) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.RENAME_TITLE");
        namedJdbc.update(sql, Map.of("sessionId", sessionId, "userId", userId, "title", title));
    }

    public void archive(String sessionId, String userId, Long archivedAt) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.ARCHIVE");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId)
                .addValue("archivedAt", archivedAt);
        namedJdbc.update(sql, params);
    }

    public void updateTitle(String sessionId, String userId, String title) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.UPDATE_TITLE");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId)
                .addValue("title", title);
        namedJdbc.update(sql, params);
    }

    public Optional<ChatSession> findById(String sessionId) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.FIND_BY_ID");
        List<ChatSession> results = jdbc.query(sql, rowMapper, sessionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<ChatSession> findByUserIdAndSessionId(String userId, String sessionId) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.FIND_BY_USER_AND_ID");
        List<ChatSession> results = namedJdbc.query(sql, Map.of("userId", userId, "sessionId", sessionId), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void updateLastActive(String sessionId, String userId, long lastActive) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.UPDATE_LAST_ACTIVE");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId)
                .addValue("lastActive", lastActive);
        namedJdbc.update(sql, params);
    }

    public List<Map<String, Object>> findAllByUser(String userId, int limit, int offset, Boolean archived) {
        if (archived == null) {
            String sql = sqlQueryLoader.getQuery("CHAT_SESSION.FIND_ALL_BY_USER");
            return namedJdbc.query(sql, Map.of("userId", userId, "limit", limit, "offset", offset), this::mapSessionRow);
        }
        String key = archived ? "CHAT_SESSION.FIND_ALL_ARCHIVED" : "CHAT_SESSION.FIND_ALL_ACTIVE";
        String sql = sqlQueryLoader.getQuery(key);
        return namedJdbc.query(sql, Map.of("userId", userId, "limit", limit, "offset", offset), this::mapSessionRow);
    }

    public long countAllByUser(String userId, Boolean archived) {
        String sql;
        if (archived == null) {
            sql = sqlQueryLoader.getQuery("CHAT_SESSION.COUNT_ALL_BY_USER");
        } else if (archived) {
            sql = sqlQueryLoader.getQuery("CHAT_SESSION.COUNT_ALL_ARCHIVED");
        } else {
            sql = sqlQueryLoader.getQuery("CHAT_SESSION.COUNT_ALL_ACTIVE");
        }
        Long count = namedJdbc.queryForObject(sql, Map.of("userId", userId), Long.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> findAll(int limit, int offset) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.FIND_ALL");
        return jdbc.query(sql, this::mapSessionRow, limit, offset);
    }

    private Map<String, Object> mapSessionRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", rs.getString("session_id"));
        row.put("userId", rs.getString("user_id"));
        row.put("createdAt", rs.getLong("created_at"));
        row.put("lastActive", rs.getLong("last_active"));
        row.put("timezone", rs.getString("timezone"));
        row.put("title", rs.getString("title"));
        row.put("archivedAt", rs.getObject("archived_at"));
        return row;
    }

    public void delete(String sessionId, String userId) {
        String sql = sqlQueryLoader.getQuery("CHAT_SESSION.DELETE");
        namedJdbc.update(sql, Map.of("sessionId", sessionId, "userId", userId));
    }
}
