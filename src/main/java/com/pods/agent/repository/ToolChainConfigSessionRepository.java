package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainConfigSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainConfigSessionRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainConfigSessionRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainConfigSession save(ToolChainConfigSession session) {
        if (session.getId() == null) session.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        if (session.getCreatedAt() == 0) session.setCreatedAt(now);
        session.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_CONFIG_SESSION.INSERT"), params(session));
        return session;
    }

    public ToolChainConfigSession update(ToolChainConfigSession session) {
        session.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_CONFIG_SESSION.UPDATE"), params(session));
        return session;
    }

    public Optional<ToolChainConfigSession> findById(String id) {
        var rows = jdbc.query(sql.getQuery("TOOL_CHAIN_CONFIG_SESSION.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ToolChainConfigSession> findByToolChainId(String toolChainId) {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_CONFIG_SESSION.FIND_BY_CHAIN"), (rs, n) -> map(rs), toolChainId);
    }

    public void delete(String id) {
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_CONFIG_SESSION.DELETE"), new MapSqlParameterSource().addValue("id", id));
    }

    private MapSqlParameterSource params(ToolChainConfigSession session) {
        return new MapSqlParameterSource()
                .addValue("id", session.getId())
                .addValue("toolChainId", session.getToolChainId())
                .addValue("title", session.getTitle())
                .addValue("status", session.getStatus())
                .addValue("latestArtifactJson", session.getLatestArtifactJson())
                .addValue("pendingQuestionJson", session.getPendingQuestionJson())
                .addValue("createdBy", session.getCreatedBy())
                .addValue("createdAt", session.getCreatedAt())
                .addValue("updatedAt", session.getUpdatedAt())
                .addValue("archivedAt", session.getArchivedAt());
    }

    private ToolChainConfigSession map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChainConfigSession.builder()
                .id(rs.getString("id"))
                .toolChainId(rs.getString("tool_chain_id"))
                .title(rs.getString("title"))
                .status(rs.getString("status"))
                .latestArtifactJson(rs.getString("latest_artifact_json"))
                .pendingQuestionJson(rs.getString("pending_question_json"))
                .createdBy(rs.getString("created_by"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .archivedAt((Long) rs.getObject("archived_at"))
                .build();
    }
}
