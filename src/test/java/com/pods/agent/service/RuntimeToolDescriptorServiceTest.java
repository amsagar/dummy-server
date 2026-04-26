package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RuntimeToolDescriptorServiceTest {

    @Test
    void toCompactDescriptor_excludesLargeSchemaPayloads() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        RuntimeToolDescriptorService service = new RuntimeToolDescriptorService(registry, new ObjectMapper());
        AgentTool tool = AgentTool.builder()
                .id("tool-1")
                .name("inventoryLookup")
                .description("Very long description that should remain compact for ranking payloads and avoid schema bloat")
                .executionKind("http_proxy")
                .method("GET")
                .endpoint("/inventory/{id}")
                .requestSchema("{\"inputSchema\":{\"type\":\"object\",\"properties\":{\"productId\":{\"type\":\"string\"}}}}")
                .responseSchema("{\"type\":\"object\"}")
                .build();

        var compact = service.toCompactDescriptor(tool);

        assertTrue(compact.containsKey("name"));
        assertFalse(compact.containsKey("inputSchema"));
        assertFalse(compact.containsKey("outputSchema"));
        assertTrue(compact.containsKey("requiredArgs"));
        assertEquals(0, compact.get("requiredArgCount"));
    }

    @Test
    void toCompactDescriptor_includesSourceDomainAndRequiredArgs() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        RuntimeToolDescriptorService service = new RuntimeToolDescriptorService(registry, new ObjectMapper());
        AgentTool tool = AgentTool.builder()
                .id("tool-2")
                .domainId("domain-1")
                .name("createOrder")
                .sourceType("mcp")
                .requestSchema("{\"inputSchema\":{\"type\":\"object\",\"required\":[\"orgId\",\"productId\"]}}")
                .build();

        var compact = service.toCompactDescriptor(tool);
        assertEquals("domain-1", compact.get("domainId"));
        assertEquals("mcp", compact.get("sourceType"));
        assertEquals(2, compact.get("requiredArgCount"));
    }
}
