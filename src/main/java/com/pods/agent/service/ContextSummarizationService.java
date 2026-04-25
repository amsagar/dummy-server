package com.pods.agent.service;

import com.pods.agent.agent.AgentSession;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class ContextSummarizationService {
    private static final int DEFAULT_SUMMARY_TOKEN_THRESHOLD = 2000;
    private static final int DEFAULT_RETAIN_RECENT_MESSAGES = 6;

    public String maybeSummarize(AgentSession session, String currentSummary, long tokenThreshold) {
        return maybeSummarize(session, currentSummary, tokenThreshold, null, DEFAULT_RETAIN_RECENT_MESSAGES).summary();
    }

    public CompactionResult maybeSummarize(AgentSession session,
                                           String currentSummary,
                                           long tokenThreshold,
                                           String providerHint,
                                           int retainRecentMessages) {
        long threshold = tokenThreshold > 0 ? tokenThreshold : DEFAULT_SUMMARY_TOKEN_THRESHOLD;
        long estimatedTokens = estimateTokens(session, providerHint);
        if (estimatedTokens < threshold) {
            return new CompactionResult(currentSummary, false, 0, session.getMessages().size(), estimatedTokens);
        }

        int keepRecent = Math.max(2, retainRecentMessages <= 0 ? DEFAULT_RETAIN_RECENT_MESSAGES : retainRecentMessages);
        List<String> snippets = session.getMessages().stream()
                .limit(Math.max(0, session.getMessages().size() - keepRecent))
                .map(m -> {
                    if (m instanceof UserMessage um) return "User: " + safe(um.getText());
                    if (m instanceof AssistantMessage am) return "Assistant: " + safe(am.getText());
                    return "";
                })
                .filter(s -> !s.isBlank())
                .toList();

        if (snippets.isEmpty()) {
            return new CompactionResult(currentSummary, false, 0, session.getMessages().size(), estimatedTokens);
        }
        String joined = String.join("\n", snippets);
        List<String> recentUserIntents = session.getMessages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .filter(t -> t != null && !t.isBlank())
                .map(ContextSummarizationService::safe)
                .skip(Math.max(0, session.getMessages().stream().filter(UserMessage.class::isInstance).count() - 3))
                .collect(Collectors.toList());
        String summary = "Rolling summary:\n"
                + "Context window: " + threshold + " tokens\n"
                + "Recent intents:\n- " + String.join("\n- ", recentUserIntents)
                + "\nConversation digest:\n" + truncate(joined, 1200);
        if (currentSummary != null && !currentSummary.isBlank()) {
            summary = truncate(currentSummary + "\n\n" + summary, 1800);
        }
        int removedMessages = Math.max(0, session.getMessages().size() - keepRecent);
        int retainedMessages = Math.min(session.getMessages().size(), keepRecent);
        return new CompactionResult(summary, removedMessages > 0, removedMessages, retainedMessages, estimatedTokens);
    }

    public long estimateTokens(AgentSession session) {
        return estimateTokens(session, null);
    }

    public long estimateTokens(AgentSession session, String providerHint) {
        double charsPerToken = charsPerToken(providerHint);
        long chars = session.getMessages().stream().mapToLong(this::messageLength).sum();
        return Math.max(1, (long) Math.ceil(chars / charsPerToken));
    }

    public long estimateTokens(List<Message> messages, String providerHint) {
        if (messages == null || messages.isEmpty()) return 0;
        double charsPerToken = charsPerToken(providerHint);
        long chars = messages.stream().mapToLong(this::messageLength).sum();
        return Math.max(1, (long) Math.ceil(chars / charsPerToken));
    }

    public long estimateTokens(String text, String providerHint) {
        if (text == null || text.isBlank()) return 0;
        double charsPerToken = charsPerToken(providerHint);
        return Math.max(1, (long) Math.ceil(text.length() / charsPerToken));
    }

    private long messageLength(Message message) {
        if (message instanceof UserMessage um) return um.getText() == null ? 0 : um.getText().length();
        if (message instanceof AssistantMessage am) return am.getText() == null ? 0 : am.getText().length();
        return 0;
    }

    private double charsPerToken(String providerHint) {
        if (providerHint == null || providerHint.isBlank()) return 4.0;
        String low = providerHint.toLowerCase(Locale.ROOT);
        if (low.contains("anthropic")) return 3.5;
        if (low.contains("openai") || low.contains("azure")) return 4.0;
        if (low.contains("ollama")) return 4.2;
        return 4.0;
    }

    private static String safe(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    public List<Message> retainRecentMessages(AgentSession session, int keepRecent) {
        if (session == null || session.getMessages().isEmpty()) return List.of();
        int keep = Math.max(2, keepRecent <= 0 ? DEFAULT_RETAIN_RECENT_MESSAGES : keepRecent);
        int removeCount = Math.max(0, session.getMessages().size() - keep);
        if (removeCount <= 0) return List.of();
        List<Message> removed = new ArrayList<>(session.getMessages().subList(0, removeCount));
        session.getMessages().subList(0, removeCount).clear();
        return removed;
    }

    public record CompactionResult(String summary,
                                   boolean compacted,
                                   int removedMessages,
                                   int retainedMessages,
                                   long estimatedTokens) {
    }
}
