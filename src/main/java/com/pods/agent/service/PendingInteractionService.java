package com.pods.agent.service;

import com.pods.agent.domain.HitlInteraction;
import com.pods.agent.repository.HitlInteractionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class PendingInteractionService {
    private final HitlInteractionRepository hitlRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<InteractionReply>> pendingFutures = new ConcurrentHashMap<>();

    public PendingInteractionService(HitlInteractionRepository hitlRepository, ObjectMapper objectMapper) {
        this.hitlRepository = hitlRepository;
        this.objectMapper = objectMapper;
    }

    public String create(String sessionId, String turnId, String type, String prompt) {
        return create(sessionId, turnId, type, prompt, null);
    }

    public String create(String sessionId, String turnId, String type, String prompt, QuestionMetadata metadata) {
        return create(sessionId, turnId, type, prompt, metadata, null);
    }

    /**
     * Register a pending interaction. When {@code externalRequestId} is supplied, the row is
     * inserted with that exact id so downstream callers (HITL replies that reference an
     * LLM-emitted question id) resolve the same row. The LLM commonly emits stable keys like
     * "validate-order-scope" across runs, so a stale row from a prior session can already
     * exist; we delete it (and discard any orphan future) and reinsert with the same id so
     * session.pendingQuestionJson, persisted system messages, and the HITL row stay in sync.
     */
    public String create(String sessionId, String turnId, String type, String prompt,
                         QuestionMetadata metadata, String externalRequestId) {
        String id = (externalRequestId == null || externalRequestId.isBlank())
                ? UUID.randomUUID().toString()
                : externalRequestId;
        if (hitlRepository.findById(id).isPresent()) {
            hitlRepository.deleteById(id);
            pendingFutures.remove(id);
        }
        hitlRepository.save(HitlInteraction.builder()
                .id(id)
                .sessionId(sessionId)
                .turnId(turnId)
                .type(type)
                .prompt(encodePrompt(prompt, metadata))
                .status("pending")
                .createdAt(System.currentTimeMillis())
                .build());
        pendingFutures.put(id, new CompletableFuture<>());
        return id;
    }

    /**
     * Blocks the calling thread until the user replies to the given interaction,
     * or until the timeout elapses.
     *
     * @throws TimeoutException if no reply arrives within {@code timeoutMs} milliseconds
     */
    public InteractionReply awaitReply(String requestId, long timeoutMs) throws TimeoutException {
        CompletableFuture<InteractionReply> future = pendingFutures.get(requestId);
        if (future == null) {
            throw new IllegalArgumentException("No pending future for interaction: " + requestId);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingFutures.remove(requestId);
            throw e;
        } catch (Exception e) {
            pendingFutures.remove(requestId);
            throw new RuntimeException("Interrupted while awaiting reply for: " + requestId, e);
        }
    }

    /**
     * Wake every pending awaitReply future for the given session by completing
     * each with a null reply. Used by the toolchain config "stop stream" path so
     * a worker thread blocked inside awaitReply unwinds and can finalise.
     * Returns the number of futures that were woken.
     */
    public int cancelBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        int woken = 0;
        for (HitlInteraction i : hitlRepository.findBySessionId(sessionId)) {
            if (!"pending".equalsIgnoreCase(i.getStatus())) continue;
            CompletableFuture<InteractionReply> future = pendingFutures.remove(i.getId());
            if (future != null) {
                future.complete(null);
                woken++;
            }
        }
        return woken;
    }

    public Interaction get(String requestId) {
        return hitlRepository.findById(requestId)
                .map(i -> {
                    PromptEnvelope envelope = decodePrompt(i.getPrompt());
                    return new Interaction(i.getId(), i.getSessionId(), i.getType(), envelope.prompt(), i.getCreatedAt(), envelope.metadata());
                })
                .orElse(null);
    }

    public void reply(String requestId, String action, String message, List<String> selectedOptionIds) {
        var current = hitlRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Pending interaction not found: " + requestId));
        if (!"pending".equalsIgnoreCase(current.getStatus())) {
            throw new IllegalStateException("Interaction already resolved: " + requestId);
        }

        PromptEnvelope envelope = decodePrompt(current.getPrompt());
        validateReply(action, message, selectedOptionIds, envelope.metadata());
        String status = switch (action) {
            case "approve" -> "approved";
            case "reject" -> "rejected";
            default -> "reply";
        };
        String responseText = composeResponseText(message, selectedOptionIds);
        hitlRepository.resolve(requestId, status, responseText);

        CompletableFuture<InteractionReply> future = pendingFutures.remove(requestId);
        if (future != null) {
            future.complete(new InteractionReply(requestId, status, responseText, System.currentTimeMillis()));
        }
    }

    public InteractionReply getReply(String requestId) {
        var interaction = hitlRepository.findById(requestId).orElse(null);
        if (interaction == null || interaction.getResolvedAt() == null) return null;
        return new InteractionReply(
                requestId,
                interaction.getStatus(),
                interaction.getResponseText(),
                interaction.getResolvedAt()
        );
    }

    public List<Interaction> listPendingBySession(String sessionId) {
        return hitlRepository.findPendingBySession(sessionId).stream()
                .map(i -> {
                    PromptEnvelope envelope = decodePrompt(i.getPrompt());
                    return new Interaction(i.getId(), i.getSessionId(), i.getType(), envelope.prompt(), i.getCreatedAt(), envelope.metadata());
                })
                .toList();
    }

    private void validateReply(String action, String message, List<String> selectedOptionIds, QuestionMetadata metadata) {
        if (!"reply".equalsIgnoreCase(action) || metadata == null) return;
        List<String> selected = selectedOptionIds == null ? List.of() : selectedOptionIds.stream().filter(s -> s != null && !s.isBlank()).toList();
        String responseMode = metadata.responseMode() == null ? "text" : metadata.responseMode();
        boolean hasText = message != null && !message.isBlank();
        List<String> allowedIds = metadata.options() == null ? List.of() : metadata.options().stream().map(QuestionOption::id).toList();

        for (String optionId : selected) {
            if (!allowedIds.contains(optionId)) {
                throw new IllegalArgumentException("Unknown option selected: " + optionId);
            }
        }

        if ("single_select".equalsIgnoreCase(responseMode)) {
            if (selected.size() > 1) throw new IllegalArgumentException("Only one option is allowed");
            if (selected.isEmpty() && !(metadata.allowCustomText() && hasText)) {
                throw new IllegalArgumentException("Please select one option or provide text");
            }
            return;
        }

        if ("multi_select".equalsIgnoreCase(responseMode)) {
            int min = metadata.minSelections() == null ? 1 : Math.max(0, metadata.minSelections());
            int max = metadata.maxSelections() == null ? Integer.MAX_VALUE : Math.max(min, metadata.maxSelections());
            if (selected.size() < min && !(metadata.allowCustomText() && hasText)) {
                throw new IllegalArgumentException("Please select at least " + min + " option(s) or provide text");
            }
            if (selected.size() > max) {
                throw new IllegalArgumentException("Please select at most " + max + " option(s)");
            }
        }
    }

    private String composeResponseText(String message, List<String> selectedOptionIds) {
        List<String> selected = selectedOptionIds == null ? List.of() : selectedOptionIds.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (!selected.isEmpty()) {
            String joined = String.join(",", selected);
            if (message != null && !message.isBlank()) {
                return "options=" + joined + "; message=" + message;
            }
            return "options=" + joined;
        }
        return message;
    }

    private String encodePrompt(String prompt, QuestionMetadata metadata) {
        if (metadata == null) return prompt;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("prompt", prompt);
        envelope.put("metadata", metadata);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return prompt;
        }
    }

    private PromptEnvelope decodePrompt(String rawPrompt) {
        if (rawPrompt == null || rawPrompt.isBlank() || !rawPrompt.trim().startsWith("{")) {
            return new PromptEnvelope(rawPrompt, null);
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(rawPrompt, Map.class);
            Object promptObj = parsed.get("prompt");
            String prompt = promptObj == null ? rawPrompt : String.valueOf(promptObj);
            Object metaObj = parsed.get("metadata");
            if (!(metaObj instanceof Map<?, ?> metaMap)) return new PromptEnvelope(prompt, null);
            String responseMode = String.valueOf(valueOrDefault(metaMap, "responseMode", "text"));
            boolean allowCustomText = Boolean.parseBoolean(String.valueOf(valueOrDefault(metaMap, "allowCustomText", "true")));
            Integer minSelections = asInteger(metaMap.get("minSelections"));
            Integer maxSelections = asInteger(metaMap.get("maxSelections"));
            List<QuestionOption> options = new ArrayList<>();
            Object optionsObj = metaMap.get("options");
            if (optionsObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> item) {
                        String id = String.valueOf(valueOrDefault(item, "id", ""));
                        String label = String.valueOf(valueOrDefault(item, "label", id));
                        if (!id.isBlank()) options.add(new QuestionOption(id, label));
                    }
                }
            }
            return new PromptEnvelope(prompt, new QuestionMetadata(responseMode, options, allowCustomText, minSelections, maxSelections));
        } catch (Exception e) {
            return new PromptEnvelope(rawPrompt, null);
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Object valueOrDefault(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private record PromptEnvelope(String prompt, QuestionMetadata metadata) {}

    public record Interaction(String requestId, String sessionId, String type, String prompt, long createdAt, QuestionMetadata metadata) {}
    public record InteractionReply(String requestId, String action, String message, long createdAt) {}
    public record QuestionOption(String id, String label) {}
    public record QuestionMetadata(String responseMode,
                                   List<QuestionOption> options,
                                   boolean allowCustomText,
                                   Integer minSelections,
                                   Integer maxSelections) {
        public QuestionMetadata {
            options = options == null ? Collections.emptyList() : options;
        }
    }
}
