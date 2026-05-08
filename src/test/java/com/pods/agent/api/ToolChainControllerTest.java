package com.pods.agent.api;

import com.pods.agent.domain.SystemToolChainProposal;
import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.repository.SystemToolChainProposalRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainRunStepRepository;
import com.pods.agent.service.SecurityContextService;
import com.pods.agent.service.SystemToolChainAsyncService;
import com.pods.agent.service.ToolChainConfigChatService;
import com.pods.agent.service.ToolChainMappingEditorService;
import com.pods.agent.service.ToolChainRuntimeService;
import com.pods.agent.service.ToolChainService;
import com.pods.agent.service.codeexec.CodeExecutionService;
import com.pods.agent.service.codeexec.CodeExecutionResult;
import com.pods.agent.service.expression.ExpressionValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolChainControllerTest {

    @Test
    void listSystemToolChainProposalsReturnsPendingRows() {
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainRuntimeService runtimeService = mock(ToolChainRuntimeService.class);
        ToolChainConfigChatService configChatService = mock(ToolChainConfigChatService.class);
        ToolChainMappingEditorService mappingEditorService = mock(ToolChainMappingEditorService.class);
        ToolChainRunRepository runRepository = mock(ToolChainRunRepository.class);
        ToolChainRunStepRepository runStepRepository = mock(ToolChainRunStepRepository.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService asyncService = mock(SystemToolChainAsyncService.class);
        SecurityContextService securityContextService = mock(SecurityContextService.class);
        ExpressionValidator expressionValidator = mock(ExpressionValidator.class);
        CodeExecutionService codeExecutionService = mock(CodeExecutionService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        when(securityContextService.currentUserIdOrThrow()).thenReturn("user-1");
        when(proposalRepository.findPendingByUser("user-1")).thenReturn(List.of(
                SystemToolChainProposal.builder()
                        .id("proposal-1")
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .status("pending")
                        .reason("Reusable flow")
                        .confidence("high")
                        .createdAt(123L)
                        .build()
        ));

        ToolChainController controller = new ToolChainController(
                toolChainService,
                runtimeService,
                configChatService,
                mappingEditorService,
                runRepository,
                runStepRepository,
                proposalRepository,
                asyncService,
                securityContextService,
                expressionValidator,
                codeExecutionService,
                objectMapper
        );

        ResponseEntity<?> response = controller.listSystemToolChainProposals();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) body.get("proposals");
        assertEquals(1, proposals.size());
        assertEquals("proposal-1", proposals.get(0).get("id"));
        assertEquals("high", proposals.get(0).get("confidence"));
    }

    @Test
    void approveSystemToolChainProposalDelegatesToAsyncService() {
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainRuntimeService runtimeService = mock(ToolChainRuntimeService.class);
        ToolChainConfigChatService configChatService = mock(ToolChainConfigChatService.class);
        ToolChainMappingEditorService mappingEditorService = mock(ToolChainMappingEditorService.class);
        ToolChainRunRepository runRepository = mock(ToolChainRunRepository.class);
        ToolChainRunStepRepository runStepRepository = mock(ToolChainRunStepRepository.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService asyncService = mock(SystemToolChainAsyncService.class);
        SecurityContextService securityContextService = mock(SecurityContextService.class);
        ExpressionValidator expressionValidator = mock(ExpressionValidator.class);
        CodeExecutionService codeExecutionService = mock(CodeExecutionService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        when(securityContextService.currentUserIdOrThrow()).thenReturn("user-1");
        when(asyncService.approveProposal(anyString(), anyString(), anyString())).thenReturn(Optional.of(
                SystemToolChainProposal.builder()
                        .id("proposal-1")
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .status("approved")
                        .confidence("high")
                        .reason("Reusable")
                        .createdAt(1L)
                        .build()
        ));

        ToolChainController controller = new ToolChainController(
                toolChainService,
                runtimeService,
                configChatService,
                mappingEditorService,
                runRepository,
                runStepRepository,
                proposalRepository,
                asyncService,
                securityContextService,
                expressionValidator,
                codeExecutionService,
                objectMapper
        );

        ResponseEntity<?> response = controller.approveSystemToolChainProposal("proposal-1", null);
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue((Boolean) body.get("ok"));
    }

    @Test
    void previewCodeReturnsExecutionPayload() {
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainRuntimeService runtimeService = mock(ToolChainRuntimeService.class);
        ToolChainConfigChatService configChatService = mock(ToolChainConfigChatService.class);
        ToolChainMappingEditorService mappingEditorService = mock(ToolChainMappingEditorService.class);
        ToolChainRunRepository runRepository = mock(ToolChainRunRepository.class);
        ToolChainRunStepRepository runStepRepository = mock(ToolChainRunStepRepository.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService asyncService = mock(SystemToolChainAsyncService.class);
        SecurityContextService securityContextService = mock(SecurityContextService.class);
        ExpressionValidator expressionValidator = mock(ExpressionValidator.class);
        CodeExecutionService codeExecutionService = mock(CodeExecutionService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        when(codeExecutionService.execute(any(), any(), any(), any(), any()))
                .thenReturn(CodeExecutionResult.success(3, "ok", ""));

        ToolChainController controller = new ToolChainController(
                toolChainService,
                runtimeService,
                configChatService,
                mappingEditorService,
                runRepository,
                runStepRepository,
                proposalRepository,
                asyncService,
                securityContextService,
                expressionValidator,
                codeExecutionService,
                objectMapper
        );
        ToolChainDtos.ToolChainCodePreviewRequest request = new ToolChainDtos.ToolChainCodePreviewRequest();
        request.setLanguage("javascript");
        request.setCode("return 1+2;");
        request.setInput(Map.of("a", 1));
        ResponseEntity<?> response = controller.previewCode(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) body.get("success"));
        assertEquals(3, body.get("output"));
    }
}
