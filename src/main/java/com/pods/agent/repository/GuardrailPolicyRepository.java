package com.pods.agent.repository;

import com.pods.agent.domain.GuardrailPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class GuardrailPolicyRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public GuardrailPolicyRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public GuardrailPolicy save(GuardrailPolicy policy) {
        if (policy.getId() == null) policy.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("POLICY.INSERT"), new MapSqlParameterSource()
                .addValue("id", policy.getId())
                .addValue("name", policy.getName())
                .addValue("scope", policy.getScope())
                .addValue("ruleType", policy.getRuleType())
                .addValue("ruleValue", policy.getRuleValue())
                .addValue("decision", policy.getDecision())
                .addValue("enabled", policy.isEnabled())
                .addValue("createdAt", policy.getCreatedAt())
                .addValue("updatedAt", policy.getUpdatedAt()));
        return policy;
    }

    public List<GuardrailPolicy> findAll() {
        return jdbc.query(sql.getQuery("POLICY.FIND_ALL"), (rs, n) -> GuardrailPolicy.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .scope(rs.getString("scope"))
                .ruleType(rs.getString("rule_type"))
                .ruleValue(rs.getString("rule_value"))
                .decision(rs.getString("decision"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build());
    }

    public List<GuardrailPolicy> findEnabled() {
        return jdbc.query(sql.getQuery("POLICY.FIND_ENABLED"), (rs, n) -> GuardrailPolicy.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .scope(rs.getString("scope"))
                .ruleType(rs.getString("rule_type"))
                .ruleValue(rs.getString("rule_value"))
                .decision(rs.getString("decision"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build());
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("POLICY.DELETE"), id);
    }
}
