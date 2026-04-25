package com.pods.agent.repository;

import com.pods.agent.domain.McpRegistryEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class McpRegistryRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public McpRegistryRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public McpRegistryEntry save(McpRegistryEntry entry) {
        if (entry.getId() == null) entry.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        if (entry.getTransportType() == null || entry.getTransportType().isBlank()) entry.setTransportType("streamable_http");
        if (entry.getBaseUrl() == null || entry.getBaseUrl().isBlank()) entry.setBaseUrl(entry.getEndpoint());
        if (entry.getConnectTimeoutMs() == null) entry.setConnectTimeoutMs(10_000);
        if (entry.getReadTimeoutMs() == null) entry.setReadTimeoutMs(30_000);
        if (entry.getVerifyTls() == null) entry.setVerifyTls(true);
        if (entry.getDiscoveredToolsCount() == null) entry.setDiscoveredToolsCount(0);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("MCP.INSERT"), params(entry));
        return entry;
    }

    public List<McpRegistryEntry> findAll() {
        return jdbc.query(sql.getQuery("MCP.FIND_ALL"), (rs, n) -> map(rs));
    }

    public List<McpRegistryEntry> findEnabled() {
        return jdbc.query(sql.getQuery("MCP.FIND_ENABLED"), (rs, n) -> map(rs));
    }

    public Optional<McpRegistryEntry> findById(String id) {
        var list = jdbc.query(sql.getQuery("MCP.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void update(McpRegistryEntry entry) {
        if (entry.getTransportType() == null || entry.getTransportType().isBlank()) entry.setTransportType("streamable_http");
        if (entry.getBaseUrl() == null || entry.getBaseUrl().isBlank()) entry.setBaseUrl(entry.getEndpoint());
        if (entry.getConnectTimeoutMs() == null) entry.setConnectTimeoutMs(10_000);
        if (entry.getReadTimeoutMs() == null) entry.setReadTimeoutMs(30_000);
        if (entry.getVerifyTls() == null) entry.setVerifyTls(true);
        if (entry.getDiscoveredToolsCount() == null) entry.setDiscoveredToolsCount(0);
        entry.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("MCP.UPDATE"), params(entry));
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("MCP.DELETE"), id);
    }

    private MapSqlParameterSource params(McpRegistryEntry entry) {
        return new MapSqlParameterSource()
                .addValue("id", entry.getId())
                .addValue("name", entry.getName())
                .addValue("transportType", entry.getTransportType())
                .addValue("baseUrl", entry.getBaseUrl())
                .addValue("endpoint", entry.getEndpoint())
                .addValue("ssePath", entry.getSsePath())
                .addValue("streamablePath", entry.getStreamablePath())
                .addValue("healthPath", entry.getHealthPath())
                .addValue("verifyTls", entry.getVerifyTls())
                .addValue("connectTimeoutMs", entry.getConnectTimeoutMs())
                .addValue("readTimeoutMs", entry.getReadTimeoutMs())
                .addValue("authType", entry.getAuthType())
                .addValue("authConfig", entry.getAuthConfig())
                .addValue("clientId", entry.getClientId())
                .addValue("encryptedClientSecret", entry.getEncryptedClientSecret())
                .addValue("tokenUrl", entry.getTokenUrl())
                .addValue("authorizationUrl", entry.getAuthorizationUrl())
                .addValue("redirectUri", entry.getRedirectUri())
                .addValue("scopes", entry.getScopes())
                .addValue("encryptedAccessToken", entry.getEncryptedAccessToken())
                .addValue("encryptedRefreshToken", entry.getEncryptedRefreshToken())
                .addValue("tokenExpiresAt", entry.getTokenExpiresAt())
                .addValue("headersJson", entry.getHeadersJson())
                .addValue("queryJson", entry.getQueryJson())
                .addValue("lastVerifiedAt", entry.getLastVerifiedAt())
                .addValue("lastStatus", entry.getLastStatus())
                .addValue("lastError", entry.getLastError())
                .addValue("discoveredToolsJson", entry.getDiscoveredToolsJson())
                .addValue("discoveredToolsCount", entry.getDiscoveredToolsCount())
                .addValue("enabled", entry.isEnabled())
                .addValue("createdAt", entry.getCreatedAt())
                .addValue("updatedAt", entry.getUpdatedAt());
    }

    private McpRegistryEntry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return McpRegistryEntry.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .transportType(rs.getString("transport_type"))
                .baseUrl(rs.getString("base_url"))
                .endpoint(rs.getString("endpoint"))
                .ssePath(rs.getString("sse_path"))
                .streamablePath(rs.getString("streamable_path"))
                .healthPath(rs.getString("health_path"))
                .verifyTls(rs.getObject("verify_tls") == null ? null : rs.getBoolean("verify_tls"))
                .connectTimeoutMs(rs.getObject("connect_timeout_ms") == null ? null : rs.getInt("connect_timeout_ms"))
                .readTimeoutMs(rs.getObject("read_timeout_ms") == null ? null : rs.getInt("read_timeout_ms"))
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
                .headersJson(rs.getString("headers_json"))
                .queryJson(rs.getString("query_json"))
                .lastVerifiedAt((Long) rs.getObject("last_verified_at"))
                .lastStatus(rs.getString("last_status"))
                .lastError(rs.getString("last_error"))
                .discoveredToolsJson(rs.getString("discovered_tools_json"))
                .discoveredToolsCount(rs.getObject("discovered_tools_count") == null ? null : rs.getInt("discovered_tools_count"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build();
    }
}
