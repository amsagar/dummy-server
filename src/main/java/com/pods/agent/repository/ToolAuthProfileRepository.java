package com.pods.agent.repository;

import com.pods.agent.domain.ToolAuthProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolAuthProfileRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public ToolAuthProfileRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public ToolAuthProfile save(ToolAuthProfile profile) {
        if (profile.getId() == null || profile.getId().isBlank()) {
            profile.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        namedJdbc.update("""
                INSERT INTO agent.tool_auth_profiles (
                    id, domain_id, name, description, auth_type, auth_config, client_id, encrypted_client_secret,
                    token_url, authorization_url, redirect_uri, scopes, encrypted_access_token, encrypted_refresh_token,
                    token_expires_at, enabled, created_at, updated_at
                ) VALUES (
                    :id, :domainId, :name, :description, :authType, :authConfig, :clientId, :encryptedClientSecret,
                    :tokenUrl, :authorizationUrl, :redirectUri, :scopes, :encryptedAccessToken, :encryptedRefreshToken,
                    :tokenExpiresAt, :enabled, :createdAt, :updatedAt
                )
                """, params(profile));
        return profile;
    }

    public List<ToolAuthProfile> findByDomainId(String domainId) {
        return jdbc.query("""
                SELECT * FROM agent.tool_auth_profiles
                WHERE domain_id = ?
                ORDER BY name
                """, (rs, n) -> map(rs), domainId);
    }

    public Optional<ToolAuthProfile> findById(String id) {
        var list = jdbc.query("SELECT * FROM agent.tool_auth_profiles WHERE id = ?", (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void update(ToolAuthProfile profile) {
        profile.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update("""
                UPDATE agent.tool_auth_profiles
                SET name = :name,
                    description = :description,
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
                    enabled = :enabled,
                    updated_at = :updatedAt
                WHERE id = :id
                """, params(profile));
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM agent.tool_auth_profiles WHERE id = ?", id);
    }

    private MapSqlParameterSource params(ToolAuthProfile profile) {
        return new MapSqlParameterSource()
                .addValue("id", profile.getId())
                .addValue("domainId", profile.getDomainId())
                .addValue("name", profile.getName())
                .addValue("description", profile.getDescription())
                .addValue("authType", profile.getAuthType())
                .addValue("authConfig", profile.getAuthConfig())
                .addValue("clientId", profile.getClientId())
                .addValue("encryptedClientSecret", profile.getEncryptedClientSecret())
                .addValue("tokenUrl", profile.getTokenUrl())
                .addValue("authorizationUrl", profile.getAuthorizationUrl())
                .addValue("redirectUri", profile.getRedirectUri())
                .addValue("scopes", profile.getScopes())
                .addValue("encryptedAccessToken", profile.getEncryptedAccessToken())
                .addValue("encryptedRefreshToken", profile.getEncryptedRefreshToken())
                .addValue("tokenExpiresAt", profile.getTokenExpiresAt())
                .addValue("enabled", profile.isEnabled())
                .addValue("createdAt", profile.getCreatedAt())
                .addValue("updatedAt", profile.getUpdatedAt());
    }

    private ToolAuthProfile map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolAuthProfile.builder()
                .id(rs.getString("id"))
                .domainId(rs.getString("domain_id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .authType(rs.getString("auth_type"))
                .authConfig(rs.getString("auth_config"))
                .clientId(rs.getString("client_id"))
                .encryptedClientSecret(rs.getString("encrypted_client_secret"))
                .tokenUrl(rs.getString("token_url"))
                .authorizationUrl(rs.getString("authorization_url"))
                .redirectUri(rs.getString("redirect_uri"))
                .scopes(rs.getString("scopes"))
                .encryptedAccessToken(rs.getString("encrypted_access_token"))
                .encryptedRefreshToken(rs.getString("encrypted_refresh_token"))
                .tokenExpiresAt((Long) rs.getObject("token_expires_at"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
