package com.pods.agent.ruledomain.runtime;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolCallDelegateTest {

    private ToolRegistryService toolRegistry;
    private ToolExecutionService toolExecutor;
    private FeelHelper feel;
    private ToolCallDelegate delegate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistryService.class);
        toolExecutor = mock(ToolExecutionService.class);
        feel = mock(FeelHelper.class);
        objectMapper = JsonMapper.builder().build();
        delegate = new ToolCallDelegate(toolRegistry, toolExecutor, objectMapper, feel);
    }

    @Test
    void resolvesArgsViaFeelAndDispatchesToTool() {
        AgentTool tool = AgentTool.builder()
                .id("t1").name("Get_OrderID").enabled(true).executionKind("http_proxy")
                .build();
        when(toolRegistry.getEnabledToolByName("Get_OrderID")).thenReturn(tool);
        when(feel.eval(eq("orderId"), anyMap())).thenReturn("600030447");
        when(toolExecutor.execute(eq(tool), anyString()))
                .thenReturn(new ToolExecutionService.ExecutionResult(true,
                        "{\"OrderIdentity\":600030447,\"Lines\":[]}", null));

        Map<String, Object> vars = new HashMap<>();
        vars.put("orderId", "600030447");
        vars.put("toolName", "Get_OrderID");
        vars.put("argTemplate", "{\"ORD_ID\":\"orderId\"}");
        vars.put("outputBinding", "order");
        DelegateExecution exec = stubExecution(vars);

        delegate.execute(exec);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(toolExecutor).execute(eq(tool), payload.capture());
        assertTrue(payload.getValue().contains("600030447"));
        verify(exec).setVariable(eq("order"), argThat(o -> {
            if (!(o instanceof Map)) return false;
            return "600030447".equals(String.valueOf(((Map<?, ?>) o).get("OrderIdentity"))) ||
                    Long.valueOf(600030447L).equals(((Map<?, ?>) o).get("OrderIdentity"));
        }));
    }

    @Test
    void throwsBpmnErrorWhenToolMissing() {
        when(toolRegistry.getEnabledToolByName("Missing")).thenReturn(null);

        Map<String, Object> vars = new HashMap<>();
        vars.put("toolName", "Missing");
        vars.put("argTemplate", "{}");
        vars.put("outputBinding", "x");
        DelegateExecution exec = stubExecution(vars);

        BpmnError err = assertThrows(BpmnError.class, () -> delegate.execute(exec));
        assertEquals("TOOL_NOT_FOUND", err.getErrorCode());
    }

    @Test
    void throwsBpmnErrorWhenToolExecutionFails() {
        AgentTool tool = AgentTool.builder()
                .id("t1").name("X").enabled(true).executionKind("http_proxy")
                .build();
        when(toolRegistry.getEnabledToolByName("X")).thenReturn(tool);
        when(toolExecutor.execute(eq(tool), anyString()))
                .thenReturn(new ToolExecutionService.ExecutionResult(false, null, "boom"));

        Map<String, Object> vars = new HashMap<>();
        vars.put("toolName", "X");
        vars.put("argTemplate", "{}");
        vars.put("outputBinding", "x");
        DelegateExecution exec = stubExecution(vars);

        BpmnError err = assertThrows(BpmnError.class, () -> delegate.execute(exec));
        assertEquals("TOOL_EXECUTION_FAILED", err.getErrorCode());
    }

    private static DelegateExecution stubExecution(Map<String, Object> vars) {
        DelegateExecution exec = mock(DelegateExecution.class);
        when(exec.getVariables()).thenReturn(vars);
        for (var e : vars.entrySet()) {
            when(exec.getVariable(e.getKey())).thenReturn(e.getValue());
        }
        return exec;
    }
}
