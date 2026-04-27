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

    public void updateAuthBinding(String id,
                                  String authProfileId,
                                  Boolean authOverrideEnabled,
                                  String authType,
                                  String authConfig,
                                  String clientId,
                                  String encryptedClientSecret,
                                  String tokenUrl,
                                  String authorizationUrl,
                                  String redirectUri,
                                  String scopes,
                                  String encryptedAccessToken,
                                  String encryptedRefreshToken,
                                  Long tokenExpiresAt) {
        namedJdbc.update("""
                        UPDATE agent.agent_tools
                        SET auth_profile_id = :authProfileId,
                            auth_override_enabled = :authOverrideEnabled,
                            auth_type = :authType,
                            auth_config = :authConfig,
                            client_id = :clientId,
                            encrypted_client_secret = :encryptedClientSecret,
                            token_url = :tokenUrl,
                            authorization_url = :authorizationUrl,
                            redirect_uri = :redirectUri,
                            scopes = :scopes,
                            encrypted_access_token = :encryptedAccessToken,
                            encrypted_refresh_token = :encryptedRefreshToken,
                            token_expires_at = :tokenExpiresAt,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("authProfileId", authProfileId)
                        .addValue("authOverrideEnabled", Boolean.TRUE.equals(authOverrideEnabled))
                        .addValue("authType", authType)
                        .addValue("authConfig", authConfig)
                        .addValue("clientId", clientId)
                        .addValue("encryptedClientSecret", encryptedClientSecret)
                        .addValue("tokenUrl", tokenUrl)
                        .addValue("authorizationUrl", authorizationUrl)
                        .addValue("redirectUri", redirectUri)
                        .addValue("scopes", scopes)
                        .addValue("encryptedAccessToken", encryptedAccessToken)
                        .addValue("encryptedRefreshToken", encryptedRefreshToken)
                        .addValue("tokenExpiresAt", tokenExpiresAt)
                        .addValue("updatedAt", System.currentTimeMillis()));
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
                .addValue("baseInjected", tool.isBaseInjected())
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
                .authProfileId(optString(rs, "auth_profile_id"))
                .authOverrideEnabled(optBoolean(rs, "auth_override_enabled"))
                .authType(optString(rs, "auth_type"))
                .authConfig(optString(rs, "auth_config"))
                .clientId(optString(rs, "client_id"))
                .encryptedClientSecret(optString(rs, "encrypted_client_secret"))
                .tokenUrl(optString(rs, "token_url"))
                .authorizationUrl(optString(rs, "authorization_url"))
                .redirectUri(optString(rs, "redirect_uri"))
                .scopes(optString(rs, "scopes"))
                .encryptedAccessToken(optString(rs, "encrypted_access_token"))
                .encryptedRefreshToken(optString(rs, "encrypted_refresh_token"))
                .tokenExpiresAt(optLong(rs, "token_expires_at"))
                .enabled(rs.getBoolean("enabled"))
                .baseInjected(Boolean.TRUE.equals(optBoolean(rs, "base_injected")))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }

    private String optString(java.sql.ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (java.sql.SQLException ignored) {
            return null;
        }
    }

    private Boolean optBoolean(java.sql.ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            if (value == null) return null;
            return rs.getBoolean(column);
        } catch (java.sql.SQLException ignored) {
            return null;
        }
    }

    private Long optLong(java.sql.ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            if (value == null) return null;
            if (value instanceof Number number) return number.longValue();
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
