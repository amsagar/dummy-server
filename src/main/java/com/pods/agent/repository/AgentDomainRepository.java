package com.pods.agent.repository;

import com.pods.agent.domain.AgentDomain;
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
public class AgentDomainRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public AgentDomainRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public AgentDomain save(AgentDomain domain) {
        if (domain.getId() == null) domain.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        domain.setCreatedAt(now);
        domain.setUpdatedAt(now);
        var params = new MapSqlParameterSource()
                .addValue("id", domain.getId())
                .addValue("name", domain.getName())
                .addValue("description", domain.getDescription())
                .addValue("enabled", domain.isEnabled())
                .addValue("createdAt", domain.getCreatedAt())
                .addValue("updatedAt", domain.getUpdatedAt());
        namedJdbc.update(sql.getQuery("DOMAIN.INSERT"), params);
        return domain;
    }

    public List<AgentDomain> findAll() {
        return jdbc.query(sql.getQuery("DOMAIN.FIND_ALL"), (rs, n) -> AgentDomain.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build());
    }

    public Optional<AgentDomain> findById(String id) {
        var list = jdbc.query(sql.getQuery("DOMAIN.FIND_BY_ID"), (rs, n) -> AgentDomain.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void update(AgentDomain domain) {
        domain.setUpdatedAt(System.currentTimeMillis());
        var params = new MapSqlParameterSource()
                .addValue("id", domain.getId())
                .addValue("name", domain.getName())
                .addValue("description", domain.getDescription())
                .addValue("enabled", domain.isEnabled())
                .addValue("updatedAt", domain.getUpdatedAt());
        namedJdbc.update(sql.getQuery("DOMAIN.UPDATE"), params);
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("DOMAIN.DELETE"), id);
    }
}
