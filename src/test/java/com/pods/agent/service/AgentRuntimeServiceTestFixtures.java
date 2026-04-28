package com.pods.agent.service;

import com.pods.agent.agent.AgentOrchestrator;
import com.pods.agent.agent.tool.AgentToolCallbackFactory;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.RuntimeTraceRepository;
import com.pods.agent.service.mcp.McpRuntimeAdapter;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AgentRuntimeServiceTestFixtures {

    private AgentRuntimeServiceTestFixtures() {}

    static class Fixture {
        AgentOrchestrator orchestrator;
        ToolRegistryService toolRegistryService;
        GuardrailPolicyEngine policyEngine;
        RuntimeEventRepository runtimeEventRepository;
        ModelAutoRouterService modelAutoRouterService;
        ModelProviderRouter modelProviderRouter;
        SkillRegistryService skillRegistryService;
        RuntimeHookRegistryService hookRegistryService;
        ToolExecutionService toolExecutionService;
        RuntimeTraceRepository runtimeTraceRepository;
        PendingInteractionService pendingInteractionService;
        RuntimeToolDescriptorService runtimeToolDescriptorService;
        McpRuntimeAdapter mcpRuntimeAdapter;
        ContextSummarizationService summarizationService;
        RuntimeTuningProperties props;
        ObjectMapper objectMapper;
        MemoryService memoryService;
        AgentToolCallbackFactory callbackFactory;
        ToolEmbeddingIndexService toolEmbeddingIndexService;
        EmbeddingAutoRouterService embeddingAutoRouterService;
    }

    @SuppressWarnings("unchecked")
    static Fixture build() {
        Fixture f = new Fixture();
        f.orchestrator = mock(AgentOrchestrator.class);
        when(f.orchestrator.streamTurn(any(), any(), any(), any(), any(), any())).thenReturn("ok");
        f.toolRegistryService = mock(ToolRegistryService.class);
        f.policyEngine = mock(GuardrailPolicyEngine.class);
        f.runtimeEventRepository = mock(RuntimeEventRepository.class);
        f.modelAutoRouterService = mock(ModelAutoRouterService.class);
        f.modelProviderRouter = mock(ModelProviderRouter.class);
        f.skillRegistryService = mock(SkillRegistryService.class);
        when(f.skillRegistryService.getEnabledSkills()).thenReturn(List.of());
        f.hookRegistryService = mock(RuntimeHookRegistryService.class);
        f.toolExecutionService = mock(ToolExecutionService.class);
        f.runtimeTraceRepository = mock(RuntimeTraceRepository.class);
        f.pendingInteractionService = mock(PendingInteractionService.class);
        f.runtimeToolDescriptorService = mock(RuntimeToolDescriptorService.class);
        when(f.runtimeToolDescriptorService.toCompactDescriptor(any())).thenReturn(Map.of("name", "tool"));
        f.mcpRuntimeAdapter = mock(McpRuntimeAdapter.class);
        when(f.mcpRuntimeAdapter.listRuntimeTools()).thenReturn(List.of());
        f.summarizationService = mock(ContextSummarizationService.class);
        f.props = new RuntimeTuningProperties();
        f.objectMapper = new ObjectMapper();
        f.memoryService = mock(MemoryService.class);
        when(f.memoryService.loadToolSignals(any())).thenReturn(Map.of());
        when(f.memoryService.loadToolDomainSignals(any())).thenReturn(Map.of());
        when(f.memoryService.loadSkillSignals(any())).thenReturn(Map.of());
        f.callbackFactory = mock(AgentToolCallbackFactory.class);
        when(f.callbackFactory.buildForTurn(any(), any(), any(), any())).thenReturn(List.<ToolCallback>of());
        when(f.callbackFactory.buildForTurn(any(), any(), any(), any(), any())).thenReturn(List.<ToolCallback>of());
        f.toolEmbeddingIndexService = mock(ToolEmbeddingIndexService.class);
        f.embeddingAutoRouterService = mock(EmbeddingAutoRouterService.class);
        return f;
    }

    static AgentRuntimeService create(Fixture f) {
        return new AgentRuntimeService(
                f.orchestrator,
                f.toolRegistryService,
                f.policyEngine,
                f.runtimeEventRepository,
                f.modelAutoRouterService,
                f.modelProviderRouter,
                f.skillRegistryService,
                f.hookRegistryService,
                f.toolExecutionService,
                f.runtimeTraceRepository,
                f.pendingInteractionService,
                f.runtimeToolDescriptorService,
                f.mcpRuntimeAdapter,
                f.summarizationService,
                f.props,
                f.objectMapper,
                f.memoryService,
                f.callbackFactory,
                f.toolEmbeddingIndexService,
                f.embeddingAutoRouterService
        );
    }
}
