package com.pods.agent.workflow.proposal;

import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Top-level orchestration for the two-phase proposal pipeline. Phase-1
 * (classifier) results land here via {@link #upsertPending(PendingProposal)};
 * the {@link #approve(String, String, String)} entry point flips state to
 * {@code approved} and hands off to the async Phase-2 builder.
 *
 * <p>The heavy lifting lives elsewhere now:
 * <ul>
 *   <li>{@link WorkflowJsonValidator} owns parsing + structural validation.</li>
 *   <li>{@link WorkflowClassifierService} owns Phase-1 classification.</li>
 *   <li>{@link WorkflowBuilderService} owns Phase-2 drafting + retry loop.</li>
 *   <li>{@link WorkflowBuilderAsyncService} owns the async dispatch.</li>
 * </ul>
 */
@Service
@Slf4j
public class WorkflowProposalService {

    private static final Pattern UUID_PATTERN = WorkflowJsonValidator.UUID_PATTERN;
    private static final Pattern LONG_NUMBER_PATTERN = WorkflowJsonValidator.LONG_NUMBER_PATTERN;

    private final WorkflowProposalRepository repo;
    private final ProcessDefService processDefService;
    private final WorkflowBuilderAsyncService builderAsyncService;

    public WorkflowProposalService(WorkflowProposalRepository repo,
                                   ProcessDefService processDefService,
                                   WorkflowBuilderAsyncService builderAsyncService) {
        this.repo = repo;
        this.processDefService = processDefService;
        this.builderAsyncService = builderAsyncService;
    }

    /**
     * Phase-1 entrypoint: idempotently insert/update a {@code pending}
     * proposal row carrying only the suggested name + session metadata.
     * The workflow JSON is intentionally absent — it is produced by the
     * Phase-2 builder once a human approves.
     */
    public WorkflowProposal upsertPending(PendingProposal pending) {
        Optional<WorkflowProposal> existing = repo.findBySessionTurn(pending.sessionId(), pending.turnId());
        boolean isNew = existing.isEmpty();
        WorkflowProposal proposal = existing.orElseGet(WorkflowProposal::new);
        proposal.setSessionId(pending.sessionId());
        proposal.setTurnId(pending.turnId());
        proposal.setUserId(pending.userId());
        proposal.setStatus("pending");
        proposal.setReason(pending.reason());
        proposal.setConfidence(pending.confidence());
        proposal.setIntentSignature(pending.intentSignature());
        proposal.setTraceRef(pending.traceRef());
        proposal.setUserPrompt(pending.userPrompt());
        proposal.setModelProviderId(pending.modelProviderId());
        proposal.setModelId(pending.modelId());
        // Phase 1 deliberately leaves the workflow JSON null. The builder
        // populates it on success.
        proposal.setProposedWorkflowJson(null);
        proposal.setMatchedToolNamesJson(pending.matchedToolNamesJson());
        proposal.setSuggestedName(pending.suggestedName());
        proposal.setSkillNamesJson(pending.skillNamesJson());
        proposal.setBuildAttempts(0);
        proposal.setDecisionComment(null);
        proposal.setDecidedBy(null);
        proposal.setDecidedAt(null);
        proposal.setMaterializedDefId(null);
        proposal.setErrorMessage(null);
        WorkflowProposal saved = isNew ? repo.save(proposal) : repo.update(proposal);
        log.info("[WorkflowProposal] phase1 {} pending proposal id={} session={} turn={} name={}",
                isNew ? "created" : "refreshed",
                saved.getId(), saved.getSessionId(), saved.getTurnId(),
                saved.getSuggestedName());
        return saved;
    }

    public List<WorkflowProposal> listPendingByUser(String userId) {
        return repo.findPendingByUser(userId);
    }

    public Optional<WorkflowProposal> getById(String id) {
        return repo.findById(id);
    }

    public List<IntentMatch> findIntentMatches(String userId, String prompt, int limit) {
        if (userId == null || userId.isBlank() || prompt == null || prompt.isBlank()) return List.of();
        List<WorkflowProposal> materialized = repo.findMaterializedByUser(userId);
        if (materialized.isEmpty()) return List.of();
        List<IntentMatch> ranked = new ArrayList<>();
        Set<String> seenDefs = new LinkedHashSet<>();
        String normalizedPrompt = normalizePrompt(prompt);
        Set<String> promptTokens = tokenize(normalizedPrompt);
        for (WorkflowProposal proposal : materialized) {
            if (proposal.getMaterializedDefId() == null || proposal.getMaterializedDefId().isBlank()) continue;
            double score = scoreIntent(promptTokens, normalizedPrompt, proposal.getIntentSignature());
            if (score < 0.34d) continue;
            if (!seenDefs.add(proposal.getMaterializedDefId())) continue;
            String name = processDefService.findById(proposal.getMaterializedDefId())
                    .map(ProcessDefDto::name)
                    .orElse(proposal.getMaterializedDefId());
            ranked.add(new IntentMatch(
                    proposal.getMaterializedDefId(),
                    name,
                    proposal.getId(),
                    score));
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        return ranked.stream().limit(Math.max(1, limit)).toList();
    }

    /**
     * Approve a pending Phase-1 proposal. Flips status to {@code approved}
     * synchronously, then hands the build off to
     * {@link WorkflowBuilderAsyncService}. Caller receives a row with
     * status {@code approved} (the dispatcher will move it to
     * {@code building} → {@code materialized}/{@code failed} asynchronously).
     */
    public Optional<WorkflowProposal> approve(String id, String approver, String comment) {
        Optional<WorkflowProposal> proposalOpt = repo.findById(id);
        if (proposalOpt.isEmpty()) {
            log.warn("[WorkflowProposal] approve failed: proposal {} not found", id);
            return Optional.empty();
        }
        WorkflowProposal proposal = proposalOpt.get();
        if (!"pending".equalsIgnoreCase(proposal.getStatus())) {
            log.info("[WorkflowProposal] approve skipped: proposal {} status is '{}', expected 'pending'",
                    id, proposal.getStatus());
            return Optional.of(proposal);
        }
        proposal.setStatus("approved");
        proposal.setDecisionComment(comment);
        proposal.setDecidedBy(approver);
        proposal.setDecidedAt(System.currentTimeMillis());
        proposal.setErrorMessage(null);
        WorkflowProposal updated = repo.update(proposal);
        log.info("[WorkflowProposal] approved id={} approver={} name={} -> enqueuing builder",
                updated.getId(), approver, updated.getSuggestedName());
        try {
            builderAsyncService.enqueue(updated.getId());
        } catch (Exception e) {
            log.warn("[WorkflowProposal] failed to enqueue builder for proposal {}: {}",
                    updated.getId(), e.getMessage());
        }
        return Optional.of(updated);
    }

    public Optional<WorkflowProposal> reject(String id, String approver, String comment) {
        Optional<WorkflowProposal> proposalOpt = repo.findById(id);
        if (proposalOpt.isEmpty()) {
            log.warn("[WorkflowProposal] reject failed: proposal {} not found", id);
            return Optional.empty();
        }
        WorkflowProposal proposal = proposalOpt.get();
        if (!"pending".equalsIgnoreCase(proposal.getStatus())) {
            log.info("[WorkflowProposal] reject skipped: proposal {} status is '{}', expected 'pending'",
                    id, proposal.getStatus());
            return Optional.of(proposal);
        }
        proposal.setStatus("rejected");
        proposal.setDecisionComment(comment);
        proposal.setDecidedBy(approver);
        proposal.setDecidedAt(System.currentTimeMillis());
        WorkflowProposal updated = repo.update(proposal);
        log.info("[WorkflowProposal] rejected id={} approver={}", updated.getId(), approver);
        return Optional.of(updated);
    }

    public boolean isDuplicateIntent(String userId, String prompt) {
        if (userId == null || userId.isBlank() || prompt == null || prompt.isBlank()) return false;
        String normalized = normalizePrompt(prompt);
        Set<String> tokens = tokenize(normalized);
        for (WorkflowProposal p : repo.findActiveByUser(userId)) {
            double score = scoreIntent(tokens, normalized, p.getIntentSignature());
            if (score >= 0.90d) return true;
        }
        return false;
    }

    public String normalizePrompt(String prompt) {
        if (prompt == null) return "";
        String normalized = UUID_PATTERN.matcher(prompt).replaceAll(" ");
        normalized = LONG_NUMBER_PATTERN.matcher(normalized).replaceAll(" ");
        return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]+", " ").replaceAll("\\s+", " ").trim();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String[] parts = text.split("\\s+");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() > 2) out.add(part);
        }
        return out;
    }

    private double scoreIntent(Set<String> promptTokens, String normalizedPrompt, String signature) {
        if (signature == null || signature.isBlank()) return 0.0d;
        Set<String> sigTokens = tokenize(signature);
        if (promptTokens.isEmpty() || sigTokens.isEmpty()) return 0.0d;
        int overlap = 0;
        for (String token : promptTokens) {
            if (sigTokens.contains(token)) overlap++;
        }
        Set<String> union = new LinkedHashSet<>(promptTokens);
        union.addAll(sigTokens);
        double jaccard = union.isEmpty() ? 0.0d : (double) overlap / (double) union.size();
        boolean contains = signature.contains(normalizedPrompt) || normalizedPrompt.contains(signature);
        return Math.min(1.0d, jaccard + (contains ? 0.2d : 0.0d));
    }

    /**
     * Phase-1 input record. {@link #suggestedName()} is the classifier's
     * proposed workflow name; {@link #skillNamesJson()} is the JSON-array
     * payload of skill names the chat agent loaded during the source turn
     * (used by the Phase-2 builder as its skill_load allowlist).
     */
    public record PendingProposal(String sessionId,
                                  String turnId,
                                  String userId,
                                  String reason,
                                  Double confidence,
                                  String intentSignature,
                                  String traceRef,
                                  String userPrompt,
                                  String modelProviderId,
                                  String modelId,
                                  String matchedToolNamesJson,
                                  String suggestedName,
                                  String skillNamesJson) {}

    public record IntentMatch(String processDefId,
                              String name,
                              String proposalId,
                              double score) {}
}
