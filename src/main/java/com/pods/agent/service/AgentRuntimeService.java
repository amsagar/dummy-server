package com.pods.agent.service;

import com.pods.agent.agent.AgentOrchestrator;
import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.agent.tool.AgentToolCallbackFactory;
import com.pods.agent.agent.tool.SkillExecutionGate;
import org.springframework.ai.tool.ToolCallback;
import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChainRun;
import com.pods.agent.domain.ToolChainVersion;
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
import java.util.Comparator;
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
    private final ToolChainRuntimeService toolChainRuntimeService;
    private final ToolChainService toolChainService;
    private final ChainParameterExtractor chainParameterExtractor;
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
                               EmbeddingAutoRouterService embeddingAutoRouterService,
                               ToolChainRuntimeService toolChainRuntimeService,
                               ToolChainService toolChainService,
                               ChainParameterExtractor chainParameterExtractor) {
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
        this.toolChainRuntimeService = toolChainRuntimeService;
        this.toolChainService = toolChainService;
        this.chainParameterExtractor = chainParameterExtractor;
    }

    public String runTurn(AgentSession session, String userText, ChatState state, SseEventSender sender) {
        return runTurn(session, userText, state, sender, UUID.randomUUID().toString());
    }

    public String runTurn(AgentSession session, String userText, ChatState state, SseEventSender sender, String turnId) {
        String runtimeMode = state.getRuntimeMode() == null || state.getRuntimeMode().isBlank()
                ? "aicore_loop"
                : state.getRuntimeMode();
        state.setRuntimeMode(runtimeMode);
        String normalizedUserText = userText == null ? "" : userText.trim();
        session.getMessages().add(new UserMessage(normalizedUserText));

        if (!isToolChainDesignerMode(runtimeMode)
                && !isToolChainArchitectRuntimeMode(runtimeMode)
                && (state.getToolChainId() == null || state.getToolChainId().isBlank())
                && !normalizedUserText.isBlank()) {
            maybeResolveToolChainByIntent(session, normalizedUserText, state, sender, turnId);
        }

        if (shouldExecuteToolChain(state)) {
            transitionState(session.getSessionId(), turnId, sender, PlannerState.EXECUTE_PARALLEL, "toolchain-execution");
            try {
                // Resolve chain version + perform the single message→typed-params LLM hop.
                // The chain only sees the structured params it declared. Free-form prose stops here.
                ToolChainVersion version = toolChainService.resolveVersion(state.getToolChainId(), state.getToolChainVersion())
                        .orElseThrow(() -> new IllegalStateException("ToolChain version not found"));
                Map<String, Object> tcInput;
                try {
                    tcInput = new LinkedHashMap<>(chainParameterExtractor.extract(
                            normalizedUserText,
                            version.getInputSchema(),
                            extractionHintsForChain(state.getToolChainId()),
                            state.getModel() != null ? state.getModel()
                                    : resolveDefaultModelRefForChain(state.getToolChainId())));
                } catch (ChainParameterExtractor.ExtractionFailed extractionFailure) {
                    log.info("[AgentRuntime] Chain param extraction failed ({}); falling back to dynamic flow",
                            extractionFailure.getMessage());
                    if (state.isToolChainSelectedByUser() || "strict".equalsIgnoreCase(runtimeMode)) {
                        throw extractionFailure;
                    }
                    state.setToolChainId(null);
                    state.setToolChainVersion(null);
                    transitionState(session.getSessionId(), turnId, sender, PlannerState.PLAN, "param-extraction-failed-falling-back");
                    return runTurn(session, userText, state, sender, turnId);
                }
                var run = toolChainRuntimeService.execute(
                        state.getToolChainId(),
                        state.getToolChainVersion(),
                        "chat",
                        UserContextHolder.currentUserId(),
                        tcInput,
                        Map.of("async", false),
                        sender,
                        session.getSessionId()
                );
                // Anchor the run to the chat transcript so the UI can render a
                // "View run" chip linking to the run detail page on refresh.
                Map<String, Object> bound = new LinkedHashMap<>();
                bound.put("sessionId", session.getSessionId());
                bound.put("toolChainId", state.getToolChainId());
                bound.put("runId", run.getId());
                bound.put("version", run.getVersion());
                bound.put("status", run.getStatus());
                sender.sendCustom("toolchain.run.bound", bound);
                runtimeEventRepository.save(RuntimeEvent.builder()
                        .sessionId(session.getSessionId())
                        .turnId(turnId)
                        .eventType("toolchain.run.bound")
                        .payload(toJsonSafe(bound))
                        .build());
                String result = renderToolChainAssistantMessage(run);
                session.getMessages().add(new AssistantMessage(result));
                transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "toolchain-execution-done");
                return result;
            } catch (Exception e) {
                log.warn("[AgentRuntime] ToolChain execution failed, falling back to dynamic flow: {}", e.getMessage());
                // Fail loud when the user explicitly chose this chain — they asked for
                // a deterministic run, not a "best effort". Strict mode also fails loud
                // even for inferred matches.
                if (state.isToolChainSelectedByUser() || "strict".equalsIgnoreCase(runtimeMode)) {
                    String error = "ToolChain execution failed: " + e.getMessage();
                    sender.sendError(error);
                    runtimeEventRepository.save(RuntimeEvent.builder()
                            .sessionId(session.getSessionId())
                            .turnId(turnId)
                            .eventType("task.done")
                            .payload("{\"task\":\"toolchain_failed\",\"status\":\"failed\",\"error\":" + jsonString(e.getMessage()) + "}")
                            .build());
                    session.getMessages().add(new AssistantMessage(error));
                    transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE,
                            state.isToolChainSelectedByUser() ? "toolchain-failed-user-selected" : "toolchain-failed-strict");
                    return error;
                }
            }
        }

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
        if (!isToolChainDesignerMode(runtimeMode) && isCapabilitiesQuery(userText)) {
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

        ScopeGateOutcome scopeGate = applyPreFlightScopeGate(normalizedUserText, session, state);
        if (scopeGate != null && scopeGate.refused()) {
            transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "strict-scope-pre-flight");
            sender.sendTextDelta(scopeGate.refusal());
            session.getMessages().add(new AssistantMessage(scopeGate.refusal()));
            saveSessionEvent(session.getSessionId(), turnId, "strict_scope.refused",
                    "{\"reason\":\"out_of_scope_pre_flight\",\"topCosine\":" + scopeGate.topCosine() + "}");
            hookRegistryService.emit("post-response", java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                    .payload("{\"task\":\"strict_scope_refused\"}").build());
            return scopeGate.refusal();
        }

        String mcpContext = buildMcpContext();
        return runModelDrivenCoreLoop(session, normalizedUserText, state, sender, turnId, runtimeMode, mcpContext);
    }

    private boolean shouldExecuteToolChain(ChatState state) {
        if (state == null) return false;
        if (isToolChainDesignerMode(state.getRuntimeMode()) || isToolChainArchitectRuntimeMode(state.getRuntimeMode())) return false;
        if (state.getToolChainId() != null && !state.getToolChainId().isBlank()) return true;
        String mode = state.getRuntimeMode();
        return mode != null && mode.toLowerCase(Locale.ROOT).contains("toolchain");
    }

    private void maybeResolveToolChainByIntent(AgentSession session,
                                               String userText,
                                               ChatState state,
                                               SseEventSender sender,
                                               String turnId) {
        List<ToolChainService.IntentMatch> matches;
        try {
            matches = toolChainService.findIntentMatches(userText, 0.30, 3);
        } catch (Exception e) {
            // Legacy ToolChain tables may be removed after workflow-only cutover.
            // Keep chat path healthy by skipping toolchain intent probing.
            log.debug("[AgentRuntime] Skipping ToolChain intent resolution: {}", e.getMessage());
            return;
        }
        if (matches.isEmpty()) return;
        String selectedToolChainId;
        Integer selectedVersion;
        if (matches.size() == 1) {
            ToolChainService.IntentMatch hit = matches.get(0);
            String question = "I found a matching ToolChain \"" + hit.name() + "\" for this request. Continue with ToolChain execution or use normal AI loop?";
            String requestId = askToolChainQuestion(session, sender, turnId, question, List.of(
                    new PendingInteractionService.QuestionOption("toolchain:" + hit.toolChainId(), "Use " + hit.name()),
                    new PendingInteractionService.QuestionOption("normal_loop", "Use normal AI loop")
            ));
            String choice = awaitSelection(requestId);
            if (choice == null || "normal_loop".equalsIgnoreCase(choice)) return;
            selectedToolChainId = hit.toolChainId();
            selectedVersion = hit.version();
        } else {
            List<PendingInteractionService.QuestionOption> options = new ArrayList<>();
            for (ToolChainService.IntentMatch hit : matches) {
                String label = hit.name() + " (score " + String.format(Locale.ROOT, "%.2f", hit.score()) + ")";
                options.add(new PendingInteractionService.QuestionOption("toolchain:" + hit.toolChainId(), label));
            }
            options.add(new PendingInteractionService.QuestionOption("normal_loop", "Use normal AI loop"));
            String question = "Multiple ToolChains match your request. Which path should I run?";
            String requestId = askToolChainQuestion(session, sender, turnId, question, options);
            String choice = awaitSelection(requestId);
            if (choice == null || "normal_loop".equalsIgnoreCase(choice)) return;
            String tcId = choice.replace("toolchain:", "");
            ToolChainService.IntentMatch chosen = matches.stream()
                    .filter(m -> m.toolChainId().equals(tcId))
                    .findFirst()
                    .orElse(matches.get(0));
            selectedToolChainId = chosen.toolChainId();
            selectedVersion = chosen.version();
        }
        state.setToolChainId(selectedToolChainId);
        state.setToolChainVersion(selectedVersion);
        state.setToolChainSelectedByUser(true);
    }

    private String askToolChainQuestion(AgentSession session,
                                        SseEventSender sender,
                                        String turnId,
                                        String question,
                                        List<PendingInteractionService.QuestionOption> options) {
        PendingInteractionService.QuestionMetadata metadata = new PendingInteractionService.QuestionMetadata(
                "single_select",
                options,
                false,
                1,
                1
        );
        String requestId = pendingInteractionService.create(
                session.getSessionId(),
                turnId,
                "question",
                question,
                metadata
        );
        sender.sendQuestion(session.getSessionId(), requestId, question, metadata);
        saveSessionEvent(session.getSessionId(), turnId, "question",
                "{\"requestId\":\"" + requestId + "\",\"question\":\"" + sanitize(question) + "\"}");
        return requestId;
    }

    private String awaitSelection(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        try {
            PendingInteractionService.InteractionReply reply = pendingInteractionService.awaitReply(
                    requestId,
                    runtimeTuningProperties.getHitlReplyTimeoutMs()
            );
            if (reply == null || reply.message() == null) return null;
            String message = reply.message();
            if (message.startsWith("options=")) {
                String optionsPart = message.substring("options=".length());
                int delimiter = optionsPart.indexOf(';');
                String selected = delimiter >= 0 ? optionsPart.substring(0, delimiter) : optionsPart;
                if (selected.contains(",")) {
                    return selected.split(",")[0].trim();
                }
                return selected.trim();
            }
            return message.trim();
        } catch (Exception e) {
            log.info("[AgentRuntime] ToolChain selection prompt timed out or failed: {}", e.getMessage());
            return null;
        }
    }

    private record ScopeGateOutcome(boolean refused, String refusal, double topCosine) {}

    private ScopeGateOutcome applyPreFlightScopeGate(String userText, AgentSession session, ChatState state) {
        if (!runtimeTuningProperties.isStrictScopeOnly()) return null;
        RuntimeTuningProperties.StrictScope cfg = runtimeTuningProperties.getStrictScope();
        if (cfg == null || !cfg.isPreFlightEnabled()) return null;
        if (userText == null || userText.isBlank()) return null;

        if (isCapabilitiesQuery(userText) || isCatalogOnlySkillQuery(userText)) return null;

        String trimmed = userText.trim();
        int wordCount = trimmed.split("\\s+").length;
        if (wordCount <= Math.max(0, cfg.getAllowConversationalMaxWords())) return null;

        // Continuation: only short user text (≤ allowConversationalMaxWords + 2) bypasses the scope
        // gate when prior tool calls exist. Long, topic-shifted queries must run the cosine check
        // even if the session has prior tool history (e.g. user pivots from GitHub to "who is PM").
        boolean isShortContinuation = wordCount <= Math.max(0, cfg.getAllowConversationalMaxWords()) + 2;
        if (isShortContinuation && session != null && !collectRecentToolCalls(session.getSessionId(), 1).isEmpty()) return null;

        ModelRef embedRef = embeddingAutoRouterService == null ? null
                : embeddingAutoRouterService.pickEmbeddingModel(state);
        if (embedRef == null || toolEmbeddingIndexService == null) return null;

        double topCosine;
        try {
            List<ToolEmbeddingIndexService.ScoredTool> top = toolEmbeddingIndexService.searchTopK(
                    userText, 3, Set.of(), Map.of(), Set.of(), embedRef);
            topCosine = (top == null || top.isEmpty()) ? 0.0 : top.get(0).score();
        } catch (Exception e) {
            log.debug("[AgentRuntime] pre-flight scope gate retrieval failed; passing through: {}", e.getMessage());
            return null;
        }

        if (topCosine >= cfg.getMinTopToolCosine()) return null;

        log.info("[AgentRuntime] strict-scope pre-flight refused: topCosine={}, threshold={}, query=\"{}\"",
                String.format(Locale.ROOT, "%.3f", topCosine),
                cfg.getMinTopToolCosine(),
                truncate(userText, 120));
        return new ScopeGateOutcome(true, outOfScopeRefusalMessage(), topCosine);
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

        // Narrow-scope agent profiles (ov-basic, ov-detailed) demand a hard
        // allow-list: the assistant must NEVER see Get_OrderID,
        // Serviceability, ContainerAvailability or any framework
        // tool — only the ov* analytics tools + the HITL question tool.
        // Selecting one of these profiles also disables the skill-first
        // gate so the model can't fall through to the pods-order-validation
        // skill flow.
        String profileId = state == null ? null : state.getAgentProfileId();
        boolean isOrderValidationProfile = profileId != null && profileId.startsWith("ov-");
        if (isOrderValidationProfile) {
            Set<String> allowed = Set.of(
                    "ovListRunsForOrder",
                    "ovGetRunDetail",
                    "ovStartValidation",
                    "ovDashboardStats",
                    "question");
            tools = tools.stream()
                    .filter(t -> t != null && t.getName() != null && allowed.contains(t.getName()))
                    .toList();
        } else {
            // Symmetric guard: the ov* tools are domain-specific analytics for
            // the Order Validation UI. Outside an ov-* profile they would
            // appear in the general chat tool catalog and any model would
            // happily pick ovListRunsForOrder on the word "validate" — that's
            // exactly the leak this branch prevents. Strip the whole family
            // so general chat sessions never see them. Keeping the names in
            // a Set (rather than a prefix check) avoids accidentally
            // shadowing any future tool that legitimately starts with "ov".
            Set<String> orderValidationOnly = Set.of(
                    "ovListRunsForOrder",
                    "ovGetRunDetail",
                    "ovStartValidation",
                    "ovDashboardStats");
            tools = tools.stream()
                    .filter(t -> t == null || t.getName() == null || !orderValidationOnly.contains(t.getName()))
                    .toList();
        }

        // Skip the skill-first gate in toolchain_designer mode — the architect skill is already
        // injected as the system prompt, and the gate would otherwise block read/edit/apply_patch
        // calls that VFS-edit mode depends on, returning a "call skill first" string instead of
        // the actual file contents.
        boolean designerMode = isToolChainDesignerMode(state.getRuntimeMode())
                || isToolChainArchitectRuntimeMode(state.getRuntimeMode());
        boolean enforceSkillFirst = !designerMode
                && !isOrderValidationProfile
                && shouldEnforceSkillFirst(userText, session.getSessionId());
        SkillExecutionGate skillExecutionGate = new SkillExecutionGate(enforceSkillFirst);
        if (enforceSkillFirst) {
            // Surface the lexically-matched skill names so the gate's
            // block-error message can name them. Saves the model an extra
            // round-trip guessing which skill to load from the catalog.
            skillExecutionGate.setSuggestedSkillNames(
                    fallbackSelectSkills(userText, skillRegistryService.getEnabledSkills(), 3).stream()
                            .map(s -> s == null || s.skill() == null ? null : s.skill().getName())
                            .filter(n -> n != null && !n.isBlank())
                            .toList());
        }
        String selectedSkillContext = buildSelectedSkillContext(session, userText, state);
        if (isToolChainArchitectRuntimeMode(state.getRuntimeMode())) {
            selectedSkillContext = "";
        }
        if ((selectedSkillContext == null || selectedSkillContext.isBlank()) && enforceSkillFirst) {
            // For execution turns that require skill-first behavior, always inject at least
            // a compact skill catalog so the model has deterministic routing guidance.
            selectedSkillContext = buildSkillCatalogContext(skillRegistryService.getEnabledSkills());
        }
        List<String> preferredSkillNames = enforceSkillFirst
                ? buildSkillShortlist(userText, session.getSessionId()).stream()
                .map(snapshot -> snapshot == null || snapshot.skill() == null ? null : snapshot.skill().getName())
                .filter(name -> name != null && !name.isBlank())
                .limit(3)
                .toList()
                : List.of();

        // In designer mode, bind the per-session workspace and bypass the approval prompt
        // for FS tools. The architect's read/edit/apply_patch are sandboxed to the workspace
        // by safePath in ToolExecutionService, so no human approval is meaningful here.
        java.nio.file.Path designerWorkspace = designerMode ? session.getWorkspacePath() : null;
        List<ToolCallback> toolCallbacks = agentToolCallbackFactory.buildForTurn(
                session.getSessionId(), turnId, sender, tools, skillExecutionGate,
                designerWorkspace, designerMode);
        // Keep skill guidance in base system prompt and load full skill content only when
        // model explicitly calls the native `skill` tool.
        String stepContext = buildStepContext(userText, selectedSkillContext, mcpContext, runtimeMode, session, "",
                enforceSkillFirst, preferredSkillNames);

        log.debug("[AgentRuntime] streamTurn start: sessionId={}, turnId={}, tools={}",
                session.getSessionId(), turnId, toolCallbacks.size());
        if (enforceSkillFirst) {
            log.info("[AgentRuntime] skill-first gate enabled for sessionId={}, turnId={}", session.getSessionId(), turnId);
        }

        String response;
        try {
            response = orchestrator.streamTurn(session, stepContext, state, sender, toolCallbacks, turnId);
        } catch (Exception e) {
            log.error("[AgentRuntime] streamTurn failed: {}", e.getMessage(), e);
            response = "I hit an error generating the response: " + e.getMessage();
            sender.sendTextDelta(response);
            session.getMessages().add(new AssistantMessage(response));
        }
        response = replaceGenericScopeReplyWithToolFailure(turnId, response);

        recordSelectionSignals(session.getSessionId(), userText, tools, response);

        sender.sendTaskDone(session.getSessionId(), stepId, "FINAL");
        transitionState(session.getSessionId(), turnId, sender, PlannerState.DONE, "core-loop-complete");
        hookRegistryService.emit("post-response",
                java.util.Map.of("sessionId", session.getSessionId(), "turnId", turnId));
        runtimeEventRepository.save(RuntimeEvent.builder()
                .sessionId(session.getSessionId()).turnId(turnId).eventType("task.done")
                .payload("{\"task\":\"core_loop_complete\"}").build());
        return response == null ? "" : response;
    }

    @SuppressWarnings("unchecked")
    private String replaceGenericScopeReplyWithToolFailure(String turnId, String response) {
        if (response == null || response.isBlank()) return response;
        String normalized = response.toLowerCase(Locale.ROOT);
        if (!normalized.contains("i can only help with tasks covered by your registered tools and skills")
                && !normalized.contains("no relevant tool or skill is configured")) {
            return response;
        }
        try {
            List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
            for (int i = events.size() - 1; i >= 0; i--) {
                RuntimeEvent event = events.get(i);
                if (event == null || event.getPayload() == null || !"tool.done".equalsIgnoreCase(event.getEventType())) continue;
                Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
                String status = String.valueOf(payload.getOrDefault("status", ""));
                if (!"error".equalsIgnoreCase(status) && !"denied".equalsIgnoreCase(status)) continue;
                String toolName = String.valueOf(payload.getOrDefault("toolName", "tool"));
                String output = String.valueOf(payload.getOrDefault("output", "Tool execution failed"));
                String hint = output.toLowerCase(Locale.ROOT).contains("access denied")
                        ? "Access was denied by the upstream API (likely IP allowlist/VPN or auth policy)."
                        : "Please review tool auth/profile and endpoint access, then retry.";
                return "Tool call failed for `" + toolName + "`.\n\n"
                        + "Error: " + truncate(output, 500) + "\n\n"
                        + hint;
            }
        } catch (Exception ignored) {
        }
        return response;
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
                Set<String> hostBoostedToolIds = computeHostBoostedToolIds(sessionId);
                List<ToolEmbeddingIndexService.ScoredTool> scored = toolEmbeddingIndexService.searchTopK(
                        userText, topK, baseIds, memorySignals, hostBoostedToolIds, embeddingModelRef);
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
                                    String observationContext,
                                    boolean enforceSkillFirst,
                                    List<String> preferredSkillNames) {
        // Per-turn metadata leads (system-style annotations), then the user's actual request.
        // Conversation history is NOT duplicated here — it's already in the messages array passed
        // to ChatClient via .messages(history). The orchestrator trims the trailing UserMessage
        // from that history so this stepContext is the sole representation of the current turn's
        // user message.
        StringBuilder context = new StringBuilder();
        if (selectedSkillContext != null && !selectedSkillContext.isBlank()) {
            context.append(selectedSkillContext).append("\n\n");
        }
        if (enforceSkillFirst) {
            context.append("<skill_first_contract>\n")
                    .append("- Before any non-`skill` tool call, load and apply relevant skill instructions.\n")
                    .append("- Do not call domain tools with `{}` while mapping details from prior tool output.\n");
            if (preferredSkillNames != null && !preferredSkillNames.isEmpty()) {
                context.append("- Preferred skills for this request: ")
                        .append(String.join(", ", preferredSkillNames))
                        .append("\n");
            }
            context.append("</skill_first_contract>\n\n");
        }
        if (mcpContext != null && !mcpContext.isBlank()) {
            context.append(mcpContext).append("\n\n");
        }
        String manifestContext = buildWorkspaceManifest(session);
        if (!manifestContext.isBlank()) {
            context.append(manifestContext).append("\n\n");
        }
        String entityContext = buildEntityCarryForwardContext(session, userText);
        if (!entityContext.isBlank()) {
            context.append(entityContext).append("\n\n");
        }
        if (observationContext != null && !observationContext.isBlank()) {
            context.append("Tool observations:\n").append(observationContext).append("\n\n");
        }
        context.append("<tool_result_vfs_policy>\n")
                .append("- If a tool result includes `storedInVfs=true`, the full output was saved to local workspace VFS at `path`.\n")
                .append("- Before final synthesis, inspect that file using filesystem tools (`read` and `grep`) against the provided `path`.\n")
                .append("- Do not assume the `preview` field contains complete output; treat it as a teaser only.\n")
                .append("</tool_result_vfs_policy>\n\n");
        if (isToolChainDesignerMode(runtimeMode)) {
            context.append("""
<toolchain_designer_mode>
You are in ToolChain designer creation mode.
- Do NOT route to or execute existing ToolChains.
- Use available tools + skills to gather what you need for creating/editing a ToolChain draft.
- Prefer the ToolChain architect skill (toolchain-architect) for graph/schema/synthesis design.
- Ask only design/clarification questions required to build the ToolChain draft.
</toolchain_designer_mode>

""");
        }
        context.append("Runtime mode: ").append(runtimeMode).append("\n\n");
        context.append(userText == null ? "" : userText);
        return context.toString();
    }

    private boolean isToolChainDesignerMode(String runtimeMode) {
        return runtimeMode != null && "toolchain_designer".equalsIgnoreCase(runtimeMode.trim());
    }

    private boolean isToolChainArchitectRuntimeMode(String runtimeMode) {
        return runtimeMode != null && "toolchain_architect_runtime".equalsIgnoreCase(runtimeMode.trim());
    }

    private String buildWorkspaceManifest(AgentSession session) {
        Path workspace = session != null ? session.getWorkspacePath() : null;
        if (workspace == null || !Files.exists(workspace) || !Files.isDirectory(workspace)) {
            return "<workspace_manifest>\nstatus: no_workspace\nguidance: There is no local workspace attached to this session. Do not call read / glob / grep / edit / write / apply_patch — there are no local files. Use MCP / integration tools for any data fetch.\n</workspace_manifest>";
        }
        Path skillRoot = workspace.resolve(".pods-agent").normalize();
        List<String> entries = new ArrayList<>();
        int maxEntries = 80;
        try (var walk = Files.walk(workspace, 6)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(skillRoot))
                    .filter(p -> {
                        String fn = p.getFileName() == null ? "" : p.getFileName().toString();
                        return !fn.startsWith(".");
                    })
                    .limit(maxEntries + 1)
                    .forEach(p -> entries.add(workspace.relativize(p).toString()));
        } catch (Exception ignored) {
            return "";
        }
        if (entries.isEmpty()) {
            return "<workspace_manifest>\nstatus: empty\nguidance: The local workspace exists but is empty. Do not call read / glob / grep / edit — there are no local files yet. write / apply_patch may be used if the user explicitly asks to create a new file.\n</workspace_manifest>";
        }
        StringBuilder out = new StringBuilder("<workspace_manifest>\n");
        out.append("status: populated\n");
        boolean truncated = entries.size() > maxEntries;
        for (int i = 0; i < Math.min(entries.size(), maxEntries); i++) {
            out.append("- ").append(entries.get(i)).append("\n");
        }
        if (truncated) {
            out.append("(... more files exist; use glob to list further)\n");
        }
        out.append("guidance: read / glob / grep / edit / write apply only to the files listed above. For data outside this workspace (GitHub repos, external APIs, web pages, remote services), use the matching MCP / integration tool — never assume the local workspace contains it.\n");
        out.append("</workspace_manifest>");
        return out.toString();
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
        List<String> recentToolCalls = collectRecentToolCalls(session.getSessionId(), 6);
        List<String> registeredIntegrations = collectRegisteredIntegrations();
        if (ids.isEmpty() && recentToolCalls.isEmpty() && registeredIntegrations.isEmpty()) return "";
        String latestId = ids.stream().reduce((a, b) -> b).orElse("");
        StringBuilder out = new StringBuilder("<entity_carry_forward>\n");
        if (!ids.isEmpty()) {
            out.append("known_ids: ").append(ids.stream().limit(12).collect(Collectors.joining(", "))).append("\n");
            out.append("latest_id: ").append(latestId).append("\n");
        }
        if (!registeredIntegrations.isEmpty()) {
            out.append("registered_integrations:\n");
            for (String entry : registeredIntegrations) {
                out.append("  - ").append(entry).append("\n");
            }
        }
        if (!recentToolCalls.isEmpty()) {
            out.append("last_turn_tools:\n");
            for (String entry : recentToolCalls) {
                out.append("  - ").append(entry).append("\n");
            }
        }
        out.append("tool_routing_hints:\n")
                .append("- The full list of tools registered for each integration host is shown above in registered_integrations — read it before deciding. If the user wants the contents/body/text/details of a file, README, page, or document inside a registered host, use the host's `*get_file_contents*` or equivalent read tool. Do NOT fall back to webfetch / websearch / codesearch / commit-history / search-code as a substitute for fetching file contents — those return metadata, not content.\n")
                .append("- For any URL or data that belongs to a host listed in registered_integrations, USE the matching MCP / integration tool. Calling webfetch / websearch / codesearch against those hosts bypasses the registered tool's auth and private-data access.\n")
                .append("- If a tool returns 404 / parse-error / empty, try a different tool from the same integration before giving up. The integration almost always has a direct read tool — use it.\n")
                .append("- If the user is asking a follow-up about the same data the prior turn fetched, prefer tools from the same MCP server / integration.\n")
                .append("- Local filesystem tools (read / glob / grep / edit / write) are for the local workspace only — do not use them for remote-hosted entities (GitHub repos, external APIs, etc.). The <workspace_manifest> block lists what local files actually exist.\n")
                .append("- If user says 'this product' or 'this organization', reuse latest_id when appropriate.\n")
                .append("- Multiple tool calls in sequence are allowed in the same turn when needed.\n")
                .append("- Do NOT end your reply with 'Would you like me to...' / 'Should I...' / 'Do you want me to...' as a substitute for actually doing the work. Take the action now and present the result.\n");
        if (userText != null && !userText.isBlank()) {
            out.append("current_user_intent: ").append(truncate(userText, 240)).append("\n");
        }
        out.append("</entity_carry_forward>");
        return out.toString();
    }

    private List<String> collectRegisteredIntegrations() {
        try {
            List<AgentTool> tools = toolRegistryService.getEnabledTools();
            if (tools == null || tools.isEmpty()) return List.of();
            Map<String, List<String>> hostToTools = new LinkedHashMap<>();
            for (AgentTool tool : tools) {
                if (tool == null || tool.isBaseInjected()) continue;
                String src = tool.getSourceType() == null ? "" : tool.getSourceType().toLowerCase(Locale.ROOT);
                if ("framework_default".equals(src)) continue;
                String host = tool.getHost();
                if (host == null || host.isBlank()) continue;
                String name = tool.getName();
                if (name == null || name.isBlank()) continue;
                hostToTools.computeIfAbsent(host, k -> new ArrayList<>()).add(name);
            }
            if (hostToTools.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            int perHostLimit = 24;
            for (var entry : hostToTools.entrySet()) {
                List<String> sorted = entry.getValue().stream()
                        .distinct()
                        .sorted(Comparator
                                .comparingInt((String n) -> readActionRank(n))
                                .thenComparing(Comparator.naturalOrder()))
                        .toList();
                String toolNames = sorted.stream().limit(perHostLimit).collect(Collectors.joining(", "));
                int extra = sorted.size() - perHostLimit;
                String suffix = extra > 0 ? ", +" + extra + " more (use any of these)" : "";
                out.add(entry.getKey() + " → " + toolNames + suffix);
                if (out.size() >= 10) break;
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * Lower rank = surfaced first. Read-action verbs (get/list/search/read) lead so the
     * model sees safe lookup tools before write/destructive ones (create/delete/fork/push).
     */
    private int readActionRank(String toolName) {
        if (toolName == null) return 100;
        String n = toolName.toLowerCase(Locale.ROOT);
        if (n.contains("get_file_contents") || n.contains("getfilecontents")) return 0;
        if (n.contains("read_") || n.endsWith("_read")) return 1;
        if (n.startsWith("mcp_get_") || n.contains("_get_") || n.endsWith("_get")) return 2;
        if (n.startsWith("mcp_list_") || n.contains("_list_") || n.endsWith("_list")) return 3;
        if (n.startsWith("mcp_search_") || n.contains("_search_") || n.endsWith("_search")) return 4;
        if (n.startsWith("mcp_describe_") || n.contains("_describe_")) return 5;
        if (n.contains("create") || n.contains("update") || n.contains("write") || n.contains("edit")) return 30;
        if (n.contains("delete") || n.contains("remove") || n.contains("drop")) return 40;
        if (n.contains("fork") || n.contains("merge") || n.contains("push") || n.contains("dispatch")) return 35;
        return 20;
    }

    private Set<String> computeHostBoostedToolIds(String sessionId) {
        Set<String> hosts = collectLastTurnHosts(sessionId);
        if (hosts.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (AgentTool tool : toolRegistryService.getEnabledTools()) {
                if (tool == null || tool.getId() == null) continue;
                String host = tool.getHost();
                if (host != null && hosts.contains(host)) {
                    ids.add(tool.getId());
                }
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private Set<String> collectLastTurnHosts(String sessionId) {
        if (sessionId == null) return Set.of();
        Set<String> hosts = new LinkedHashSet<>();
        try {
            List<RuntimeEvent> events = runtimeEventRepository.findBySessionId(sessionId);
            if (events == null || events.isEmpty()) return Set.of();
            int scanned = 0;
            for (int i = events.size() - 1; i >= 0 && scanned < 12; i--) {
                RuntimeEvent ev = events.get(i);
                if (ev == null || !"tool.call".equalsIgnoreCase(ev.getEventType())) continue;
                scanned++;
                String payload = ev.getPayload();
                if (payload == null) continue;
                Matcher matcher = TOOL_NAME_PATTERN.matcher(payload);
                if (!matcher.find()) continue;
                String toolName = matcher.group(1);
                AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
                if (tool == null) continue;
                String host = tool.getHost();
                if (host != null && !host.isBlank()) hosts.add(host);
            }
        } catch (Exception ignored) {
        }
        return hosts;
    }

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("\"toolName\"\\s*:\\s*\"([^\"]+)\"");

    private List<String> collectRecentToolCalls(String sessionId, int limit) {
        if (sessionId == null) return List.of();
        try {
            List<RuntimeEvent> events = runtimeEventRepository.findBySessionId(sessionId);
            if (events == null || events.isEmpty()) return List.of();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (int i = events.size() - 1; i >= 0 && seen.size() < limit; i--) {
                RuntimeEvent ev = events.get(i);
                if (ev == null || !"tool.call".equalsIgnoreCase(ev.getEventType())) continue;
                String payload = ev.getPayload();
                if (payload == null) continue;
                Matcher matcher = TOOL_NAME_PATTERN.matcher(payload);
                if (!matcher.find()) continue;
                String toolName = matcher.group(1);
                String label = decorateToolLabel(toolName);
                seen.add(label);
            }
            List<String> ordered = new ArrayList<>(seen);
            java.util.Collections.reverse(ordered);
            return ordered;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String decorateToolLabel(String toolName) {
        if (toolName == null || toolName.isBlank()) return "";
        try {
            AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
            if (tool != null) {
                String host = tool.getHost();
                String src = tool.getSourceType();
                if (host != null && !host.isBlank()) {
                    return toolName + " (" + (src != null && !src.isBlank() ? src + ":" : "") + host + ")";
                }
                if (src != null && !src.isBlank()) {
                    return toolName + " (" + src + ")";
                }
            }
        } catch (Exception ignored) {
        }
        return toolName;
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
        SkillContextMode mode = resolveSkillContextMode(userText, state == null ? null : state.getRuntimeMode());
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

    private boolean shouldEnforceSkillFirst(String userText, String sessionId) {
        if (userText == null || userText.isBlank()) return false;
        if (isCapabilitiesQuery(userText) || isCatalogOnlySkillQuery(userText)) return false;
        if (!isTaskOrExecutionIntent(userText)) return false;
        List<SkillRegistryService.SkillSnapshot> shortlisted = buildSkillShortlist(userText, sessionId);
        if (shortlisted == null || shortlisted.isEmpty()) return false;
        return !fallbackSelectSkills(userText, shortlisted, 2).isEmpty();
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

    private SkillContextMode resolveSkillContextMode(String userText, String runtimeMode) {
        if (userText == null || userText.isBlank()) return SkillContextMode.NONE;
        if (isToolChainDesignerMode(runtimeMode)) {
            return runtimeTuningProperties.isIncludeFullSkillFiles()
                    ? SkillContextMode.FULL_SKILL_FILES
                    : SkillContextMode.CATALOG_ONLY;
        }
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
                        "analyze", "summarize", "parse", "fix", "run", "execute", "write", "read", "use",
                        "validate", "validation", "check", "verify", "investigate", "debug", "triage",
                        "report", "audit", "review", "reconcile", "remediate", "diagnose"
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

    /**
     * Reads the chain's metadata for `paramExtractionHints` (LLM-authored examples of how a
     * user phrase maps to typed params). Returned to the extractor as the "hints" payload.
     */
    @SuppressWarnings("unchecked")
    private String extractionHintsForChain(String toolChainId) {
        if (toolChainId == null || toolChainId.isBlank()) return null;
        try {
            var chain = toolChainService.getRequired(toolChainId);
            String raw = chain.getMetadataJson();
            if (raw == null || raw.isBlank()) return null;
            Map<String, Object> meta = objectMapper.readValue(raw, Map.class);
            Object hints = meta.get("paramExtractionHints");
            return hints == null ? null : String.valueOf(hints);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Falls back to the chain's stored default model when the chat session doesn't have one
     * (e.g., the chain was matched without an active state.model). Mirrors
     * ToolChainRuntimeService.resolveModelRefForSynthesis.
     */
    @SuppressWarnings("unchecked")
    private ModelRef resolveDefaultModelRefForChain(String toolChainId) {
        if (toolChainId == null || toolChainId.isBlank()) return null;
        try {
            var chain = toolChainService.getRequired(toolChainId);
            String raw = chain.getMetadataJson();
            if (raw == null || raw.isBlank()) return null;
            Map<String, Object> meta = objectMapper.readValue(raw, Map.class);
            Object refObj = meta.get("runtimeModelRef");
            if (!(refObj instanceof Map<?, ?>)) refObj = meta.get("defaultModelRef");
            if (!(refObj instanceof Map<?, ?> refMap)) return null;
            Object pid = refMap.get("providerID");
            Object mid = refMap.get("modelID");
            if (pid == null || mid == null) return null;
            return new ModelRef(String.valueOf(pid), String.valueOf(mid));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String renderToolChainAssistantMessage(ToolChainRun run) {
        if (run == null) return "";
        String snapshot = run.getOutputSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return "{\"runId\":\"" + (run.getId() == null ? "" : run.getId())
                    + "\",\"status\":\"" + (run.getStatus() == null ? "" : run.getStatus()) + "\"}";
        }
        try {
            Map<String, Object> outer = objectMapper.readValue(snapshot, Map.class);
            Object resultField = outer.get("result");
            String summary = findSummary(resultField);
            if (summary != null && !summary.isBlank()) return summary;
            if (resultField instanceof String s && !s.isBlank()) {
                String trimmed = s.trim();
                // synthesized_text mode returns the synthesis text directly (not JSON-shaped)
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return s;
            }
            String topSummary = findSummary(outer);
            if (topSummary != null && !topSummary.isBlank()) return topSummary;
        } catch (Exception e) {
            log.warn("[AgentRuntime] could not parse toolchain outputSnapshot for assistant render: {}", e.getMessage());
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private String findSummary(Object node) {
        if (node == null) return null;
        if (node instanceof String s) {
            String trimmed = s.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
            try {
                Object parsed = objectMapper.readValue(trimmed, Object.class);
                return findSummary(parsed);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (node instanceof Map<?, ?> map) {
            Object summary = map.get("summary");
            if (summary instanceof String s && !s.isBlank()) return s;
            for (Object value : map.values()) {
                String nested = findSummary(value);
                if (nested != null) return nested;
            }
        }
        return null;
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

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
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
