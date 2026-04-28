package com.pods.agent.service;

import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeServiceToolExposureTest {

    private AgentRuntimeServiceTestFixtures.Fixture withCatalog(int nonDefaultCount) {
        AgentRuntimeServiceTestFixtures.Fixture f = AgentRuntimeServiceTestFixtures.build();
        List<AgentTool> framework = List.of(
                AgentTool.builder().id("fw-1").name("websearch").sourceType("framework_default").enabled(true).build(),
                AgentTool.builder().id("fw-2").name("read").sourceType("framework_default").enabled(true).build(),
                AgentTool.builder().id("fw-3").name("plan_exit").sourceType("framework_default").enabled(true).build()
        );
        List<AgentTool> nonDefault = IntStream.range(0, nonDefaultCount)
                .mapToObj(i -> AgentTool.builder()
                        .id("nd-" + i).name("tool_" + i).sourceType("mcp").enabled(true).build())
                .toList();
        when(f.toolRegistryService.getBaseInjectedTools()).thenReturn(framework);
        when(f.toolRegistryService.getNonDefaultEnabledTools()).thenReturn(nonDefault);
        java.util.List<AgentTool> all = new java.util.ArrayList<>(framework);
        all.addAll(nonDefault);
        when(f.toolRegistryService.getEnabledTools()).thenReturn(all);
        return f;
    }

    @Test
    void singleStreamTurn_evenOnRefusalLikeResponse() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(0);
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(null);
        when(f.orchestrator.streamTurn(any(), any(), any(), any(), any(), any())).thenReturn("I cannot access this");

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "what?", ChatState.builder().build(), mock(SseEventSender.class));
        verify(f.orchestrator, times(1)).streamTurn(any(), any(), any(), any(), any(), any());
    }

    @Test
    void retrieval_drivesSelection_toolByCosineNotLexicalMatch() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(3);
        ModelRef ref = new ModelRef("openai", "text-embedding-3-small");
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(ref);
        // Suppose top retrieval returns nd-2 (no lexical overlap with "list our tenants").
        when(f.toolEmbeddingIndexService.searchTopK(any(), anyInt(), any(), any(), eq(ref)))
                .thenReturn(List.of(new ToolEmbeddingIndexService.ScoredTool("nd-2", 0.9)));

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "list our tenants", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> cap = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), cap.capture(), any());
        assertTrue(cap.getValue().stream().anyMatch(t -> "nd-2".equals(t.getId())));
    }

    @Test
    void providerCap_honored_largeIndex() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(1000);
        f.props.setMaxToolCallbacksPerTurn(40);
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(null);

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "any", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> cap = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), cap.capture(), any());
        assertTrue(cap.getValue().size() <= 40);
    }

    @Test
    void frameworkTools_alwaysPresent_withLargeIndex() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(1000);
        ModelRef ref = new ModelRef("openai", "text-embedding-3-small");
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(ref);
        // Retrieval returns lots of non-defaults, no framework
        when(f.toolEmbeddingIndexService.searchTopK(any(), anyInt(), any(), any(), eq(ref)))
                .thenReturn(IntStream.range(0, 30)
                        .mapToObj(i -> new ToolEmbeddingIndexService.ScoredTool("nd-" + i, 0.5))
                        .toList());

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "anything", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> cap = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), cap.capture(), any());
        Set<String> ids = cap.getValue().stream().map(AgentTool::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("fw-1", "fw-2", "fw-3")));
    }

    @Test
    void frameworkTools_present_evenWhenSemanticBelowFloor() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(2);
        ModelRef ref = new ModelRef("openai", "text-embedding-3-small");
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(ref);
        when(f.toolEmbeddingIndexService.searchTopK(any(), anyInt(), any(), any(), eq(ref)))
                .thenReturn(List.of(new ToolEmbeddingIndexService.ScoredTool("nd-0", 0.4)));

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "x", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> cap = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), cap.capture(), any());
        Set<String> ids = cap.getValue().stream().map(AgentTool::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("fw-1", "fw-2", "fw-3")));
    }

    @Test
    void frameworkTools_present_whenEmbeddingThrows() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(5);
        ModelRef ref = new ModelRef("openai", "text-embedding-3-small");
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(ref);
        when(f.toolEmbeddingIndexService.searchTopK(any(), anyInt(), any(), any(), eq(ref)))
                .thenThrow(new RuntimeException("embedding service down"));

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "x", ChatState.builder().build(), mock(SseEventSender.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> cap = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), cap.capture(), any());
        Set<String> ids = cap.getValue().stream().map(AgentTool::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("fw-1", "fw-2", "fw-3")));
    }

    @Test
    void fallback_whenIndexEmpty_runsOnce_frameworkPresent() {
        AgentRuntimeServiceTestFixtures.Fixture f = withCatalog(2);
        ModelRef ref = new ModelRef("openai", "text-embedding-3-small");
        when(f.embeddingAutoRouterService.pickEmbeddingModel(any())).thenReturn(ref);
        when(f.toolEmbeddingIndexService.searchTopK(any(), anyInt(), any(), any(), eq(ref)))
                .thenReturn(List.of());

        AgentRuntimeService svc = AgentRuntimeServiceTestFixtures.create(f);
        svc.runTurn(new AgentSession("s"), "x", ChatState.builder().build(), mock(SseEventSender.class));

        verify(f.orchestrator, times(1)).streamTurn(any(), any(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentTool>> cap = ArgumentCaptor.forClass(List.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), cap.capture(), any());
        Set<String> ids = cap.getValue().stream().map(AgentTool::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("fw-1", "fw-2", "fw-3")));
    }
}
