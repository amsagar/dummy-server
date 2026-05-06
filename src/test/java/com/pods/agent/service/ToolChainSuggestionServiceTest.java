package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolChainSuggestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsSystemSuggestionWhenTurnHasValidToolSequence() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(runtimeEventRepository, toolChainService, objectMapper);

        when(runtimeEventRepository.findByTurnId("turn-1")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"search_orders\",\"input\":\"{\\\"orderId\\\":\\\"123\\\"}\"}"),
                event("tool.done", "{\"toolName\":\"search_orders\",\"status\":\"success\"}"),
                event("tool.call", "{\"toolName\":\"update_order\",\"input\":\"{\\\"orderId\\\":\\\"123\\\",\\\"status\\\":\\\"closed\\\"}\"}"),
                event("tool.done", "{\"toolName\":\"update_order\",\"status\":\"success\"}")
        ));
        when(toolChainService.findBySignatures(anyString(), anyString())).thenReturn(Optional.empty());
        when(toolChainService.createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ToolChain.builder().id("tc-1").name("Suggested").build());

        Optional<ToolChain> created = service.createSuggestionFromTurn("session-1", "turn-1", "close order 123", "user-1");

        assertTrue(created.isPresent());
        ArgumentCaptor<ToolChainDtos.ToolChainVersionRequest> requestCaptor =
                ArgumentCaptor.forClass(ToolChainDtos.ToolChainVersionRequest.class);
        verify(toolChainService).createVersion(eq("tc-1"), requestCaptor.capture(), eq("user-1"));
        ToolChainDtos.ToolChainVersionRequest req = requestCaptor.getValue();
        assertFalse(String.valueOf(req.getIntentSignature()).isBlank());
        assertFalse(String.valueOf(req.getStructureSignature()).isBlank());
        assertTrue(req.getGraphJson().contains("\"tool_1\""));
        assertTrue(req.getGraphJson().contains("\"tool_2\""));
    }

    @Test
    void skipsCreationWhenSignatureAlreadyExists() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(runtimeEventRepository, toolChainService, objectMapper);

        when(runtimeEventRepository.findByTurnId("turn-2")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"a\",\"input\":\"{}\"}"),
                event("tool.call", "{\"toolName\":\"b\",\"input\":\"{}\"}")
        ));
        when(toolChainService.findBySignatures(anyString(), anyString()))
                .thenReturn(Optional.of(ToolChain.builder().id("existing").build()));

        Optional<ToolChain> created = service.createSuggestionFromTurn("session-1", "turn-2", "run flow", "user-1");

        assertTrue(created.isPresent());
        verify(toolChainService, never()).createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(toolChainService, never()).createVersion(anyString(), any(), anyString());
    }

    private RuntimeEvent event(String type, String payload) {
        return RuntimeEvent.builder()
                .eventType(type)
                .payload(payload)
                .build();
    }
}
