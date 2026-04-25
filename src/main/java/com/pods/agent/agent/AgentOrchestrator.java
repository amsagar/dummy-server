package com.pods.agent.agent;

import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.MemoryService;
import com.pods.agent.service.instruction.InstructionLoaderService;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

    private static final String DEFAULT_BASE_SYSTEM_PROMPT = """
            You are AI Agent.
            Operate within registered tools and skills scope.
            Be concise, accurate, and friendly.
            """;

    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final InstructionLoaderService instructionLoaderService;
    private final MemoryService memoryService;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final ObjectMapper objectMapper;
    private final String baseSystemPrompt;
    private final String memoryToolsPrompt;

    public record ToolIntent(String name, Map<String, Object> arguments) {}
    public record StepDecision(String mode,
                               String reason,
                               List<ToolIntent> toolCalls,
                               String finalResponse,
                               String finishReason,
                               String assistantMessage) {}

    public AgentOrchestrator(ModelProviderRouter modelProviderRouter,
                             SkillRegistryService skillRegistryService,
                             InstructionLoaderService instructionLoaderService,
                             MemoryService memoryService,
                             RuntimeTuningProperties runtimeTuningProperties,
                             ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.instructionLoaderService = instructionLoaderService;
        this.memoryService = memoryService;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.objectMapper = objectMapper;
        this.baseSystemPrompt = loadBaseSystemPrompt();
        this.memoryToolsPrompt = loadPromptResource("prompts/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md");
    }

    public String chat(AgentSession session, String userText, ChatState state, SseEventSender sender) {
        ModelRef modelRef = state != null ? state.getModel() : null;
        ModelProviderRouter.Spec modelSpec = modelProviderRouter.resolve(modelRef);
        ChatClient client = modelSpec.client();

        session.getMessages().add(new UserMessage(normalizeUserMessageForHistory(userText)));
        applyPromptWindowGuard(session, state, userText);

        log.info("[AgentOrchestrator] Starting chat: session={}, model={}",
                session.getSessionId(),
                modelRef != null ? modelRef : "default");

        try {
            String systemPrompt = buildSystemPrompt(state, session);
            List<Message> history = sanitizeMessageList(buildMessageList(session));
            var promptSpec = client.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .user(userText)
                    .advisors(new org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor());

            if (modelSpec.options() != null) {
                promptSpec = promptSpec.options(modelSpec.options());
            }

            StringBuilder fullResponse = new StringBuilder();

            promptSpec.stream()
                    .content()
                    .doOnNext(delta -> {
                        log.debug("[AgentOrchestrator] Delta received: {}", delta);
                        fullResponse.append(delta);
                        sender.sendTextDelta(delta);
                    })
                    .blockLast();

            String finalContent = fullResponse.toString();
            if (!finalContent.isBlank()) {
                session.getMessages().add(new AssistantMessage(finalContent));
            }

            return finalContent.isEmpty() ? "Done." : finalContent;

        } catch (Exception e) {
            log.error("[AgentOrchestrator] Chat failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    public StepDecision decideNextStep(AgentSession session,
                                       String userText,
                                       ChatState state,
                                       List<Map<String, Object>> toolCatalog,
                                       int stepNumber) {
        try {
            ModelRef modelRef = state != null ? state.getModel() : null;
            ModelProviderRouter.Spec modelSpec = modelProviderRouter.resolve(modelRef);
            ChatClient client = modelSpec.client();
            StringBuilder prompt = new StringBuilder();
            prompt.append("Step: ").append(stepNumber).append("\n\n");
            prompt.append("User/request context:\n").append(userText == null ? "" : userText).append("\n\n");
            prompt.append("Available tools JSON:\n").append(objectMapper.writeValueAsString(toolCatalog == null ? List.of() : toolCatalog)).append("\n\n");
            prompt.append("Return ONLY JSON with exact shape:\n");
            prompt.append("{\"mode\":\"tools|final\",\"reason\":\"short_reason\",\"finishReason\":\"stop|continue|compact|error\",\"toolCalls\":[{\"name\":\"tool_name\",\"arguments\":{}}],\"finalResponse\":\"assistant response when mode=final\",\"assistantMessage\":\"optional short user-facing message for this step\"}\n");
            prompt.append("Rules:\n");
            prompt.append("1) Choose mode='tools' only when tool execution is required for accurate answer.\n");
            prompt.append("2) Choose mode='final' when enough context exists to answer user.\n");
            prompt.append("3) If mode='tools', include 1-3 concrete toolCalls with valid arguments.\n");
            prompt.append("4) Never invent unavailable tool names.\n");
            prompt.append("5) If prior tool observations are null/empty/unhelpful, continue with alternative relevant tools instead of mode='final'.\n");
            prompt.append("6) Prefer tools whose name/description semantically matches user's latest request.\n");
            prompt.append("7) If mode='final', finalResponse must be non-empty and directly answer the request.\n");
            prompt.append("8) If context is too large/noisy and needs history compression before next action, set finishReason='compact'.\n");
            prompt.append("9) assistantMessage is optional; when present keep it short and useful for user progress updates.\n");

            String raw = client.prompt()
                    .system("You are a strict agent step planner. Output JSON only.")
                    .user(prompt.toString())
                    .call()
                    .content();
            if (raw == null || raw.isBlank()) {
                return new StepDecision("final", "empty_model_output", List.of(),
                        "I could not produce a response for this step.",
                        "error",
                        "");
            }
            String json = extractJsonObject(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String mode = parsed.get("mode") == null ? "final" : String.valueOf(parsed.get("mode")).toLowerCase(Locale.ROOT);
            if (!"tools".equals(mode)) {
                mode = "final";
            }
            String reason = parsed.get("reason") == null ? "" : String.valueOf(parsed.get("reason"));
            String finishReason = parsed.get("finishReason") == null
                    ? ("tools".equals(mode) ? "tool-calls" : "stop")
                    : String.valueOf(parsed.get("finishReason"));
            String finalResponse = parsed.get("finalResponse") == null ? "" : String.valueOf(parsed.get("finalResponse"));
            String assistantMessage = parsed.get("assistantMessage") == null ? "" : String.valueOf(parsed.get("assistantMessage"));
            List<ToolIntent> intents = new ArrayList<>();
            Object tc = parsed.get("toolCalls");
            if (tc instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) continue;
                    String name = map.get("name") == null ? "" : String.valueOf(map.get("name")).trim();
                    if (name.isBlank()) continue;
                    Map<String, Object> args = new LinkedHashMap<>();
                    Object argsRaw = map.get("arguments");
                    if (argsRaw instanceof Map<?, ?> aMap) {
                        for (Map.Entry<?, ?> entry : aMap.entrySet()) {
                            if (entry.getKey() == null) continue;
                            args.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    intents.add(new ToolIntent(name, args));
                }
            }
            if ("tools".equals(mode) && intents.isEmpty()) {
                return new StepDecision("final",
                        reason.isBlank() ? "no_tool_calls_returned" : reason,
                        List.of(),
                        finalResponse,
                        "stop",
                        assistantMessage);
            }
            if ("final".equals(mode) && (finalResponse == null || finalResponse.isBlank())) {
                finalResponse = "I could not complete the request in this step.";
                finishReason = "error";
            }
            return new StepDecision(mode, reason, intents.stream().limit(3).toList(), finalResponse, finishReason, assistantMessage);
        } catch (Exception e) {
            log.warn("[AgentOrchestrator] Step decision failed: {}", e.getMessage());
            return new StepDecision("final", "step_decision_error", List.of(),
                    "I hit an internal step-planning error.",
                    "error",
                    "");
        }
    }

    private String buildSystemPrompt(ChatState state, AgentSession session) {
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

        // Skill catalog — lightweight listing so LLM always knows what skills exist
        List<SkillRegistryService.SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (!skills.isEmpty()) {
            prompt.append("\n## Available Skills\n");
            prompt.append("The following skills are available. Relevant skill content will be provided in <skill_content> blocks when appropriate.\n\n");
            for (SkillRegistryService.SkillSnapshot s : skills) {
                prompt.append("- **").append(s.skill().getName()).append("**");
                if (s.skill().getDescription() != null && !s.skill().getDescription().isBlank()) {
                    prompt.append(": ").append(s.skill().getDescription());
                }
                prompt.append("\n");
            }
        }

        if (session != null && session.getWorkspacePath() != null) {
            String instructions = instructionLoaderService.load(session.getWorkspacePath());
            if (!instructions.isBlank()) {
                prompt.append("\n## Workspace Instructions\n")
                        .append(instructions)
                        .append("\n");
            }
            prompt.append("\n## Workspace Skill Manifest\n")
                    .append("Skill files are materialized under: ")
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
        return loaded == null || loaded.isBlank() ? DEFAULT_BASE_SYSTEM_PROMPT : loaded;
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
                .append("Prefer concise responses, avoid repeating large prior content, and prioritize latest user intent.\n");
    }

    private void appendProviderCachingHint(StringBuilder prompt, ChatState state) {
        if (!runtimeTuningProperties.isEnableAnthropicPromptCachingHints()) return;
        String provider = state != null && state.getModel() != null ? state.getModel().providerID() : "";
        if (provider == null || !provider.toLowerCase(Locale.ROOT).contains("anthropic")) return;
        prompt.append("\n<anthropic_cache_hint>\n")
                .append("Treat stable system sections and skill blocks as cache-friendly context.\n")
                .append("</anthropic_cache_hint>\n");
    }

    private String buildSkillContentContext(List<SkillRegistryService.SkillSnapshot> skills) {
        int maxChars = Math.max(1000, runtimeTuningProperties.getMaxSkillContentChars());
        int maxFilesPerSkill = Math.max(1, runtimeTuningProperties.getMaxSkillFilesPerSkill());
        StringBuilder content = new StringBuilder("\n## Skill Content\n");
        int budgetUsed = 0;
        List<SkillRegistryService.SkillSnapshot> ordered = skills.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(s -> s.skill().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (SkillRegistryService.SkillSnapshot skill : ordered) {
            if (budgetUsed >= maxChars) break;
            String skillName = skill.skill().getName();
            content.append("<skill name=\"").append(skillName).append("\">\n");
            String skillMd = skill.files().entrySet().stream()
                    .filter(e -> "SKILL.md".equalsIgnoreCase(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
            if (!skillMd.isBlank()) {
                String clipped = clipToBudget(skillMd, maxChars - budgetUsed);
                content.append(clipped).append("\n");
                budgetUsed += clipped.length();
            }
            int fileCount = 0;
            for (Map.Entry<String, String> entry : skill.files().entrySet()) {
                if ("SKILL.md".equalsIgnoreCase(entry.getKey())) continue;
                if (fileCount >= maxFilesPerSkill || budgetUsed >= maxChars) break;
                content.append("<file path=\"").append(entry.getKey()).append("\">\n");
                String clipped = clipToBudget(entry.getValue(), maxChars - budgetUsed);
                content.append(clipped).append("\n</file>\n");
                budgetUsed += clipped.length();
                fileCount++;
            }
            content.append("</skill>\n");
        }
        return content.toString();
    }

    private String clipToBudget(String text, int remainingChars) {
        if (text == null || text.isBlank() || remainingChars <= 0) return "";
        if (text.length() <= remainingChars) return text;
        if (remainingChars <= 20) return "";
        return text.substring(0, remainingChars - 20) + "\n...[truncated]";
    }

    private String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }
}
