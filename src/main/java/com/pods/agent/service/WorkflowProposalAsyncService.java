package com.pods.agent.service;

import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.workflow.proposal.WorkflowArchitectService;
import com.pods.agent.workflow.proposal.WorkflowProposalService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

@Service
@Slf4j
public class WorkflowProposalAsyncService {
    private final RuntimeEventRepository runtimeEventRepository;
    private final WorkflowProposalService proposalService;
    private final WorkflowArchitectService workflowArchitectService;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public WorkflowProposalAsyncService(RuntimeEventRepository runtimeEventRepository,
                                        WorkflowProposalService proposalService,
                                        WorkflowArchitectService workflowArchitectService,
                                        SessionWorkspaceService sessionWorkspaceService,
                                        ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.proposalService = proposalService;
        this.workflowArchitectService = workflowArchitectService;
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
            if (job.userPrompt() == null || job.userPrompt().isBlank()) return;
            if (proposalService.isDuplicateIntent(job.userId(), job.userPrompt())) return;
            List<String> tools = collectToolNames(job.turnId());
            if (tools.isEmpty()) return;

            Optional<WorkflowProposalService.GeneratedProposal> generated = tryAgentDraft(job, tools);
            if (generated.isEmpty()) {
                generated = proposalService.generateByLlm(
                        job.sessionId(),
                        job.turnId(),
                        job.userId(),
                        job.userPrompt(),
                        job.assistantResponse(),
                        tools,
                        job.modelRef());
            }
            generated.ifPresent(proposalService::upsertGenerated);
        } catch (Exception e) {
            log.warn("[WorkflowProposalAsyncService] proposal generation failed for turn {}: {}", job.turnId(), e.getMessage());
        }
    }

    /**
     * Run the read-only subagent (filesystem + skill_load tools) when the
     * session has a usable workspace and a turn-trace file. Falls back to the
     * legacy single-shot prompt when either is missing or the subagent draft
     * is rejected by validation.
     */
    private Optional<WorkflowProposalService.GeneratedProposal> tryAgentDraft(Job job, List<String> tools) {
        if (job.sessionId() == null || job.sessionId().isBlank()) return Optional.empty();
        Path workspace = sessionWorkspaceService.get(job.sessionId());
        if (workspace == null) return Optional.empty();
        Path turnFile = job.turnFilePath();
        if (turnFile == null || !Files.isRegularFile(turnFile)) {
            log.debug("[WorkflowProposalAsyncService] no turn trace file for turn {}; falling back to single-shot prompt",
                    job.turnId());
            return Optional.empty();
        }
        try {
            return workflowArchitectService.draftWorkflowJson(new WorkflowArchitectService.GenerationContext(
                            job.sessionId(),
                            job.turnId(),
                            job.userPrompt(),
                            job.assistantResponse(),
                            tools,
                            job.modelRef(),
                            workspace,
                            turnFile))
                    .flatMap(rawJson -> proposalService.generateFromAgentDraft(
                            job.sessionId(),
                            job.turnId(),
                            job.userId(),
                            job.userPrompt(),
                            tools,
                            job.modelRef(),
                            rawJson));
        } catch (Exception e) {
            log.warn("[WorkflowProposalAsyncService] architect draft failed for turn {}: {} (falling back)",
                    job.turnId(), e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> collectToolNames(String turnId) {
        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        Set<String> tools = new LinkedHashSet<>();
        for (RuntimeEvent event : events) {
            if (event == null || !"tool.call".equalsIgnoreCase(event.getEventType())) continue;
            Map<String, Object> payload = toMap(event.getPayload());
            String toolName = payload.get("toolName") == null ? null : String.valueOf(payload.get("toolName"));
            if (toolName != null && !toolName.isBlank()) tools.add(toolName);
        }
        return tools.stream().limit(6).toList();
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
     * @param turnFilePath optional absolute path to the canonical per-turn JSON
     *     document written by {@link com.pods.agent.service.workspace.WorkflowTurnTraceService}.
     *     Phase 1 carries this through unused so Phase 2 (subagent generation)
     *     can consume it without another schema change.
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
