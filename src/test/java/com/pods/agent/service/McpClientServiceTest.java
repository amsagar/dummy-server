package com.pods.agent.service;

import com.pods.agent.config.McpOAuthDefaultsProperties;
import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.McpRegistryRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpClientServiceTest {

    @Test
    void blocksInvocationWhenCachedToolDisabled() {
        McpRegistryRepository mcpRepository = mock(McpRegistryRepository.class);
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);

        McpRegistryEntry server = McpRegistryEntry.builder()
                .id("srv-1")
                .name("Miro")
                .baseUrl("https://mcp.example.com")
                .endpoint("https://mcp.example.com")
                .streamablePath("/mcp")
                .enabled(true)
                .discoveredToolsJson("[{\"name\":\"boards.search\",\"enabled\":false}]")
                .build();
        when(mcpRepository.findById("srv-1")).thenReturn(Optional.of(server));

        McpAuthService authService = new McpAuthService(
                new ObjectMapper(),
                new EncryptionService(""),
                new McpOAuthDefaultsProperties(),
                mcpRepository
        );
        McpClientService service = new McpClientService(
                new ObjectMapper(),
                authService,
                mcpRepository,
                domainRepository,
                toolRepository,
                toolRegistryService
        );

        assertThrows(IllegalArgumentException.class, () -> service.callTool("srv-1", "boards.search", "{}"));
    }

    @Test
    void blocksInvocationWhenToolNotPresentInCache() {
        McpRegistryRepository mcpRepository = mock(McpRegistryRepository.class);
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);

        McpRegistryEntry server = McpRegistryEntry.builder()
                .id("srv-1")
                .name("Miro")
                .baseUrl("https://mcp.example.com")
                .endpoint("https://mcp.example.com")
                .streamablePath("/mcp")
                .enabled(true)
                .discoveredToolsJson("[{\"name\":\"boards.search\",\"enabled\":true}]")
                .build();
        when(mcpRepository.findById("srv-1")).thenReturn(Optional.of(server));

        McpAuthService authService = new McpAuthService(
                new ObjectMapper(),
                new EncryptionService(""),
                new McpOAuthDefaultsProperties(),
                mcpRepository
        );
        McpClientService service = new McpClientService(
                new ObjectMapper(),
                authService,
                mcpRepository,
                domainRepository,
                toolRepository,
                toolRegistryService
        );

        assertThrows(IllegalArgumentException.class, () -> service.callTool("srv-1", "code_widget_create", "{}"));
    }

    @Test
    void detectsJsonRpcErrorHintsInProbePayload() {
        String okBody = "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[]}}";
        String errBody = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Authentication required\"}}";
        assertFalse(McpClientService.hasJsonRpcErrorPayload(okBody));
        assertTrue(McpClientService.hasJsonRpcErrorPayload(errBody));
        assertTrue(McpClientService.containsAuthErrorHint("authentication required"));
    }

    @Test
    void extractsJsonPayloadFromSseDataRows() {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"x\"}]}}\n\n";
        String extracted = McpClientService.extractJsonFromSse(sse);
        assertTrue(extracted.contains("\"jsonrpc\""));
        assertTrue(extracted.contains("\"tools\""));
    }
}

