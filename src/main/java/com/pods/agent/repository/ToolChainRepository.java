package com.pods.agent.repository;

import com.pods.agent.domain.ToolChain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChain save(ToolChain chain) {
        if (chain.getId() == null) chain.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        if (chain.getCreatedAt() == 0) chain.setCreatedAt(now);
        chain.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("TOOL_CHAIN.INSERT"), params(chain));
        return chain;
    }

    public ToolChain update(ToolChain chain) {
        chain.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN.UPDATE"), params(chain));
        return chain;
    }

    public Optional<ToolChain> findById(String id) {
        var rows = jdbc.query(sql.getQuery("TOOL_CHAIN.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ToolChain> findAll() {
        return jdbc.query(sql.getQuery("TOOL_CHAIN.FIND_ALL"), (rs, n) -> map(rs));
    }

    public List<ToolChain> findEnabled() {
        return jdbc.query(sql.getQuery("TOOL_CHAIN.FIND_ENABLED"), (rs, n) -> map(rs));
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("TOOL_CHAIN.DELETE"), id);
    }

    private MapSqlParameterSource params(ToolChain chain) {
        return new MapSqlParameterSource()
                .addValue("id", chain.getId())
                .addValue("name", chain.getName())
                .addValue("description", chain.getDescription())
                .addValue("enabled", chain.isEnabled())
                .addValue("status", chain.getStatus())
                .addValue("currentVersion", chain.getCurrentVersion())
                .addValue("metadataJson", chain.getMetadataJson())
                .addValue("createdBy", chain.getCreatedBy())
                .addValue("createdAt", chain.getCreatedAt())
                .addValue("updatedAt", chain.getUpdatedAt());
    }

    private ToolChain map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChain.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .enabled(rs.getBoolean("enabled"))
                .status(rs.getString("status"))
                .currentVersion((Integer) rs.getObject("current_version"))
                .metadataJson(rs.getString("metadata_json"))
                .createdBy(rs.getString("created_by"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
