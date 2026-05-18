package com.pods.agent.ordervalidation.service;

import com.pods.agent.ordervalidation.model.RunSummary;
import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.runtime.BpmnRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Dispatches an order-validation run. There is no dedicated
 * {@code order_validation_runs} table — the OV-UI derives every run
 * from {@code rule_executions} grouped by {@code (session_id, turn_id)}.
 * This service only needs to (a) pick the right session/turn ids so the
 * analytics layer can group the per-rule rows back together, and (b)
 * drive the fan-out.
 *
 * <p>The "runId" surfaced to the UI is exactly the grouping key
 * ({@code session_id || '__' || turn_id}), so a subsequent
 * {@code GET /runs/{runId}} resolves directly without a parent row.
 */
@Service
@Slf4j
public class OrderValidationRunService {

    private final RuleDomainRepository ruleDomainRepo;
    private final BpmnRuntime bpmnRuntime;
    private final ObjectMapper objectMapper;
    private final Executor fanout = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "ov-fanout");
                t.setDaemon(true);
                return t;
            });

    public OrderValidationRunService(RuleDomainRepository ruleDomainRepo,
                                     BpmnRuntime bpmnRuntime,
                                     ObjectMapper objectMapper) {
        this.ruleDomainRepo = ruleDomainRepo;
        this.bpmnRuntime = bpmnRuntime;
        this.objectMapper = objectMapper;
    }

    /**
     * Kick off a workflow run. When {@code asyncDispatch=true}, the
     * fan-out continues off-thread and we return a summary with
     * {@code state=RUNNING} immediately — the UI polls
     * {@code GET /runs/{id}} (which reads the view) until it sees
     * {@code state=COMPLETED}.
     */
    public RunSummary start(String workflowId,
                            String orderId,
                            String requesterId,
                            boolean asyncDispatch) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }

        List<RuleDomain> rules = ruleDomainRepo.listBySkill(workflowId).stream()
                .filter(d -> RuleDomain.STATUS_ACTIVE.equals(d.getStatus()))
                .toList();
        if (rules.isEmpty()) {
            throw new IllegalStateException("No ACTIVE rule_domains for workflow/skill " + workflowId);
        }

        // Encode the run id so the view's group key (session_id || '__'
        // || turn_id) derives back to exactly this string.
        String runUuid = UUID.randomUUID().toString();
        String sessionId = "ov-run-" + runUuid;
        String turnId = "ov-turn-" + runUuid;
        String runId = sessionId + "__" + turnId;
        long startedAt = System.currentTimeMillis();

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("orderId", orderId);

        if (asyncDispatch) {
            CompletableFuture.runAsync(
                    () -> dispatch(rules, inputs, sessionId, turnId),
                    fanout);
            return new RunSummary(
                    runId, workflowId, "RUNNING",
                    startedAt, null,
                    requesterId, null, null, null);
        }

        List<RuleRun> results = dispatch(rules, inputs, sessionId, turnId);
        return buildSummary(runId, workflowId, requesterId, startedAt, results);
    }

    /** Run every rule of the workflow in parallel; collect outcomes. */
    private List<RuleRun> dispatch(List<RuleDomain> rules,
                                   Map<String, Object> inputs,
                                   String sessionId,
                                   String turnId) {
        List<CompletableFuture<RuleRun>> futures = new ArrayList<>();
        for (RuleDomain d : rules) {
            String ruleName = d.getRuleName() != null ? d.getRuleName() : d.getIntentLabel();
            CompletableFuture<RuleRun> f = bpmnRuntime
                    .executeAsync(d, inputs, sessionId, turnId, false)
                    .handle((outcome, ex) -> new RuleRun(ruleName, outcome, ex));
            futures.add(f);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private record RuleRun(String ruleName, ExecutionOutcome outcome, Throwable failure) {}

    private RunSummary buildSummary(String runId,
                                    String workflowId,
                                    String requesterId,
                                    long startedAt,
                                    List<RuleRun> results) {
        Map<String, Object> assembledOutputs = new LinkedHashMap<>();
        String firstError = null;
        boolean anyFailure = false;
        for (RuleRun rr : results) {
            if (rr.failure() != null) {
                anyFailure = true;
                firstError = firstNonBlank(firstError, rr.failure().getMessage());
                continue;
            }
            ExecutionOutcome outcome = rr.outcome();
            if (outcome != null) {
                if (outcome.outputs() != null) {
                    assembledOutputs.put(rr.ruleName(), outcome.outputs());
                }
                if (outcome.error() != null && !outcome.error().isBlank()) {
                    anyFailure = true;
                    firstError = firstNonBlank(firstError, outcome.error());
                }
            }
        }

        Object resultBody = assembledOutputs.isEmpty() ? null : assembledOutputs;
        String state = anyFailure ? "FAILED" : "COMPLETED";
        long endedAt = System.currentTimeMillis();
        return new RunSummary(
                runId, workflowId, state,
                startedAt, endedAt,
                requesterId, null, firstError,
                resultBody);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}
