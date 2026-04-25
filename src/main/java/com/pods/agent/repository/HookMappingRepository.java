package com.pods.agent.repository;

import com.pods.agent.domain.HookMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class HookMappingRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public HookMappingRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public HookMapping save(HookMapping hook) {
        if (hook.getId() == null) hook.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        hook.setCreatedAt(now);
        hook.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("HOOK.INSERT"), new MapSqlParameterSource()
                .addValue("id", hook.getId())
                .addValue("hookPoint", hook.getHookPoint())
                .addValue("hookName", hook.getHookName())
                .addValue("profileId", hook.getProfileId())
                .addValue("enabled", hook.isEnabled())
                .addValue("configJson", hook.getConfigJson())
                .addValue("createdAt", hook.getCreatedAt())
                .addValue("updatedAt", hook.getUpdatedAt()));
        return hook;
    }

    public List<HookMapping> findAll() {
        return jdbc.query(sql.getQuery("HOOK.FIND_ALL"), (rs, n) -> map(rs));
    }

    public List<HookMapping> findEnabledByPoint(String hookPoint) {
        return jdbc.query(sql.getQuery("HOOK.FIND_ENABLED_BY_POINT"), (rs, n) -> map(rs), hookPoint);
    }

    public void deleteByPoint(String hookPoint) {
        jdbc.update(sql.getQuery("HOOK.DELETE_BY_POINT"), hookPoint);
    }

    private HookMapping map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return HookMapping.builder()
                .id(rs.getString("id"))
                .hookPoint(rs.getString("hook_point"))
                .hookName(rs.getString("hook_name"))
                .profileId(rs.getString("profile_id"))
                .enabled(rs.getBoolean("enabled"))
                .configJson(rs.getString("config_json"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
