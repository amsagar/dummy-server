package com.pods.agent.service;

import com.pods.agent.agent.AgentOrchestrator;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.tool.AgentToolCallbackFactory;
import org.springframework.ai.tool.ToolCallback;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.RuntimeTraceRepository;
import com.pods.agent.service.mcp.McpRuntimeAdapter;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AgentRuntimeService {
    private enum SkillContextMode { NONE, CATALOG_ONLY, FULL_SKILL_FILES }
    private enum PlannerState { PLAN, APPROVAL, EXECUTE_PARALLEL, SYNTHESIZE, REPLAN, DONE }

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b");

    private final AgentOrchestrator orchestrator;
    private final ToolRegistryService toolRegistryService;
    private final GuardrailPolicyEngine policyEngine;
    private final RuntimeEventRepository runtimeEventRepository;
    private final ModelAutoRouterService modelAutoRouterService;
    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final RuntimeHookRegistryService hookRegistryService;
    private final ToolExecutionService toolExecutionService;
    private final RuntimeTraceRepository runtimeTraceRepository;
    private final PendingInteractionService pendingInteractionService;
    private final RuntimeToolDescriptorService runtimeToolDescriptorService;
    private final McpRuntimeAdapter mcpRuntimeAdapter;
    private final ContextSummarizationService summarizationService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final AgentToolCallbackFactory agentToolCallbackFactory;
    private final ToolEmbeddingIndexService toolEmbeddingIndexService;
    private final EmbeddingAutoRouterService embeddingAutoRouterService;
    private final Map<String, Map<String, Double>> toolMemorySignals = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> toolDomainMemorySignals = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> skillMemorySignals = new ConcurrentHashMap<>();

    public AgentRuntimeService(AgentOrchestrator orchestrator,
                               ToolRegistryService toolRegistryService,
                               GuardrailPolicyEngine policyEngine,
                               RuntimeEventRepository runtimeEventRepository,
                               ModelAutoRouterService modelAutoRouterService,
                               ModelProviderRouter modelProviderRouter,
                               SkillRegistryService skillRegistryService,
                               RuntimeHookRegistryService hookRegistryService,
                               ToolExecutionService toolExecutionService,
                               RuntimeTraceRepository runtimeTraceRepository,
                               PendingInteractionService pendingInteractionService,
                               RuntimeToolDescriptorService runtimeToolDescriptorService,
                               McpRuntimeAdapter mcpRuntimeAdapter,
                               ContextSummarizationService summarizationService,
                               RuntimeTuningProperties runtimeTuningProperties,
                               ObjectMapper objectMapper,
                               MemoryService memoryService,
                               AgentToolCallbackFactory agentToolCallbackFactory,
                               ToolEmbeddingIndexService toolEmbeddingIndexService,
                               EmbeddingAutoRouterService embeddingAutoRouterService) {
        this.orchestrator = orchestrator;
        this.toolRegistryService = toolRegistryService;
        this.policyEngine = policyEngine;
        this.runtimeEventRepository = runtimeEventRepository;
        this.modelAutoRouterService = modelAutoRouterService;
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.hookRegistryService = hookRegistryService;
        this.toolExecutionService = toolExecutionService;
        this.runtimeTraceRepository = runtimeTraceRepository;
        this.pendingInteractionService = pendingInteractionService;
        this.runtimeToolDescriptorService = runtimeToolDescriptorService;
        this.mcpRuntimeAdapter = mcpRuntimeAdapter;
        this.summarizationService = summarizationService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
        this.toolEmbeddingIndexService = toolEmbeddingIndexService;
        this.embeddingAutoRouterService = embeddingAutoRouterService;
    }

    public String runTurn(AgentSession session, String userText, ChatState state, SseEventSender sender) {
        return runTurn(session, userText, state, sender, UUID.randomUUID().toString());
    }

    public String runTurn(AgentSession session, String userText, ChatState state, SseEventSender sender, String turnId) {
        String runtimeMode = "aicore_loop";
        state.setRuntimeMode(runtimeMode);
        String normalizedUserText = userText == null ? "" : userText.trim();
        session.getMessages().add(new UserMessage(normalizedUserText));

        if ("auto".equalsIgnoreCase(state.getModelSelectionMode())) {
            ModelRef auto = modelAutoRouterService.pickModel(userText, session.getMessages().size(), true);
            if (auto != null) state.setModel(auto);
            sender.sendStateUpdated(session.getSessionId(), state);
        }

        hookRegistryService.emit("pre-prompt", java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId, "mode", runtimeMode));
        sender.sendPlanCreated(session.getSessionId(), runtimeMode, "Runtime mode: " + runtimeMode);
        transitionState(session.getSessionId(), turnId, sender, PlannerState.PLAN, "turn-start");
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId())
                .turnId(turnId)
                .eventType("plan.created")
                .payload("{\"mode\":\"" + runtimeMode + "\"}")
                .build());
        if (isCapabilitiesQuery(userText)) {
            if (runtimeTuningProperties.isStrictScopeOnly() && !runtimeTuningProperties.isAllowCapabilitiesQueries()) {
                transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "strict-out-of-scope");
                return outOfScopeRefusalMessage();
            }
            transitionState(session.getSessionId(), turnId, sender, PlannerState.SYNTHESIZE, "capabilities-fast-path");
            String response = buildCapabilitiesResponse();
            transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "capabilities-complete");
            hookRegistryService.emit("post-response", java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                    .payload("{\"task\":\"capabilities_complete\"}").build());
            return response;
        }

        String mcpContext = buildMcpContext();
        return runModelDrivenCoreLoop(session, normalizedUserText, state, sender, turnId, runtimeMode, mcpContext);
    }

    private String runModelDrivenCoreLoop(AgentSession session,
                                          String userText,
                                          ChatState state,
                                          SseEventSender sender,
                                          String turnId,
                                          String runtimeMode,
                                          String mcpContext) {
        String stepId = "step-1";
        transitionState(session.getSessionId(), turnId, sender, PlannerState.PLAN, "model-driven-" + stepId + "-plan");
        sender.sendTaskStarted(session.getSessionId(), stepId, "MODEL_STEP");
        sender.sendStepStarted(session.getSessionId(), 1, null);
        saveSessionEvent(session.getSessionId(), turnId, "step.started", "{\"step\":1}");

        if (shouldCompactLoopContext(session, state)) {
            compactSessionInLoop(session, state, sender, turnId, 1);
        }

        List<AgentTool> baseTools = toolRegistryService.getBaseInjectedTools();
        if (baseTools == null) baseTools = List.of();
        Set<String> baseIds = baseTools.stream()
                .filter(t -> t != null && t.getId() != null)
                .map(AgentTool::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Double> memorySignals = loadToolMemorySignals(session.getSessionId());
        ModelRef embeddingModelRef = embeddingAutoRouterService.pickEmbeddingModel(state);

        List<AgentTool> tools = selectTurnTools(userText, baseTools, baseIds, memorySignals, embeddingModelRef, session.getSessionId());

        List<ToolCallback> toolCallbacks = agentToolCallbackFactory.buildForTurn(
                session.getSessionId(), turnId, sender, tools);
        String selectedSkillContext = buildSelectedSkillContext(session, userText, state);
        String stepContext = buildStepContext(userText, selectedSkillContext, mcpContext, runtimeMode, session, "");

        log.debug("[AgentRuntime] streamTurn start: sessionId={}, turnId={}, tools={}",
                session.getSessionId(), turnId, toolCallbacks.size());

        String response;
        try {
            response = orchestrator.streamTurn(session, stepContext, state, sender, toolCallbacks);
        } catch (Exception e) {
            log.error("[AgentRuntime] streamTurn failed: {}", e.getMessage(), e);
            response = "I hit an error generating the response: " + e.getMessage();
            sender.sendTextDelta(response);
            session.getMessages().add(new AssistantMessage(response));
        }

        recordSelectionSignals(session.getSessionId(), userText, tools, response);

        sender.sendTaskDone(session.getSessionId(), stepId, "FINAL");
        sender.sendStepFinished(session.getSessionId(), 1, "final", false);
        saveSessionEvent(session.getSessionId(), turnId, "step.finished",
                "{\"step\":1,\"mode\":\"final\"}");
        transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "core-loop-complete");
        hookRegistryService.emit("post-response",
                java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                .payload("{\"task\":\"core_loop_complete\"}").build());
        return response == null ? "" : response;
    }

    private List<AgentTool> selectTurnTools(String userText,
                                            List<AgentTool> baseTools,
                                            Set<String> baseIds,
                                            Map<String, Double> memorySignals,
                                            ModelRef embeddingModelRef,
                                            String sessionId) {
        int hardCap = Math.max(1, runtimeTuningProperties.getMaxToolCallbacksPerTurn());
        int topK = Math.max(1, Math.min(hardCap,
                runtimeTuningProperties.getToolRetrieval() == null ? 40 : runtimeTuningProperties.getToolRetrieval().getTopK()));

        // Try semantic retrieval first when an embedding model is configured.
        List<AgentTool> retrieved = List.of();
        boolean retrievalAttempted = false;
        if (embeddingModelRef != null && toolEmbeddingIndexService != null) {
            retrievalAttempted = true;
            try {
                List<ToolEmbeddingIndexService.ScoredTool> scored = toolEmbeddingIndexService.searchTopK(
                        userText, topK, baseIds, memorySignals, embeddingModelRef);
                if (scored != null && !scored.isEmpty()) {
                    Map<String, AgentTool> byId = new LinkedHashMap<>();
                    for (AgentTool t : toolRegistryService.getEnabledTools()) {
                        if (t != null && t.getId() != null) byId.put(t.getId(), t);
                    }
                    List<AgentTool> ordered = new ArrayList<>();
                    for (var s : scored) {
                        AgentTool t = byId.get(s.toolId());
                        if (t != null) ordered.add(t);
                    }
                    retrieved = ordered;
                }
            } catch (Exception e) {
                log.warn("[AgentRuntime] retrieval failed; falling back: {}", e.getMessage());
            }
        }

        if (!retrievalAttempted || retrieved.isEmpty()) {
            // Fallback: framework defaults + memory-ranked non-defaults capped.
            return capToProviderLimit(baseTools, memoryRankedNonDefaults(memorySignals), hardCap);
        }
        return capToProviderLimit(baseTools, retrieved, hardCap);
    }

    private List<AgentTool> memoryRankedNonDefaults(Map<String, Double> memorySignals) {
        List<AgentTool> nonDefault = toolRegistryService.getNonDefaultEnabledTools();
        if (nonDefault == null || nonDefault.isEmpty()) return List.of();
        if (memorySignals == null || memorySignals.isEmpty()) return nonDefault;
        return nonDefault.stream()
                .sorted((a, b) -> Double.compare(
                        memorySignals.getOrDefault(b.getName() == null ? "" : b.getName(), 0.0),
                        memorySignals.getOrDefault(a.getName() == null ? "" : a.getName(), 0.0)))
                .toList();
    }

    private List<AgentTool> capToProviderLimit(List<AgentTool> baseTools,
                                               List<AgentTool> retrievedOrFallback,
                                               int hardCap) {
        // Framework set non-trimmable. Always include in full.
        List<AgentTool> framework = baseTools == null ? List.of() : baseTools.stream()
                .filter(t -> t != null && t.getId() != null)
                .toList();
        Set<String> seen = framework.stream().map(AgentTool::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<AgentTool> result = new ArrayList<>(framework);

        if (framework.size() >= hardCap) {
            log.warn("[AgentRuntime] framework tool count ({}) >= cap ({}); emitting framework set as-is",
                    framework.size(), hardCap);
            return result;
        }
        int remaining = hardCap - framework.size();
        if (retrievedOrFallback != null) {
            for (AgentTool t : retrievedOrFallback) {
                if (t == null || t.getId() == null) continue;
                if (seen.contains(t.getId())) continue;
                result.add(t);
                seen.add(t.getId());
                if (--remaining <= 0) break;
            }
        }
        return result;
    }

    private String buildRecentConversationContext(AgentSession session, int maxMessages) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return "";
        }
        List<Message> messages = session.getMessages();
        int start = Math.max(0, messages.size() - Math.max(1, maxMessages));
        StringBuilder out = new StringBuilder("Recent conversation context:\n");
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof UserMessage user) {
                String text = user.getText() == null ? "" : user.getText().trim();
                if (!text.isBlank()) {
                    out.append("- user: ").append(truncate(text, 600)).append("\n");
                }
            } else if (message instanceof AssistantMessage assistant) {
                String text = assistant.getText() == null ? "" : assistant.getText().trim();
                if (!text.isBlank()) {
                    out.append("- assistant: ").append(truncate(text, 600)).append("\n");
                }
            }
        }
        return out.toString().trim();
    }

    private String buildStepContext(String userText,
                                    String selectedSkillContext,
                                    String mcpContext,
                                    String runtimeMode,
                                    AgentSession session,
                                    String observationContext) {
        StringBuilder context = new StringBuilder();
        context.append("Original user request:\n").append(userText == null ? "" : userText).append("\n\n");
        if (selectedSkillContext != null && !selectedSkillContext.isBlank()) {
            context.append(selectedSkillContext).append("\n\n");
        }
        if (mcpContext != null && !mcpContext.isBlank()) {
            context.append(mcpContext).append("\n\n");
        }
        String historyContext = buildRecentConversationContext(session, 10);
        if (!historyContext.isBlank()) {
            context.append(historyContext).append("\n\n");
        }
        String entityContext = buildEntityCarryForwardContext(session, userText);
        if (!entityContext.isBlank()) {
            context.append(entityContext).append("\n\n");
        }
        if (observationContext != null && !observationContext.isBlank()) {
            context.append("Tool observations:\n").append(observationContext).append("\n\n");
        }
        context.append("Runtime mode: ").append(runtimeMode).append("\n");
        return context.toString();
    }

    private String buildEntityCarryForwardContext(AgentSession session, String userText) {
        if (session == null || session.getSessionId() == null) return "";
        Set<String> ids = new LinkedHashSet<>();
        List<Message> messages = session.getMessages() == null ? List.of() : session.getMessages();
        int start = Math.max(0, messages.size() - 16);
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            String text = "";
            if (m instanceof UserMessage user) {
                text = user.getText();
            } else if (m instanceof AssistantMessage assistant) {
                text = assistant.getText();
            }
            collectUuids(text, ids);
        }
        try {
            List<com.pods.agent.domain.RuntimeTrace> traces = runtimeTraceRepository.findBySession(session.getSessionId());
            int traceStart = Math.max(0, traces.size() - 8);
            for (int i = traceStart; i < traces.size(); i++) {
                var trace = traces.get(i);
                if (trace == null) continue;
                if (!"tool".equalsIgnoreCase(trace.getTraceType())) continue;
                collectUuids(trace.getPayload(), ids);
            }
        } catch (Exception ignored) {
        }
        if (ids.isEmpty()) return "";
        String latestId = ids.stream().reduce((a, b) -> b).orElse("");
        StringBuilder out = new StringBuilder("<entity_carry_forward>\n");
        out.append("known_ids: ").append(ids.stream().limit(12).collect(Collectors.joining(", "))).append("\n");
        out.append("latest_id: ").append(latestId).append("\n");
        out.append("tool_routing_hints:\n")
                .append("- If user says 'this product' or 'this organization', reuse latest_id when appropriate.\n")
                .append("- Multiple tool calls in sequence are allowed in the same turn when needed.\n");
        if (userText != null && !userText.isBlank()) {
            out.append("current_user_intent: ").append(truncate(userText, 240)).append("\n");
        }
        out.append("</entity_carry_forward>");
        return out.toString();
    }

    private void collectUuids(String text, Set<String> into) {
        if (text == null || text.isBlank() || into == null) return;
        Matcher matcher = UUID_PATTERN.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group());
        }
    }

    private void recordSelectionSignals(String sessionId,
                                        String userText,
                                        List<AgentTool> toolShortlist,
                                        String response) {
        if (toolShortlist == null || toolShortlist.isEmpty()) return;
        double delta = (response == null || response.isBlank()) ? -0.25 : 0.35;
        Map<String, Double> map = toolMemorySignals.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        Map<String, Double> domainMap = toolDomainMemorySignals.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        String userId = UserContextHolder.currentUserId();
        for (AgentTool tool : toolShortlist) {
            if (tool == null || tool.getName() == null) continue;
            map.merge(tool.getName(), delta, Double::sum);
            if (tool.getDomainId() != null && !tool.getDomainId().isBlank()) {
                domainMap.merge(tool.getDomainId(), delta, Double::sum);
            }
            if (memoryService != null && userId != null && !userId.isBlank()) {
                memoryService.recordToolSignal(userId, tool.getName(), delta);
                if (tool.getDomainId() != null && !tool.getDomainId().isBlank()) {
                    memoryService.recordToolDomainSignal(userId, tool.getDomainId(), delta);
                }
            }
        }
        List<SkillRegistryService.SkillSnapshot> skillShortlist = buildSkillShortlist(userText, sessionId);
        if (skillShortlist.isEmpty()) return;
        Map<String, Double> skillMap = skillMemorySignals.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        for (SkillRegistryService.SkillSnapshot snapshot : skillShortlist) {
            if (snapshot == null || snapshot.skill() == null || snapshot.skill().getName() == null) continue;
            skillMap.merge(snapshot.skill().getName(), delta, Double::sum);
            if (memoryService != null && userId != null && !userId.isBlank()) {
                memoryService.recordSkillSignal(userId, snapshot.skill().getName(), delta);
            }
        }
    }

    private boolean shouldCompactLoopContext(AgentSession session, ChatState state) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) return false;
        String providerHint = state != null && state.getModel() != null ? state.getModel().providerID() : null;
        long estimated = summarizationService.estimateTokens(session, providerHint);
        long threshold = Math.max(256, runtimeTuningProperties.getSummaryTokenThreshold());
        return estimated >= threshold && session.getMessages().size() > runtimeTuningProperties.getSummaryRetainRecentMessages();
    }

    private void compactSessionInLoop(AgentSession session,
                                      ChatState state,
                                      SseEventSender sender,
                                      String turnId,
                                      int step) {
        String currentSummary = state != null ? state.getRollingSummary() : null;
        String providerHint = state != null && state.getModel() != null ? state.getModel().providerID() : null;
        ContextSummarizationService.CompactionResult compaction = summarizationService.maybeSummarize(
                session,
                currentSummary,
                runtimeTuningProperties.getSummaryTokenThreshold(),
                providerHint,
                runtimeTuningProperties.getSummaryRetainRecentMessages());
        if (!compaction.compacted()) return;
        summarizationService.retainRecentMessages(session, runtimeTuningProperties.getSummaryRetainRecentMessages());
        if (state != null) {
            state.setRollingSummary(compaction.summary());
        }
        sender.sendSummaryUpdated(session.getSessionId(), compaction.summary());
        saveSessionEvent(session.getSessionId(), turnId, "summary.updated",
                "{\"step\":" + step + ",\"removedMessages\":" + compaction.removedMessages() + ",\"retainedMessages\":" + compaction.retainedMessages() + "}");
    }

    private String buildMcpContext() {
        if (!runtimeTuningProperties.isIncludeMcpPromptHints()) {
            return "";
        }
        try {
            List<Map<String, Object>> tools = mcpRuntimeAdapter.listRuntimeTools();
            if (tools.isEmpty()) return "";
            List<String> lines = tools.stream()
                    .limit(30)
                    .map(t -> String.valueOf(t.getOrDefault("name", "tool"))
                            + " @ " + String.valueOf(t.getOrDefault("serverName", "mcp")))
                    .toList();
            return "<mcp_tools>\n" + String.join("\n", lines) + "\n</mcp_tools>";
        } catch (Exception e) {
            return "";
        }
    }

    private String buildSelectedSkillContext(AgentSession session, String userText, ChatState state) {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills.isEmpty()) return "";
        SkillContextMode mode = resolveSkillContextMode(userText);
        if (mode == SkillContextMode.NONE) return "";
        if (mode == SkillContextMode.CATALOG_ONLY) return buildSkillCatalogContext(skills);
        List<SkillRegistryService.SkillSnapshot> shortlisted = buildSkillShortlist(userText, session.getSessionId());
        if (shortlisted.isEmpty()) {
            if (runtimeTuningProperties.isSkillShortlistFallbackToCatalogOnMiss()) {
                return buildSkillCatalogContext(skills);
            }
            shortlisted = skills;
        }

        try {
            List<Map<String, String>> skillCatalog = shortlisted.stream()
                    .limit(40)
                    .map(s -> Map.of(
                            "name", s.skill().getName(),
                            "description", s.skill().getDescription() == null ? "" : s.skill().getDescription()
                    ))
                    .toList();

            String prompt = "User request:\n" + userText + "\n\n"
                    + "Available skills JSON:\n"
                    + objectMapper.writeValueAsString(skillCatalog)
                    + "\n\nReturn ONLY JSON object:\n"
                    + "{\"selectedSkills\":[\"skill1\",\"skill2\"],\"reason\":\"short\"}";

            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(state != null ? state.getModel() : null);
            String raw = spec.client()
                    .prompt()
                    .system("You choose relevant skills for an agent run. Output JSON only.")
                    .user(prompt)
                    .call()
                    .content();
            if (raw == null || raw.isBlank()) return "";

            String json = extractJsonObject(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            List<String> selected = new ArrayList<>();
            Object selectedRaw = parsed.get("selectedSkills");
            if (selectedRaw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) selected.add(String.valueOf(item));
                }
            }
            if (selected.isEmpty()) return "";

            int skillLimit = resolveSkillShortlistSize();
            List<String> selectedNormalized = selected.stream().map(String::trim).filter(s -> !s.isBlank()).limit(skillLimit).toList();
            List<SkillRegistryService.SkillSnapshot> selectedSkills = shortlisted.stream()
                    .filter(s -> selectedNormalized.stream().anyMatch(name -> name.equalsIgnoreCase(s.skill().getName())))
                    .limit(skillLimit)
                    .toList();
            if (selectedSkills.isEmpty()) {
                selectedSkills = fallbackSelectSkills(userText, shortlisted, skillLimit);
            }
            if (selectedSkills.isEmpty()) return buildSkillCatalogContext(shortlisted);

            return buildSkillContentContext(session, selectedSkills);
        } catch (Exception e) {
            log.warn("[AgentRuntime] AI skill selector failed, continuing without skill context: {}", e.getMessage());
            List<SkillRegistryService.SkillSnapshot> fallback = fallbackSelectSkills(userText, shortlisted, resolveSkillShortlistSize());
            if (!fallback.isEmpty()) {
                return buildSkillContentContext(session, fallback);
            }
            return buildSkillCatalogContext(shortlisted);
        }
    }

    private List<SkillRegistryService.SkillSnapshot> buildSkillShortlist(String userText, String sessionId) {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (!runtimeTuningProperties.isDynamicSkillExposureEnabled() || skills.size() <= 1) {
            return skills;
        }
        int shortlistSize = resolveSkillShortlistSize();
        if (skills.size() <= shortlistSize) return skills;

        Set<String> userTokens = simpleTokenize(userText);
        Map<String, Double> memoryScores = loadSkillMemorySignals(sessionId);
        double memoryWeight = Math.max(0.0, runtimeTuningProperties.getSkillMemoryBiasWeight());
        List<Map.Entry<SkillRegistryService.SkillSnapshot, Double>> ranked = skills.stream()
                .map(snapshot -> {
                    int lexical = scoreSkill(userTokens, snapshot);
                    String name = snapshot.skill() == null ? "" : snapshot.skill().getName();
                    double score = lexical + (memoryScores.getOrDefault(name, 0.0) * memoryWeight * 10.0);
                    return Map.entry(snapshot, score);
                })
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
        List<SkillRegistryService.SkillSnapshot> selected = ranked.stream()
                .limit(shortlistSize)
                .map(Map.Entry::getKey)
                .toList();
        if (selected.isEmpty() && runtimeTuningProperties.isSkillShortlistFallbackToCatalogOnMiss()) {
            return skills;
        }
        return selected;
    }

    private Map<String, Double> loadToolMemorySignals(String sessionId) {
        Map<String, Double> sessionSignals = toolMemorySignals.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        String userId = UserContextHolder.currentUserId();
        if (memoryService == null || userId == null || userId.isBlank()) {
            return sessionSignals;
        }
        Map<String, Double> persisted = memoryService.loadToolSignals(userId);
        if (persisted == null || persisted.isEmpty()) {
            return sessionSignals;
        }
        persisted.forEach((k, v) -> sessionSignals.merge(k, v, Double::sum));
        return sessionSignals;
    }

    private Map<String, Double> loadSkillMemorySignals(String sessionId) {
        Map<String, Double> sessionSignals = skillMemorySignals.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        String userId = UserContextHolder.currentUserId();
        if (memoryService == null || userId == null || userId.isBlank()) {
            return sessionSignals;
        }
        Map<String, Double> persisted = memoryService.loadSkillSignals(userId);
        if (persisted == null || persisted.isEmpty()) {
            return sessionSignals;
        }
        persisted.forEach((k, v) -> sessionSignals.merge(k, v, Double::sum));
        return sessionSignals;
    }

    @SuppressWarnings("unused")
    private Map<String, Double> loadToolDomainMemorySignals(String sessionId) {
        Map<String, Double> sessionSignals = toolDomainMemorySignals.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        String userId = UserContextHolder.currentUserId();
        if (memoryService == null || userId == null || userId.isBlank()) {
            return sessionSignals;
        }
        Map<String, Double> persisted = memoryService.loadToolDomainSignals(userId);
        if (persisted == null || persisted.isEmpty()) {
            return sessionSignals;
        }
        persisted.forEach((k, v) -> sessionSignals.merge(k, v, Double::sum));
        return sessionSignals;
    }

    private int resolveSkillShortlistSize() {
        return Math.max(1, runtimeTuningProperties.getSkillShortlistDefaultSize());
    }

    private SkillContextMode resolveSkillContextMode(String userText) {
        if (userText == null || userText.isBlank()) return SkillContextMode.NONE;
        if (isCapabilitiesQuery(userText) || isCatalogOnlySkillQuery(userText)) {
            return SkillContextMode.CATALOG_ONLY;
        }
        if (isTaskOrExecutionIntent(userText)) {
            return runtimeTuningProperties.isIncludeFullSkillFiles()
                    ? SkillContextMode.FULL_SKILL_FILES
                    : SkillContextMode.CATALOG_ONLY;
        }
        return SkillContextMode.NONE;
    }

    private boolean isCapabilitiesQuery(String userText) {
        if (userText == null) return false;
        String low = userText.toLowerCase(Locale.ROOT);
        return (low.contains("what skills") || low.contains("available skills") || low.contains("which skills"))
                || (low.contains("capabilities") && low.contains("skill"))
                || (low.contains("what can you do") && low.contains("skill"));
    }

    private boolean isCatalogOnlySkillQuery(String userText) {
        if (userText == null) return false;
        String low = userText.toLowerCase(Locale.ROOT);
        return (low.contains("list skills") || low.contains("show skills") || low.contains("skills available"))
                && !isTaskOrExecutionIntent(userText);
    }

    private boolean isTaskOrExecutionIntent(String userText) {
        if (userText == null) return false;
        String low = userText.toLowerCase(Locale.ROOT);
        if (low.startsWith("/") || low.startsWith("tool:")) return true;
        return List.of(
                        "create", "build", "generate", "extract", "merge", "split", "fill", "convert",
                        "analyze", "summarize", "parse", "fix", "run", "execute", "write", "read", "use"
                ).stream()
                .anyMatch(low::contains);
    }

    private String buildCapabilitiesResponse() {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills.isEmpty()) {
            return "No skills are currently available.";
        }
        StringBuilder out = new StringBuilder("Available skills:\n");
        for (SkillRegistryService.SkillSnapshot snapshot : skills) {
            out.append("- ").append(snapshot.skill().getName());
            String description = snapshot.skill().getDescription();
            if (description != null && !description.isBlank()) {
                out.append(": ").append(description);
            }
            out.append("\n");
        }
        return out.toString().trim();
    }

    private String buildSkillCatalogContext(List<SkillRegistryService.SkillSnapshot> skills) {
        if (skills == null || skills.isEmpty()) return "";
        StringBuilder context = new StringBuilder("<skill_catalog>\n");
        for (SkillRegistryService.SkillSnapshot snapshot : skills.stream().limit(20).toList()) {
            context.append("- ").append(snapshot.skill().getName());
            String description = snapshot.skill().getDescription();
            if (description != null && !description.isBlank()) {
                context.append(": ").append(description);
            }
            context.append("\n");
        }
        context.append("</skill_catalog>");
        return context.toString();
    }

    private List<SkillRegistryService.SkillSnapshot> fallbackSelectSkills(String userText,
                                                                          List<SkillRegistryService.SkillSnapshot> skills,
                                                                          int limit) {
        if (userText == null || skills == null || skills.isEmpty()) return List.of();
        Set<String> tokens = simpleTokenize(userText);
        return skills.stream()
                .map(snapshot -> Map.entry(snapshot, scoreSkill(tokens, snapshot)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(Math.max(1, limit))
                .toList();
    }

    private int scoreSkill(Set<String> userTokens, SkillRegistryService.SkillSnapshot snapshot) {
        if (snapshot == null || snapshot.skill() == null) return 0;
        String name = snapshot.skill().getName() == null ? "" : snapshot.skill().getName();
        String description = snapshot.skill().getDescription() == null ? "" : snapshot.skill().getDescription();
        int score = 0;
        score += overlap(userTokens, simpleTokenize(name)) * 8;
        score += overlap(userTokens, simpleTokenize(description)) * 5;
        return score;
    }

    private String buildSkillContentContext(AgentSession session, List<SkillRegistryService.SkillSnapshot> selectedSkills) {
        if (selectedSkills == null || selectedSkills.isEmpty()) return "";
        StringBuilder context = new StringBuilder();
        context.append("<skill_workspace_manifest>\n");
        Path workspace = session != null ? session.getWorkspacePath() : null;
        if (workspace != null) {
            Path skillRoot = workspace.resolve(".pods-agent/skills").normalize();
            context.append("root=workspace://skills\n");
            if (Files.exists(skillRoot)) {
                try (var walk = Files.walk(skillRoot)) {
                    walk.filter(Files::isRegularFile)
                            .limit(200)
                            .forEach(p -> context.append("- ").append(skillRoot.relativize(p)).append("\n"));
                } catch (Exception ignored) {
                }
            }
        }
        context.append("</skill_workspace_manifest>\n");

        for (SkillRegistryService.SkillSnapshot snapshot : selectedSkills) {
            String skillName = snapshot.skill().getName();
            context.append("<skill_content name=\"").append(skillName).append("\">\n");
            String skillMd = snapshot.files().entrySet().stream()
                    .filter(e -> "SKILL.md".equalsIgnoreCase(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
            if (!skillMd.isBlank()) {
                context.append(skillMd).append("\n");
            }
            List<Map.Entry<String, String>> otherFiles = snapshot.files().entrySet().stream()
                    .filter(e -> !"SKILL.md".equalsIgnoreCase(e.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            if (!otherFiles.isEmpty()) {
                context.append("\n<skill_files>\n");
                for (Map.Entry<String, String> file : otherFiles) {
                    context.append("<file path=\"").append(file.getKey()).append("\">\n");
                    context.append(file.getValue());
                    context.append("\n</file>\n");
                }
                context.append("</skill_files>\n");
            }
            context.append("</skill_content>\n");
        }
        return context.toString().trim();
    }

    private String extractJsonObject(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstBrace = text.indexOf('{');
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private PendingInteractionService.QuestionMetadata textQuestionMetadata() {
        return new PendingInteractionService.QuestionMetadata("text", List.of(), true, null, null);
    }

    private String outOfScopeRefusalMessage() {
        String configured = runtimeTuningProperties.getOutOfScopeRefusalMessage();
        if (configured == null || configured.isBlank()) {
            return "I can’t answer this because it is outside my allowed skills/tools scope.";
        }
        return configured;
    }

    private void transitionState(String sessionId, String turnId, SseEventSender sender, PlannerState state, String reason) {
        sender.sendStateUpdated(sessionId, java.util.Map.of("plannerState", state.name(), "reason", reason));
        if (runtimeTuningProperties.isPersistInternalEvents()) {
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .eventType("state.transition")
                    .payload("{\"state\":\"" + state.name() + "\",\"reason\":\"" + sanitize(reason) + "\"}")
                    .build());
        }
        runtimeTraceRepository.save(com.pods.agent.domain.RuntimeTrace.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .traceType("plan")
                .correlationId("state-" + state.name().toLowerCase())
                .payload(reason)
                .build());
    }

    private String sanitize(String message) {
        if (message == null) return "";
        return message.replace("\"", "'").replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    private void saveSessionEvent(String sessionId, String turnId, String eventType, String payload) {
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .eventType(eventType)
                .payload(payload)
                .build());
    }

    @SuppressWarnings("unused")
    private String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + sanitize(value) + "\"";
    }

    @SuppressWarnings("unused")
    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        return "[" + values.stream().map(this::jsonString).collect(Collectors.joining(",")) + "]";
    }

    private Set<String> simpleTokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = text
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('/', ' ');
        return Pattern.compile("[^a-z0-9]+")
                .splitAsStream(normalized)
                .filter(token -> token.length() > 1)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private int overlap(Set<String> source, Set<String> target) {
        if (source.isEmpty() || target.isEmpty()) return 0;
        int matches = 0;
        for (String token : source) {
            if (target.contains(token)) matches++;
        }
        return matches;
    }
}
