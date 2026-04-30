package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainConfigLayout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainConfigLayoutRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainConfigLayoutRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainConfigLayout upsert(ToolChainConfigLayout layout) {
        if (layout.getId() == null || layout.getId().isBlank()) {
            layout.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        if (layout.getCreatedAt() == 0) layout.setCreatedAt(now);
        layout.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_CONFIG_LAYOUT.UPSERT"), params(layout));
        return layout;
    }

    public Optional<ToolChainConfigLayout> findByScope(String toolChainId, String sessionId, String userId) {
        var rows = jdbc.query(
                sql.getQuery("TOOL_CHAIN_CONFIG_LAYOUT.FIND_BY_SCOPE"),
                (rs, n) -> map(rs),
                toolChainId,
                sessionId,
                userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private MapSqlParameterSource params(ToolChainConfigLayout layout) {
        return new MapSqlParameterSource()
                .addValue("id", layout.getId())
                .addValue("toolChainId", layout.getToolChainId())
                .addValue("sessionId", layout.getSessionId())
                .addValue("userId", layout.getUserId())
                .addValue("positionsJson", layout.getPositionsJson())
                .addValue("viewportJson", layout.getViewportJson())
                .addValue("createdAt", layout.getCreatedAt())
                .addValue("updatedAt", layout.getUpdatedAt());
    }

    private ToolChainConfigLayout map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChainConfigLayout.builder()
                .id(rs.getString("id"))
                .toolChainId(rs.getString("tool_chain_id"))
                .sessionId(rs.getString("session_id"))
                .userId(rs.getString("user_id"))
                .positionsJson(rs.getString("positions_json"))
                .viewportJson(rs.getString("viewport_json"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
