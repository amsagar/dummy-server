package com.pods.agent.repository;

import com.pods.agent.domain.AgentProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentProfileRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    private static final RowMapper<AgentProfile> ROW_MAPPER = (rs, n) -> AgentProfile.builder()
            .id(rs.getString("id"))
            .name(rs.getString("name"))
            .mode(rs.getString("mode"))
            .systemPrompt(rs.getString("system_prompt"))
            .modelStrategy(rs.getString("model_strategy"))
            .enabled(rs.getBoolean("enabled"))
            .metadata(rs.getString("metadata"))
            .kind(rs.getString("kind"))
            .createdAt(rs.getLong("created_at"))
            .updatedAt(rs.getLong("updated_at"))
            .build();

    public AgentProfileRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public AgentProfile save(AgentProfile profile) {
        if (profile.getId() == null) profile.setId(UUID.randomUUID().toString());
        if (profile.getKind() == null || profile.getKind().isBlank()) profile.setKind("system");
        long now = System.currentTimeMillis();
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("AGENT_PROFILE.INSERT"), params(profile));
        return profile;
    }

    public List<AgentProfile> findAll() {
        return jdbc.query(sql.getQuery("AGENT_PROFILE.FIND_ALL"), ROW_MAPPER);
    }

    public List<AgentProfile> findByKind(String kind) {
        return jdbc.query(sql.getQuery("AGENT_PROFILE.FIND_BY_KIND"), ROW_MAPPER, kind);
    }

    public Optional<AgentProfile> findById(String id) {
        var list = jdbc.query(sql.getQuery("AGENT_PROFILE.FIND_BY_ID"), ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void update(AgentProfile profile) {
        if (profile.getKind() == null || profile.getKind().isBlank()) profile.setKind("system");
        profile.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("AGENT_PROFILE.UPDATE"), params(profile));
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("AGENT_PROFILE.DELETE"), id);
    }

    private MapSqlParameterSource params(AgentProfile profile) {
        return new MapSqlParameterSource()
                .addValue("id", profile.getId())
                .addValue("name", profile.getName())
                .addValue("mode", profile.getMode())
                .addValue("systemPrompt", profile.getSystemPrompt())
                .addValue("modelStrategy", profile.getModelStrategy())
                .addValue("enabled", profile.isEnabled())
                .addValue("metadata", profile.getMetadata())
                .addValue("kind", profile.getKind() == null ? "system" : profile.getKind())
                .addValue("createdAt", profile.getCreatedAt())
                .addValue("updatedAt", profile.getUpdatedAt());
    }
}
