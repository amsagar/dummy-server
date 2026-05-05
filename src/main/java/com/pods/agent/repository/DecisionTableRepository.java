package com.pods.agent.repository;

import com.pods.agent.domain.DecisionTable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DecisionTableRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public DecisionTableRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public DecisionTable save(DecisionTable table) {
        if (table.getId() == null || table.getId().isBlank()) {
            table.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        if (table.getCreatedAt() == 0) table.setCreatedAt(now);
        table.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("DECISION_TABLE.INSERT"), params(table));
        return table;
    }

    public DecisionTable updateByName(String name, DecisionTable table) {
        long now = System.currentTimeMillis();
        table.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("DECISION_TABLE.UPDATE_BY_NAME"), new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("newName", table.getName())
                .addValue("description", table.getDescription())
                .addValue("dmnJson", table.getDmnJson())
                .addValue("hitPolicy", table.getHitPolicy())
                .addValue("metadataJson", table.getMetadataJson())
                .addValue("updatedAt", table.getUpdatedAt()));
        return findByName(table.getName()).orElseThrow(() -> new IllegalArgumentException("Decision table not found: " + table.getName()));
    }

    public List<DecisionTable> findAll() {
        return jdbc.query(sql.getQuery("DECISION_TABLE.FIND_ALL"), (rs, n) -> map(rs));
    }

    public Optional<DecisionTable> findByName(String name) {
        var rows = jdbc.query(sql.getQuery("DECISION_TABLE.FIND_BY_NAME"), (rs, n) -> map(rs), name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void deleteByName(String name) {
        jdbc.update(sql.getQuery("DECISION_TABLE.DELETE_BY_NAME"), name);
    }

    private MapSqlParameterSource params(DecisionTable table) {
        return new MapSqlParameterSource()
                .addValue("id", table.getId())
                .addValue("name", table.getName())
                .addValue("description", table.getDescription())
                .addValue("dmnJson", table.getDmnJson())
                .addValue("hitPolicy", table.getHitPolicy())
                .addValue("metadataJson", table.getMetadataJson())
                .addValue("createdAt", table.getCreatedAt())
                .addValue("updatedAt", table.getUpdatedAt());
    }

    private DecisionTable map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return DecisionTable.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .dmnJson(rs.getString("dmn_json"))
                .hitPolicy(rs.getString("hit_policy"))
                .metadataJson(rs.getString("metadata_json"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
