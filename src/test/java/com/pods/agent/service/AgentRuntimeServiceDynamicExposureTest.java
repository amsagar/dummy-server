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
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
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
    void retrieval_drivenSelection_includesFrameworkAndRetrievedTools() {
        AgentRuntimeServiceTestFixtures.Fixture f = AgentRuntimeServiceTestFixtures.build();
        List<AgentTool> baseTools = List.of(
                AgentTool.builder().id("b-1").name("memoryview").sourceType("framework_default").enabled(true).build(),
                AgentTool.builder().id("b-2").name("read").sourceType("framework_default").enabled(true).build()
        );
        List<AgentTool> nonDefaultTools = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> AgentTool.builder()
                        .id("n-" + i).name("inv_" + i).sourceType("mcp").enabled(true).build())
                .toList();
        when(f.toolRegistryService.getBaseInjectedTools()).thenReturn(baseTools);
        when(f.toolRegistryService.getNonDefaultEnabledTools()).thenReturn(nonDefaultTools);
        java.util.List<AgentTool> all = new java.util.ArrayList<>(baseTools);
        all.addAll(nonDefaultTools);
        when(f.toolRegistryService.getEnabledTools()).thenReturn(all);

        // No embedding model configured → retrievalAttempted=false → fallback path is used.
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(null);

        f.props.setMaxToolCallbacksPerTurn(120);

        AgentRuntimeService service = AgentRuntimeServiceTestFixtures.create(f);
        service.runTurn(new AgentSession("s-1"), "inventory for sku 100", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> shortlistCaptor = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), shortlistCaptor.capture(), any());
        List<AgentTool> exposed = shortlistCaptor.getValue();
        // Framework defaults always present
        assertTrue(exposed.stream().anyMatch(t -> "b-1".equals(t.getId())));
        assertTrue(exposed.stream().anyMatch(t -> "b-2".equals(t.getId())));
    }

    @Test
    void providerSafetyCap_appliesToCombinedSet() {
        AgentRuntimeServiceTestFixtures.Fixture f = AgentRuntimeServiceTestFixtures.build();
        List<AgentTool> framework = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> AgentTool.builder()
                        .id("fw-" + i).name("fw_" + i).sourceType("framework_default").enabled(true).build())
                .toList();
        List<AgentTool> nonDefault = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> AgentTool.builder()
                        .id("nd-" + i).name("nd_" + i).sourceType("mcp").enabled(true).build())
                .toList();
        when(f.toolRegistryService.getBaseInjectedTools()).thenReturn(framework);
        when(f.toolRegistryService.getNonDefaultEnabledTools()).thenReturn(nonDefault);
        java.util.List<AgentTool> all = new java.util.ArrayList<>(framework);
        all.addAll(nonDefault);
        when(f.toolRegistryService.getEnabledTools()).thenReturn(all);
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(null);

        f.props.setMaxToolCallbacksPerTurn(50);

        AgentRuntimeService service = AgentRuntimeServiceTestFixtures.create(f);
        service.runTurn(new AgentSession("s-cap"), "any", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> shortlistCaptor = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), shortlistCaptor.capture(), any());
        assertTrue(shortlistCaptor.getValue().size() <= 50);
    }

    @Test
    void singleStreamTurnInvocation_noRetryLoop() {
        AgentRuntimeServiceTestFixtures.Fixture f = AgentRuntimeServiceTestFixtures.build();
        when(f.toolRegistryService.getBaseInjectedTools()).thenReturn(List.of());
        when(f.toolRegistryService.getNonDefaultEnabledTools()).thenReturn(List.of());
        when(f.toolRegistryService.getEnabledTools()).thenReturn(List.of());
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(null);

        AgentRuntimeService service = AgentRuntimeServiceTestFixtures.create(f);
        service.runTurn(new AgentSession("s-x"), "i cannot access this thing", ChatState.builder().build(), mock(SseEventSender.class));

        verify(f.orchestrator, times(1)).streamTurn(any(), any(), any(), any(), any());
    }
}
