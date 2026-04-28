package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.ToolExecutionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolCallbackSkillGateTest {

    @Test
    void blocksDomainToolWhenSkillGateActiveAndSkillNotLoaded() {
        AgentTool tool = AgentTool.builder().id("t1").name("GetOrder").description("fetch order").enabled(true).build();
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SseEventSender sender = mock(SseEventSender.class);

        when(policyEngine.evaluateTool(any())).thenReturn(new GuardrailPolicyEngine.Decision("allow", ""));

        AgentToolCallback callback = new AgentToolCallback(
                tool,
                executionService,
                policyEngine,
                pendingInteractionService,
                sender,
                "s1",
                "t1",
                1000,
                new ObjectMapper(),
                null,
                new SkillExecutionGate(true)
        );

        String output = callback.call("{\"orderId\":\"123\"}");

        assertTrue(output.contains("Skill-first gate active"));
        verify(executionService, never()).execute(any(), any());
        verify(sender).sendToolResult(eq("s1"), any(), eq("GetOrder"), eq(output), eq("blocked"));
    }

    @Test
    void allowsDomainToolAfterSkillGateLoaded() {
        AgentTool tool = AgentTool.builder().id("t1").name("GetOrder").description("fetch order").enabled(true).build();
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        PendingInteractionService pendingInteractionService = mock(PendingInteractionService.class);
        SseEventSender sender = mock(SseEventSender.class);

        when(policyEngine.evaluateTool(any())).thenReturn(new GuardrailPolicyEngine.Decision("allow", ""));
        when(executionService.execute(any(), any())).thenReturn(new ToolExecutionService.ExecutionResult(true, "{\"ok\":true}", null));

        SkillExecutionGate gate = new SkillExecutionGate(true);
        gate.markSkillLoaded();

        AgentToolCallback callback = new AgentToolCallback(
                tool,
                executionService,
                policyEngine,
                pendingInteractionService,
                sender,
                "s1",
                "t1",
                1000,
                new ObjectMapper(),
                null,
                gate
        );

        String output = callback.call("{\"orderId\":\"123\"}");

        assertTrue(output.contains("\"ok\":true"));
        verify(executionService).execute(any(), any());
    }
}
