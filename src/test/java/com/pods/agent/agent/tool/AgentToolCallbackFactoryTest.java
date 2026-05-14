package com.pods.agent.agent.tool;

import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import com.pods.agent.service.UserContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(toolB));

        // Selected tool + toolsearch + skillsearch + native skill callback
        assertEquals(4, callbacks.size());
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
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of());

        // 2 registry tools + toolsearch + skillsearch + native skill callback
        assertEquals(5, callbacks.size());
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
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(skill));

        // Skill registry-tool is filtered; toolsearch + skillsearch + native skill callback added.
        assertEquals(3, callbacks.size());
        assertTrue(callbacks.stream().anyMatch(cb -> cb instanceof SkillToolCallback));
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
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(toolA));

        // toolA + toolsearch + skillsearch + native skill callback
        assertEquals(4, callbacks.size());
        long skillCount = callbacks.stream()
                .filter(cb -> "skill".equalsIgnoreCase(cb.getToolDefinition().name()))
                .count();
        assertEquals(1, skillCount);
    }

    @Test
    void buildForTurn_includesMemoryCallbacksWhenUserAuthenticated() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool toolA = AgentTool.builder().id("a").name("GetOrder").description("A").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(toolA));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = UserContextHolder.withUser("user-1",
                () -> factory.buildForTurn("s1", "t1", null, List.of(toolA)));

        // toolA + 6 memory tools + toolsearch + skillsearch + native skill callback
        assertEquals(10, callbacks.size());
        Set<String> names = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name().toLowerCase())
                .collect(Collectors.toSet());
        assertTrue(names.contains("memoryview"));
        assertTrue(names.contains("memorycreate"));
        assertTrue(names.contains("memorystrreplace"));
        assertTrue(names.contains("memoryinsert"));
        assertTrue(names.contains("memorydelete"));
        assertTrue(names.contains("memoryrename"));
        assertTrue(names.contains("toolsearch"));
        assertTrue(names.contains("skillsearch"));
    }

    @Test
    void buildForTurn_omitsMemoryCallbacksForAnonymousContext() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool toolA = AgentTool.builder().id("a").name("GetOrder").description("A").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(toolA));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository
        );

        // No UserContextHolder.withUser wrapper -> currentUserId() is null
        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(toolA));

        // toolA + toolsearch + skillsearch + native skill callback (no memory tools)
        assertEquals(4, callbacks.size());
        Set<String> names = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name().toLowerCase())
                .collect(Collectors.toSet());
        assertTrue(names.stream().noneMatch(n -> n.startsWith("memory")));
        assertTrue(names.contains("toolsearch"));
        assertTrue(names.contains("skillsearch"));
    }

    @Test
    void buildForTurn_dedupesRetrievalToolsWhenAlreadyInRegistry() {
        ToolRegistryService registry = mock(ToolRegistryService.class);
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine guardrailPolicyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        RuntimeTuningProperties properties = new RuntimeTuningProperties();

        AgentTool dbToolsearch = AgentTool.builder().id("ts").name("toolsearch").description("db-seeded").enabled(true).build();
        AgentTool dbSkillsearch = AgentTool.builder().id("ss").name("skillsearch").description("db-seeded").enabled(true).build();
        when(registry.getEnabledTools()).thenReturn(List.of(dbToolsearch, dbSkillsearch));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                registry,
                executionService,
                guardrailPolicyEngine,
                pendingInteractionService,
                skillRegistryService,
                properties,
                new ObjectMapper(),
                runtimeEventRepository
        );

        List<ToolCallback> callbacks = factory.buildForTurn("s1", "t1", null, List.of(dbToolsearch, dbSkillsearch));

        // 2 DB-seeded retrieval tools + native skill callback. No synthetic duplicates.
        assertEquals(3, callbacks.size());
        long toolsearchCount = callbacks.stream()
                .filter(cb -> "toolsearch".equalsIgnoreCase(cb.getToolDefinition().name()))
                .count();
        long skillsearchCount = callbacks.stream()
                .filter(cb -> "skillsearch".equalsIgnoreCase(cb.getToolDefinition().name()))
                .count();
        assertEquals(1, toolsearchCount);
        assertEquals(1, skillsearchCount);
    }
}
