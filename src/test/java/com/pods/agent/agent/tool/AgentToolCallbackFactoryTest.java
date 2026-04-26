package com.pods.agent.agent.tool;

import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCallbackFactoryTest {

    @Test
    void buildForTurn_usesExplicitToolSelectionWhenProvided() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool toolA = AgentTool.builder().id("a").name("toolA").description("A").enabled(true).build();
        AgentTool toolB = AgentTool.builder().id("b").name("toolB").description("B").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(toolA, toolB));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(toolB));

        assertEquals(1, callbacks.size());
    }

    @Test
    void buildForTurn_fallsBackToRegistryWhenSelectionIsEmpty() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool toolA = AgentTool.builder().id("a").name("toolA").description("A").enabled(true).build();
        AgentTool toolB = AgentTool.builder().id("b").name("toolB").description("B").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(toolA, toolB));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of());

        assertEquals(2, callbacks.size());
    }
}
