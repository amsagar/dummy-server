package com.pods.agent.service;

import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.domain.ModelRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelAutoRouterServiceTest {

    @Test
    void picksSmallModelForSimplePrompt() {
        ModelRegistryService registry = mock(ModelRegistryService.class);
        when(registry.listEnabled()).thenReturn(sampleModels());
        RuntimeTuningProperties props = new RuntimeTuningProperties();
        ModelAutoRouterService router = new ModelAutoRouterService(registry, props);

        ModelRef ref = router.pickModel("hello", 1, false);
        assertNotNull(ref);
        assertEquals("gpt-4.1-mini", ref.modelID());
    }

    @Test
    void picksLargeModelForComplexPrompt() {
        ModelRegistryService registry = mock(ModelRegistryService.class);
        when(registry.listEnabled()).thenReturn(sampleModels());
        RuntimeTuningProperties props = new RuntimeTuningProperties();
        ModelAutoRouterService router = new ModelAutoRouterService(registry, props);

        String complex = "Please implement planner worker swarm runtime with parallel tasks and policy guardrails and long context summarization ".repeat(20);
        ModelRef ref = router.pickModel(complex, 50, true);
        assertNotNull(ref);
        assertEquals("claude-opus-4", ref.modelID());
    }

    private List<ModelConfig> sampleModels() {
        return List.of(
                ModelConfig.builder().providerId("openai").modelId("gpt-4.1-mini").displayName("Mini").enabled(true).build(),
                ModelConfig.builder().providerId("anthropic").modelId("claude-sonnet-4").displayName("Sonnet").enabled(true).build(),
                ModelConfig.builder().providerId("anthropic").modelId("claude-opus-4").displayName("Opus").enabled(true).build()
        );
    }
}
