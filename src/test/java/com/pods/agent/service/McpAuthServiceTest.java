package com.pods.agent.service;

import com.pods.agent.config.McpOAuthDefaultsProperties;
import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.repository.McpRegistryRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class McpAuthServiceTest {

    @Test
    void reportsMissingOauthAuthorizationFields() {
        McpAuthService service = new McpAuthService(
                new ObjectMapper(),
                new EncryptionService(""),
                new McpOAuthDefaultsProperties(),
                mock(McpRegistryRepository.class)
        );
        McpRegistryEntry server = McpRegistryEntry.builder()
                .authorizationUrl("https://mcp.example.com/auth")
                .build();

        List<String> missing = service.missingAuthCodeAuthorizationFields(server);
        assertEquals(2, missing.size());
        assertTrue(missing.contains("clientId"));
        assertTrue(missing.contains("redirectUri"));
    }

    @Test
    void appliesOauthDefaultsWithoutPromptingUserInputs() {
        McpOAuthDefaultsProperties defaults = new McpOAuthDefaultsProperties();
        defaults.setDefaultClientId("global-client");
        defaults.setDefaultRedirectUri("http://localhost:5173/oauth2/callback");
        defaults.setDefaultAuthorizationUrl("https://mcp.example.com/oauth/authorize");
        defaults.setDefaultTokenUrl("https://mcp.example.com/oauth/token");
        defaults.setDefaultScopes("boards:read");

        McpAuthService service = new McpAuthService(
                new ObjectMapper(),
                new EncryptionService(""),
                defaults,
                mock(McpRegistryRepository.class)
        );
        McpRegistryEntry server = McpRegistryEntry.builder().build();
        service.applyAuthCodeDefaults(server);

        assertEquals("global-client", server.getClientId());
        assertEquals("http://localhost:5173/oauth2/callback", server.getRedirectUri());
        assertEquals("https://mcp.example.com/oauth/authorize", server.getAuthorizationUrl());
        assertEquals("https://mcp.example.com/oauth/token", server.getTokenUrl());
        assertEquals("boards:read", server.getScopes());
    }
}

