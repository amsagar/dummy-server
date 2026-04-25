package com.pods.agent.agent;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.Skill;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.service.SkillRegistryService.SkillSnapshot;
import com.pods.agent.service.MemoryService;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.instruction.InstructionLoaderService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOrchestratorPromptTest {

    @Test
    void loadsBaseSystemPromptFromResource() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(ModelProviderRouter.class),
                mock(SkillRegistryService.class),
                mock(InstructionLoaderService.class),
                mock(MemoryService.class),
                new RuntimeTuningProperties()
        );

        String prompt = orchestrator.baseSystemPromptForTest();
        assertTrue(prompt.contains("You are PODS AI Agent."));
        assertTrue(prompt.contains("outside allowed scope"));
    }

    @Test
    void normalizesAugmentedRuntimeContextForHistory() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(ModelProviderRouter.class),
                skillRegistryService,
                mock(InstructionLoaderService.class),
                mock(MemoryService.class),
                new RuntimeTuningProperties()
        );

        String normalized = orchestrator.normalizeUserMessageForHistory(
                "i need python snake game code\n\nTool execution result:\nTool 'x' returned: y\n\nRuntime mode: planner_worker"
        );

        assertEquals("i need python snake game code", normalized);
    }

    @Test
    void injectsSkillContentIntoSystemPrompt() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of(
                new SkillSnapshot(
                        Skill.builder().id("s1").name("pdf").description("pdf operations").enabled(true).build(),
                        Map.of("SKILL.md", "Use this skill for PDF operations.", "examples.md", "example content")
                )
        ));
        RuntimeTuningProperties props = new RuntimeTuningProperties();
        props.setIncludeSkillContentInSystemPrompt(true);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(ModelProviderRouter.class),
                skillRegistryService,
                mock(InstructionLoaderService.class),
                mock(MemoryService.class),
                props
        );

        String prompt = orchestrator.buildSystemPromptForTest(ChatState.builder().build(), new AgentSession("s4"));

        assertTrue(prompt.contains("## Skill Content"));
        assertTrue(prompt.contains("<skill name=\"pdf\">"));
        assertTrue(prompt.contains("Use this skill for PDF operations."));
    }
}
