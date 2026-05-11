package com.pods.agent.workflow.proposal;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Daemon dispatcher for the Phase-2 builder loop. Approval flips a proposal
 * to {@code approved} synchronously inside the HTTP request; this service
 * picks the row up off a single-thread executor and runs
 * {@link WorkflowBuilderService#build(WorkflowProposal)} until the proposal
 * reaches a terminal state ({@code materialized} or {@code failed}).
 *
 * <p>On startup we sweep the table for any rows still in {@code approved}
 * (e.g. left behind by a previous JVM that crashed mid-build) and re-enqueue
 * them so no approval ever gets stranded.
 */
@Service
@Slf4j
public class WorkflowBuilderAsyncService {

    private final WorkflowProposalRepository repo;
    private final WorkflowBuilderService builderService;
    private final ExecutorService executor;

    public WorkflowBuilderAsyncService(WorkflowProposalRepository repo,
                                       WorkflowBuilderService builderService) {
        this.repo = repo;
        this.builderService = builderService;
        this.executor = Executors.newFixedThreadPool(1, new BuilderThreadFactory());
    }

    /**
     * Pick up any leftover {@code approved} rows from a previous run and
     * resume them. Builds happen serially (single-thread pool) so this is
     * safe even when many rows queue at once.
     */
    @PostConstruct
    void resumePendingBuilds() {
        try {
            for (WorkflowProposal proposal : repo.findApprovedReadyToBuild()) {
                log.info("[WorkflowBuilderAsync] resuming build for proposal {} (status was 'approved')",
                        proposal.getId());
                executor.submit(() -> safeBuild(proposal.getId()));
            }
        } catch (Exception e) {
            log.warn("[WorkflowBuilderAsync] failed to enumerate approved proposals on startup: {}",
                    e.getMessage());
        }
    }

    public void enqueue(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return;
        log.info("[WorkflowBuilderAsync] enqueued proposal {} for build", proposalId);
        executor.submit(() -> safeBuild(proposalId));
    }

    private void safeBuild(String proposalId) {
        try {
            Optional<WorkflowProposal> proposalOpt = repo.findById(proposalId);
            if (proposalOpt.isEmpty()) {
                log.warn("[WorkflowBuilderAsync] proposal {} not found; skipping build", proposalId);
                return;
            }
            WorkflowProposal proposal = proposalOpt.get();
            String status = proposal.getStatus() == null ? "" : proposal.getStatus().toLowerCase();
            if (!"approved".equals(status) && !"building".equals(status)) {
                // Anything else (pending/rejected/materialized/failed) is
                // not a valid starting state — refuse silently rather than
                // overwrite a terminal verdict.
                log.info("[WorkflowBuilderAsync] proposal {} is in status '{}'; skipping build",
                        proposalId, proposal.getStatus());
                return;
            }
            builderService.build(proposal);
        } catch (Exception e) {
            log.warn("[WorkflowBuilderAsync] build for proposal {} threw: {}", proposalId, e.getMessage(), e);
            try {
                repo.findById(proposalId).ifPresent(p -> {
                    p.setStatus("failed");
                    p.setErrorMessage("builder_threw:" + e.getMessage());
                    repo.update(p);
                });
            } catch (Exception ignored) {
            }
        }
    }

    private static final class BuilderThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("workflow-builder-async-" + sequence.getAndIncrement());
            return thread;
        }
    }
}
