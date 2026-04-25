package com.pods.agent.service;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformRuntimeScenariosTest {

    @Test
    void disabledDomainExcludesToolsFromRegistry() {
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);

        when(domainRepository.findAll()).thenReturn(List.of(
                AgentDomain.builder().id("d1").name("On").enabled(true).build(),
                AgentDomain.builder().id("d2").name("Off").enabled(false).build()
        ));
        when(toolRepository.findAll()).thenReturn(List.of(
                AgentTool.builder().id("t1").domainId("d1").name("allowed").enabled(true).build(),
                AgentTool.builder().id("t2").domainId("d2").name("blocked").enabled(true).build()
        ));

        ToolRegistryService registry = new ToolRegistryService(domainRepository, toolRepository, new RuntimeTuningProperties());
        List<AgentTool> enabled = registry.getEnabledTools();
        assertEquals(1, enabled.size());
        assertEquals("allowed", enabled.get(0).getName());
    }

    @Test
    void summarizationTriggersForLongSessions() {
        ContextSummarizationService summarizer = new ContextSummarizationService();
        var session = new com.pods.agent.agent.AgentSession("s1");
        for (int i = 0; i < 20; i++) {
            session.getMessages().add(new UserMessage("User message " + i));
            session.getMessages().add(new AssistantMessage("Assistant answer " + i));
        }
        String summary = summarizer.maybeSummarize(session, "", 2000);
        assertNotNull(summary);
    }

    @Test
    void guardrailDefaultsToOpenDevAllow() {
        var policyRepo = mock(com.pods.agent.repository.GuardrailPolicyRepository.class);
        when(policyRepo.findEnabled()).thenReturn(List.of());

        GuardrailPolicyEngine engine = new GuardrailPolicyEngine(policyRepo, new RuntimeTuningProperties());
        var decision = engine.evaluateTool(AgentTool.builder().name("sample").enabled(true).build());
        assertEquals("allow", decision.decision());
    }
}
