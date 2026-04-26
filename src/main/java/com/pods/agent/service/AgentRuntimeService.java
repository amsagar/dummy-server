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
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private enum LoopDirective { CONTINUE, STOP, COMPACT }
    private record ToolInvocation(AgentTool tool, String payload) {}
    private record ToolRunOutcome(String contextLine, boolean terminateTurn, String terminalResponse, boolean meaningfulEvidence) {}
    private record ToolCandidate(AgentTool tool, int score) {}
    private record ToolMatchResult(ToolInvocation invocation,
                                   String clarificationPrompt,
                                   PendingInteractionService.QuestionMetadata clarificationMetadata) {}
    private record AiToolDecision(String selectedTool,
                                  boolean needsClarification,
                                  boolean noToolNeeded,
                                  String reason,
                                  String clarificationPrompt,
                                  List<String> candidates) {}
    private record CatalogCandidate(AgentTool tool, double score, List<String> requiredArgs, List<String> missingArgs) {}
    private record LoopStepOutcome(LoopDirective directive, String response) {}

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "get", "give", "how", "i", "in",
            "is", "it", "me", "my", "of", "on", "or", "please", "show", "the", "to", "we", "with", "you", "your"
    );
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
    private final Map<String, Map<String, Double>> toolMemorySignals = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> toolDomainMemorySignals = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> skillMemorySignals = new ConcurrentHashMap<>();

    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(4);

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
                               AgentToolCallbackFactory agentToolCallbackFactory) {
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

        String response = "";
        int maxRetries = Math.max(0, runtimeTuningProperties.getQualityExpansionMaxRetries());
        boolean expanded = false;
        boolean catalogInjected = false;
        int expansionRetriesUsed = 0;
        List<AgentTool> baseTools = buildBaseToolSet();
        List<AgentTool> selectedNonDefault = List.of();
        int safetyPasses = Math.max(2, maxRetries + 3);

        for (int pass = 0; pass < safetyPasses; pass++) {
            if (catalogInjected) {
                selectedNonDefault = buildCatalogToolShortlist(userText, session.getSessionId(), expanded, baseTools.size());
            } else {
                selectedNonDefault = List.of();
            }
            List<AgentTool> toolShortlist = mergeWithProviderCap(baseTools, selectedNonDefault, userText, session.getSessionId());
            List<ToolCallback> toolCallbacks = agentToolCallbackFactory.buildForTurn(
                    session.getSessionId(), turnId, sender, toolShortlist);
            String selectedSkillContext = buildSelectedSkillContext(session, userText, state, expanded);
            String stepContext = buildStepContext(userText, selectedSkillContext, mcpContext, runtimeMode, session, "");
            log.debug("[AgentRuntime] streamTurn start: sessionId={}, turnId={}, tools={}, expandedAttempt={}",
                    session.getSessionId(), turnId, toolCallbacks.size(), expanded);
            try {
                response = orchestrator.streamTurn(session, stepContext, state, sender, toolCallbacks);
            } catch (Exception e) {
                log.error("[AgentRuntime] streamTurn failed: {}", e.getMessage(), e);
                response = "I hit an error generating the response: " + e.getMessage();
                sender.sendTextDelta(response);
                session.getMessages().add(new AssistantMessage(response));
            }
            recordSelectionSignals(session.getSessionId(), userText, catalogInjected ? selectedNonDefault : baseTools, response, expanded);

            if (!catalogInjected && shouldInvokeCatalogSearch(userText, response)) {
                catalogInjected = true;
                expanded = false;
                log.info("[AgentRuntime] tool_catalog_search invoked (needed-only): sessionId={}, turnId={}",
                        session.getSessionId(), turnId);
                saveSessionEvent(session.getSessionId(), turnId, "catalog.search.invoked",
                        "{\"reason\":\"needed_only_gate\"}");
                continue;
            }

            if (catalogInjected && shouldExpandAndRetry(response, expanded, expansionRetriesUsed, maxRetries)) {
                expanded = true;
                expansionRetriesUsed++;
                log.info("[AgentRuntime] Quality gate retry triggered: sessionId={}, turnId={}, retry={}",
                        session.getSessionId(), turnId, expansionRetriesUsed);
                saveSessionEvent(session.getSessionId(), turnId, "quality.retry",
                        "{\"retry\":" + expansionRetriesUsed + "}");
                continue;
            }
            break;
        }

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
        if (!ids.isEmpty()) {
            out.append("known_ids: ").append(ids.stream().limit(12).collect(Collectors.joining(", "))).append("\n");
            out.append("latest_id: ").append(latestId).append("\n");
        }
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

    private List<AgentTool> buildBaseToolSet() {
        List<AgentTool> base = runtimeTuningProperties.isBaseOnlyDefaultToolInjectionEnabled()
                ? toolRegistryService.getBaseInjectedTools()
                : toolRegistryService.getEnabledTools();
        if (base == null) return List.of();
        return base;
    }

    private List<AgentTool> buildCatalogToolShortlist(String userText,
                                                      String sessionId,
                                                      boolean expanded,
                                                      int baseCount) {
        List<AgentTool> nonDefaultTools = toolRegistryService.getNonDefaultEnabledTools();
        if (nonDefaultTools.isEmpty()) return List.of();
        int hardCap = Math.max(1, runtimeTuningProperties.getMaxToolCallbacksPerTurn());
        int availableSlots = Math.max(0, hardCap - Math.max(0, baseCount));
        if (availableSlots <= 0) return List.of();

        int shortlistSize = runtimeTuningProperties.isDynamicToolExposureEnabled()
                ? (expanded
                ? Math.max(1, runtimeTuningProperties.getToolShortlistExpandedSize())
                : Math.max(1, runtimeTuningProperties.getToolShortlistDefaultSize()))
                : availableSlots;
        shortlistSize = Math.min(shortlistSize, availableSlots);

        List<CatalogCandidate> ranked = toolCatalogSearch(userText, sessionId, nonDefaultTools, shortlistSize);
        if (ranked.isEmpty()) return List.of();
        List<AgentTool> selected = toolCatalogGet(ranked, shortlistSize);
        if (selected.isEmpty() && runtimeTuningProperties.isToolShortlistFallbackToAllOnMiss()) {
            return nonDefaultTools.stream().limit(availableSlots).toList();
        }
        return selected;
    }

    private List<CatalogCandidate> toolCatalogSearch(String userText,
                                                     String sessionId,
                                                     List<AgentTool> nonDefaultTools,
                                                     int limit) {
        int candidateLimit = Math.max(1, runtimeTuningProperties.getCatalogSearchCandidateLimit());
        return rankCatalogCandidates(userText, sessionId, nonDefaultTools).stream()
                .limit(Math.max(1, Math.min(limit, candidateLimit)))
                .toList();
    }

    private List<AgentTool> toolCatalogGet(List<CatalogCandidate> rankedCandidates, int limit) {
        if (rankedCandidates == null || rankedCandidates.isEmpty()) return List.of();
        return rankedCandidates.stream()
                .limit(Math.max(1, limit))
                .map(CatalogCandidate::tool)
                .toList();
    }

    private List<AgentTool> mergeWithProviderCap(List<AgentTool> baseTools,
                                                 List<AgentTool> selectedNonDefault,
                                                 String userText,
                                                 String sessionId) {
        int hardCap = Math.max(1, runtimeTuningProperties.getMaxToolCallbacksPerTurn());
        List<AgentTool> merged = new ArrayList<>();
        if (baseTools != null) {
            merged.addAll(baseTools.stream().filter(t -> t != null && t.getName() != null).toList());
        }
        if (selectedNonDefault != null && !selectedNonDefault.isEmpty()) {
            Set<String> seen = merged.stream().map(t -> t.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            for (AgentTool tool : selectedNonDefault) {
                if (tool == null || tool.getName() == null) continue;
                String key = tool.getName().toLowerCase(Locale.ROOT);
                if (seen.add(key)) merged.add(tool);
            }
        }
        if (merged.size() <= hardCap) return merged;
        // preserve base tools first; trim non-default remainder deterministically.
        List<AgentTool> baseOnly = baseTools == null ? List.of() : baseTools.stream().filter(t -> t != null && t.getName() != null).toList();
        int remaining = Math.max(0, hardCap - baseOnly.size());
        List<AgentTool> trimmed = new ArrayList<>(baseOnly.stream().limit(hardCap).toList());
        if (remaining > 0 && selectedNonDefault != null && !selectedNonDefault.isEmpty()) {
            List<AgentTool> ranked = rankToolsByIntent(userText, sessionId, selectedNonDefault).stream()
                    .map(Map.Entry::getKey)
                    .limit(remaining)
                    .toList();
            Set<String> seen = trimmed.stream().map(t -> t.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            for (AgentTool tool : ranked) {
                if (tool == null || tool.getName() == null) continue;
                if (seen.add(tool.getName().toLowerCase(Locale.ROOT))) trimmed.add(tool);
            }
        }
        log.info("[AgentRuntime] Tool callbacks capped for provider safety: baseTools={}, selectedNonDefault={}, final={}",
                baseOnly.size(),
                selectedNonDefault == null ? 0 : selectedNonDefault.size(),
                trimmed.size());
        return trimmed;
    }

    private List<CatalogCandidate> rankCatalogCandidates(String userText, String sessionId, List<AgentTool> tools) {
        Set<String> userTokens = tokenize(userText);
        Map<String, Double> memoryScores = loadToolMemorySignals(sessionId);
        Map<String, Double> domainScores = loadToolDomainMemorySignals(sessionId);
        double memoryWeight = Math.max(0.0, runtimeTuningProperties.getToolMemoryBiasWeight());
        String low = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        return tools.stream()
                .map(tool -> {
                    int lexical = matchScore(low, userTokens, tool);
                    List<String> requiredArgs = extractRequiredArgs(tool);
                    List<String> missingArgs = requiredArgs.stream()
                            .filter(arg -> !containsToken(low, arg))
                            .toList();
                    double schemaFitPenalty = missingArgs.isEmpty() ? 0.0 : Math.min(20.0, missingArgs.size() * 4.0);
                    double domainBias = domainScores.getOrDefault(tool.getDomainId(), 0.0) * memoryWeight * 5.0;
                    double score = lexical
                            + (memoryScores.getOrDefault(tool.getName(), 0.0) * memoryWeight * 10.0)
                            + domainBias
                            - schemaFitPenalty;
                    return new CatalogCandidate(tool, score, requiredArgs, missingArgs);
                })
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }

    private List<Map.Entry<AgentTool, Double>> rankToolsByIntent(String userText,
                                                                 String sessionId,
                                                                 List<AgentTool> tools) {
        Set<String> userTokens = tokenize(userText);
        Map<String, Double> memoryScores = loadToolMemorySignals(sessionId);
        double memoryWeight = Math.max(0.0, runtimeTuningProperties.getToolMemoryBiasWeight());
        return tools.stream()
                .map(tool -> {
                    int lexical = matchScore(userText.toLowerCase(Locale.ROOT), userTokens, tool);
                    double score = lexical + (memoryScores.getOrDefault(tool.getName(), 0.0) * memoryWeight * 10.0);
                    return Map.entry(tool, score);
                })
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
    }

    private boolean shouldExpandAndRetry(String response, boolean expanded, int attempt, int maxRetries) {
        if (expanded) return false;
        if (attempt >= maxRetries) return false;
        if (response == null || response.isBlank()) return true;
        String normalized = response.toLowerCase(Locale.ROOT);
        return normalized.contains("need more")
                || normalized.contains("not enough")
                || normalized.contains("insufficient")
                || normalized.contains("couldn't find")
                || normalized.contains("cannot find");
    }

    private boolean shouldInvokeCatalogSearch(String userText, String response) {
        List<AgentTool> nonDefault = toolRegistryService.getNonDefaultEnabledTools();
        if (nonDefault.isEmpty()) return false;
        if (response == null || response.isBlank()) return true;
        String normalized = response.toLowerCase(Locale.ROOT);
        if (normalized.contains("cannot access")
                || normalized.contains("can't access")
                || normalized.contains("do not have access")
                || normalized.contains("don't have access")
                || normalized.contains("not available")
                || normalized.contains("unable to")
                || normalized.contains("i cannot")) {
            return true;
        }
        String query = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        return query.contains("organization")
                || query.contains("product")
                || query.contains("repo")
                || query.contains("mcp")
                || query.contains("api");
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRequiredArgs(AgentTool tool) {
        if (tool == null || tool.getRequestSchema() == null || tool.getRequestSchema().isBlank()) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(tool.getRequestSchema(), Object.class);
            if (parsed instanceof Map<?, ?> root) {
                Object schema = root.containsKey("inputSchema") ? root.get("inputSchema") : root;
                if (schema instanceof Map<?, ?> schemaMap) {
                    Object required = schemaMap.get("required");
                    if (required instanceof List<?> list) {
                        return list.stream().filter(v -> v != null).map(String::valueOf).toList();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private boolean containsToken(String low, String arg) {
        if (low == null || low.isBlank() || arg == null || arg.isBlank()) return false;
        String normalized = arg.toLowerCase(Locale.ROOT).replace("_", " ").replace("-", " ");
        if (low.contains(normalized)) return true;
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank() && low.contains(token)) return true;
        }
        return false;
    }

    private void recordSelectionSignals(String sessionId,
                                        String userText,
                                        List<AgentTool> toolShortlist,
                                        String response,
                                        boolean expandedAttempt) {
        if (toolShortlist == null || toolShortlist.isEmpty()) return;
        double delta = (response == null || response.isBlank()) ? -0.25 : 0.35;
        if (expandedAttempt) delta = delta / 2.0;
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
        List<SkillRegistryService.SkillSnapshot> skillShortlist = buildSkillShortlist(userText, sessionId, expandedAttempt);
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
        log.debug("[AgentRuntime] Context compacted: sessionId={}, turnId={}, step={}, removed={}, retained={}, estimatedTokens={}",
                session.getSessionId(),
                turnId,
                step,
                compaction.removedMessages(),
                compaction.retainedMessages(),
                compaction.estimatedTokens());
    }

    private ToolRunOutcome executeModelRequestedTool(AgentSession session,
                                                     ChatState state,
                                                     SseEventSender sender,
                                                     String turnId,
                                                     AgentTool tool,
                                                     String payload,
                                                     String callId) {
        hookRegistryService.emit("pre-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
        GuardrailPolicyEngine.Decision decision = policyEngine.evaluateTool(tool);
        if ("deny".equalsIgnoreCase(decision.decision())) {
            String denied = "Denied by policy: " + decision.reason();
            sender.sendToolCall(session.getSessionId(), callId, tool.getName(), payload);
            saveSessionEvent(session.getSessionId(), turnId, "tool.call",
                    "{\"callId\":" + jsonString(callId) + ",\"toolName\":" + jsonString(tool.getName()) + ",\"input\":" + jsonString(payload) + "}");
            sender.sendToolResult(session.getSessionId(), callId, tool.getName(), denied, "denied");
            saveSessionEvent(session.getSessionId(), turnId, "tool.done",
                    "{\"callId\":" + jsonString(callId) + ",\"toolName\":" + jsonString(tool.getName()) + ",\"status\":\"denied\",\"output\":" + jsonString(denied) + "}");
            hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
            return new ToolRunOutcome("Tool '" + tool.getName() + "' denied: " + denied, false, null, false);
        }
        if ("ask".equalsIgnoreCase(decision.decision())) {
            transitionState(session.getSessionId(), turnId, sender, PlannerState.APPROVAL, "policy-ask");
            String approvalReason = "Tool requires approval: " + tool.getName();
            String requestId = pendingInteractionService.create(session.getSessionId(), turnId, "approval_required", approvalReason);
            sender.sendApprovalRequired(session.getSessionId(), requestId, approvalReason);
            saveSessionEvent(session.getSessionId(), turnId, "approval_required",
                    "{\"requestId\":" + jsonString(requestId) + ",\"reason\":" + jsonString(approvalReason) + "}");
            try {
                PendingInteractionService.InteractionReply reply =
                        pendingInteractionService.awaitReply(requestId, runtimeTuningProperties.getHitlReplyTimeoutMs());
                if (reply == null) {
                    hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
                    return new ToolRunOutcome("Tool '" + tool.getName() + "' approval timed out", true, "Approval request timed out. Please try again.", false);
                }
                if ("rejected".equalsIgnoreCase(reply.action())) {
                    hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
                    return new ToolRunOutcome("Tool '" + tool.getName() + "' was rejected by user", true, "Action rejected by user.", false);
                }
            } catch (TimeoutException e) {
                hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
                return new ToolRunOutcome("Tool '" + tool.getName() + "' approval timed out", true, "Approval request timed out. Please try again.", false);
            }
        }

        sender.sendToolCall(session.getSessionId(), callId, tool.getName(), payload);
        saveSessionEvent(session.getSessionId(), turnId, "tool.call",
                "{\"callId\":" + jsonString(callId) + ",\"toolName\":" + jsonString(tool.getName()) + ",\"input\":" + jsonString(payload) + "}");
        log.debug("[AgentRuntime] Tool call: sessionId={}, turnId={}, callId={}, tool={}, payload={}",
                session.getSessionId(),
                turnId,
                callId,
                tool.getName(),
                truncate(payload, 1200));
        ToolExecutionService.ExecutionResult execution = toolExecutionService.execute(tool, payload);
        if (execution.success() && "question".equalsIgnoreCase(tool.getName())) {
            transitionState(session.getSessionId(), turnId, sender, PlannerState.APPROVAL, "question-tool");
            String requestId = pendingInteractionService.create(session.getSessionId(), turnId, "question", execution.body(), textQuestionMetadata());
            sender.sendQuestion(session.getSessionId(), requestId, execution.body(), textQuestionMetadata());
            saveSessionEvent(session.getSessionId(), turnId, "question",
                    "{\"requestId\":" + jsonString(requestId) + ",\"question\":" + jsonString(execution.body()) + "}");
            try {
                PendingInteractionService.InteractionReply reply =
                        pendingInteractionService.awaitReply(requestId, runtimeTuningProperties.getHitlReplyTimeoutMs());
                if (reply == null) {
                    hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
                    return new ToolRunOutcome("Tool '" + tool.getName() + "' question timed out", true, "Question timed out. Please retry the request.", false);
                }
                if ("rejected".equalsIgnoreCase(reply.action())) {
                    hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
                    return new ToolRunOutcome("Tool '" + tool.getName() + "' question rejected", true, "Tool question was rejected by user.", false);
                }
                String answer = reply.message() == null || reply.message().isBlank()
                        ? "No explicit answer provided."
                        : reply.message();
                execution = new ToolExecutionService.ExecutionResult(true, answer, null);
            } catch (TimeoutException e) {
                hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
                return new ToolRunOutcome("Tool '" + tool.getName() + "' question timed out", true, "Question timed out. Please retry the request.", false);
            }
        }

        String toolResult = execution.success() ? execution.body() : execution.error();
        log.debug("[AgentRuntime] Tool result: sessionId={}, turnId={}, callId={}, tool={}, success={}, output={}",
                session.getSessionId(),
                turnId,
                callId,
                tool.getName(),
                execution.success(),
                truncate(toolResult, 2000));
        sender.sendToolResult(session.getSessionId(), callId, tool.getName(), toolResult, execution.success() ? "success" : "error");
        saveSessionEvent(session.getSessionId(), turnId, "tool.done",
                "{\"callId\":" + jsonString(callId) + ",\"toolName\":" + jsonString(tool.getName()) + ",\"status\":" + jsonString(execution.success() ? "success" : "error") + ",\"output\":" + jsonString(toolResult) + "}");
        runtimeTraceRepository.save(com.pods.agent.domain.RuntimeTrace.builder()
                .sessionId(session.getSessionId())
                .turnId(turnId)
                .traceType("tool")
                .correlationId(tool.getId())
                .payload(toolResult)
                .build());
        hookRegistryService.emit("post-tool", java.util.Map.of("sessionId", session.getSessionId(), "toolName", tool.getName()));
        if (execution.success() && "plan_exit".equalsIgnoreCase(tool.getName())) {
            return new ToolRunOutcome("Tool '" + tool.getName() + "' returned terminal response: " + toolResult, true, toolResult, true);
        }
        return new ToolRunOutcome(
                execution.success()
                        ? "Tool '" + tool.getName() + "' returned: " + toolResult
                        : "Tool '" + tool.getName() + "' failed: " + toolResult,
                false,
                null,
                hasMeaningfulToolOutput(toolResult));
    }

    private boolean hasMeaningfulToolOutput(String toolResult) {
        if (toolResult == null) return false;
        String normalized = toolResult.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return false;
        if ("null".equals(normalized)) return false;
        if ("{}".equals(normalized) || "[]".equals(normalized)) return false;
        if (normalized.contains("\"text\":\"null\"")) return false;
        if (normalized.contains("\"content\":[{\"type\":\"text\",\"text\":\"null\"}]")) return false;
        return normalized.length() > 8;
    }

    private String runCoreLoopSynthesis(AgentSession session,
                                        String userText,
                                        ChatState state,
                                        SseEventSender sender,
                                        String turnId,
                                        String runtimeMode,
                                        String toolExecutionContext,
                                        String selectedSkillContext) {
        transitionState(session.getSessionId(), turnId, sender, PlannerState.SYNTHESIZE, "core-loop-synthesize-start");
        sender.sendTaskStarted(session.getSessionId(), "synthesizer", "SYNTHESIZE");
        String toolPrefix = (toolExecutionContext != null && !toolExecutionContext.isBlank())
                ? "Tool execution result:\n" + toolExecutionContext + "\n\n"
                : "";
        String skillPrefix = (selectedSkillContext != null && !selectedSkillContext.isBlank())
                ? selectedSkillContext + "\n\n"
                : "";
        String runtimeHint = "Runtime mode: " + (runtimeMode == null || runtimeMode.isBlank() ? "planner_worker" : runtimeMode);
        String response = orchestrator.chat(session, userText + "\n\n" + skillPrefix + toolPrefix + runtimeHint, state, sender);
        sender.sendTaskDone(session.getSessionId(), "synthesizer", "DONE");
        transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "core-loop-complete");
        hookRegistryService.emit("post-response", java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                .payload("{\"task\":\"core_loop_complete\"}").build());
        return response;
    }

    private String runPlannerWorker(AgentSession session, String userText, ChatState state, SseEventSender sender, String turnId, String toolExecutionContext, String selectedSkillContext) {
        transitionState(session.getSessionId(), turnId, sender, PlannerState.PLAN, "planner-start");
        sender.sendTaskStarted(session.getSessionId(), "planner", "PLAN");
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId()).turnId(turnId).eventType("task.started")
                .payload("{\"task\":\"planner\"}").build());
        runtimeTraceRepository.save(com.pods.agent.domain.RuntimeTrace.builder()
                .sessionId(session.getSessionId())
                .turnId(turnId)
                .traceType("plan")
                .correlationId("planner")
                .payload(userText)
                .build());

        List<String> tasks = buildWorkerTasks(userText);
        transitionState(session.getSessionId(), turnId, sender, PlannerState.EXECUTE_PARALLEL, "workers-start");
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> executeWorkerTask(session.getSessionId(), turnId, task, userText, sender), parallelExecutor));
        }
        List<String> workerOutputs = new ArrayList<>();
        int failedWorkers = 0;
        for (CompletableFuture<String> future : futures) {
            try {
                workerOutputs.add(future.get(Math.max(5_000, runtimeTuningProperties.getWorkerTaskTimeoutMs()), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                failedWorkers++;
                future.cancel(true);
                workerOutputs.add("worker_timeout");
            } catch (Exception e) {
                failedWorkers++;
                workerOutputs.add("worker_error:" + sanitize(e.getMessage()));
            }
        }

        transitionState(session.getSessionId(), turnId, sender, PlannerState.SYNTHESIZE, "synthesize-start");
        sender.sendTaskStarted(session.getSessionId(), "synthesizer", "SYNTHESIZE");
        runtimeTraceRepository.save(com.pods.agent.domain.RuntimeTrace.builder()
                .sessionId(session.getSessionId())
                .turnId(turnId)
                .traceType("synthesis")
                .correlationId("synthesizer")
                .payload(String.join("\n", workerOutputs))
                .build());

        if (failedWorkers > 0) {
            transitionState(session.getSessionId(), turnId, sender, PlannerState.REPLAN, "worker-failure");
            workerOutputs.add(0, "REPLAN requested due to " + failedWorkers + " worker failure(s)");
        }
        String plannerHint = "Planner worker outputs:\n- " + String.join("\n- ", workerOutputs);
        String toolPrefix = (toolExecutionContext != null && !toolExecutionContext.isBlank())
                ? "Tool execution result:\n" + toolExecutionContext + "\n\n"
                : "";
        String skillPrefix = (selectedSkillContext != null && !selectedSkillContext.isBlank())
                ? selectedSkillContext + "\n\n"
                : "";
        String response = orchestrator.chat(session, userText + "\n\n" + skillPrefix + toolPrefix + plannerHint, state, sender);
        sender.sendTaskDone(session.getSessionId(), "synthesizer", "DONE");
        transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "planner-complete");
        hookRegistryService.emit("post-response", java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                .payload("{\"task\":\"planner_worker_complete\"}").build());
        return response;
    }

    private String runSwarm(AgentSession session, String userText, ChatState state, SseEventSender sender, String turnId, String toolExecutionContext, String selectedSkillContext) {
        transitionState(session.getSessionId(), turnId, sender, PlannerState.EXECUTE_PARALLEL, "swarm-start");
        List<String> agents = List.of("research_agent", "planner_agent", "validator_agent");
        List<String> sharedFindings = new ArrayList<>();
        int maxRounds = 2;
        for (int round = 1; round <= maxRounds; round++) {
            for (String agent : agents) {
                String taskId = agent + "_round_" + round;
                sender.sendTaskStarted(session.getSessionId(), taskId, agent + " round " + round);
                String finding = agent + " finding round " + round + " for: " + summarizeUserText(userText);
                sharedFindings.add(finding);
                runtimeTraceRepository.save(com.pods.agent.domain.RuntimeTrace.builder()
                        .sessionId(session.getSessionId())
                        .turnId(turnId)
                        .traceType("worker")
                        .correlationId(taskId)
                        .payload(finding)
                        .build());
                sender.sendTaskDone(session.getSessionId(), taskId, "shared context updated");
            }
        }

        String skillHint = (selectedSkillContext != null && !selectedSkillContext.isBlank())
                ? selectedSkillContext
                : "Active skills: " + skillRegistryService.getEnabledSkills().stream()
                .map(s -> s.skill().getName())
                .limit(10)
                .toList();
        String toolPrefix = (toolExecutionContext != null && !toolExecutionContext.isBlank())
                ? "Tool execution result:\n" + toolExecutionContext + "\n\n"
                : "";
        String response = orchestrator.chat(session,
                userText + "\n\n" + toolPrefix + skillHint + "\n\nSwarm findings:\n- " + String.join("\n- ", sharedFindings),
                state,
                sender);
        transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "swarm-complete");
        hookRegistryService.emit("post-response", java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                .payload("{\"task\":\"swarm_complete\"}").build());
        return response;
    }

    private List<String> buildWorkerTasks(String userText) {
        String compact = summarizeUserText(userText);
        return List.of(
                "analyze requirements for: " + compact,
                "collect supporting context for: " + compact,
                "prepare concise answer for: " + compact
        );
    }

    private String executeWorkerTask(String sessionId, String turnId, String task, String userText, SseEventSender sender) {
        String taskId = UUID.randomUUID().toString();
        sender.sendTaskStarted(sessionId, taskId, task);
        runtimeTraceRepository.save(com.pods.agent.domain.RuntimeTrace.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .traceType("worker")
                .correlationId(taskId)
                .payload(task)
                .build());
        String output = "worker_result(" + task + ")";
        sender.sendTaskDone(sessionId, taskId, output);
        return output;
    }

    private String summarizeUserText(String userText) {
        if (userText == null) return "";
        String compact = userText.trim().replaceAll("\\s+", " ");
        return compact.length() <= 120 ? compact : compact.substring(0, 117) + "...";
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

    private String runBoundedToolLoop(AgentSession session,
                                      ChatState state,
                                      SseEventSender sender,
                                      String turnId,
                                      String originalUserText,
                                      String firstToolName,
                                      String initialContext) {
        if (firstToolName != null && Set.of("webfetch", "websearch", "codesearch")
                .contains(firstToolName.toLowerCase(Locale.ROOT))) {
            // Web lookups are usually terminal evidence gathering steps for a turn;
            // avoid a second routing pass that can emit noisy ambiguity events.
            return initialContext;
        }
        int maxSteps = Math.max(1, runtimeTuningProperties.getToolLoopMaxSteps());
        if (maxSteps <= 1) return initialContext;
        Set<String> seenTools = new HashSet<>();
        seenTools.add(firstToolName.toLowerCase(Locale.ROOT));
        String context = initialContext;
        for (int step = 2; step <= maxSteps; step++) {
            String loopPrompt = originalUserText + "\n\nRecent tool context:\n" + truncate(context, 1200);
            ToolMatchResult next = pickMatchedTool(loopPrompt, state, session.getSessionId(), turnId, sender, seenTools);
            if (next == null || next.invocation() == null) break;
            AgentTool loopTool = next.invocation().tool();
            if (loopTool == null) break;
            String key = loopTool.getName() == null ? "" : loopTool.getName().toLowerCase(Locale.ROOT);
            if (seenTools.contains(key)) break;
            seenTools.add(key);

            sender.sendStateUpdated(session.getSessionId(), java.util.Map.of("plannerState", "EXECUTE_PARALLEL", "reason", "loop-step-" + step));
            saveSessionEvent(
                    session.getSessionId(),
                    turnId,
                    "tool.call",
                    "{\"toolName\":" + jsonString(loopTool.getName()) + ",\"input\":" + jsonString(next.invocation().payload()) + "}"
            );
            sender.sendToolCall(session.getSessionId(), loopTool.getName(), next.invocation().payload());
            GuardrailPolicyEngine.Decision loopDecision = policyEngine.evaluateTool(loopTool);
            if ("deny".equalsIgnoreCase(loopDecision.decision())) {
                String denied = "Denied by policy: " + loopDecision.reason();
                saveSessionEvent(
                        session.getSessionId(),
                        turnId,
                        "tool.done",
                        "{\"toolName\":" + jsonString(loopTool.getName()) + ",\"status\":\"denied\",\"output\":" + jsonString(denied) + "}"
                );
                sender.sendToolDone(session.getSessionId(), loopTool.getName(), denied);
                context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: " + denied;
                break;
            }
            if ("ask".equalsIgnoreCase(loopDecision.decision())) {
                String approvalReason = "Tool requires approval: " + loopTool.getName();
                String requestId = pendingInteractionService.create(
                        session.getSessionId(),
                        turnId,
                        "approval_required",
                        approvalReason
                );
                sender.sendApprovalRequired(session.getSessionId(), requestId, approvalReason);
                saveSessionEvent(
                        session.getSessionId(),
                        turnId,
                        "approval_required",
                        "{\"requestId\":" + jsonString(requestId) + ",\"reason\":" + jsonString(approvalReason) + "}"
                );
                try {
                    PendingInteractionService.InteractionReply reply =
                            pendingInteractionService.awaitReply(requestId, runtimeTuningProperties.getHitlReplyTimeoutMs());
                    if (reply == null || "rejected".equalsIgnoreCase(reply.action())) {
                        context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: approval not granted";
                        break;
                    }
                } catch (TimeoutException e) {
                    context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: approval timed out";
                    break;
                }
            }
            var exec = toolExecutionService.execute(loopTool, next.invocation().payload());
            if (exec.success() && "question".equalsIgnoreCase(loopTool.getName())) {
                String requestId = pendingInteractionService.create(
                        session.getSessionId(),
                        turnId,
                        "question",
                        exec.body(),
                        textQuestionMetadata()
                );
                sender.sendQuestion(session.getSessionId(), requestId, exec.body(), textQuestionMetadata());
                saveSessionEvent(
                        session.getSessionId(),
                        turnId,
                        "question",
                        "{\"requestId\":" + jsonString(requestId) + ",\"question\":" + jsonString(exec.body()) + "}"
                );
                try {
                    PendingInteractionService.InteractionReply reply =
                            pendingInteractionService.awaitReply(requestId, runtimeTuningProperties.getHitlReplyTimeoutMs());
                    if (reply == null) {
                        context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: question timed out";
                        break;
                    }
                    if ("rejected".equalsIgnoreCase(reply.action())) {
                        context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: question rejected";
                        break;
                    }
                    String answer = reply.message() == null ? "" : reply.message();
                    saveSessionEvent(
                            session.getSessionId(),
                            turnId,
                            "tool.done",
                            "{\"toolName\":" + jsonString(loopTool.getName()) + ",\"status\":\"success\",\"output\":" + jsonString(answer) + "}"
                    );
                    sender.sendToolDone(session.getSessionId(), loopTool.getName(), answer);
                    context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: " + truncate(answer, 2000);
                    continue;
                } catch (TimeoutException e) {
                    context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: question timed out";
                    break;
                }
            }
            String body = exec.success() ? exec.body() : exec.error();
            saveSessionEvent(
                    session.getSessionId(),
                    turnId,
                    "tool.done",
                    "{\"toolName\":" + jsonString(loopTool.getName()) + ",\"status\":" + jsonString(exec.success() ? "success" : "error") + ",\"output\":" + jsonString(body) + "}"
            );
            sender.sendToolDone(session.getSessionId(), loopTool.getName(), body);
            context = context + "\n\nStep " + step + " [" + loopTool.getName() + "]: " + truncate(body, 2000);
            if (!exec.success()) break;
        }
        return context;
    }

    private ToolMatchResult pickMatchedTool(String userText, ChatState state, String sessionId, String turnId, SseEventSender sender) {
        return pickMatchedTool(userText, state, sessionId, turnId, sender, Set.of());
    }

    private ToolMatchResult pickMatchedTool(String userText,
                                            ChatState state,
                                            String sessionId,
                                            String turnId,
                                            SseEventSender sender,
                                            Set<String> excludedTools) {
        if (userText == null || userText.isBlank()) return null;
        if (isContextualAffirmation(userText)) {
            // Let the LLM continue from prior turn context for short acknowledgements
            // (e.g. "yes please"), instead of forcing a fresh tool disambiguation loop.
            return null;
        }
        String low = userText.toLowerCase(Locale.ROOT);
        Set<String> excluded = excludedTools == null ? Set.of() : excludedTools.stream()
                .map(v -> v == null ? "" : v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<AgentTool> tools = toolRegistryService.getEnabledTools().stream()
                .filter(t -> t.getName() != null && !excluded.contains(t.getName().toLowerCase(Locale.ROOT)))
                .toList();
        if (tools.isEmpty()) return null;

        // explicit call: /toolName {json...}
        Matcher slash = Pattern.compile("^\\s*/([a-zA-Z0-9_\\-]+)\\s*(\\{.*)?$", Pattern.DOTALL).matcher(userText.trim());
        if (slash.matches()) {
            String name = slash.group(1);
            String payload = slash.group(2) == null || slash.group(2).isBlank() ? "{}" : slash.group(2).trim();
            AgentTool exact = tools.stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (exact != null) {
                sender.sendToolMatch(sessionId, exact.getName(), matchScore(low, tokenize(userText), exact), false, "explicit_slash_command", List.of(exact.getName() + ":explicit"));
                return new ToolMatchResult(new ToolInvocation(exact, payload), null, null);
            }
        }

        // explicit call: tool:tool_name {json...}
        Matcher tagged = Pattern.compile("(?i)tool:([a-zA-Z0-9_\\-]+)\\s*(\\{.*)?$", Pattern.DOTALL).matcher(userText.trim());
        if (tagged.matches()) {
            String name = tagged.group(1);
            String payload = tagged.group(2) == null || tagged.group(2).isBlank() ? "{}" : tagged.group(2).trim();
            AgentTool exact = tools.stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (exact != null) {
                sender.sendToolMatch(sessionId, exact.getName(), matchScore(low, tokenize(userText), exact), false, "explicit_tagged_command", List.of(exact.getName() + ":explicit"));
                return new ToolMatchResult(new ToolInvocation(exact, payload), null, null);
            }
        }

        // AI-first tool selection, fallback to lexical scorer if model call fails.
        AiToolDecision aiDecision = pickToolWithAi(userText, tools, state);
        if (aiDecision != null) {
            // Conversational query — no tool needed, let the LLM answer directly
            if (aiDecision.noToolNeeded()) {
                sender.sendToolMatch(sessionId, null, 0, false, "no_tool_needed", List.of());
                return null;
            }
            List<String> considered = (aiDecision.candidates() == null ? List.<String>of() : aiDecision.candidates())
                    .stream()
                    .limit(5)
                    .toList();
            if (aiDecision.needsClarification() || aiDecision.selectedTool() == null || aiDecision.selectedTool().isBlank()) {
                String reason = aiDecision.reason() == null || aiDecision.reason().isBlank()
                        ? "needs_clarification"
                        : aiDecision.reason();
                sender.sendToolMatch(sessionId, null, 0, true, reason, considered);
                runtimeEventRepository.save(RuntimeEvent.builder()
                        .sessionId(sessionId)
                        .turnId(turnId)
                        .eventType("tool.match")
                        .payload("{\"selectedTool\":null,\"score\":0,\"needsClarification\":true,\"reason\":" + jsonString(reason) + ",\"candidates\":" + jsonArray(considered) + "}")
                        .build());
                String clarification = (aiDecision.clarificationPrompt() == null || aiDecision.clarificationPrompt().isBlank())
                        ? "I found multiple possible tools. Which one should I run?"
                        : aiDecision.clarificationPrompt();
                return new ToolMatchResult(
                        null,
                        clarification,
                        optionsQuestionMetadataFromNames(tools, considered)
                );
            }

            AgentTool selected = tools.stream()
                    .filter(t -> t.getName().equalsIgnoreCase(aiDecision.selectedTool()))
                    .findFirst()
                    .orElse(null);
            if (selected != null) {
                sender.sendToolMatch(sessionId, selected.getName(), 100, false, "ai_selected", considered);
                runtimeEventRepository.save(RuntimeEvent.builder()
                        .sessionId(sessionId)
                        .turnId(turnId)
                        .eventType("tool.match")
                        .payload("{\"selectedTool\":" + jsonString(selected.getName()) + ",\"score\":100,\"needsClarification\":false,\"reason\":\"ai_selected\",\"candidates\":" + jsonArray(considered) + "}")
                        .build());
                return new ToolMatchResult(new ToolInvocation(selected, buildAutoInvocationPayload(selected, userText)), null, null);
            }
        }

        Set<String> userTokens = tokenize(userText);
        List<ToolCandidate> ranked = tools.stream()
                .map(t -> new ToolCandidate(t, matchScore(low, userTokens, t)))
                .filter(c -> c.score() > 0)
                .sorted(Comparator
                        .comparingInt(ToolCandidate::score).reversed()
                        .thenComparing(c -> c.tool().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (ranked.isEmpty()) {
            sender.sendToolMatch(sessionId, null, 0, true, "no_candidate_match", List.of());
            return null;
        }

        ToolCandidate top = ranked.get(0);
        ToolCandidate second = ranked.size() > 1 ? ranked.get(1) : null;
        int minScore = Math.max(1, runtimeTuningProperties.getToolAutoPickMinScore());
        int ambiguityDelta = Math.max(1, runtimeTuningProperties.getToolAutoPickAmbiguityDelta());

        List<String> considered = ranked.stream()
                .limit(5)
                .map(c -> c.tool().getName() + ":" + c.score())
                .collect(Collectors.toList());

        if (top.score() < minScore) {
            sender.sendToolMatch(sessionId, null, top.score(), true, "low_confidence", considered);
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .eventType("tool.match")
                    .payload("{\"selectedTool\":null,\"score\":" + top.score() + ",\"needsClarification\":true,\"reason\":\"low_confidence\",\"candidates\":" + jsonArray(considered) + "}")
                    .build());
            return new ToolMatchResult(
                    null,
                    "I can use tools here, but I need more detail to pick the right one.",
                    optionsQuestionMetadata(ranked)
            );
        }

        if (second != null && Math.abs(top.score() - second.score()) <= ambiguityDelta) {
            sender.sendToolMatch(sessionId, null, top.score(), true, "ambiguous_candidates", considered);
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .eventType("tool.match")
                    .payload("{\"selectedTool\":null,\"score\":" + top.score() + ",\"needsClarification\":true,\"reason\":\"ambiguous_candidates\",\"candidates\":" + jsonArray(considered) + "}")
                    .build());
            return new ToolMatchResult(
                    null,
                    "I found multiple matching tools (" + top.tool().getName() + ", " + second.tool().getName() + "). Which one should I run?",
                    optionsQuestionMetadata(ranked)
            );
        }

        sender.sendToolMatch(sessionId, top.tool().getName(), top.score(), false, "auto_selected", considered);
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .eventType("tool.match")
                .payload("{\"selectedTool\":" + jsonString(top.tool().getName()) + ",\"score\":" + top.score() + ",\"needsClarification\":false,\"reason\":\"auto_selected\",\"candidates\":" + jsonArray(considered) + "}")
                .build());
        return new ToolMatchResult(new ToolInvocation(top.tool(), buildAutoInvocationPayload(top.tool(), userText)), null, null);
    }

    private String buildAutoInvocationPayload(AgentTool tool, String userText) {
        String sanitized = sanitize(userText);
        String toolName = tool == null || tool.getName() == null ? "" : tool.getName().toLowerCase(Locale.ROOT);
        return switch (toolName) {
            case "websearch", "codesearch" -> "{\"search_term\":\"" + sanitized + "\"}";
            case "webfetch" -> "{\"url\":\"" + sanitized + "\"}";
            default -> "{\"query\":\"" + sanitized + "\"}";
        };
    }

    private AiToolDecision pickToolWithAi(String userText, List<AgentTool> tools, ChatState state) {
        if (tools == null || tools.isEmpty()) return null;
        try {
            List<Map<String, Object>> toolCatalog = tools.stream()
                    .limit(80)
                    .map(runtimeToolDescriptorService::toCompactDescriptor)
                    .toList();

            String prompt = "User request:\n" + userText + "\n\n"
                    + "Available tools JSON:\n"
                    + objectMapper.writeValueAsString(toolCatalog)
                    + "\n\nReturn ONLY JSON with this exact shape:\n"
                    + "{"
                    + "\"selectedTool\":\"tool_name_or_null\","
                    + "\"needsClarification\":true|false,"
                    + "\"noToolNeeded\":true|false,"
                    + "\"reason\":\"short_reason\","
                    + "\"clarificationPrompt\":\"question for user when clarification is needed\","
                    + "\"candidates\":[\"toolA\",\"toolB\"]"
                    + "}\n"
                    + "Rules:\n"
                    + "1. If the request is conversational, a greeting, a question about capabilities/skills, or can be answered from knowledge alone — set noToolNeeded=true, selectedTool=null, needsClarification=false.\n"
                    + "2. If a single tool clearly matches — set selectedTool=<name>, noToolNeeded=false, needsClarification=false.\n"
                    + "3. If multiple tools could match and you cannot decide — set needsClarification=true, noToolNeeded=false, selectedTool=null.\n"
                    + "Prefer noToolNeeded over needsClarification when no tool is appropriate.";

            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(state != null ? state.getModel() : null);
            String raw = spec.client()
                    .prompt()
                    .system("You are a strict tool router. Output JSON only.")
                    .user(prompt)
                    .call()
                    .content();
            if (raw == null || raw.isBlank()) return null;
            String json = extractJsonObject(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String selectedTool = parsed.get("selectedTool") == null ? null : String.valueOf(parsed.get("selectedTool"));
            if ("null".equalsIgnoreCase(String.valueOf(selectedTool))) selectedTool = null;
            boolean noToolNeeded = parsed.get("noToolNeeded") instanceof Boolean b2 ? b2 : false;
            boolean needsClarification = !noToolNeeded && (parsed.get("needsClarification") instanceof Boolean b ? b : selectedTool == null);
            String reason = parsed.get("reason") == null ? null : String.valueOf(parsed.get("reason"));
            String clarificationPrompt = parsed.get("clarificationPrompt") == null ? null : String.valueOf(parsed.get("clarificationPrompt"));
            List<String> candidates = new ArrayList<>();
            Object c = parsed.get("candidates");
            if (c instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) candidates.add(String.valueOf(item));
                }
            }
            return new AiToolDecision(selectedTool, needsClarification, noToolNeeded, reason, clarificationPrompt, candidates);
        } catch (Exception e) {
            log.warn("[AgentRuntime] AI tool picker failed; falling back to lexical matcher: {}", e.getMessage());
            return null;
        }
    }

    private String buildSelectedSkillContext(AgentSession session, String userText, ChatState state) {
        return buildSelectedSkillContext(session, userText, state, false);
    }

    private String buildSelectedSkillContext(AgentSession session, String userText, ChatState state, boolean expanded) {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills.isEmpty()) return "";
        SkillContextMode mode = resolveSkillContextMode(userText);
        if (mode == SkillContextMode.NONE) return "";
        if (mode == SkillContextMode.CATALOG_ONLY) return buildSkillCatalogContext(skills);
        List<SkillRegistryService.SkillSnapshot> shortlisted = buildSkillShortlist(userText, session.getSessionId(), expanded);
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

            int skillLimit = resolveSkillShortlistSize(expanded);
            List<String> selectedNormalized = selected.stream().map(String::trim).filter(s -> !s.isBlank()).limit(skillLimit).toList();
            List<SkillRegistryService.SkillSnapshot> selectedSkills = shortlisted.stream()
                    .filter(s -> selectedNormalized.stream().anyMatch(name -> name.equalsIgnoreCase(s.skill().getName())))
                    .limit(skillLimit)
                    .toList();
            if (selectedSkills.isEmpty()) {
                selectedSkills = fallbackSelectSkills(userText, shortlisted, skillLimit);
            }
            if (selectedSkills.isEmpty()) return buildSkillCatalogContext(shortlisted);

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

                // SKILL.md first (primary instructions)
                String skillMd = snapshot.files().entrySet().stream()
                        .filter(e -> "SKILL.md".equalsIgnoreCase(e.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse("");
                if (!skillMd.isBlank()) {
                    context.append(skillMd).append("\n");
                }

                // All other files — full content, labelled with path
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
        } catch (Exception e) {
            log.warn("[AgentRuntime] AI skill selector failed, continuing without skill context: {}", e.getMessage());
            List<SkillRegistryService.SkillSnapshot> fallback = fallbackSelectSkills(userText, shortlisted, resolveSkillShortlistSize(expanded));
            if (!fallback.isEmpty()) {
                return buildSkillContentContext(session, fallback);
            }
            return buildSkillCatalogContext(shortlisted);
        }
    }

    private List<SkillRegistryService.SkillSnapshot> buildSkillShortlist(String userText,
                                                                         String sessionId,
                                                                         boolean expanded) {
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (!runtimeTuningProperties.isDynamicSkillExposureEnabled() || skills.size() <= 1) {
            return skills;
        }
        int shortlistSize = resolveSkillShortlistSize(expanded);
        if (skills.size() <= shortlistSize) return skills;

        Set<String> userTokens = tokenize(userText);
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

    private int resolveSkillShortlistSize(boolean expanded) {
        int size = expanded
                ? runtimeTuningProperties.getSkillShortlistExpandedSize()
                : runtimeTuningProperties.getSkillShortlistDefaultSize();
        return Math.max(1, size);
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
        Set<String> tokens = tokenize(userText);
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
        score += overlap(userTokens, tokenize(name)) * 8;
        score += overlap(userTokens, tokenize(description)) * 5;
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

    private PendingInteractionService.QuestionMetadata optionsQuestionMetadataFromNames(List<AgentTool> tools, List<String> names) {
        List<PendingInteractionService.QuestionOption> options = names.stream()
                .map(candidate -> candidate.contains(":") ? candidate.substring(0, candidate.indexOf(':')) : candidate)
                .distinct()
                .filter(name -> tools.stream().anyMatch(t -> t.getName().equalsIgnoreCase(name)))
                .limit(5)
                .map(name -> new PendingInteractionService.QuestionOption(name, name))
                .toList();
        if (options.isEmpty()) return textQuestionMetadata();
        return new PendingInteractionService.QuestionMetadata("single_select", options, true, 1, 1);
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

    /**
     * Parses the selected tool name from an interaction reply's response text.
     * Handles formats produced by {@code PendingInteractionService#composeResponseText}:
     *   "options=toolName"
     *   "options=toolName; message=someText"
     *   or a bare tool name when the user typed it manually.
     */
    private String parseSelectedToolName(String responseText) {
        if (responseText == null || responseText.isBlank()) return null;
        if (responseText.startsWith("options=")) {
            String rest = responseText.substring("options=".length());
            int semicolon = rest.indexOf(';');
            String optionsPart = semicolon >= 0 ? rest.substring(0, semicolon).trim() : rest.trim();
            // Take the first option when multiple are comma-separated
            String first = optionsPart.split(",")[0].trim();
            return first.isBlank() ? null : first;
        }
        // Bare text reply — treat as tool name directly
        return responseText.trim();
    }

    private PendingInteractionService.QuestionMetadata textQuestionMetadata() {
        return new PendingInteractionService.QuestionMetadata("text", List.of(), true, null, null);
    }

    private PendingInteractionService.QuestionMetadata optionsQuestionMetadata(List<ToolCandidate> ranked) {
        List<PendingInteractionService.QuestionOption> options = ranked.stream()
                .limit(5)
                .map(c -> new PendingInteractionService.QuestionOption(c.tool().getName(), c.tool().getName()))
                .toList();
        return new PendingInteractionService.QuestionMetadata("single_select", options, true, 1, 1);
    }

    private boolean isAllowedStrictScopeRequest(String userText) {
        if (userText == null || userText.isBlank()) return false;
        if (runtimeTuningProperties.isAllowCapabilitiesQueries()
                && (isCapabilitiesQuery(userText) || isCatalogOnlySkillQuery(userText))) {
            return true;
        }
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills.isEmpty()) return false;
        Set<String> userTokens = tokenize(userText);
        return skills.stream().anyMatch(snapshot -> scoreSkill(userTokens, snapshot) >= 12);
    }

    private String outOfScopeRefusalMessage() {
        String configured = runtimeTuningProperties.getOutOfScopeRefusalMessage();
        if (configured == null || configured.isBlank()) {
            return "I can’t answer this because it is outside my allowed skills/tools scope.";
        }
        return configured;
    }

    private boolean isExplicitToolInvocation(String userText) {
        if (userText == null) return false;
        String trimmed = userText.trim();
        return trimmed.startsWith("/") || trimmed.toLowerCase(Locale.ROOT).startsWith("tool:");
    }

    private boolean isFrameworkWebSearchTool(AgentTool tool) {
        if (tool == null || tool.getName() == null) return false;
        String name = tool.getName().toLowerCase(Locale.ROOT);
        return "websearch".equals(name) || "codesearch".equals(name) || "webfetch".equals(name);
    }

    private boolean isGenericCodingQuery(String userText) {
        if (userText == null || userText.isBlank()) return false;
        String low = userText.toLowerCase(Locale.ROOT);
        return List.of(
                "write code", "generate code", "python", "java", "javascript", "typescript",
                "c++", "c#", "golang", "rust", "php", "ruby", "kotlin", "swift",
                "snake game", "leetcode", "algorithm", "function", "class", "program"
        ).stream().anyMatch(low::contains);
    }

    private int matchScore(String low, Set<String> userTokens, AgentTool tool) {
        int score = 0;
        String name = tool.getName() == null ? "" : tool.getName().toLowerCase(Locale.ROOT);
        String description = tool.getDescription() == null ? "" : tool.getDescription().toLowerCase(Locale.ROOT);
        String endpoint = tool.getEndpoint() == null ? "" : tool.getEndpoint().toLowerCase(Locale.ROOT);
        String method = tool.getMethod() == null ? "" : tool.getMethod().toLowerCase(Locale.ROOT);

        if (!name.isBlank() && low.contains(name)) score += 120;
        if (!method.isBlank() && low.contains(method)) score += 8;
        score += overlap(userTokens, tokenize(name)) * 32;
        score += overlap(userTokens, tokenize(description)) * 14;
        score += overlap(userTokens, tokenize(endpoint)) * 10;
        if (!tool.isExperimental()) score += 3;
        return score;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = text
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('/', ' ');
        return Pattern.compile("[^a-z0-9]+")
                .splitAsStream(normalized)
                .filter(token -> token.length() > 1)
                .filter(token -> !STOP_WORDS.contains(token))
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

    private boolean isContextualAffirmation(String userText) {
        if (userText == null) return false;
        String normalized = userText.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        if (normalized.length() > 40) return false;
        return normalized.equals("yes")
                || normalized.equals("yes please")
                || normalized.equals("yeah")
                || normalized.equals("yeah please")
                || normalized.equals("yep")
                || normalized.equals("ok")
                || normalized.equals("okay")
                || normalized.equals("ok please")
                || normalized.equals("continue")
                || normalized.equals("go ahead")
                || normalized.equals("do it")
                || normalized.equals("please do");
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

    private String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + sanitize(value) + "\"";
    }

    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        return "[" + values.stream().map(this::jsonString).collect(Collectors.joining(",")) + "]";
    }
}
