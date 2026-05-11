package com.pods.agent.workflow.api;

import com.pods.agent.workflow.engine.AuditTrailManager;
import com.pods.agent.workflow.engine.WorkflowManager;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.persistence.PendingApprovalRepository;
import com.pods.agent.workflow.persistence.PendingApprovalRow;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.WorkflowVariableRepository;
import com.pods.agent.workflow.persistence.WorkflowVariableRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the human-approval gate. The dispatcher sets a run to suspended
 * when a manual activity has {@code requireApproval=true}; the approve and
 * reject methods here write the decision back into the run's variable scope
 * (as {@code approvalDecision} and {@code approvalComment}) and call
 * {@link WorkflowManager#resumeProcess} so the executor re-dispatches the
 * suspended activity with the decision in scope.
 */
@Service
@Slf4j
public class WorkflowApprovalService {

    private final PendingApprovalRepository repo;
    private final WorkflowVariableRepository variableRepo;
    private final ProcessInstRepository processInstRepo;
    private final ProcessDefService defService;
    private final WorkflowManager workflowManager;
    private final AuditTrailManager audit;
    private final ObjectMapper objectMapper;

    public WorkflowApprovalService(PendingApprovalRepository repo,
                                   WorkflowVariableRepository variableRepo,
                                   ProcessInstRepository processInstRepo,
                                   ProcessDefService defService,
                                   WorkflowManager workflowManager,
                                   AuditTrailManager audit,
                                   ObjectMapper objectMapper) {
        this.repo = repo;
        this.variableRepo = variableRepo;
        this.processInstRepo = processInstRepo;
        this.defService = defService;
        this.workflowManager = workflowManager;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<PendingApprovalRow> listPending(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return repo.findPending(safe);
    }

    public long countPending() {
        return repo.countPending();
    }

    public Optional<PendingApprovalRow> get(String id) {
        return repo.findById(id);
    }

    /** Approve and resume; returns the new run state on success. */
    public Optional<WorkflowManager.StartResult> approve(String id, String decidedBy, String comment) {
        return decide(id, decidedBy, "approve", comment);
    }

    /** Reject and resume; the activity will fail and the workflow can route via an error edge. */
    public Optional<WorkflowManager.StartResult> reject(String id, String decidedBy, String comment) {
        return decide(id, decidedBy, "reject", comment);
    }

    private Optional<WorkflowManager.StartResult> decide(String id,
                                                         String decidedBy,
                                                         String decision,
                                                         String comment) {
        PendingApprovalRow row = repo.findById(id).orElse(null);
        if (row == null || row.decidedAt() != null) {
            return Optional.empty();
        }
        long now = Instant.now().toEpochMilli();
        if (!repo.decide(id, decidedBy, now, decision, comment)) {
            return Optional.empty();
        }

        // Write the decision into the run's variable scope so the dispatcher
        // sees it on resume. Variables are scoped by inst_id (parent scope).
        persistVariable(row.instId(), "approvalDecision", decision);
        if (comment != null && !comment.isBlank()) {
            persistVariable(row.instId(), "approvalComment", comment);
        }

        audit.record(row.instId(), row.activityInstId(),
                AuditTrailManager.Action.APPROVAL_DECIDED, decidedBy,
                Map.of("approvalId", id,
                        "decision", decision,
                        "comment", comment == null ? "" : comment));

        String defId = processInstRepo.findById(row.instId()).map(r -> r.defId()).orElse(null);
        ProcessDefinition def = defId == null ? null : defService.loadDomainById(defId).orElse(null);
        if (def == null) {
            log.warn("[WorkflowApprovalService] cannot resolve definition for inst {}", row.instId());
            return Optional.empty();
        }
        WorkflowManager.StartResult result = workflowManager.resumeProcess(row.instId(), def, decidedBy);
        return Optional.ofNullable(result);
    }

    private void persistVariable(String instId, String name, String value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            variableRepo.upsert(new WorkflowVariableRow(
                    UUID.randomUUID().toString(),
                    instId,
                    instId, // scope = inst_id (parent)
                    name,
                    "java.lang.String",
                    json,
                    Instant.now().toEpochMilli()));
        } catch (RuntimeException e) {
            log.warn("[WorkflowApprovalService] failed to persist variable {}: {}", name, e.getMessage());
        }
    }
}
