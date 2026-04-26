package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolAuthProfile {
    private String id;
    private String domainId;
    private String name;
    private String description;
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
    private boolean enabled;
    private long createdAt;
    private long updatedAt;
}
