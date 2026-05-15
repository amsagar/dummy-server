package com.pods.agent.agent;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.Skill;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.repository.AgentProfileRepository;
import com.pods.agent.repository.RuntimeEventRepository;
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

    private AgentOrchestrator newOrchestrator(SkillRegistryService skillRegistryService, RuntimeTuningProperties props) {
        // Optional collaborators (rule-domain pipeline + turn-tool cache) are
        // ObjectProvider<?> on the production constructor. Tests don't exercise
        // those paths, so pass null — AgentOrchestrator guards every read with
        // a null check on the provider.
        return new AgentOrchestrator(
                mock(ModelProviderRouter.class),
                skillRegistryService,
                mock(InstructionLoaderService.class),
                mock(MemoryService.class),
                props,
                mock(RuntimeEventRepository.class),
                new tools.jackson.databind.ObjectMapper(),
                mock(AgentProfileRepository.class),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Test
    void loadsBaseSystemPromptFromResource() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(SkillRegistryService.class), new RuntimeTuningProperties());

        String prompt = orchestrator.baseSystemPromptForTest();
        assertTrue(prompt.contains("You are AI Agent."));
        assertTrue(prompt.contains("outside allowed scope"));
    }

    @Test
    void normalizesAugmentedRuntimeContextForHistory() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        AgentOrchestrator orchestrator = newOrchestrator(skillRegistryService, new RuntimeTuningProperties());

        String normalized = orchestrator.normalizeUserMessageForHistory(
                "i need python snake game code\n\nTool execution result:\nTool 'x' returned: y\n\nRuntime mode: planner_worker"
        );

        assertEquals("i need python snake game code", normalized);
    }

    @Test
    void usesRetrievalContractAndInstructsModelToUseSkillTool() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.getEnabledSkills()).thenReturn(List.of(
                new SkillSnapshot(
                        Skill.builder().id("s1").name("pdf").description("pdf operations").enabled(true).build(),
                        Map.of("SKILL.md", "Use this skill for PDF operations.", "examples.md", "example content")
                )
        ));
        RuntimeTuningProperties props = new RuntimeTuningProperties();
        props.setIncludeSkillContentInSystemPrompt(true);
        AgentOrchestrator orchestrator = newOrchestrator(skillRegistryService, props);

        String prompt = orchestrator.buildSystemPromptForTest(ChatState.builder().build(), new AgentSession("s4"));

        assertTrue(prompt.contains("## Retrieval Catalog Contract"));
        assertTrue(prompt.contains("skillsearch"));
        assertTrue(prompt.contains("toolsearch"));
        assertTrue(prompt.contains("call the `skill` tool"));
    }
}
