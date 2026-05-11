package com.pods.agent.service;

import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.workflow.proposal.WorkflowClassifierService;
import com.pods.agent.workflow.proposal.WorkflowProposalService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase-1 dispatcher: enqueues a classifier run after every chat turn.
 *
 * <p>Cheap by design — no skill_load, no workflow drafting. The classifier
 * is a small read-only LLM that decides whether the turn is worth proposing
 * as a reusable workflow and, if so, emits a name + reason. The full
 * workflow JSON is produced later by the Phase-2 builder, only when a human
 * approves.
 */
@Service
@Slf4j
public class WorkflowProposalAsyncService {
    private final RuntimeEventRepository runtimeEventRepository;
    private final WorkflowProposalService proposalService;
    private final WorkflowClassifierService classifierService;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public WorkflowProposalAsyncService(RuntimeEventRepository runtimeEventRepository,
                                        WorkflowProposalService proposalService,
                                        WorkflowClassifierService classifierService,
                                        SessionWorkspaceService sessionWorkspaceService,
                                        ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.proposalService = proposalService;
        this.classifierService = classifierService;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(2, new ProposalThreadFactory());
    }

    public void enqueue(Job job) {
        if (job == null || job.turnId() == null || job.turnId().isBlank()) return;
        executor.submit(() -> process(job));
    }

    void process(Job job) {
        try {
            if (job.userPrompt() == null || job.userPrompt().isBlank()) {
                log.debug("[WorkflowProposalAsync] skip turn {}: empty prompt", job.turnId());
                return;
            }
            if (proposalService.isDuplicateIntent(job.userId(), job.userPrompt())) {
                log.info("[WorkflowProposalAsync] skip turn {} for user {}: duplicate intent already proposed",
                        job.turnId(), job.userId());
                return;
            }

            Path workspace = sessionWorkspaceService.get(job.sessionId());
            if (workspace == null) {
                log.debug("[WorkflowProposalAsync] skip turn {}: no workspace for session {}",
                        job.turnId(), job.sessionId());
                return;
            }
            Path turnFile = job.turnFilePath();
            if (turnFile == null || !Files.isRegularFile(turnFile)) {
                log.debug("[WorkflowProposalAsync] skip turn {}: execution-log file missing ({})",
                        job.turnId(), turnFile);
                return;
            }

            List<String> toolNames = collectToolNames(job.turnId());
            List<String> skillNames = collectSkillNames(job.turnId());
            log.debug("[WorkflowProposalAsync] classifying turn {}: tools={} skills={}",
                    job.turnId(), toolNames, skillNames);

            Optional<WorkflowClassifierService.ClassifierDecision> decisionOpt =
                    classifierService.classify(new WorkflowClassifierService.ClassificationContext(
                            job.sessionId(),
                            job.turnId(),
                            job.userPrompt(),
                            job.assistantResponse(),
                            toolNames,
                            skillNames,
                            job.modelRef(),
                            workspace,
                            turnFile));

            if (decisionOpt.isEmpty()) {
                log.warn("[WorkflowProposalAsync] classifier returned no decision for turn {} (LLM error or malformed JSON)",
                        job.turnId());
                return;
            }
            WorkflowClassifierService.ClassifierDecision decision = decisionOpt.get();
            if (!decision.needed()) {
                log.info("[WorkflowProposalAsync] classifier verdict 'not needed' for turn {}: {}",
                        job.turnId(), decision.reason());
                return;
            }
            log.info("[WorkflowProposalAsync] classifier verdict 'needed' for turn {}: name='{}' reason='{}'",
                    job.turnId(), decision.suggestedName(), decision.reason());

            String intentSignature = chooseIntentSignature(decision, job.userPrompt());
            String matchedToolNamesJson = writeJson(toolNames);
            String skillNamesJson = writeJson(skillNames);
            String modelProviderId = job.modelRef() == null ? null : job.modelRef().providerID();
            String modelId = job.modelRef() == null ? null : job.modelRef().modelID();

            proposalService.upsertPending(new WorkflowProposalService.PendingProposal(
                    job.sessionId(),
                    job.turnId(),
                    job.userId(),
                    decision.reason(),
                    0.85d,
                    intentSignature,
                    "turn:" + job.turnId(),
                    job.userPrompt(),
                    modelProviderId,
                    modelId,
                    matchedToolNamesJson,
                    decision.suggestedName(),
                    skillNamesJson));
        } catch (Exception e) {
            log.warn("[WorkflowProposalAsync] classifier dispatch failed for turn {}: {}",
                    job.turnId(), e.getMessage(), e);
        }
    }

    private String chooseIntentSignature(WorkflowClassifierService.ClassifierDecision decision, String userPrompt) {
        if (decision.intentHint() != null && !decision.intentHint().isBlank()) {
            return decision.intentHint();
        }
        return proposalService.normalizePrompt(userPrompt);
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            log.debug("[WorkflowProposalAsyncService] json serialization failed: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> collectToolNames(String turnId) {
        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        Set<String> tools = new LinkedHashSet<>();
        for (RuntimeEvent event : events) {
            if (event == null || !"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = toMap(event.getPayload());
            String toolName = payload.get("toolName") == null ? null : String.valueOf(payload.get("toolName"));
            if (toolName == null || toolName.isBlank()) continue;
            // The chat-side `skill` tool is reported as toolName="skill"; we
            // surface those separately via collectSkillNames so the builder
            // knows which skills to load. They do NOT belong in the tool list.
            if ("skill".equalsIgnoreCase(toolName)) continue;
            tools.add(toolName);
        }
        return tools.stream().limit(12).toList();
    }

    /**
     * Pull the names of every skill the chat agent loaded during the turn.
     * Each {@code tool.call} event with {@code toolName == "skill"} carries
     * the loaded-skill name in {@code payload.input.name} (see
     * {@link com.pods.agent.agent.tool.SkillToolCallback}).
     */
    private List<String> collectSkillNames(String turnId) {
        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        Set<String> names = new LinkedHashSet<>();
        for (RuntimeEvent event : events) {
            if (event == null || !"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = toMap(event.getPayload());
            String toolName = payload.get("toolName") == null ? null : String.valueOf(payload.get("toolName"));
            if (toolName == null || !"skill".equalsIgnoreCase(toolName)) continue;
            // input is itself a JSON-string body that the SkillToolCallback
            // emits via json(payload). Re-parse to extract the "name" field.
            Object input = payload.get("input");
            String skillName = extractSkillNameFromInput(input);
            if (skillName != null && !skillName.isBlank()
                    && !skillName.equalsIgnoreCase("workflow-architect")) {
                names.add(skillName.toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(names);
    }

    private String extractSkillNameFromInput(Object input) {
        if (input == null) return null;
        try {
            String text;
            if (input instanceof String s) {
                text = s;
            } else {
                text = objectMapper.writeValueAsString(input);
            }
            // Try parse-as-object first; if input was double-encoded, fall back to a string parse.
            try {
                Map<String, Object> parsed = toMap(text);
                Object value = parsed.get("name");
                return value == null ? null : String.valueOf(value);
            } catch (Exception ignored) {
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(payload, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) return Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * @param turnFilePath absolute path to the canonical per-turn execution
     *     log written by {@link com.pods.agent.service.workspace.ExecutionLogService}.
     *     Required for the classifier to inspect the turn — when missing,
     *     classification is skipped silently.
     */
    public record Job(String sessionId,
                      String turnId,
                      String userPrompt,
                      String assistantResponse,
                      String userId,
                      ModelRef modelRef,
                      java.nio.file.Path turnFilePath) {

        public Job(String sessionId,
                   String turnId,
                   String userPrompt,
                   String assistantResponse,
                   String userId,
                   ModelRef modelRef) {
            this(sessionId, turnId, userPrompt, assistantResponse, userId, modelRef, null);
        }
    }

    private static final class ProposalThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("workflow-proposal-async-" + sequence.getAndIncrement());
            return thread;
        }
    }
}
