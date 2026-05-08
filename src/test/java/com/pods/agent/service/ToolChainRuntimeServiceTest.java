package com.pods.agent.service;

import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.ToolChainApprovalRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainRunStepRepository;
import com.pods.agent.service.expression.BooleanExpressionEvaluator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolChainRuntimeServiceTest {

    @Test
    void blocksExecutionForPendingSystemSuggestedToolChain() {
        ToolChainService toolChainService = mock(ToolChainService.class);
        when(toolChainService.getRequired("tc-1")).thenReturn(
                ToolChain.builder()
                        .id("tc-1")
                        .origin(ToolChainService.ORIGIN_SYSTEM_SUGGESTED)
                        .approvalStatus(ToolChainService.APPROVAL_PENDING)
                        .build()
        );

        ToolChainRuntimeService runtimeService = new ToolChainRuntimeService(
                toolChainService,
                mock(ToolRegistryService.class),
                mock(ToolExecutionService.class),
                mock(ToolChainRunRepository.class),
                mock(ToolChainRunStepRepository.class),
                mock(ToolChainApprovalRepository.class),
                mock(RuntimeEventRepository.class),
                mock(com.pods.agent.config.ModelProviderRouter.class),
                mock(SkillRegistryService.class),
                mock(com.pods.agent.config.RuntimeTuningProperties.class),
                mock(DecisionTableService.class),
                new ObjectMapper(),
                new ArgMappingResolver(new BooleanExpressionEvaluator()),
                mock(LlmArgResolver.class),
                new BooleanExpressionEvaluator()
        );

        assertThrows(IllegalStateException.class, () ->
                runtimeService.execute("tc-1", null, "api", "user-1", Map.of(), Map.of(), null));
    }
}
