package com.pods.agent.service;

import com.pods.agent.agent.AgentOrchestrator;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.tool.AgentToolCallbackFactory;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.RuntimeTraceRepository;
import com.pods.agent.service.mcp.McpRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeServiceDynamicExposureTest {

    @Test
    void policy_baseOnlyInjection_usesFrameworkDefaultsBeforeCatalogSelection() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.streamTurn(any(), any(), any(), any(), any())).thenReturn("ok");

        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        List<AgentTool> baseTools = List.of(
                AgentTool.builder().id("b-1").name("memoryview").sourceType("framework_default").enabled(true).build(),
                AgentTool.builder().id("b-2").name("read").sourceType("framework_default").enabled(true).build()
        );
        List<AgentTool> nonDefaultTools = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> AgentTool.builder()
                        .id("n-" + i)
                        .name("inventory_tool_" + i)
                        .sourceType("mcp")
                        .description("inventory lookup")
                        .enabled(true)
                        .build())
                .toList();
        when(toolRegistryService.getBaseInjectedTools()).thenReturn(baseTools);
        when(toolRegistryService.getNonDefaultEnabledTools()).thenReturn(nonDefaultTools);
        when(toolRegistryService.getEnabledTools()).thenReturn(baseTools);

        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        RuntimeEventRepository eventRepository = mock(RuntimeEventRepository.class);
        ModelAutoRouterService modelAutoRouterService = mock(ModelAutoRouterService.class);
        ModelProviderRouter modelProviderRouter = mock(ModelProviderRouter.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of());
        RuntimeHookRegistryService hookRegistryService = mock(RuntimeHookRegistryService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        RuntimeTraceRepository traceRepository = mock(RuntimeTraceRepository.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        RuntimeToolDescriptorService runtimeToolDescriptorService = mock(RuntimeToolDescriptorService.class);
        when(runtimeToolDescriptorService.toCompactDescriptor(any())).thenReturn(Map.of("name", "tool"));
        McpRuntimeAdapter mcpRuntimeAdapter = mock(McpRuntimeAdapter.class);
        when(mcpRuntimeAdapter.listRuntimeTools()).thenReturn(List.of());
        ContextSummarizationService summarizationService = mock(ContextSummarizationService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.loadToolSignals(any())).thenReturn(Map.of());
        when(memoryService.loadToolDomainSignals(any())).thenReturn(Map.of());
        when(memoryService.loadSkillSignals(any())).thenReturn(Map.of());

        AgentToolCallbackFactory callbackFactory = mock(AgentToolCallbackFactory.class);
        when(callbackFactory.buildForTurn(any(), any(), any(), any())).thenReturn(List.<ToolCallback>of());

        RuntimeTuningProperties props = new RuntimeTuningProperties();
        props.setDynamicToolExposureEnabled(true);
        props.setToolShortlistDefaultSize(5);
        props.setQualityExpansionMaxRetries(0);
        props.setBaseOnlyDefaultToolInjectionEnabled(true);

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
                props,
                new ObjectMapper(),
                memoryService,
                callbackFactory
        );

        service.runTurn(new AgentSession("s-short"), "inventory for sku 100", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> shortlistCaptor = ArgumentCaptor.forClass(List.class);
        verify(callbackFactory).buildForTurn(any(), any(), any(), shortlistCaptor.capture());
        assertTrue(shortlistCaptor.getValue().size() == 2);
        assertTrue(shortlistCaptor.getValue().stream().allMatch(t -> "framework_default".equalsIgnoreCase(t.getSourceType())));
    }

    @Test
    void providerSafetyCap_appliesEvenWhenDynamicExposureDisabled() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.streamTurn(any(), any(), any(), any(), any())).thenReturn("ok");

        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        List<AgentTool> tools = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> AgentTool.builder()
                        .id("t-" + i)
                        .name("tool_" + i)
                        .sourceType("framework_default")
                        .description("generic tool")
                        .enabled(true)
                        .build())
                .toList();
        when(toolRegistryService.getBaseInjectedTools()).thenReturn(tools);
        when(toolRegistryService.getNonDefaultEnabledTools()).thenReturn(List.of());
        when(toolRegistryService.getEnabledTools()).thenReturn(tools);

        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        RuntimeEventRepository eventRepository = mock(RuntimeEventRepository.class);
        ModelAutoRouterService modelAutoRouterService = mock(ModelAutoRouterService.class);
        ModelProviderRouter modelProviderRouter = mock(ModelProviderRouter.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of());
        RuntimeHookRegistryService hookRegistryService = mock(RuntimeHookRegistryService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        RuntimeTraceRepository traceRepository = mock(RuntimeTraceRepository.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        RuntimeToolDescriptorService runtimeToolDescriptorService = mock(RuntimeToolDescriptorService.class);
        when(runtimeToolDescriptorService.toCompactDescriptor(any())).thenReturn(Map.of("name", "tool"));
        McpRuntimeAdapter mcpRuntimeAdapter = mock(McpRuntimeAdapter.class);
        when(mcpRuntimeAdapter.listRuntimeTools()).thenReturn(List.of());
        ContextSummarizationService summarizationService = mock(ContextSummarizationService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.loadToolSignals(any())).thenReturn(Map.of());
        when(memoryService.loadToolDomainSignals(any())).thenReturn(Map.of());
        when(memoryService.loadSkillSignals(any())).thenReturn(Map.of());

        AgentToolCallbackFactory callbackFactory = mock(AgentToolCallbackFactory.class);
        when(callbackFactory.buildForTurn(any(), any(), any(), any())).thenReturn(List.<ToolCallback>of());

        RuntimeTuningProperties props = new RuntimeTuningProperties();
        props.setDynamicToolExposureEnabled(false);
        props.setBaseOnlyDefaultToolInjectionEnabled(false);
        props.setMaxToolCallbacksPerTurn(120);
        props.setQualityExpansionMaxRetries(0);

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
                props,
                new ObjectMapper(),
                memoryService,
                callbackFactory
        );

        service.runTurn(new AgentSession("s-cap"), "get product organizations", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> shortlistCaptor = ArgumentCaptor.forClass(List.class);
        verify(callbackFactory).buildForTurn(any(), any(), any(), shortlistCaptor.capture());
        assertTrue(shortlistCaptor.getValue().size() <= 120);
    }

    @Test
    void qualityGate_retryExpandsCatalogShortlistOnce() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.streamTurn(any(), any(), any(), any(), any()))
                .thenReturn("not enough evidence")
                .thenReturn("not enough evidence")
                .thenReturn("grounded answer");

        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        List<AgentTool> baseTools = List.of(
                AgentTool.builder().id("b-1").name("memoryview").sourceType("framework_default").enabled(true).build(),
                AgentTool.builder().id("b-2").name("read").sourceType("framework_default").enabled(true).build()
        );
        List<AgentTool> nonDefaultTools = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> AgentTool.builder()
                        .id("n-" + i)
                        .name("repo_tool_" + i)
                        .sourceType("mcp")
                        .description("repo utility")
                        .enabled(true)
                        .build())
                .toList();
        when(toolRegistryService.getBaseInjectedTools()).thenReturn(baseTools);
        when(toolRegistryService.getNonDefaultEnabledTools()).thenReturn(nonDefaultTools);
        when(toolRegistryService.getEnabledTools()).thenReturn(baseTools);

        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        RuntimeEventRepository eventRepository = mock(RuntimeEventRepository.class);
        ModelAutoRouterService modelAutoRouterService = mock(ModelAutoRouterService.class);
        ModelProviderRouter modelProviderRouter = mock(ModelProviderRouter.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of());
        RuntimeHookRegistryService hookRegistryService = mock(RuntimeHookRegistryService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        RuntimeTraceRepository traceRepository = mock(RuntimeTraceRepository.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        RuntimeToolDescriptorService runtimeToolDescriptorService = mock(RuntimeToolDescriptorService.class);
        when(runtimeToolDescriptorService.toCompactDescriptor(any())).thenReturn(Map.of("name", "tool"));
        McpRuntimeAdapter mcpRuntimeAdapter = mock(McpRuntimeAdapter.class);
        when(mcpRuntimeAdapter.listRuntimeTools()).thenReturn(List.of());
        ContextSummarizationService summarizationService = mock(ContextSummarizationService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.loadToolSignals(any())).thenReturn(Map.of());
        when(memoryService.loadToolDomainSignals(any())).thenReturn(Map.of());
        when(memoryService.loadSkillSignals(any())).thenReturn(Map.of());

        AgentToolCallbackFactory callbackFactory = mock(AgentToolCallbackFactory.class);
        when(callbackFactory.buildForTurn(any(), any(), any(), any())).thenReturn(List.<ToolCallback>of());

        RuntimeTuningProperties props = new RuntimeTuningProperties();
        props.setDynamicToolExposureEnabled(true);
        props.setBaseOnlyDefaultToolInjectionEnabled(true);
        props.setToolShortlistDefaultSize(3);
        props.setToolShortlistExpandedSize(8);
        props.setQualityExpansionMaxRetries(1);

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
                props,
                new ObjectMapper(),
                memoryService,
                callbackFactory
        );

        service.runTurn(new AgentSession("s-retry"), "repo risk summary", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> shortlistCaptor = ArgumentCaptor.forClass(List.class);
        verify(callbackFactory, times(3)).buildForTurn(any(), any(), any(), shortlistCaptor.capture());
        List<List<AgentTool>> rounds = shortlistCaptor.getAllValues();
        assertTrue(rounds.get(0).size() == 2);
        assertTrue(rounds.get(1).size() <= 5);
        assertTrue(rounds.get(2).size() <= 10);
        assertTrue(rounds.get(2).size() >= rounds.get(1).size());
    }
}
