package com.pods.agent.repository;

import com.pods.agent.domain.AgentTool;
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
public class AgentToolRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public AgentToolRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public AgentTool save(AgentTool tool) {
        if (tool.getId() == null) tool.setId(UUID.randomUUID().toString());
        if (tool.getExecutionKind() == null || tool.getExecutionKind().isBlank()) tool.setExecutionKind("http_proxy");
        if (tool.getInputSchemaVersion() <= 0) tool.setInputSchemaVersion(1);
        long now = System.currentTimeMillis();
        tool.setCreatedAt(now);
        tool.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("TOOL.INSERT"), params(tool));
        return tool;
    }

    public List<AgentTool> findAll() {
        return jdbc.query(sql.getQuery("TOOL.FIND_ALL"), (rs, n) -> map(rs));
    }

    public List<AgentTool> findByDomainId(String domainId) {
        return jdbc.query(sql.getQuery("TOOL.FIND_BY_DOMAIN"), (rs, n) -> map(rs), domainId);
    }

    public Optional<AgentTool> findById(String id) {
        var list = jdbc.query(sql.getQuery("TOOL.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void update(AgentTool tool) {
        tool.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL.UPDATE"), params(tool));
    }

    public void setEnabled(String id, boolean enabled) {
        namedJdbc.update(sql.getQuery("TOOL.SET_ENABLED"), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("enabled", enabled)
                .addValue("updatedAt", System.currentTimeMillis()));
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("TOOL.DELETE"), id);
    }

    private MapSqlParameterSource params(AgentTool tool) {
        return new MapSqlParameterSource()
                .addValue("id", tool.getId())
                .addValue("domainId", tool.getDomainId())
                .addValue("name", tool.getName())
                .addValue("description", tool.getDescription())
                .addValue("sourceType", tool.getSourceType())
                .addValue("executionKind", tool.getExecutionKind())
                .addValue("permissionScope", tool.getPermissionScope())
                .addValue("requiresApproval", tool.isRequiresApproval())
                .addValue("modelGate", tool.getModelGate())
                .addValue("providerGate", tool.getProviderGate())
                .addValue("experimental", tool.isExperimental())
                .addValue("inputSchemaVersion", tool.getInputSchemaVersion())
                .addValue("method", tool.getMethod())
                .addValue("host", tool.getHost())
                .addValue("endpoint", tool.getEndpoint())
                .addValue("requestSchema", tool.getRequestSchema())
                .addValue("responseSchema", tool.getResponseSchema())
                .addValue("sampleRequest", tool.getSampleRequest())
                .addValue("sampleResponse", tool.getSampleResponse())
                .addValue("enabled", tool.isEnabled())
                .addValue("createdAt", tool.getCreatedAt())
                .addValue("updatedAt", tool.getUpdatedAt());
    }

    private AgentTool map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return AgentTool.builder()
                .id(rs.getString("id"))
                .domainId(rs.getString("domain_id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .sourceType(rs.getString("source_type"))
                .executionKind(rs.getString("execution_kind"))
                .permissionScope(rs.getString("permission_scope"))
                .requiresApproval(rs.getBoolean("requires_approval"))
                .modelGate(rs.getString("model_gate"))
                .providerGate(rs.getString("provider_gate"))
                .experimental(rs.getBoolean("experimental"))
                .inputSchemaVersion(rs.getInt("input_schema_version"))
                .method(rs.getString("method"))
                .host(rs.getString("host"))
                .endpoint(rs.getString("endpoint"))
                .requestSchema(rs.getString("request_schema"))
                .responseSchema(rs.getString("response_schema"))
                .sampleRequest(rs.getString("sample_request"))
                .sampleResponse(rs.getString("sample_response"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
