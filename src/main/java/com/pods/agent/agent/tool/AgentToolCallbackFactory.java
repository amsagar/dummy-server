package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import com.pods.agent.service.UserContextHolder;
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
    private final SkillRegistryService skillRegistryService;

    public AgentToolCallbackFactory(ToolRegistryService toolRegistryService,
                                    ToolExecutionService toolExecutionService,
                                    GuardrailPolicyEngine policyEngine,
                                    PendingInteractionService pendingInteractionService,
                                    SkillRegistryService skillRegistryService,
                                    RuntimeTuningProperties runtimeTuningProperties,
                                    ObjectMapper objectMapper,
                                    RuntimeEventRepository runtimeEventRepository) {
        this.toolRegistryService = toolRegistryService;
        this.toolExecutionService = toolExecutionService;
        this.policyEngine = policyEngine;
        this.pendingInteractionService = pendingInteractionService;
        this.skillRegistryService = skillRegistryService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.objectMapper = objectMapper;
        this.runtimeEventRepository = runtimeEventRepository;
    }

    public List<ToolCallback> buildForTurn(String sessionId, String turnId, SseEventSender sender) {
        return buildForTurn(sessionId, turnId, sender, toolRegistryService.getEnabledTools(), null);
    }

    public List<ToolCallback> buildForTurn(String sessionId,
                                           String turnId,
                                           SseEventSender sender,
                                           List<AgentTool> selectedTools) {
        return buildForTurn(sessionId, turnId, sender, selectedTools, null);
    }

    public List<ToolCallback> buildForTurn(String sessionId,
                                           String turnId,
                                           SseEventSender sender,
                                           List<AgentTool> selectedTools,
                                           SkillExecutionGate skillExecutionGate) {
        return buildForTurn(sessionId, turnId, sender, selectedTools, skillExecutionGate, null, false);
    }

    public List<ToolCallback> buildForTurn(String sessionId,
                                           String turnId,
                                           SseEventSender sender,
                                           List<AgentTool> selectedTools,
                                           SkillExecutionGate skillExecutionGate,
                                           java.nio.file.Path workspace,
                                           boolean bypassApprovalGate) {
        return buildForTurn(sessionId, turnId, sender, selectedTools, skillExecutionGate,
                workspace, bypassApprovalGate, null);
    }

    public List<ToolCallback> buildForTurn(String sessionId,
                                           String turnId,
                                           SseEventSender sender,
                                           List<AgentTool> selectedTools,
                                           SkillExecutionGate skillExecutionGate,
                                           java.nio.file.Path workspace,
                                           boolean bypassApprovalGate,
                                           String workspaceSubroot) {
        long timeoutMs = runtimeTuningProperties.getHitlReplyTimeoutMs();
        String userId = UserContextHolder.currentUserId();
        List<AgentTool> tools = (selectedTools == null || selectedTools.isEmpty())
                ? toolRegistryService.getEnabledTools()
                : selectedTools;
        List<ToolCallback> callbacks = tools.stream()
                .filter(Objects::nonNull)
                .filter(t -> !isSkillTool(t))
                .map((AgentTool t) -> (ToolCallback) new AgentToolCallback(
                        t,
                        toolExecutionService,
                        policyEngine,
                        pendingInteractionService,
                        sender,
                        sessionId,
                        turnId,
                        userId,
                        timeoutMs,
                        objectMapper,
                        runtimeEventRepository,
                        skillExecutionGate,
                        workspace,
                        bypassApprovalGate,
                        runtimeTuningProperties.getToolOutputVfsSpillThresholdChars(),
                        runtimeTuningProperties.getToolIoLogMode(),
                        runtimeTuningProperties.isProductionEnvironment(),
                        workspaceSubroot))
                .collect(Collectors.toList());

        java.util.Set<String> registeredNames = tools.stream()
                .filter(Objects::nonNull)
                .map(AgentTool::getName)
                .filter(Objects::nonNull)
                .map(n -> n.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(java.util.HashSet::new));

        if (userId != null && !userId.isBlank()) {
            for (AgentTool memoryTool : MemoryToolDefinitions.all()) {
                String key = memoryTool.getName().toLowerCase(java.util.Locale.ROOT);
                if (registeredNames.contains(key)) {
                    continue;
                }
                callbacks.add(new AgentToolCallback(
                        memoryTool,
                        toolExecutionService,
                        policyEngine,
                        pendingInteractionService,
                        sender,
                        sessionId,
                        turnId,
                        userId,
                        timeoutMs,
                        objectMapper,
                        runtimeEventRepository,
                        skillExecutionGate,
                        workspace,
                        bypassApprovalGate,
                        runtimeTuningProperties.getToolOutputVfsSpillThresholdChars(),
                        runtimeTuningProperties.getToolIoLogMode(),
                        runtimeTuningProperties.isProductionEnvironment()));
                registeredNames.add(key);
            }
        }

        for (AgentTool retrievalTool : RetrievalToolDefinitions.all()) {
            String key = retrievalTool.getName().toLowerCase(java.util.Locale.ROOT);
            if (registeredNames.contains(key)) {
                continue;
            }
            callbacks.add(new AgentToolCallback(
                    retrievalTool,
                    toolExecutionService,
                    policyEngine,
                    pendingInteractionService,
                    sender,
                    sessionId,
                    turnId,
                    userId,
                    timeoutMs,
                    objectMapper,
                    runtimeEventRepository,
                    skillExecutionGate,
                    workspace,
                    bypassApprovalGate,
                    runtimeTuningProperties.getToolOutputVfsSpillThresholdChars(),
                    runtimeTuningProperties.getToolIoLogMode(),
                    runtimeTuningProperties.isProductionEnvironment()));
            registeredNames.add(key);
        }

        callbacks.add(new SkillToolCallback(
                skillRegistryService,
                runtimeTuningProperties,
                sender,
                sessionId,
                turnId,
                objectMapper,
                runtimeEventRepository,
                skillExecutionGate,
                runtimeTuningProperties.getToolIoLogMode(),
                runtimeTuningProperties.isProductionEnvironment()
        ));
        return callbacks;
    }

    private boolean isSkillTool(AgentTool tool) {
        if (tool == null || tool.getName() == null) return false;
        return "skill".equalsIgnoreCase(tool.getName().trim());
    }
}
