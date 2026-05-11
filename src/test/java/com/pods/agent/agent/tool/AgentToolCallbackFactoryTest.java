package com.pods.agent.agent.tool;

import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import com.pods.agent.service.workspace.ExecutionLogService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCallbackFactoryTest {

    @Test
    void buildForTurn_usesExplicitToolSelectionWhenProvided() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
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
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository,
                mock(ExecutionLogService.class)
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(toolB));

        // Selected tool + native skill callback + native architect_note callback
        assertEquals(3, callbacks.size());
    }

    @Test
    void buildForTurn_fallsBackToRegistryWhenSelectionIsEmpty() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
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
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository,
                mock(ExecutionLogService.class)
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of());

        // 2 registry tools + native skill callback + native architect_note callback
        assertEquals(4, callbacks.size());
    }

    @Test
    void buildForTurn_doesNotDuplicateSkillWhenAlreadyPresent() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool skill = AgentTool.builder().id("s").name("skill").description("load skill").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(skill));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository,
                mock(ExecutionLogService.class)
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(skill));

        // Skill registry-tool is filtered, native skill callback + architect_note are added.
        assertEquals(2, callbacks.size());
        assertTrue(callbacks.stream().anyMatch(cb -> cb instanceof SkillToolCallback));
        assertTrue(callbacks.stream().anyMatch(cb -> cb instanceof ArchitectNoteCallback));
    }

    @Test
    void buildForTurn_includesNativeSkillCallback() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool toolA = AgentTool.builder().id("a").name("GetOrder").description("A").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(toolA));
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of(
                new SkillRegistryService.SkillSnapshot(
                        com.pods.agent.domain.Skill.builder().id("s").name("Billing Rules").description("desc").enabled(true).build(),
                        Map.of("SKILL.md", "content")
                )
        ));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository,
                mock(ExecutionLogService.class)
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(toolA));

        // toolA + native skill callback + native architect_note callback
        assertEquals(3, callbacks.size());
        long skillCount = callbacks.stream()
                .filter(cb -> "skill".equalsIgnoreCase(cb.getToolDefinition().name()))
                .count();
        assertEquals(1, skillCount);
        long noteCount = callbacks.stream()
                .filter(cb -> "architect_note".equalsIgnoreCase(cb.getToolDefinition().name()))
                .count();
        assertEquals(1, noteCount);
    }
}
