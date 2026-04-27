package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTool {
    private String id;
    private String domainId;
    private String name;
    private String description;
    private String sourceType;
    private String executionKind;
    private String permissionScope;
    private boolean requiresApproval;
    private String modelGate;
    private String providerGate;
    private boolean experimental;
    private int inputSchemaVersion;
    private String method;
    private String host;
    private String endpoint;
    private String requestSchema;
    private String responseSchema;
    private String sampleRequest;
    private String sampleResponse;
    private String authProfileId;
    private Boolean authOverrideEnabled;
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
    private boolean baseInjected;
    private long createdAt;
    private long updatedAt;
}
