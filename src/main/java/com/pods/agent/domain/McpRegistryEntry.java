package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpRegistryEntry {
    private String id;
    private String name;
    private String transportType;
    private String baseUrl;
    private String endpoint;
    private String ssePath;
    private String streamablePath;
    private String healthPath;
    private Boolean verifyTls;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private String authType;
    private String authConfig;
    private String clientId;
    private String encryptedClientSecret;
    private String tokenUrl;
    private String authorizationUrl;
    private String redirectUri;
    private String scopes;
    private String encryptedAccessToken;
    private String encryptedRefreshToken;
    private Long tokenExpiresAt;
    private String headersJson;
    private String queryJson;
    private Long lastVerifiedAt;
    private String lastStatus;
    private String lastError;
    private String discoveredToolsJson;
    private Integer discoveredToolsCount;
    private boolean enabled;
    private Long createdAt;
    private Long updatedAt;
}
