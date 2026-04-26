package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the per-turn list of {@link ToolCallback}s exposed to the LLM.
 * Tools are constructed per-turn because each callback captures the
 * session-scoped {@link SseEventSender} used to emit tool.call/tool.result events.
 */
@Component
public class AgentToolCallbackFactory {

    private final ToolRegistryService toolRegistryService;
    private final ToolExecutionService toolExecutionService;
    private final GuardrailPolicyEngine policyEngine;
    private final PendingInteractionService pendingInteractionService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final ObjectMapper objectMapper;
    private final RuntimeEventRepository runtimeEventRepository;

    public AgentToolCallbackFactory(ToolRegistryService toolRegistryService,
                                    ToolExecutionService toolExecutionService,
                                    GuardrailPolicyEngine policyEngine,
                                    PendingInteractionService pendingInteractionService,
                                    RuntimeTuningProperties runtimeTuningProperties,
                                    ObjectMapper objectMapper,
                                    RuntimeEventRepository runtimeEventRepository) {
        this.toolRegistryService = toolRegistryService;
        this.toolExecutionService = toolExecutionService;
        this.policyEngine = policyEngine;
        this.pendingInteractionService = pendingInteractionService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.objectMapper = objectMapper;
        this.runtimeEventRepository = runtimeEventRepository;
    }

    public List<ToolCallback> buildForTurn(String sessionId, String turnId, SseEventSender sender) {
        return buildForTurn(sessionId, turnId, sender, toolRegistryService.getEnabledTools());
    }

    public List<ToolCallback> buildForTurn(String sessionId,
                                           String turnId,
                                           SseEventSender sender,
                                           List<AgentTool> selectedTools) {
        long timeoutMs = runtimeTuningProperties.getHitlReplyTimeoutMs();
        List<AgentTool> tools = (selectedTools == null || selectedTools.isEmpty())
                ? toolRegistryService.getEnabledTools()
                : selectedTools;
        return tools.stream()
                .filter(Objects::nonNull)
                .map((AgentTool t) -> (ToolCallback) new AgentToolCallback(
                        t,
                        toolExecutionService,
                        policyEngine,
                        pendingInteractionService,
                        sender,
                        sessionId,
                        turnId,
                        timeoutMs,
                        objectMapper,
                        runtimeEventRepository))
                .collect(Collectors.toList());
    }
}
