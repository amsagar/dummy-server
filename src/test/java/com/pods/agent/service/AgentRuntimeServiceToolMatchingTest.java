package com.pods.agent.service;

import com.pods.agent.agent.AgentOrchestrator;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.tool.AgentToolCallbackFactory;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.Skill;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.RuntimeTraceRepository;
import com.pods.agent.service.mcp.McpRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

class AgentRuntimeServiceToolMatchingTest {

    @Test
    void autoPicksInventoryToolWhenConfidenceIsStrong() {
        AgentTool inventory = AgentTool.builder()
                .id("t_inventory")
                .name("getInventory")
                .description("fetch inventory by sku and location")
                .method("GET")
                .endpoint("/inventory")
                .enabled(true)
                .build();
        AgentTool orders = AgentTool.builder()
                .id("t_orders")
                .name("getOrders")
                .description("fetch customer order status")
                .enabled(true)
                .build();

        AgentRuntimeService service = buildService(
                List.of(inventory, orders),
                props(25, 8)
        );

        AgentSession session = new AgentSession("s-auto");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        service.runTurn(session, "get me inventory for sku 1001", state, sender);

        verify(sender).sendToolMatch(eq("s-auto"), eq("getInventory"), any(Integer.class), eq(false), eq("auto_selected"), any(List.class));
    }

    @Test
    void asksClarificationWhenTopCandidatesAreAmbiguous() {
        AgentTool bySku = AgentTool.builder()
                .id("t1")
                .name("inventoryBySku")
                .description("inventory lookup by sku")
                .enabled(true)
                .build();
        AgentTool byLocation = AgentTool.builder()
                .id("t2")
                .name("inventoryByLocation")
                .description("inventory lookup by location")
                .enabled(true)
                .build();

        PendingInteractionService pending = mock(PendingInteractionService.class);
        when(pending.create(any(), any(), any(), any(), any())).thenReturn("req-amb-1");

        AgentRuntimeService service = buildService(
                List.of(bySku, byLocation),
                props(10, 30),
                pending
        );

        AgentSession session = new AgentSession("s-amb");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = service.runTurn(session, "get inventory details", state, sender);

        assertTrue(response.contains("multiple possible tools"));
        verify(sender).sendQuestion(eq("s-amb"), eq("req-amb-1"), contains("Which one should I run"), any());
    }

    @Test
    void asksClarificationWhenConfidenceIsBelowThreshold() {
        AgentTool generic = AgentTool.builder()
                .id("t_generic")
                .name("dataLookup")
                .description("lookup inventory data records")
                .enabled(true)
                .build();

        PendingInteractionService pending = mock(PendingInteractionService.class);
        when(pending.create(any(), any(), any(), any(), any())).thenReturn("req-low-1");

        AgentRuntimeService service = buildService(
                List.of(generic),
                props(80, 8),
                pending
        );

        AgentSession session = new AgentSession("s-low");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = service.runTurn(session, "inventory", state, sender);

        assertTrue(response.contains("multiple possible tools"));
        verify(sender).sendToolMatch(eq("s-low"), eq(null), any(Integer.class), eq(true), eq("low_confidence"), any(List.class));
    }

    @Test
    void ambiguityOrderingIsDeterministicWithLexicalTieBreak() {
        AgentTool toolA = AgentTool.builder()
                .id("t_a")
                .name("a_inventory")
                .description("inventory action")
                .enabled(true)
                .build();
        AgentTool toolZ = AgentTool.builder()
                .id("t_z")
                .name("z_inventory")
                .description("inventory action")
                .enabled(true)
                .build();

        PendingInteractionService pending = mock(PendingInteractionService.class);
        when(pending.create(any(), any(), any(), any(), any())).thenReturn("req-order");

        AgentRuntimeService service = buildService(
                List.of(toolZ, toolA),
                props(10, 15),
                pending
        );

        AgentSession session = new AgentSession("s-order");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        service.runTurn(session, "inventory tool", state, sender);

        verify(sender).sendQuestion(eq("s-order"), eq("req-order"), contains("(a_inventory, z_inventory)"), any());
    }

    @Test
    void returnsCapabilitiesFromRegistryWithoutOrchestratorCall() {
        ServiceFixture fixture = buildFixture(List.of(), props(25, 8), mock(PendingInteractionService.class));
        when(fixture.skillRegistryService().getEnabledSkills()).thenReturn(List.of(
                new SkillRegistryService.SkillSnapshot(
                        Skill.builder().id("s1").name("pdf").description("PDF operations").enabled(true).build(),
                        Map.of("SKILL.md", "pdf content")
                ),
                new SkillRegistryService.SkillSnapshot(
                        Skill.builder().id("s2").name("skill-creator").description("Create/improve skills").enabled(true).build(),
                        Map.of("SKILL.md", "skill creator content")
                )
        ));
        AgentSession session = new AgentSession("s-skills");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = fixture.service().runTurn(session, "What skills do you have available?", state, sender);

        assertTrue(response.contains("Available skills"));
        assertTrue(response.contains("pdf"));
        assertTrue(response.contains("skill-creator"));
        verify(fixture.orchestrator(), never()).streamTurn(any(), any(), any(), any(), any());
    }

    @Test
    void taskQueryBuildsSkillContextWithoutAbsoluteWorkspacePath() {
        RuntimeTuningProperties properties = props(25, 8);
        properties.setIncludeFullSkillFiles(true);
        ServiceFixture fixture = buildFixture(List.of(), properties, mock(PendingInteractionService.class));
        when(fixture.skillRegistryService().getEnabledSkills()).thenReturn(List.of(
                new SkillRegistryService.SkillSnapshot(
                        Skill.builder().id("s-pdf").name("pdf").description("Use for merge split and extract PDF tasks").enabled(true).build(),
                        Map.of(
                                "SKILL.md", "PDF instructions",
                                "reference.md", "extra details"
                        )
                )
        ));
        AgentSession session = new AgentSession("s-pdf");
        session.setWorkspacePath(Path.of("/tmp/session-workspace"));
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        fixture.service().runTurn(session, "Please merge pdf files for me", state, sender);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(fixture.orchestrator()).streamTurn(any(), userPrompt.capture(), any(), any(), any());
        String composedPrompt = userPrompt.getValue();
        assertTrue(composedPrompt.contains("<skill_content name=\"pdf\">"));
        assertTrue(composedPrompt.contains("workspace://skills"));
        assertTrue(!composedPrompt.contains("/var/"));
    }

    @Test
    void strictScopeRefusesOutOfScopeGeneralQuery() {
        RuntimeTuningProperties properties = props(25, 8);
        properties.setStrictScopeOnly(true);
        properties.setOutOfScopeRefusalMessage("I can’t answer this because it is outside my allowed skills/tools scope.");
        ServiceFixture fixture = buildFixture(List.of(), properties, mock(PendingInteractionService.class));
        AgentSession session = new AgentSession("s-strict-general");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = fixture.service().runTurn(session, "Who is the president of mars?", state, sender);

        assertEquals("I can’t answer this because it is outside my allowed skills/tools scope.", response);
        verify(fixture.orchestrator(), never()).streamTurn(any(), any(), any(), any(), any());
    }

    @Test
    void strictScopeRefusesGenericCodingQuery() {
        RuntimeTuningProperties properties = props(25, 8);
        properties.setStrictScopeOnly(true);
        properties.setOutOfScopeRefusalMessage("I can’t answer this because it is outside my allowed skills/tools scope.");
        ServiceFixture fixture = buildFixture(List.of(), properties, mock(PendingInteractionService.class));
        AgentSession session = new AgentSession("s-strict-coding");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = fixture.service().runTurn(session, "Write a Java method to reverse a string.", state, sender);

        assertEquals("I can’t answer this because it is outside my allowed skills/tools scope.", response);
        verify(fixture.orchestrator(), never()).streamTurn(any(), any(), any(), any(), any());
    }

    @Test
    void strictScopeStillExecutesInScopeToolQuery() {
        AgentTool inventory = AgentTool.builder()
                .id("t_inventory")
                .name("getInventory")
                .description("fetch inventory by sku and location")
                .method("GET")
                .endpoint("/inventory")
                .enabled(true)
                .build();
        RuntimeTuningProperties properties = props(25, 8);
        properties.setStrictScopeOnly(true);
        ServiceFixture fixture = buildFixture(List.of(inventory), properties, mock(PendingInteractionService.class));
        AgentSession session = new AgentSession("s-strict-in-scope");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = fixture.service().runTurn(session, "Get inventory for sku 1001", state, sender);

        assertEquals("orchestrator-response", response);
        verify(sender).sendToolMatch(eq("s-strict-in-scope"), eq("getInventory"), any(Integer.class), eq(false), any(), any(List.class));
        verify(fixture.orchestrator()).streamTurn(any(), any(), any(), any(), any());
    }

    @Test
    void strictScopeBlocksAutoWebsearchForGeneralQuery() {
        AgentTool websearch = AgentTool.builder()
                .id("t_websearch")
                .name("websearch")
                .description("Search the web")
                .executionKind("web")
                .enabled(true)
                .build();
        RuntimeTuningProperties properties = props(1, 1);
        properties.setStrictScopeOnly(true);
        properties.setOutOfScopeRefusalMessage("I can’t answer this because it is outside my allowed skills/tools scope.");
        ServiceFixture fixture = buildFixture(List.of(websearch), properties, mock(PendingInteractionService.class));
        AgentSession session = new AgentSession("s-strict-web");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = fixture.service().runTurn(session, "who is the ipl 2025 winner?", state, sender);

        assertEquals("I can’t answer this because it is outside my allowed skills/tools scope.", response);
        verify(sender, never()).sendToolCall(any(), any(), any());
        verify(fixture.orchestrator(), never()).streamTurn(any(), any(), any(), any(), any());
    }

    @Test
    void strictScopeBlocksGenericCodingEvenWithAutoMatchedMcpTool() {
        AgentTool mcpCodeTool = AgentTool.builder()
                .id("t_mcp_code")
                .name("mcp_code_widget_create")
                .description("Generate code widgets")
                .executionKind("integration")
                .enabled(true)
                .build();
        RuntimeTuningProperties properties = props(1, 1);
        properties.setStrictScopeOnly(true);
        properties.setOutOfScopeRefusalMessage("I can’t answer this because it is outside my allowed skills/tools scope.");
        ServiceFixture fixture = buildFixture(List.of(mcpCodeTool), properties, mock(PendingInteractionService.class));
        AgentSession session = new AgentSession("s-strict-mcp-code");
        ChatState state = ChatState.builder().runtimeMode("planner_worker").build();
        SseEventSender sender = mock(SseEventSender.class);

        String response = fixture.service().runTurn(session, "i need python snake game code", state, sender);

        assertEquals("I can’t answer this because it is outside my allowed skills/tools scope.", response);
        verify(sender, never()).sendToolCall(any(), any(), any());
        verify(fixture.orchestrator(), never()).streamTurn(any(), any(), any(), any(), any());
    }

    private AgentRuntimeService buildService(List<AgentTool> enabledTools,
                                             RuntimeTuningProperties properties) {
        return buildService(enabledTools, properties, mock(PendingInteractionService.class));
    }

    private AgentRuntimeService buildService(List<AgentTool> enabledTools,
                                             RuntimeTuningProperties properties,
                                             PendingInteractionService pendingInteractionService) {
        return buildFixture(enabledTools, properties, pendingInteractionService).service();
    }

    private ServiceFixture buildFixture(List<AgentTool> enabledTools,
                                        RuntimeTuningProperties properties,
                                        PendingInteractionService pendingInteractionService) {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.chat(any(), any(), any(), any())).thenReturn("orchestrator-response");
        when(orchestrator.streamTurn(any(), any(), any(), any(), any())).thenReturn("orchestrator-response");

        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        when(toolRegistryService.getEnabledTools()).thenReturn(enabledTools);

        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        when(policyEngine.evaluateTool(any())).thenReturn(new GuardrailPolicyEngine.Decision("allow", "test"));

        RuntimeEventRepository eventRepository = mock(RuntimeEventRepository.class);
        RuntimeTraceRepository traceRepository = mock(RuntimeTraceRepository.class);
        ModelAutoRouterService modelAutoRouterService = mock(ModelAutoRouterService.class);
        ModelProviderRouter modelProviderRouter = mock(ModelProviderRouter.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of());
        RuntimeHookRegistryService hookRegistryService = mock(RuntimeHookRegistryService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.execute(any(), any())).thenReturn(new ToolExecutionService.ExecutionResult(true, "{\"ok\":true}", null));
        RuntimeToolDescriptorService runtimeToolDescriptorService = mock(RuntimeToolDescriptorService.class);
        McpRuntimeAdapter mcpRuntimeAdapter = mock(McpRuntimeAdapter.class);
        when(mcpRuntimeAdapter.listRuntimeTools()).thenReturn(List.of());
        ContextSummarizationService summarizationService = mock(ContextSummarizationService.class);
        MemoryService memoryService = mock(MemoryService.class);
        AgentToolCallbackFactory agentToolCallbackFactory = mock(AgentToolCallbackFactory.class);
        when(agentToolCallbackFactory.buildForTurn(any(), any(), any(), any())).thenReturn(List.of());

        AgentRuntimeService service = new AgentRuntimeService(
                orchestrator,
                toolRegistryService,
                policyEngine,
                eventRepository,
                modelAutoRouterService,
                modelProviderRouter,
                skillRegistryService,
                hookRegistryService,
                toolExecutionService,
                traceRepository,
                pendingInteractionService,
                runtimeToolDescriptorService,
                mcpRuntimeAdapter,
                summarizationService,
                properties,
                new ObjectMapper(),
                memoryService,
                agentToolCallbackFactory
        );
        return new ServiceFixture(service, orchestrator, skillRegistryService);
    }

    private record ServiceFixture(
            AgentRuntimeService service,
            AgentOrchestrator orchestrator,
            SkillRegistryService skillRegistryService
    ) {}

    private RuntimeTuningProperties props(int minScore, int ambiguityDelta) {
        RuntimeTuningProperties properties = new RuntimeTuningProperties();
        properties.setToolAutoPickMinScore(minScore);
        properties.setToolAutoPickAmbiguityDelta(ambiguityDelta);
        return properties;
    }
}
