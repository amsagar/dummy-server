package com.pods.agent.service;

import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolChainArchitectAgentServiceTest {

    @Test
    void evaluateEligibilityParsesValidResponse() {
        AgentRuntimeService runtimeService = mock(AgentRuntimeService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainArchitectAgentService service = new ToolChainArchitectAgentService(
                runtimeService,
                runtimeEventRepository,
                new ObjectMapper()
        );

        when(runtimeService.runTurn(any(), anyString(), any(), any(), eq("turn-1:eligibility")))
                .thenReturn("""
                        {"isToolChainNeeded":true,"isSimpleTurn":false,"confidence":"high","reason":"Multiple reusable tool calls","referencedSkills":["Billing Rules"]}
                        """);
        when(runtimeEventRepository.findByTurnId("turn-1:eligibility")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"skill\",\"input\":{\"name\":\"toolchain-architect\"}}"),
                event("tool.call", "{\"toolName\":\"skill\",\"input\":{\"name\":\"Billing Rules\"}}"),
                event("tool.call", "{\"toolName\":\"read\",\"input\":{\"path\":\".pods-agent/turns/turn-1/toolchain-trace.json\"}}")
        ));

        Optional<SystemToolChainEligibility> out = service.evaluateEligibilityFromTrace(
                Path.of("/tmp"),
                ".pods-agent/turns/turn-1/toolchain-trace.json",
                "session-1",
                "turn-1",
                "user-1",
                "please automate this workflow",
                "done",
                new ModelRef("openai", "gpt-4o"),
                "turn-1:eligibility"
        );

        assertTrue(out.isPresent());
        assertTrue(out.get().toolChainNeeded());
        assertEquals("high", out.get().normalizedConfidence());
        assertEquals(List.of("Billing Rules"), out.get().referencedSkills());
    }

    @Test
    void evaluateEligibilityFailsClosedOnInvalidConfidence() {
        AgentRuntimeService runtimeService = mock(AgentRuntimeService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainArchitectAgentService service = new ToolChainArchitectAgentService(
                runtimeService,
                runtimeEventRepository,
                new ObjectMapper()
        );

        when(runtimeService.runTurn(any(), anyString(), any(), any(), eq("turn-2:eligibility")))
                .thenReturn("""
                        {"isToolChainNeeded":true,"isSimpleTurn":false,"confidence":"certain","reason":"x"}
                        """);
        when(runtimeEventRepository.findByTurnId("turn-2:eligibility")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"skill\",\"input\":{\"name\":\"toolchain-architect\"}}"),
                event("tool.call", "{\"toolName\":\"read\",\"input\":{\"path\":\".pods-agent/turns/turn-2/toolchain-trace.json\"}}")
        ));

        Optional<SystemToolChainEligibility> out = service.evaluateEligibilityFromTrace(
                Path.of("/tmp"),
                ".pods-agent/turns/turn-2/toolchain-trace.json",
                "session-2",
                "turn-2",
                "user-1",
                "hi",
                "hello",
                new ModelRef("openai", "gpt-4o"),
                "turn-2:eligibility"
        );

        assertTrue(out.isEmpty());
    }

    @Test
    void evaluateEligibilitySanitizesReferencedSkillWhenNotLoaded() {
        AgentRuntimeService runtimeService = mock(AgentRuntimeService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainArchitectAgentService service = new ToolChainArchitectAgentService(
                runtimeService,
                runtimeEventRepository,
                new ObjectMapper()
        );

        when(runtimeService.runTurn(any(), anyString(), any(), any(), eq("turn-3:eligibility")))
                .thenReturn("""
                        {"isToolChainNeeded":true,"isSimpleTurn":false,"confidence":"high","reason":"Reusable","referencedSkills":["D365 Rules"]}
                        """);
        when(runtimeEventRepository.findByTurnId("turn-3:eligibility")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"skill\",\"input\":{\"name\":\"toolchain-architect\"}}"),
                event("tool.call", "{\"toolName\":\"read\",\"input\":{\"path\":\".pods-agent/turns/turn-3/toolchain-trace.json\"}}")
        ));

        Optional<SystemToolChainEligibility> out = service.evaluateEligibilityFromTrace(
                Path.of("/tmp"),
                ".pods-agent/turns/turn-3/toolchain-trace.json",
                "session-3",
                "turn-3",
                "user-1",
                "please automate",
                "done",
                new ModelRef("openai", "gpt-4o"),
                "turn-3:eligibility"
        );

        assertTrue(out.isPresent());
        assertEquals(List.of(), out.get().referencedSkills());
    }

    @Test
    void generateFromTraceParsesReferencedSkillsAndRequiresSkillEvidence() {
        AgentRuntimeService runtimeService = mock(AgentRuntimeService.class);
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainArchitectAgentService service = new ToolChainArchitectAgentService(
                runtimeService,
                runtimeEventRepository,
                new ObjectMapper()
        );

        when(runtimeService.runTurn(any(), anyString(), any(), any(), eq("turn-4:architect")))
                .thenReturn("""
                        {"name":"Order Validator","description":"Validates order data","intents":["validate order"],"referencedSkills":["Billing Rules"],"graph":{"nodes":[{"id":"start"},{"id":"end"}],"edges":[{"from":"start","to":"end"}]},"inputSchema":{"type":"object"},"outputSchema":{"type":"object"},"responseMode":"hybrid","synthesisPrompt":"Summarize.","ragConfig":{}}
                        """);
        when(runtimeEventRepository.findByTurnId("turn-4:architect")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"skill\",\"input\":{\"name\":\"toolchain-architect\"}}"),
                event("tool.call", "{\"toolName\":\"skill\",\"input\":{\"name\":\"Billing Rules\"}}"),
                event("tool.call", "{\"toolName\":\"read\",\"input\":{\"path\":\".pods-agent/turns/turn-4/toolchain-trace.json\"}}")
        ));

        Optional<SystemToolChainArtifact> out = service.generateFromTrace(
                Path.of("/tmp"),
                ".pods-agent/turns/turn-4/toolchain-trace.json",
                "session-4",
                "turn-4",
                "user-1",
                "validate order",
                "done",
                new ModelRef("openai", "gpt-4o"),
                "turn-4:architect"
        );

        assertTrue(out.isPresent());
        assertEquals(List.of("Billing Rules"), out.get().referencedSkills());
    }

    private RuntimeEvent event(String type, String payload) {
        return RuntimeEvent.builder()
                .eventType(type)
                .payload(payload)
                .build();
    }
}
