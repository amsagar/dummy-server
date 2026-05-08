package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.domain.ToolChainConfigLayout;
import com.pods.agent.domain.ToolChainConfigSession;
import com.pods.agent.domain.ToolChainUserLayout;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.ChatSessionRepository;
import com.pods.agent.repository.McpRegistryRepository;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.repository.ToolChainApprovalRepository;
import com.pods.agent.repository.ToolChainConfigLayoutRepository;
import com.pods.agent.repository.ToolChainConfigMessageRepository;
import com.pods.agent.repository.ToolChainConfigSessionRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainUserLayoutRepository;
import com.pods.agent.config.ModelProviderRouter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void upsertSessionLayoutKeepsViewportWhenPatchOmitsViewport() {
        ToolChainConfigSessionRepository sessionRepository = mock(ToolChainConfigSessionRepository.class);
        ToolChainConfigLayoutRepository layoutRepository = mock(ToolChainConfigLayoutRepository.class);
        ToolChainConfigSession session = ToolChainConfigSession.builder()
                .id("session-1")
                .toolChainId("tc-1")
                .build();
        ToolChainConfigLayout existingLayout = ToolChainConfigLayout.builder()
                .id("layout-1")
                .toolChainId("tc-1")
                .sessionId("session-1")
                .userId("user-1")
                .positionsJson("{\"node-1\":{\"x\":10,\"y\":20}}")
                .viewportJson("{\"zoom\":1.2}")
                .build();
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(layoutRepository.findByScope("tc-1", "session-1", "user-1")).thenReturn(Optional.of(existingLayout));

        ToolChainConfigChatService service = buildService(sessionRepository, layoutRepository, mock(ToolChainUserLayoutRepository.class), mock(ToolChainService.class));
        ToolChainDtos.ToolChainConfigSessionLayoutRequest request = new ToolChainDtos.ToolChainConfigSessionLayoutRequest();
        request.setPositions(Map.of("node-1", Map.of("x", 35, "y", 45)));

        Map<String, Object> response = service.upsertSessionLayout("tc-1", "session-1", request, "user-1");

        Map<String, Object> viewport = (Map<String, Object>) response.get("viewport");
        assertEquals(1.2d, Double.parseDouble(String.valueOf(viewport.get("zoom"))), 0.0001d);
        verify(layoutRepository).upsert(any(ToolChainConfigLayout.class));
    }

    @Test
    void upsertUserLayoutKeepsPositionsWhenPatchOmitsPositions() {
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainUserLayoutRepository userLayoutRepository = mock(ToolChainUserLayoutRepository.class);
        when(toolChainService.getRequired("tc-1")).thenReturn(ToolChain.builder().id("tc-1").build());
        when(userLayoutRepository.findByScope("tc-1", "user-1")).thenReturn(Optional.of(
                ToolChainUserLayout.builder()
                        .id("layout-1")
                        .toolChainId("tc-1")
                        .userId("user-1")
                        .positionsJson("{\"node-a\":{\"x\":5,\"y\":15}}")
                        .viewportJson("{\"zoom\":1.1}")
                        .build()
        ));

        ToolChainConfigChatService service = buildService(mock(ToolChainConfigSessionRepository.class), mock(ToolChainConfigLayoutRepository.class), userLayoutRepository, toolChainService);
        ToolChainDtos.ToolChainConfigSessionLayoutRequest request = new ToolChainDtos.ToolChainConfigSessionLayoutRequest();
        request.setViewport(Map.of("x", 25, "y", 40, "zoom", 2.0));

        Map<String, Object> response = service.upsertUserLayout("tc-1", request, "user-1");

        Map<String, Object> positions = (Map<String, Object>) response.get("positions");
        Map<String, Object> nodeA = (Map<String, Object>) positions.get("node-a");
        assertEquals(5.0d, Double.parseDouble(String.valueOf(nodeA.get("x"))), 0.0001d);
        assertEquals(15.0d, Double.parseDouble(String.valueOf(nodeA.get("y"))), 0.0001d);
        verify(userLayoutRepository).upsert(any(ToolChainUserLayout.class));
    }

    @Test
    void canonicalizeGraphToolNodeTypesCorrectsMismatchedToolAndMcpTypes() throws Exception {
        ToolChainConfigChatService service = buildService();
        String graphJson = """
                {
                  "nodes": [
                    {"id":"n1","type":"mcp_tool","config":{"toolName":"get_products"}},
                    {"id":"n2","type":"tool","config":{"toolName":"mcp_fetch_orders"}}
                  ],
                  "edges": []
                }
                """;

        String normalized = service.canonicalizeGraphToolNodeTypes(
                graphJson,
                Set.of("get_products"),
                Set.of("mcp_fetch_orders")
        );

        Map<String, Object> parsed = objectMapper.readValue(normalized, Map.class);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) parsed.get("nodes");
        Map<String, String> typesById = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> String.valueOf(row.get("id")),
                        row -> String.valueOf(row.get("type"))
                ));
        assertEquals("tool", typesById.get("n1"));
        assertEquals("mcp_tool", typesById.get("n2"));
    }

    @Test
    void canonicalizeGraphToolNodeTypesPreservesAmbiguousAndUnknownTypes() throws Exception {
        ToolChainConfigChatService service = buildService();
        String graphJson = """
                {
                  "nodes": [
                    {"id":"a","type":"mcp_tool","config":{"toolName":"shared_name"}},
                    {"id":"b","type":"mcp_tool","config":{"toolName":"missing_name"}}
                  ],
                  "edges": []
                }
                """;

        String normalized = service.canonicalizeGraphToolNodeTypes(
                graphJson,
                Set.of("shared_name"),
                Set.of("shared_name")
        );

        Map<String, Object> parsed = objectMapper.readValue(normalized, Map.class);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) parsed.get("nodes");
        Map<String, String> typesById = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> String.valueOf(row.get("id")),
                        row -> String.valueOf(row.get("type"))
                ));
        assertEquals("mcp_tool", typesById.get("a"));
        assertEquals("mcp_tool", typesById.get("b"));
    }

    private ToolChainConfigChatService buildService() {
        return buildService(
                mock(ToolChainConfigSessionRepository.class),
                mock(ToolChainConfigLayoutRepository.class),
                mock(ToolChainUserLayoutRepository.class),
                mock(ToolChainService.class)
        );
    }

    private ToolChainConfigChatService buildService(ToolChainConfigSessionRepository sessionRepository,
                                                    ToolChainConfigLayoutRepository layoutRepository,
                                                    ToolChainUserLayoutRepository userLayoutRepository,
                                                    ToolChainService toolChainService) {
        return new ToolChainConfigChatService(
                sessionRepository,
                mock(ToolChainConfigMessageRepository.class),
                toolChainService,
                mock(ToolChainRunRepository.class),
                mock(ToolChainApprovalRepository.class),
                layoutRepository,
                userLayoutRepository,
                mock(AgentToolRepository.class),
                mock(ChatSessionRepository.class),
                mock(SkillRepository.class),
                mock(McpRegistryRepository.class),
                mock(ModelProviderRouter.class),
                mock(SkillRegistryService.class),
                mock(AgentRuntimeService.class),
                mock(PendingInteractionService.class),
                mock(SessionWorkspaceService.class),
                mock(WorkspaceSkillSyncService.class),
                new RuntimeTuningProperties(),
                objectMapper
        );
    }
}
