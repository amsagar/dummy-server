package com.pods.agent.agent;

import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentProfile;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.AgentProfileRepository;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.ruledomain.ResponseSummarizer;
import com.pods.agent.ruledomain.RuleDomainOrchestrator;
import com.pods.agent.ruledomain.SkillRouter;
import com.pods.agent.ruledomain.SseRuleDomainEventBus;
import com.pods.agent.ruledomain.compiler.AsyncTraceCompiler;
import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.service.TurnToolCache;
import com.pods.agent.service.MemoryService;
import com.pods.agent.service.instruction.InstructionLoaderService;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.UserContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;

/**
 * Streams a single chat turn from the configured LLM. No tools / skills.
 * Multi-model: ChatState.model (ModelRef) drives routing via ModelProviderRouter.
 */
@Component
@Slf4j
public class AgentOrchestrator {

    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final InstructionLoaderService instructionLoaderService;
    private final MemoryService memoryService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final RuntimeEventRepository runtimeEventRepository;
    private final ObjectMapper objectMapper;
    private final AgentProfileRepository agentProfileRepository;
    /** Optional — only present when the compiled-rule-domain feature is wired in. */
    private final ObjectProvider<RuleDomainOrchestrator> ruleDomainOrchestrator;
    private final ObjectProvider<ResponseSummarizer> responseSummarizer;
    private final ObjectProvider<SseRuleDomainEventBus> ruleDomainEventBus;
    private final ObjectProvider<TurnToolCache> turnToolCache;
    private final ObjectProvider<AsyncTraceCompiler> asyncTraceCompiler;
    private final ObjectProvider<SkillRouter> skillRouter;
    private final String baseSystemPrompt;
    private final String memoryToolsPrompt;

    public AgentOrchestrator(ModelProviderRouter modelProviderRouter,
                             SkillRegistryService skillRegistryService,
                             InstructionLoaderService instructionLoaderService,
                             MemoryService memoryService,
                             RuntimeTuningProperties runtimeTuningProperties,
                             RuntimeEventRepository runtimeEventRepository,
                             ObjectMapper objectMapper,
                             AgentProfileRepository agentProfileRepository,
                             ObjectProvider<RuleDomainOrchestrator> ruleDomainOrchestrator,
                             ObjectProvider<ResponseSummarizer> responseSummarizer,
                             ObjectProvider<SseRuleDomainEventBus> ruleDomainEventBus,
                             ObjectProvider<TurnToolCache> turnToolCache,
                             ObjectProvider<AsyncTraceCompiler> asyncTraceCompiler,
                             ObjectProvider<SkillRouter> skillRouter) {
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.instructionLoaderService = instructionLoaderService;
        this.memoryService = memoryService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.runtimeEventRepository = runtimeEventRepository;
        this.objectMapper = objectMapper;
        this.agentProfileRepository = agentProfileRepository;
        this.ruleDomainOrchestrator = ruleDomainOrchestrator;
        this.responseSummarizer = responseSummarizer;
        this.ruleDomainEventBus = ruleDomainEventBus;
        this.turnToolCache = turnToolCache;
        this.asyncTraceCompiler = asyncTraceCompiler;
        this.skillRouter = skillRouter;
        this.baseSystemPrompt = loadBaseSystemPrompt();
        this.memoryToolsPrompt = loadPromptResource("prompts/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md");
    }

    /**
     * Streams a single agent turn end-to-end via Spring AI native tool calling.
     * The LLM produces token deltas (forwarded to {@code sender.sendTextDelta})
     * and structured tool-use blocks; Spring AI invokes the matching
     * {@link ToolCallback} (which itself emits tool.call/tool.result SSE events),
     * then resumes streaming until the model completes.
     */
    public String streamTurn(AgentSession session,
                             String userText,
                             ChatState state,
                             SseEventSender sender,
                             List<ToolCallback> tools,
                             String turnId) {
        ModelRef modelRef = state != null ? state.getModel() : null;
        ModelProviderRouter.Spec modelSpec = modelProviderRouter.resolve(modelRef);
        ChatClient client = modelSpec.client();

        // The bare user message is added to session.getMessages() by AgentRuntimeService.runTurn
        // before streamTurn is invoked. buildMessageList() drops the trailing user entry, so the
        // history we pass via .messages(history) excludes the current turn's user message — and
        // .user(userText) below sends the stepContext (per-turn metadata + bare user text) as the
        // single representation of the current turn.
        applyPromptWindowGuard(session, state, userText);

        log.info("[AgentOrchestrator] streamTurn: session={}, model={}, tools={}",
                session.getSessionId(),
                modelRef != null ? modelRef : "default",
                tools == null ? 0 : tools.size());

        // ── Compiled rule-domain path (additive, opt-in per skill) ──
        // If a compiled domain handles this intent, stream the summarized
        // result and short-circuit before the LLM loop. Any failure here
        // falls through to the existing LLM loop below.
        RuleDomainOrchestrator rdo = ruleDomainOrchestrator == null ? null : ruleDomainOrchestrator.getIfAvailable();
        ResponseSummarizer summarizer = responseSummarizer == null ? null : responseSummarizer.getIfAvailable();
        SseRuleDomainEventBus rdBus = ruleDomainEventBus == null ? null : ruleDomainEventBus.getIfAvailable();
        TurnToolCache toolCache = turnToolCache == null ? null : turnToolCache.getIfAvailable();
        // State threaded to the LLM-loop block below so it can append the
        // fallback advisory to the assistant response.
        boolean fellBackFromRuleDomain = false;
        String fallbackFailedTool = null;
        if (rdo != null && summarizer != null) {
            if (rdBus != null) rdBus.bind(turnId, session.getSessionId(), sender);
            try {
                ExecutionOutcome outcome = rdo.handleIfApplicable(userText, null, session.getSessionId(), turnId);
                if (outcome.handled() && outcome.error() == null) {
                    String summary = summarizer.summarize(outcome, userText);
                    if (summary != null && !summary.isBlank()) {
                        sender.sendTextDelta(summary);
                        session.getMessages().add(new AssistantMessage(summary));
                    }
                    sender.sendCustom("rule_domain.executed", Map.of(
                            "domainId", outcome.domainId() == null ? "" : outcome.domainId(),
                            "procId", outcome.flowableProcId() == null ? "" : outcome.flowableProcId(),
                            "fromCacheHit", outcome.fromCacheHit(),
                            "latencyMs", outcome.latencyMs(),
                            "error", ""
                    ));
                    return summary == null ? "" : summary;
                }
                if (outcome.handled() && outcome.error() != null) {
                    log.warn("[AgentOrchestrator] Compiled rule-domain failed ({}); falling back to LLM loop.",
                            outcome.error());
                    if (outcome.errorMeta() != null) {
                        fallbackFailedTool = outcome.errorMeta().get("failedTool");
                    }
                    fellBackFromRuleDomain = true;
                    sender.sendCustom("rule_domain.failed", Map.of(
                            "domainId", outcome.domainId() == null ? "" : outcome.domainId(),
                            "procId", outcome.flowableProcId() == null ? "" : outcome.flowableProcId(),
                            "error", outcome.error(),
                            "failedTool", fallbackFailedTool == null ? "" : fallbackFailedTool
                    ));
                    // intentional fall-through below
                }
            } catch (Exception ex) {
                log.warn("[AgentOrchestrator] Rule-domain path threw; falling back to LLM loop: {}",
                        ex.getMessage());
            } finally {
                if (rdBus != null) rdBus.unbind(turnId);
                // Cache clearing also happens in the outer finally below, but
                // the rule-domain success path early-returns and never reaches
                // it. Clear here too — clearTurn is idempotent.
                if (toolCache != null && turnId != null) {
                    toolCache.clearTurn(turnId);
                }
            }
        }

        StringBuilder fullResponse = new StringBuilder();
        try {
            String systemPrompt = buildSystemPrompt(state, session);
            List<Message> history = sanitizeMessageList(buildMessageList(session));
            var promptSpec = client.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .user(userText)
                    .advisors(new org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor());

            if (tools != null && !tools.isEmpty()) {
                promptSpec = promptSpec.toolCallbacks(tools);
            }
            if (modelSpec.options() != null) {
                promptSpec = promptSpec.options(modelSpec.options());
            }

            StringBuilder nativeReasoning = new StringBuilder();
            ThinkTagParser thinkParser = new ThinkTagParser(sender, fullResponse);
            promptSpec.stream()
                    .chatResponse()
                    .doOnNext(chatResponse -> {
                        if (session.isCancelled()) throw new java.util.concurrent.CancellationException("Turn cancelled by user");
                        if (chatResponse == null || chatResponse.getResult() == null) return;
                        var output = chatResponse.getResult().getOutput();
                        String delta = output == null ? null : output.getText();
                        if (delta == null || delta.isEmpty()) return;
                        log.debug("[AgentOrchestrator] Delta received: {}", delta);
                        boolean isNativeThinking = Boolean.TRUE.equals(
                                output.getMetadata() != null ? output.getMetadata().get("thinking") : null);
                        if (isNativeThinking) {
                            nativeReasoning.append(delta);
                            sender.sendReasoningDelta(delta);
                        } else {
                            thinkParser.feed(delta);
                        }
                    })
                    .blockLast();
            thinkParser.flush();

            // If we fell back here from a failed compiled rule-domain, append a
            // one-line advisory so the user knows the precompiled workflow had
            // trouble and can investigate it in the admin UI. Both stream and
            // persist (appending to fullResponse before .toString()) so the
            // advisory survives into the next turn's conversation history.
            if (fellBackFromRuleDomain) {
                String advisory = "\n\n> *Note: the precompiled workflow for this intent failed"
                        + (fallbackFailedTool != null && !fallbackFailedTool.isBlank()
                            ? " (`" + fallbackFailedTool + "`)" : "")
                        + ". This answer came from the live agent — review the domain at **Settings → Rule Domains**.*";
                sender.sendTextDelta(advisory);
                fullResponse.append(advisory);
            }

            String finalContent = fullResponse.toString();
            if (!finalContent.isBlank()) {
                session.getMessages().add(new AssistantMessage(finalContent));
            }

            // Persist accumulated reasoning (native + <think>-tag) as a single event
            String allReasoning = nativeReasoning + thinkParser.getAccumulatedReasoning();
            if (!allReasoning.isBlank() && turnId != null) {
                try {
                    String payload = objectMapper.writeValueAsString(Map.of("content", allReasoning));
                    runtimeEventRepository.save(RuntimeEvent.builder()
                            .sessionId(session.getSessionId())
                            .turnId(turnId)
                            .eventType("reasoning")
                            .payload(payload)
                            .build());
                } catch (Exception ex) {
                    log.warn("[AgentOrchestrator] Failed to persist reasoning: {}", ex.getMessage());
                }
            }

            // Schedule a trace-based compile in the background. The user
            // already has their answer; this runs async and produces compiled
            // rule BPMNs from the recorded tool trace. Idempotent — no-op
            // when the skill has no rule manifest, or when every rule is
            // already trace-compiled. See AsyncTraceCompiler.
            scheduleTraceCompile(session, userText, turnId, finalContent);

            return finalContent.isEmpty() ? "Done." : finalContent;
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[AgentOrchestrator] Turn cancelled by user: session={}", session.getSessionId());
            String partial = fullResponse.toString();
            return partial.isEmpty() ? "" : partial;
        } catch (Exception e) {
            log.error("[AgentOrchestrator] streamTurn failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            // Drop this turn's tool-cache bucket. Idempotent — safe to call
            // even when the rule-domain path early-returned. Cancels any
            // outstanding in-flight CompletableFutures (e.g. when a turn
            // is cancelled mid-call).
            if (toolCache != null && turnId != null) {
                toolCache.clearTurn(turnId);
            }
        }
    }

    /**
     * Kick off the async trace-based compile. No-op when:
     * <ul>
     *   <li>the LLM-loop didn't produce useful content (failed / cancelled), or</li>
     *   <li>the skill router can't pick a skill for this message, or</li>
     *   <li>that skill has no {@code rules:} frontmatter (legacy single-BPMN flow), or</li>
     *   <li>any of the trace-compile beans isn't wired in.</li>
     * </ul>
     */
    private void scheduleTraceCompile(AgentSession session, String userText, String turnId, String finalContent) {
        if (finalContent == null || finalContent.isBlank()) return;
        if (turnId == null || turnId.isBlank()) return;
        SkillRouter router = skillRouter == null ? null : skillRouter.getIfAvailable();
        AsyncTraceCompiler compiler = asyncTraceCompiler == null ? null : asyncTraceCompiler.getIfAvailable();
        if (router == null || compiler == null) return;
        try {
            router.route(userText).ifPresent(routed -> {
                if (routed.manifest() == null || routed.manifest().isEmpty()) return;
                compiler.scheduleCompile(routed, session.getSessionId(), turnId);
            });
        } catch (Exception ex) {
            log.debug("[AgentOrchestrator] scheduleTraceCompile failed (suppressed): {}", ex.getMessage());
        }
    }

    private String buildSystemPrompt(ChatState state, AgentSession session) {
        // When an agent profile is explicitly selected (e.g. ov-basic /
        // ov-detailed from the order-validation-ui), its system_prompt
        // *replaces* the base prompt and the skill catalog. This lets a
        // narrow assistant fully scope its own behavior without competing
        // with skill instructions baked into the default prompt. We still
        // append timezone + memory-tools so the profile can call the
        // memory tools and reason about "today's date" correctly.
        String profileId = state == null ? null : state.getAgentProfileId();
        if (profileId != null && !profileId.isBlank()) {
            AgentProfile profile = agentProfileRepository.findById(profileId).orElse(null);
            if (profile != null && profile.getSystemPrompt() != null && !profile.getSystemPrompt().isBlank()) {
                StringBuilder p = new StringBuilder(profile.getSystemPrompt());
                p.append("\n\nCurrent date: ");
                String ptz = (state != null && state.getTimezone() != null) ? state.getTimezone() : "America/New_York";
                try {
                    p.append(ZonedDateTime.now(ZoneId.of(ptz)).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                } catch (Exception e) {
                    p.append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
                }
                p.append("\n");
                return p.toString();
            }
        }

        StringBuilder prompt = new StringBuilder(baseSystemPrompt);

        // Date/time
        prompt.append("\nCurrent date: ");
        String tz = (state != null && state.getTimezone() != null) ? state.getTimezone() : "America/New_York";
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(tz));
            prompt.append(now.format(DateTimeFormatter.RFC_1123_DATE_TIME));
        } catch (Exception e) {
            prompt.append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
        }
        prompt.append("\n");

        prompt.append("\n## Retrieval Catalog Contract\n")
                .append("- Use `skillsearch` to find relevant skills by embedding + lexical ranking over skill metadata.\n")
                .append("- Use `toolsearch` to find relevant tools (framework + imported + MCP) by embedding + lexical ranking.\n")
                .append("- After selecting a skill from `skillsearch`, call `skill` with exact name before domain tool execution.\n");

        if (session != null && session.getWorkspacePath() != null) {
            String instructions = instructionLoaderService.load(session.getWorkspacePath());
            if (!instructions.isBlank()) {
                prompt.append("\n## Workspace Instructions\n")
                        .append(instructions)
                        .append("\n");
            }
            prompt.append("\n## Workspace Skill Manifest\n")
                    .append("workspace://skills")
                    .append("\n");
        }

        // Conversation rolling summary
        if (state != null && state.getRollingSummary() != null && !state.getRollingSummary().isBlank()) {
            prompt.append("\nConversation summary context:\n")
                    .append(state.getRollingSummary())
                    .append("\n");
        }
        String userId = UserContextHolder.currentUserId();
        if (userId != null && !userId.isBlank()) {
            if (memoryToolsPrompt != null && !memoryToolsPrompt.isBlank()) {
                prompt.append("\n").append(memoryToolsPrompt).append("\n");
            }
            String memoryPrompt = memoryService.buildInjectionPrompt(userId, state != null ? state.getRollingSummary() : "", 2000);
            if (!memoryPrompt.isBlank()) {
                prompt.append("\n").append(memoryPrompt).append("\n");
            }
        }
        appendBudgetHint(prompt, state, session);
        appendProviderCachingHint(prompt, state);
        return prompt.toString();
    }

    String baseSystemPromptForTest() {
        return baseSystemPrompt;
    }

    String buildSystemPromptForTest(ChatState state, AgentSession session) {
        return buildSystemPrompt(state, session);
    }

    private String loadBaseSystemPrompt() {
        String loaded = loadPromptResource("prompts/base-system-prompt.md");
        if (loaded != null && !loaded.isBlank()) {
            return loaded;
        }
        // Last-resort fallback to keep startup resilient if resource is missing.
        return "You are AI Agent.";
    }

    private String loadPromptResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!prompt.isBlank()) return prompt;
        } catch (Exception ignored) {
        }
        return "";
    }

    String normalizeUserMessageForHistory(String userText) {
        if (userText == null || userText.isBlank()) return "";
        String normalized = userText;
        List<String> markers = List.of(
                "\n\n<skill_catalog>",
                "\n\n<skill_content",
                "\n\n<skill_workspace_manifest>",
                "\n\nTool execution result:",
                "\n\nRuntime mode:",
                "\n\nPlanner worker outputs:",
                "\n\nSwarm findings:"
        );
        int cut = -1;
        for (String marker : markers) {
            int idx = normalized.indexOf(marker);
            if (idx >= 0 && (cut < 0 || idx < cut)) {
                cut = idx;
            }
        }
        if (cut >= 0) {
            normalized = normalized.substring(0, cut);
        }
        return normalized.trim();
    }

    private List<Message> buildMessageList(AgentSession session) {
        List<Message> messages = session.getMessages();
        if (messages.size() <= 1) return List.of();
        return new ArrayList<>(messages.subList(0, messages.size() - 1));
    }

    private List<Message> sanitizeMessageList(List<Message> original) {
        if (original == null || original.isEmpty()) return List.of();
        if (!runtimeTuningProperties.isEnableMessageSanitization()) return original;
        List<Message> sanitized = new ArrayList<>();
        for (Message message : original) {
            if (message instanceof UserMessage um) {
                String cleaned = normalizeUserMessageForHistory(um.getText());
                if (!cleaned.isBlank()) {
                    sanitized.add(new UserMessage(cleaned));
                }
            } else if (message instanceof AssistantMessage am) {
                String text = am.getText() == null ? "" : am.getText().trim();
                if (!text.isBlank()) {
                    sanitized.add(new AssistantMessage(text));
                }
            }
        }
        return sanitized;
    }

    private void applyPromptWindowGuard(AgentSession session, ChatState state, String userText) {
        if (!runtimeTuningProperties.isEnablePromptWindowGuard()) return;
        int contextWindow = resolveContextWindowTokens(state != null ? state.getModel() : null);
        int utilization = Math.min(95, Math.max(10, runtimeTuningProperties.getContextWindowUtilizationPercent()));
        long budgetTokens = Math.max(512, (long) Math.floor(contextWindow * (utilization / 100.0)));
        int minKeep = Math.max(2, runtimeTuningProperties.getMinHistoryMessagesToKeep());
        int safetyCounter = 0;
        while (safetyCounter++ < 300) {
            String systemPrompt = buildSystemPrompt(state, session);
            List<Message> history = sanitizeMessageList(buildMessageList(session));
            long estimated = estimatePromptTokens(systemPrompt, history, userText, state != null ? state.getModel() : null);
            if (estimated <= budgetTokens) return;
            if (session.getMessages().size() <= (minKeep + 1)) return;
            session.getMessages().remove(0);
        }
    }

    private long estimatePromptTokens(String systemPrompt, List<Message> history, String userText, ModelRef modelRef) {
        double charsPerToken = resolveCharsPerToken(modelRef);
        long chars = (systemPrompt == null ? 0 : systemPrompt.length()) + (userText == null ? 0 : userText.length());
        if (history != null) {
            for (Message m : history) {
                if (m instanceof UserMessage um && um.getText() != null) chars += um.getText().length();
                if (m instanceof AssistantMessage am && am.getText() != null) chars += am.getText().length();
            }
        }
        return Math.max(1, (long) Math.ceil(chars / charsPerToken));
    }

    private double resolveCharsPerToken(ModelRef modelRef) {
        String provider = modelRef == null || modelRef.providerID() == null
                ? ""
                : modelRef.providerID().toLowerCase(Locale.ROOT);
        if (provider.contains("anthropic")) return runtimeTuningProperties.getAnthropicCharsPerToken();
        if (provider.contains("ollama")) return runtimeTuningProperties.getOllamaCharsPerToken();
        return runtimeTuningProperties.getOpenAiCharsPerToken();
    }

    private int resolveContextWindowTokens(ModelRef modelRef) {
        String provider = modelRef == null || modelRef.providerID() == null
                ? ""
                : modelRef.providerID().toLowerCase(Locale.ROOT);
        if (provider.contains("anthropic")) return 200_000;
        if (provider.contains("ollama")) return 32_000;
        if (provider.contains("openai") || provider.contains("azure") || provider.contains("google")) return 128_000;
        return 64_000;
    }

    private void appendBudgetHint(StringBuilder prompt, ChatState state, AgentSession session) {
        if (!runtimeTuningProperties.isEnableBudgetHints()) return;
        int contextWindow = resolveContextWindowTokens(state != null ? state.getModel() : null);
        long estimate = estimatePromptTokens("", sanitizeMessageList(buildMessageList(session)), "", state != null ? state.getModel() : null);
        int usagePercent = (int) Math.min(100, Math.round((estimate * 100.0) / Math.max(1, contextWindow)));
        if (usagePercent < runtimeTuningProperties.getBudgetHintWarnPercent()) return;
        String level = usagePercent >= runtimeTuningProperties.getBudgetHintCriticalPercent()
                ? "critical"
                : usagePercent >= runtimeTuningProperties.getBudgetHintElevatedPercent() ? "elevated" : "warn";
        prompt.append("\n## Context Budget\n")
                .append("Estimated usage: ").append(usagePercent).append("% (").append(level).append(")\n")
                .append("Token budget is getting high — avoid repeating large prior content verbatim, but still answer the user's question fully.\n");
    }

    private void appendProviderCachingHint(StringBuilder prompt, ChatState state) {
        if (!runtimeTuningProperties.isEnableAnthropicPromptCachingHints()) return;
        String provider = state != null && state.getModel() != null ? state.getModel().providerID() : "";
        if (provider == null || !provider.toLowerCase(Locale.ROOT).contains("anthropic")) return;
        prompt.append("\n<anthropic_cache_hint>\n")
                .append("Treat stable system sections and skill blocks as cache-friendly context.\n")
                .append("</anthropic_cache_hint>\n");
    }

    /**
     * Stateful streaming parser that splits <think>…</think> blocks out of the text stream.
     * Thinking content is emitted as reasoning.delta; everything else goes to text.delta and
     * accumulates in fullResponse. Works for Ollama DeepSeek-R1, OpenAI o-series via LiteLLM,
     * and any other provider that embeds reasoning in <think> tags.
     */
    private static final class ThinkTagParser {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";

        private final SseEventSender sender;
        private final StringBuilder fullResponse;
        private final StringBuilder buf = new StringBuilder();
        private final StringBuilder accumulatedReasoning = new StringBuilder();
        private boolean inThink = false;

        ThinkTagParser(SseEventSender sender, StringBuilder fullResponse) {
            this.sender = sender;
            this.fullResponse = fullResponse;
        }

        String getAccumulatedReasoning() {
            return accumulatedReasoning.toString();
        }

        void feed(String delta) {
            buf.append(delta);
            process();
        }

        void flush() {
            // Emit whatever remains — if still in a think block treat it as reasoning
            String rest = buf.toString();
            buf.setLength(0);
            if (rest.isEmpty()) return;
            if (inThink) {
                accumulatedReasoning.append(rest);
                sender.sendReasoningDelta(rest);
            } else {
                fullResponse.append(rest);
                sender.sendTextDelta(rest);
            }
        }

        private void emitReasoning(String text) {
            accumulatedReasoning.append(text);
            sender.sendReasoningDelta(text);
        }

        private void process() {
            while (buf.length() > 0) {
                if (!inThink) {
                    int tagIdx = buf.indexOf(OPEN);
                    if (tagIdx >= 0) {
                        if (tagIdx > 0) {
                            String before = buf.substring(0, tagIdx);
                            fullResponse.append(before);
                            sender.sendTextDelta(before);
                        }
                        buf.delete(0, tagIdx + OPEN.length());
                        inThink = true;
                    } else {
                        int safe = buf.length() - (OPEN.length() - 1);
                        if (safe <= 0) break;
                        String safe_text = buf.substring(0, safe);
                        fullResponse.append(safe_text);
                        sender.sendTextDelta(safe_text);
                        buf.delete(0, safe);
                        break;
                    }
                } else {
                    int closeIdx = buf.indexOf(CLOSE);
                    if (closeIdx >= 0) {
                        if (closeIdx > 0) emitReasoning(buf.substring(0, closeIdx));
                        buf.delete(0, closeIdx + CLOSE.length());
                        inThink = false;
                    } else {
                        int safe = buf.length() - (CLOSE.length() - 1);
                        if (safe <= 0) break;
                        emitReasoning(buf.substring(0, safe));
                        buf.delete(0, safe);
                        break;
                    }
                }
            }
        }
    }

}
