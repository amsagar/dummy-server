package com.pods.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "pods.mcp.oauth")
public class McpOAuthDefaultsProperties {
    private String defaultClientId;
    private String defaultClientSecret;
    private String defaultRedirectUri;
    private String defaultScopes;
    private String defaultAuthorizationUrl;
    private String defaultTokenUrl;
}

