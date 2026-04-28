package com.pods.agent.service;

import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.tool.SkillExecutionGate;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.domain.Skill;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeServiceSkillSelectionTest {

    @Test
    void validationRequests_doNotPreInjectFullSkillContentInStepContext() {
        AgentRuntimeServiceTestFixtures.Fixture f = AgentRuntimeServiceTestFixtures.build();

        Skill skill = Skill.builder()
                .id("system-validation-report-format")
                .name("Validation Report Format")
                .description("Exact format for PODS order validation report output")
                .enabled(true)
                .build();
        SkillRegistryService.SkillSnapshot snapshot = new SkillRegistryService.SkillSnapshot(
                skill,
                Map.of("SKILL.md", "# Validation Report Format\nUse numbered sections only.")
        );
        when(f.skillRegistryService.getEnabledSkills()).thenReturn(List.of(snapshot));

        AgentRuntimeService service = AgentRuntimeServiceTestFixtures.create(f);
        service.runTurn(
                new AgentSession("s-skill"),
                "Validate order 10045. Check all systems and produce a full validation report.",
                ChatState.builder().build(),
                mock(SseEventSender.class)
        );

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(f.orchestrator, atLeastOnce()).streamTurn(any(), contextCaptor.capture(), any(), any(), any(), any());
        String stepContext = contextCaptor.getAllValues().get(contextCaptor.getAllValues().size() - 1);
        assertFalse(stepContext.contains("<skill_content"));
        assertTrue(stepContext.contains("Validate order 10045"));

        ArgumentCaptor<SkillExecutionGate> gateCaptor = ArgumentCaptor.forClass(SkillExecutionGate.class);
        verify(f.callbackFactory).buildForTurn(any(), any(), any(), any(), gateCaptor.capture());
        SkillExecutionGate gate = gateCaptor.getValue();
        assertNotNull(gate);
        assertTrue(gate.isRequired());
    }
}
