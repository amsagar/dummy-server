package com.pods.agent.service;

import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.McpRegistryRepository;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.repository.ToolChainApprovalRepository;
import com.pods.agent.repository.ToolChainConfigLayoutRepository;
import com.pods.agent.repository.ToolChainConfigMessageRepository;
import com.pods.agent.repository.ToolChainConfigSessionRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.config.ModelProviderRouter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolChainConfigChatServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeAndValidateAcceptsExactRequiredKey() {
        ToolChainConfigChatService service = buildService();
        String graphJson = "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"get_order\",\"type\":\"tool\",\"config\":{\"toolName\":\"get_order\"}}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"get_order\"}]}";
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "string")),
                "required", List.of("id")
        );
        Map<String, ToolChainConfigChatService.ToolInputContract> contracts = Map.of(
                "get_order", new ToolChainConfigChatService.ToolInputContract(List.of("id"), List.of("id"))
        );

        ToolChainConfigChatService.GraphRequirementsResult result =
                service.normalizeAndValidateGraphRequirements(graphJson, contracts, inputSchema);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void normalizeAndValidateAutoMapsAliasForPathParam() throws Exception {
        ToolChainConfigChatService service = buildService();
        String graphJson = "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"get_order\",\"type\":\"tool\",\"config\":{\"toolName\":\"get_order\"}}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"get_order\"}]}";
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("orderId", Map.of("type", "string")),
                "required", List.of("orderId")
        );
        Map<String, ToolChainConfigChatService.ToolInputContract> contracts = Map.of(
                "get_order", new ToolChainConfigChatService.ToolInputContract(List.of("id"), List.of("id"))
        );

        ToolChainConfigChatService.GraphRequirementsResult result =
                service.normalizeAndValidateGraphRequirements(graphJson, contracts, inputSchema);

        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        Map<String, Object> normalized = objectMapper.readValue(result.normalizedGraphJson(), Map.class);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) normalized.get("nodes");
        Map<String, Object> getOrderNode = nodes.stream()
                .filter(n -> "get_order".equals(String.valueOf(n.get("id"))))
                .findFirst()
                .orElseThrow();
        Map<String, Object> config = (Map<String, Object>) getOrderNode.get("config");
        Map<String, Object> mappings = (Map<String, Object>) config.get("argMappings");
        assertEquals("orderId", String.valueOf(mappings.get("id")));
    }

    @Test
    void normalizeAndValidateFailsWhenRequiredParamUnresolved() {
        ToolChainConfigChatService service = buildService();
        String graphJson = "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"get_order\",\"type\":\"tool\",\"config\":{\"toolName\":\"get_order\"}}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"get_order\"}]}";
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("orderRef", Map.of("type", "string")),
                "required", List.of("orderRef")
        );
        Map<String, ToolChainConfigChatService.ToolInputContract> contracts = Map.of(
                "get_order", new ToolChainConfigChatService.ToolInputContract(List.of("id"), List.of("id"))
        );

        ToolChainConfigChatService.GraphRequirementsResult result =
                service.normalizeAndValidateGraphRequirements(graphJson, contracts, inputSchema);

        assertTrue(result.hasErrors());
    }

    private ToolChainConfigChatService buildService() {
        return new ToolChainConfigChatService(
                mock(ToolChainConfigSessionRepository.class),
                mock(ToolChainConfigMessageRepository.class),
                mock(ToolChainService.class),
                mock(ToolChainRunRepository.class),
                mock(ToolChainApprovalRepository.class),
                mock(ToolChainConfigLayoutRepository.class),
                mock(AgentToolRepository.class),
                mock(SkillRepository.class),
                mock(McpRegistryRepository.class),
                mock(ModelProviderRouter.class),
                mock(SkillRegistryService.class),
                objectMapper
        );
    }
}
