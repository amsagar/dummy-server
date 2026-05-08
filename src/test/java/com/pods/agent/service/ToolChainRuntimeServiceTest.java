package com.pods.agent.service;

import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.ToolChainApprovalRepository;
import com.pods.agent.repository.ToolChainRunRepository;
import com.pods.agent.repository.ToolChainRunStepRepository;
import com.pods.agent.service.codeexec.CodeExecutionService;
import com.pods.agent.service.expression.BooleanExpressionEvaluator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        ToolChainRuntimeService runtimeService = newRuntimeService(toolChainService);

        assertThrows(IllegalStateException.class, () ->
                runtimeService.execute("tc-1", null, "api", "user-1", Map.of(), Map.of(), null));
    }

    @Test
    void resolvesSubchainReferenceFromSlugName() throws Exception {
        ToolChainService toolChainService = mock(ToolChainService.class);
        when(toolChainService.getRequired("pods-serviceability-leg-check"))
                .thenThrow(new IllegalArgumentException("ToolChain not found: pods-serviceability-leg-check"));
        when(toolChainService.listAll()).thenReturn(List.of(
                ToolChain.builder()
                        .id("subchain-uuid-1")
                        .name("Pods Serviceability Leg Check")
                        .build()
        ));
        ToolChainRuntimeService runtimeService = newRuntimeService(toolChainService);
        Method method = ToolChainRuntimeService.class.getDeclaredMethod("resolveToolChainReference", String.class);
        method.setAccessible(true);
        Object resolved = method.invoke(runtimeService, "pods-serviceability-leg-check");
        assertEquals("subchain-uuid-1", resolved);
    }

    @Test
    void findsDecisionRequiredInputInsideStepOutputEnvelope() throws Exception {
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainRuntimeService runtimeService = newRuntimeService(toolChainService);
        Method method = ToolChainRuntimeService.class.getDeclaredMethod(
                "findRequiredInputInContext", Map.class, String.class);
        method.setAccessible(true);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("extract_legs", Map.of(
                "output", Map.of("journeyType", "Long Distance"),
                "stdout", ""
        ));

        Object resolved = method.invoke(runtimeService, context, "journeyType");
        assertEquals("Long Distance", resolved);
    }

    private ToolChainRuntimeService newRuntimeService(ToolChainService toolChainService) {
        return new ToolChainRuntimeService(
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
                new BooleanExpressionEvaluator(),
                mock(CodeExecutionService.class)
        );
    }
}
