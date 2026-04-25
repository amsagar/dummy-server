package com.pods.agent.repository;

import com.pods.agent.domain.Memory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MemoryRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sqlQueryLoader;

    public MemoryRepository(JdbcTemplate jdbc,
                            NamedParameterJdbcTemplate namedJdbc,
                            SqlQueryLoader sqlQueryLoader) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sqlQueryLoader = sqlQueryLoader;
    }

    public Memory save(Memory memory) {
        if (memory.getId() == null || memory.getId().isBlank()) {
            memory.setId(UUID.randomUUID().toString());
        }
        String sql = sqlQueryLoader.getQuery("MEMORY.INSERT");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", memory.getId())
                .addValue("userId", memory.getUserId())
                .addValue("sessionId", memory.getSessionId())
                .addValue("category", memory.getCategory())
                .addValue("memoryFilePath", memory.getMemoryFilePath())
                .addValue("content", memory.getContent())
                .addValue("tags", toSqlArray(memory.getTags()))
                .addValue("createdAt", memory.getCreatedAt())
                .addValue("updatedAt", memory.getUpdatedAt());
        namedJdbc.update(sql, params);
        return memory;
    }

    public void update(Memory memory) {
        String sql = sqlQueryLoader.getQuery("MEMORY.UPDATE");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", memory.getId())
                .addValue("userId", memory.getUserId())
                .addValue("sessionId", memory.getSessionId())
                .addValue("category", memory.getCategory())
                .addValue("content", memory.getContent())
                .addValue("tags", toSqlArray(memory.getTags()))
                .addValue("updatedAt", memory.getUpdatedAt());
        namedJdbc.update(sql, params);
    }

    public Optional<Memory> findById(String userId, String id) {
        String sql = sqlQueryLoader.getQuery("MEMORY.FIND_BY_ID");
        List<Memory> rows = namedJdbc.query(sql, Map.of("userId", userId, "id", id), this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Memory> findByUserIdAndFilePath(String userId, String memoryFilePath) {
        String sql = sqlQueryLoader.getQuery("MEMORY.FIND_BY_USER_AND_FILE_PATH");
        List<Memory> rows = namedJdbc.query(sql, Map.of("userId", userId, "memoryFilePath", memoryFilePath), this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Memory> findByUserId(String userId) {
        String sql = sqlQueryLoader.getQuery("MEMORY.FIND_BY_USER");
        return namedJdbc.query(sql, Map.of("userId", userId), this::mapRow);
    }

    public List<Memory> findByUserIdAndCategory(String userId, String category) {
        String sql = sqlQueryLoader.getQuery("MEMORY.FIND_BY_USER_AND_CATEGORY");
        return namedJdbc.query(sql, Map.of("userId", userId, "category", category), this::mapRow);
    }

    public List<Memory> searchByContent(String userId, String query, int limit) {
        String sql = sqlQueryLoader.getQuery("MEMORY.SEARCH_BY_CONTENT");
        return namedJdbc.query(sql,
                Map.of("userId", userId, "pattern", "%" + query + "%", "limit", Math.max(1, limit)),
                this::mapRow);
    }

    public void delete(String userId, String id) {
        String sql = sqlQueryLoader.getQuery("MEMORY.DELETE");
        namedJdbc.update(sql, Map.of("userId", userId, "id", id));
    }

    public void deleteByUserId(String userId) {
        String sql = sqlQueryLoader.getQuery("MEMORY.DELETE_BY_USER");
        namedJdbc.update(sql, Map.of("userId", userId));
    }

    private Memory mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Array tagsArray = rs.getArray("tags");
        List<String> tags = List.of();
        if (tagsArray != null) {
            Object arr = tagsArray.getArray();
            if (arr instanceof String[] values) {
                tags = List.of(values);
            }
        }
        return Memory.builder()
                .id(rs.getString("id"))
                .userId(rs.getString("user_id"))
                .sessionId(rs.getString("session_id"))
                .category(rs.getString("category"))
                .memoryFilePath(rs.getString("memory_file_path"))
                .content(rs.getString("content"))
                .tags(tags)
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }

    private Object toSqlArray(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.toArray(new String[0]);
    }
}
