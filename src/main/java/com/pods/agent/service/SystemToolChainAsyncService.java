package com.pods.agent.service;

import com.pods.agent.agent.AgentSession;
import com.pods.agent.agent.AgentSessionManager;
import com.pods.agent.agent.SseEventSender;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.SystemToolChainProposal;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.SystemToolChainProposalRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
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

@Service
@Slf4j
public class SystemToolChainAsyncService {
    private final RuntimeEventRepository runtimeEventRepository;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final ToolChainTraceFileService traceFileService;
    private final ToolChainArchitectAgentService architectAgentService;
    private final ToolChainSuggestionService toolChainSuggestionService;
    private final ToolChainService toolChainService;
    private final SystemToolChainDurableTraceService durableTraceService;
    private final AgentSessionManager sessionManager;
    private final SystemToolChainProposalRepository proposalRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public SystemToolChainAsyncService(RuntimeEventRepository runtimeEventRepository,
                                       SessionWorkspaceService sessionWorkspaceService,
                                       ToolChainTraceFileService traceFileService,
                                       ToolChainArchitectAgentService architectAgentService,
                                       ToolChainSuggestionService toolChainSuggestionService,
                                       ToolChainService toolChainService,
                                       SystemToolChainDurableTraceService durableTraceService,
                                       AgentSessionManager sessionManager,
                                       SystemToolChainProposalRepository proposalRepository,
                                       ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.traceFileService = traceFileService;
        this.architectAgentService = architectAgentService;
        this.toolChainSuggestionService = toolChainSuggestionService;
        this.toolChainService = toolChainService;
        this.durableTraceService = durableTraceService;
        this.sessionManager = sessionManager;
        this.proposalRepository = proposalRepository;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(2, new SystemToolChainThreadFactory());
    }

    public void enqueue(Job job) {
        if (job == null || job.turnId() == null || job.turnId().isBlank()) return;
        saveJobEvent(job, "system_toolchain_job.queued", Map.of(
                "sessionId", safe(job.sessionId()),
                "turnId", safe(job.turnId())
        ));
        executor.submit(() -> process(job));
    }

    @PostConstruct
    void recoverApprovedProposalsOnStartup() {
        try {
            var approved = proposalRepository.findApprovedForRecovery(200);
            for (SystemToolChainProposal proposal : approved) {
                if (proposal == null || proposal.getId() == null) continue;
                executor.submit(() -> materializeApprovedProposal(proposal.getId()));
            }
        } catch (Exception e) {
            log.warn("[SystemToolChainAsyncService] Failed startup recovery for approved proposals: {}", e.getMessage());
        }
    }

    void process(Job job) {
        saveJobEvent(job, "system_toolchain_job.started", Map.of(
                "sessionId", safe(job.sessionId()),
                "turnId", safe(job.turnId())
        ));
        try {
            if (job.modelRef() == null) {
                saveJobEvent(job, "system_toolchain_job.failed", Map.of("reason", "missing_model_ref"));
                return;
            }
            Optional<DedupMatch> dedup = findDedupMatch(job);
            if (dedup.isPresent()) {
                DedupMatch match = dedup.get();
                saveJobEvent(job, "system_toolchain_job.skipped_duplicate", Map.of(
                        "duplicateType", match.type(),
                        "duplicateId", match.id(),
                        "duplicateLabel", match.label(),
                        "score", match.score()
                ));
                return;
            }

            Path workspace = job.workspacePath() != null
                    ? job.workspacePath()
                    : sessionWorkspaceService.getOrCreate(job.sessionId());

            String tracePath = traceFileService.writeTurnTrace(
                    workspace,
                    job.sessionId(),
                    job.turnId(),
                    job.userPrompt(),
                    job.assistantResponse()
            );
            String durableTraceJson = durableTraceService
                    .persistAsJson(workspace, tracePath)
                    .orElse("");

            String eligibilityTurnId = job.turnId() + ":toolchain-eligibility";
            Optional<SystemToolChainEligibility> eligibilityOpt = architectAgentService.evaluateEligibilityFromTrace(
                    workspace,
                    tracePath,
                    job.sessionId(),
                    job.turnId(),
                    job.userId(),
                    job.userPrompt(),
                    job.assistantResponse(),
                    job.modelRef(),
                    eligibilityTurnId
            );
            if (eligibilityOpt.isEmpty()) {
                String retryTurnId = eligibilityTurnId + ":retry-1";
                saveJobEvent(job, "system_toolchain_job.eligibility_retry", Map.of(
                        "attempt", 2,
                        "turnId", retryTurnId
                ));
                eligibilityOpt = architectAgentService.evaluateEligibilityFromTrace(
                        workspace,
                        tracePath,
                        job.sessionId(),
                        job.turnId(),
                        job.userId(),
                        job.userPrompt(),
                        job.assistantResponse(),
                        job.modelRef(),
                        retryTurnId
                );
                if (eligibilityOpt.isPresent()) {
                    eligibilityTurnId = retryTurnId;
                }
            }
            if (eligibilityOpt.isEmpty()) {
                String failureReason = classifyEligibilityFailure(eligibilityTurnId, tracePath);
                saveJobEvent(job, "system_toolchain_job.failed", Map.of(
                        "reason", "eligibility_invalid",
                        "failureDetail", failureReason,
                        "tracePath", tracePath
                ));
                return;
            }
            SystemToolChainEligibility eligibility = eligibilityOpt.get();
            saveJobEvent(job, "system_toolchain_job.eligibility_evaluated", Map.of(
                    "isToolChainNeeded", eligibility.toolChainNeeded(),
                    "isSimpleTurn", eligibility.simpleTurn(),
                    "confidence", eligibility.normalizedConfidence(),
                    "reason", safe(eligibility.reason())
            ));
            if (eligibility.simpleTurn()) {
                saveJobEvent(job, "system_toolchain_job.eligibility_skipped_simple", Map.of(
                        "reason", safe(eligibility.reason()),
                        "tracePath", tracePath
                ));
                return;
            }
            if (!eligibility.toolChainNeeded()) {
                saveJobEvent(job, "system_toolchain_job.eligibility_not_needed", Map.of(
                        "confidence", eligibility.normalizedConfidence(),
                        "reason", safe(eligibility.reason()),
                        "tracePath", tracePath
                ));
                return;
            }
            if (!eligibility.isHighConfidence()) {
                saveJobEvent(job, "system_toolchain_job.eligibility_confidence_too_low", Map.of(
                        "confidence", eligibility.normalizedConfidence(),
                        "reason", safe(eligibility.reason()),
                        "tracePath", tracePath
                ));
                return;
            }

            if (!createApprovalProposal(job, eligibility, tracePath, durableTraceJson)) {
                return;
            }
            saveJobEvent(job, "system_toolchain_job.awaiting_approval", Map.of(
                    "tracePath", tracePath,
                    "durableTraceStored", !durableTraceJson.isBlank()
            ));
        } catch (Exception e) {
            log.warn("[SystemToolChainAsyncService] async generation failed for turn {}: {}", job.turnId(), e.getMessage());
            saveJobEvent(job, "system_toolchain_job.failed", Map.of(
                    "reason", "exception",
                    "message", e.getMessage() == null ? "unknown" : e.getMessage()
            ));
        }
    }

    private boolean createApprovalProposal(Job job,
                                           SystemToolChainEligibility eligibility,
                                           String tracePath,
                                           String durableTraceJson) {
        String reason = buildApprovalReason(eligibility);
        SystemToolChainProposal proposal = upsertPendingProposal(job, eligibility, tracePath, durableTraceJson);
        if (proposal == null) return false;
        saveJobEvent(job, "system_toolchain_job.proposal_created", Map.of(
                "proposalId", proposal.getId(),
                "reason", reason,
                "durableTraceStored", proposal.getDurableTraceJson() != null && !proposal.getDurableTraceJson().isBlank()
        ));
        emitApprovalRequired(job.sessionId(), proposal.getId(), reason);
        return true;
    }

    private void emitApprovalRequired(String sessionId, String requestId, String reason) {
        try {
            AgentSession session = sessionManager.get(sessionId);
            if (session == null || session.getActiveEmitter() == null) return;
            new SseEventSender(session.getActiveEmitter(), objectMapper)
                    .sendApprovalRequired(sessionId, requestId, reason);
        } catch (Exception e) {
            log.debug("[SystemToolChainAsyncService] Failed to emit approval event: {}", e.getMessage());
        }
    }

    private String buildApprovalReason(SystemToolChainEligibility eligibility) {
        String reason = safe(eligibility.reason());
        if (reason.isBlank()) {
            reason = "This turn appears reusable as a toolchain.";
        }
        return "Create a reusable toolchain from this turn? " + reason;
    }

    private SystemToolChainProposal upsertPendingProposal(Job job,
                                                          SystemToolChainEligibility eligibility,
                                                          String tracePath,
                                                          String durableTraceJson) {
        try {
            Optional<SystemToolChainProposal> existing = proposalRepository.findBySessionTurn(job.sessionId(), job.turnId());
            SystemToolChainProposal proposal = existing.orElseGet(SystemToolChainProposal::new);
            proposal.setSessionId(job.sessionId());
            proposal.setTurnId(job.turnId());
            proposal.setUserId(job.userId());
            proposal.setStatus("pending");
            proposal.setReason(safe(eligibility.reason()));
            proposal.setConfidence(eligibility.normalizedConfidence());
            proposal.setTracePath(tracePath);
            proposal.setDurableTraceJson(durableTraceJson == null || durableTraceJson.isBlank() ? null : durableTraceJson);
            proposal.setUserPrompt(job.userPrompt());
            proposal.setAssistantResponse(job.assistantResponse());
            proposal.setModelProviderId(job.modelRef() == null ? null : job.modelRef().providerID());
            proposal.setModelId(job.modelRef() == null ? null : job.modelRef().modelID());
            proposal.setDecisionComment(null);
            proposal.setDecidedBy(null);
            proposal.setDecidedAt(null);
            proposal.setToolChainId(null);
            proposal.setErrorMessage(null);
            if (existing.isPresent()) return proposalRepository.update(proposal);
            return proposalRepository.save(proposal);
        } catch (Exception e) {
            log.warn("[SystemToolChainAsyncService] Failed to upsert system proposal: {}", e.getMessage());
            return null;
        }
    }

    public Optional<SystemToolChainProposal> approveProposal(String proposalId, String approver, String comment) {
        Optional<SystemToolChainProposal> proposalOpt = proposalRepository.findById(proposalId);
        if (proposalOpt.isEmpty()) return Optional.empty();
        SystemToolChainProposal proposal = proposalOpt.get();
        if (!"pending".equalsIgnoreCase(proposal.getStatus())) return Optional.of(proposal);
        proposal.setStatus("approved");
        proposal.setDecisionComment(comment);
        proposal.setDecidedBy(approver);
        proposal.setDecidedAt(System.currentTimeMillis());
        proposalRepository.update(proposal);
        saveProposalEvent(proposal, "system_toolchain_job.proposal_approved", Map.of(
                "proposalId", proposal.getId()
        ));
        executor.submit(() -> materializeApprovedProposal(proposal.getId()));
        return Optional.of(proposal);
    }

    public Optional<SystemToolChainProposal> rejectProposal(String proposalId, String approver, String comment) {
        Optional<SystemToolChainProposal> proposalOpt = proposalRepository.findById(proposalId);
        if (proposalOpt.isEmpty()) return Optional.empty();
        SystemToolChainProposal proposal = proposalOpt.get();
        if (!"pending".equalsIgnoreCase(proposal.getStatus())) return Optional.of(proposal);
        proposal.setStatus("rejected");
        proposal.setDecisionComment(comment);
        proposal.setDecidedBy(approver);
        proposal.setDecidedAt(System.currentTimeMillis());
        proposalRepository.update(proposal);
        saveProposalEvent(proposal, "system_toolchain_job.proposal_rejected", Map.of(
                "proposalId", proposal.getId()
        ));
        return Optional.of(proposal);
    }

    public void materializeApprovedProposal(String proposalId) {
        Optional<SystemToolChainProposal> proposalOpt = proposalRepository.findById(proposalId);
        if (proposalOpt.isEmpty()) return;
        SystemToolChainProposal proposal = proposalOpt.get();
        if (!"approved".equalsIgnoreCase(proposal.getStatus())) return;
        try {
            ModelRef modelRef = null;
            if (proposal.getModelProviderId() != null && !proposal.getModelProviderId().isBlank()
                    && proposal.getModelId() != null && !proposal.getModelId().isBlank()) {
                modelRef = new ModelRef(proposal.getModelProviderId(), proposal.getModelId());
            }
            if (modelRef == null) {
                proposal.setStatus("failed");
                proposal.setErrorMessage("missing_model_ref");
                proposalRepository.update(proposal);
                return;
            }
            Path workspace = sessionWorkspaceService.getOrCreate(proposal.getSessionId());
            String tracePathToUse = ensureTracePathForMaterialization(workspace, proposal)
                    .orElse(null);
            if (tracePathToUse == null || tracePathToUse.isBlank()) {
                proposal.setStatus("failed");
                proposal.setErrorMessage("missing_trace_file");
                proposalRepository.update(proposal);
                saveProposalEvent(proposal, "system_toolchain_job.proposal_failed", Map.of(
                        "proposalId", proposal.getId(),
                        "reason", "missing_trace_file"
                ));
                return;
            }
            String architectTurnId = proposal.getTurnId() + ":toolchain-architect";
            Optional<SystemToolChainArtifact> artifact = architectAgentService.generateFromTrace(
                    workspace,
                    tracePathToUse,
                    proposal.getSessionId(),
                    proposal.getTurnId(),
                    proposal.getUserId(),
                    proposal.getUserPrompt(),
                    proposal.getAssistantResponse(),
                    modelRef,
                    architectTurnId
            );
            if (artifact.isEmpty()) {
                proposal.setStatus("failed");
                proposal.setErrorMessage("architect_output_invalid");
                proposalRepository.update(proposal);
                saveProposalEvent(proposal, "system_toolchain_job.proposal_failed", Map.of(
                        "proposalId", proposal.getId(),
                        "reason", "architect_output_invalid"
                ));
                return;
            }
            Optional<ToolChain> persisted = toolChainSuggestionService.createSuggestionFromArchitectArtifact(
                    artifact.get(),
                    proposal.getSessionId(),
                    proposal.getTurnId(),
                    tracePathToUse,
                    proposal.getUserId(),
                    modelRef
            );
            if (persisted.isEmpty()) {
                proposal.setStatus("failed");
                proposal.setErrorMessage("persist_rejected");
                proposalRepository.update(proposal);
                saveProposalEvent(proposal, "system_toolchain_job.proposal_failed", Map.of(
                        "proposalId", proposal.getId(),
                        "reason", "persist_rejected"
                ));
                return;
            }
            proposal.setStatus("materialized");
            proposal.setToolChainId(persisted.get().getId());
            proposal.setErrorMessage(null);
            proposalRepository.update(proposal);
            saveProposalEvent(proposal, "system_toolchain_job.proposal_materialized", Map.of(
                    "proposalId", proposal.getId(),
                    "toolChainId", persisted.get().getId()
            ));
        } catch (Exception e) {
            proposal.setStatus("failed");
            proposal.setErrorMessage(safe(e.getMessage()));
            proposalRepository.update(proposal);
            saveProposalEvent(proposal, "system_toolchain_job.proposal_failed", Map.of(
                    "proposalId", proposal.getId(),
                    "reason", "exception",
                    "message", safe(e.getMessage())
            ));
        }
    }

    private void saveProposalEvent(SystemToolChainProposal proposal, String eventType, Map<String, Object> payload) {
        try {
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(proposal.getSessionId())
                    .turnId(proposal.getTurnId())
                    .eventType(eventType)
                    .payload(toJson(payload))
                    .build());
        } catch (Exception ignored) {
        }
    }

    private Optional<String> ensureTracePathForMaterialization(Path workspace, SystemToolChainProposal proposal) {
        String tracePath = safe(proposal.getTracePath());
        if (!tracePath.isBlank()) {
            Path candidate = workspace.resolve(tracePath).normalize();
            if (candidate.startsWith(workspace) && Files.exists(candidate)) {
                return Optional.of(tracePath);
            }
        }
        return durableTraceService.restoreToWorkspace(
                workspace,
                proposal.getDurableTraceJson(),
                proposal.getTurnId()
        );
    }

    private String classifyEligibilityFailure(String eligibilityTurnId, String tracePath) {
        try {
            var events = runtimeEventRepository.findByTurnId(eligibilityTurnId);
            boolean sawSkillLoad = false;
            boolean sawLocalTraceRead = false;
            String normalizedTracePath = safe(tracePath).replace("\\", "/").toLowerCase(Locale.ROOT);
            for (RuntimeEvent event : events) {
                if (event == null || event.getEventType() == null) continue;
                if (!"tool.call".equalsIgnoreCase(event.getEventType())) continue;
                Map<String, Object> payload = readPayload(event.getPayload());
                String toolName = safe(payload.get("toolName") == null ? null : String.valueOf(payload.get("toolName")))
                        .toLowerCase(Locale.ROOT);
                Map<String, Object> input = toMap(parseFlexible(payload.get("input")));
                if ("skill".equals(toolName)) {
                    String skillName = safe(input.get("name") == null ? null : String.valueOf(input.get("name")))
                            .toLowerCase(Locale.ROOT);
                    if ("toolchain-architect".equals(skillName)) sawSkillLoad = true;
                }
                if ("read".equals(toolName) || "grep".equals(toolName)) {
                    String path = safe(input.get("path") == null ? null : String.valueOf(input.get("path")))
                            .replace("\\", "/")
                            .toLowerCase(Locale.ROOT);
                    if (!path.isBlank() && (path.endsWith(normalizedTracePath) || path.contains(normalizedTracePath))) {
                        sawLocalTraceRead = true;
                    }
                }
            }
            if (!sawSkillLoad) return "evidence_missing_skill_load";
            if (!sawLocalTraceRead) return "evidence_missing_trace_read";
            return "eligibility_response_invalid";
        } catch (Exception ignored) {
            return "eligibility_response_invalid";
        }
    }

    private Optional<DedupMatch> findDedupMatch(Job job) {
        String prompt = safe(job.userPrompt()).trim();
        if (prompt.isBlank()) return Optional.empty();

        Optional<ToolChainService.IntentMatch> chainMatch = toolChainService.findBestIntentMatchForDedup(prompt, 0.82);
        if (chainMatch.isPresent()) {
            ToolChainService.IntentMatch match = chainMatch.get();
            return Optional.of(new DedupMatch(
                    "toolchain",
                    safe(match.toolChainId()),
                    safe(match.name()),
                    match.score()
            ));
        }

        String userId = safe(job.userId()).trim();
        if (userId.isBlank()) return Optional.empty();
        List<SystemToolChainProposal> active = proposalRepository.findActiveByUser(userId);
        DedupMatch best = null;
        for (SystemToolChainProposal proposal : active) {
            if (proposal == null || proposal.getId() == null) continue;
            String other = safe(proposal.getUserPrompt()).trim();
            if (other.isBlank()) continue;
            double score = scorePromptSimilarity(prompt, other);
            if (score < 0.88d) continue;
            DedupMatch candidate = new DedupMatch(
                    "proposal",
                    proposal.getId(),
                    "system_toolchain_proposal",
                    score
            );
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private double scorePromptSimilarity(String left, String right) {
        Set<String> a = tokenize(left);
        Set<String> b = tokenize(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0d;
        int overlap = 0;
        for (String token : a) {
            if (b.contains(token)) overlap++;
        }
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        double jaccard = union.isEmpty() ? 0.0d : ((double) overlap / (double) union.size());
        String leftLower = left.toLowerCase(Locale.ROOT);
        String rightLower = right.toLowerCase(Locale.ROOT);
        boolean directContains = leftLower.contains(rightLower) || rightLower.contains(leftLower);
        return Math.min(1.0d, jaccard + (directContains ? 0.2d : 0.0d));
    }

    private Set<String> tokenize(String input) {
        if (input == null || input.isBlank()) return Set.of();
        String[] raw = input.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw) {
            if (token != null && token.length() > 1) out.add(token);
        }
        return out;
    }

    private Map<String, Object> readPayload(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(raw, Object.class);
            return toMap(parsed);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Object parseFlexible(Object raw) {
        if (!(raw instanceof String s)) return raw;
        if (s.isBlank()) return "";
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception ignored) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private void saveJobEvent(Job job, String eventType, Map<String, Object> payload) {
        try {
            runtimeEventRepository.save(RuntimeEvent.builder()
                    .sessionId(job.sessionId())
                    .turnId(job.turnId())
                    .eventType(eventType)
                    .payload(toJson(payload))
                    .build());
        } catch (Exception e) {
            log.debug("[SystemToolChainAsyncService] Failed to save {}: {}", eventType, e.getMessage());
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public record Job(String sessionId,
                      String turnId,
                      String userPrompt,
                      String assistantResponse,
                      String userId,
                      ModelRef modelRef,
                      Path workspacePath) {}

    private record DedupMatch(String type, String id, String label, double score) {}

    private static final class SystemToolChainThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("system-toolchain-async-" + sequence.getAndIncrement());
            return thread;
        }
    }
}
