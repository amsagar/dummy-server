package com.pods.agent.ruledomain;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.matcher.IntentLabeller;
import com.pods.agent.ruledomain.matcher.IntentMatcher;
import com.pods.agent.ruledomain.matcher.IntentMatcher.RuleMatch;
import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import com.pods.agent.ruledomain.runtime.BpmnRuntime;
import com.pods.agent.ruledomain.runtime.CoverageEvaluator;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level entry point for the compiled rule-domain path. Called from
 * {@code AgentOrchestrator.streamTurn()} before the LLM loop.
 *
 * <p><b>Composer model</b> (Phase 1.6): the orchestrator no longer runs one
 * rule at most. It calls {@link IntentMatcher#findMatches} which returns one
 * or more rules — a narrow rule-level hit, or a fan-out from an umbrella
 * domain match — and runs them in parallel. Each rule's outcome is merged
 * into a composite outcome keyed by {@link RuleDomain#getResultKey()}.
 *
 * <p>Per-rule lifecycle events ({@code rule_domain.rule.start},
 * {@code rule_domain.rule.done}) are emitted so the UI can show parallel
 * tracks. This is the "load-bearing observability contract" — debugging a
 * fanout where 3 rules ran but only 2 produced output is intractable
 * without these.
 *
 * <p><b>Soft circuit breaker</b>: per-rule in-memory counter. After N
 * consecutive failures we skip that rule temporarily; siblings keep running.
 * No DB writes — the rule stays {@code ACTIVE} so it gets retried after the
 * cool-off window.
 *
 * <p><b>Coverage check</b> (Phase 3 hook): before executing a rule we ask
 * {@link CoverageEvaluator} whether the current input falls within the
 * rule's coverage manifest. If not, we skip the rule (orchestrator falls
 * back to the LLM loop for that branch only). Other rules in the fan-out
 * continue with their BPMNs.
 *
 * <p>Any failure in the compiled path returns
 * {@link ExecutionOutcome#notHandled()} so the caller falls back to the
 * existing LLM loop without an HTTP 500 escaping to the user.
 */
@Component
@Slf4j
public class RuleDomainOrchestrator {

    private final SkillRouter skillRouter;
    private final IntentMatcher intentMatcher;
    private final IntentLabeller intentLabeller;
    private final BpmnCompiler compiler;
    private final BpmnRuntime runtime;
    private final RuleDomainRepository repo;
    private final RuleExecutionRepository executionRepo;
    private final ToolRegistryService toolRegistry;
    private final RuleDomainProperties props;
    private final RuleDomainEventBus bus;
    private final ObjectProvider<CoverageEvaluator> coverageEvaluator;

    private final ConcurrentHashMap<String, FailureState> circuitByDomainId = new ConcurrentHashMap<>();

    public RuleDomainOrchestrator(SkillRouter skillRouter,
                                  IntentMatcher intentMatcher,
                                  IntentLabeller intentLabeller,
                                  BpmnCompiler compiler,
                                  BpmnRuntime runtime,
                                  RuleDomainRepository repo,
                                  RuleExecutionRepository executionRepo,
                                  ToolRegistryService toolRegistry,
                                  RuleDomainProperties props,
                                  RuleDomainEventBus bus,
                                  ObjectProvider<CoverageEvaluator> coverageEvaluator) {
        this.skillRouter = skillRouter;
        this.intentMatcher = intentMatcher;
        this.intentLabeller = intentLabeller;
        this.compiler = compiler;
        this.runtime = runtime;
        this.repo = repo;
        this.executionRepo = executionRepo;
        this.toolRegistry = toolRegistry;
        this.props = props;
        this.bus = bus;
        this.coverageEvaluator = coverageEvaluator;
    }

    public ExecutionOutcome handleIfApplicable(String userMessage,
                                               Map<String, Object> userInputs,
                                               String sessionId,
                                               String turnId) {
        var routed = skillRouter.route(userMessage);
        if (routed.isEmpty()) return ExecutionOutcome.notHandled();
        var skill = routed.get().skill();

        bus.emit("rule_domain.routed", Map.of(
                "turnId", safe(turnId),
                "skillId", safe(skill.getId()),
                "skillName", safe(skill.getName())));

        try {
            boolean hasManifest = routed.get().manifest() != null
                    && !routed.get().manifest().isEmpty();

            bus.emit("rule_domain.cache_lookup", Map.of("turnId", safe(turnId)));
            // Skill that opts into the rule manifest: skip the legacy
            // single-row fallback so a stale prose-compiled monolithic row
            // doesn't keep running and prevent the trace-based compile
            // from producing the new per-rule BPMNs.
            List<RuleMatch> matches = intentMatcher.findMatches(
                    skill.getId(), userMessage, /*skipLegacyFallback=*/ hasManifest);

            if (matches.isEmpty()) {
                bus.emit("rule_domain.cache_miss", Map.of(
                        "turnId", safe(turnId), "skillName", skill.getName()));
                // First request for this skill: fall through to the LLM loop.
                // AgentOrchestrator.scheduleTraceCompile will run after the
                // turn ends; AsyncTraceCompiler derives a manifest from prose
                // + trace (Option B) and compiles per-rule BPMNs. The next
                // request hits those compiled rules.
                //
                // We no longer call the legacy prose-based BpmnCompiler here.
                // That path produced field-path-hallucinated BPMNs because
                // the compiler couldn't see real tool response shapes; the
                // trace-grounded path doesn't have that problem.
                return ExecutionOutcome.notHandled();
            }

            // Filter out circuit-open + coverage-miss rules. Rules removed
            // here cause a partial fan-out — the orchestrator surfaces a
            // coverage_miss event so the caller can decide to fall back to
            // the LLM loop for the skipped branch.
            Map<String, Object> inputs = effectiveInputs(userMessage, userInputs);
            List<RuleMatch> runnable = new ArrayList<>(matches.size());
            for (RuleMatch m : matches) {
                if (isCircuitOpen(m.rule().getId())) {
                    log.info("[RuleDomain] Circuit open for rule={} ({}) — skipping",
                            m.rule().getRuleName(), m.rule().getId());
                    bus.emit("rule_domain.circuit_open", Map.of(
                            "turnId", safe(turnId),
                            "ruleId", safe(m.rule().getId()),
                            "ruleName", safe(m.rule().getRuleName())));
                    continue;
                }
                if (!coversInput(m.rule(), inputs, turnId)) continue;
                runnable.add(m);
            }

            if (runnable.isEmpty()) {
                // Every matched rule was either circuit-open or coverage-miss.
                // Caller falls back to LLM loop.
                return ExecutionOutcome.notHandled();
            }

            // Cache hit signal — one event per matched rule. Useful for the UI
            // to render parallel tracks; redundant for single-rule narrow hits
            // but cheap.
            for (RuleMatch m : runnable) {
                bus.emit("rule_domain.cache_hit", Map.of(
                        "turnId", safe(turnId),
                        "ruleId", safe(m.rule().getId()),
                        "ruleName", safe(m.rule().getRuleName()),
                        "fromFanout", m.fromFanout(),
                        "similarity", m.similarity()));
            }

            return executeFanout(runnable, inputs, sessionId, turnId);

        } catch (Exception ex) {
            log.warn("[RuleDomain] Path failed for skill={}: {}", skill.getName(), ex.getMessage(), ex);
            return ExecutionOutcome.notHandled();
        }
    }

    /** Run one or more rules in parallel and merge their outcomes. */
    private ExecutionOutcome executeFanout(List<RuleMatch> runnable,
                                           Map<String, Object> inputs,
                                           String sessionId,
                                           String turnId) {
        long start = System.currentTimeMillis();
        List<CompletableFuture<RuleResult>> futures = new ArrayList<>(runnable.size());
        for (RuleMatch m : runnable) {
            RuleDomain rule = m.rule();
            bus.emit("rule_domain.rule.start", Map.of(
                    "turnId", safe(turnId),
                    "ruleId", safe(rule.getId()),
                    "ruleName", safe(rule.getRuleName()),
                    "domainGroupId", safe(rule.getDomainGroupId()),
                    "fromFanout", m.fromFanout()));
            CompletableFuture<ExecutionOutcome> future = runtime.executeAsync(
                    rule, inputs, sessionId, turnId, /*fromCacheHit*/ true);
            futures.add(future.handle((outcome, ex) -> {
                if (ex != null) {
                    log.warn("[RuleDomain] Rule {} threw async: {}", rule.getRuleName(), ex.getMessage());
                    return new RuleResult(rule, ExecutionOutcome.failed(
                            rule.getId(), null, ex.getMessage(),
                            System.currentTimeMillis() - start));
                }
                return new RuleResult(rule, outcome);
            }));
        }

        List<RuleResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<RuleResult> f : futures) {
            try { results.add(f.join()); }
            catch (Exception ex) {
                log.warn("[RuleDomain] join failed: {}", ex.getMessage());
            }
        }

        // Emit per-rule done + record circuit breaker outcomes.
        for (RuleResult r : results) {
            boolean ok = r.outcome().error() == null;
            recordCircuitOutcome(r.rule().getId(), ok);
            bus.emit("rule_domain.rule.done", Map.of(
                    "turnId", safe(turnId),
                    "ruleId", safe(r.rule().getId()),
                    "ruleName", safe(r.rule().getRuleName()),
                    "success", ok,
                    "latencyMs", r.outcome().latencyMs(),
                    "error", r.outcome().error() == null ? "" : r.outcome().error()));
            // Promote DRAFT → ACTIVE after enough successful runs.
            if (ok) maybePromote(r.rule());
        }

        return mergeOutcomes(results, System.currentTimeMillis() - start);
    }

    /**
     * Combine per-rule outcomes into one composite outcome. Each rule's
     * output lands under its {@code result_key} so the summarizer can render
     * a structured answer. If <em>any</em> rule failed and no rule succeeded,
     * surface as failed so the caller falls back to LLM loop. If <em>some</em>
     * rules succeeded, return handled with the partial-result map (caller
     * sees a mixed outcome and can describe both successes and failures).
     */
    private ExecutionOutcome mergeOutcomes(List<RuleResult> results, long totalLatencyMs) {
        if (results.isEmpty()) return ExecutionOutcome.notHandled();

        Map<String, Object> mergedOutputs = new LinkedHashMap<>();
        Map<String, String> mergedErrors = new LinkedHashMap<>();
        boolean anySuccess = false;
        boolean anyFailure = false;
        String firstFailedTool = null;
        String firstProcId = null;
        boolean anyFromCacheHit = false;

        for (RuleResult r : results) {
            String key = r.rule().getResultKey() != null && !r.rule().getResultKey().isBlank()
                    ? r.rule().getResultKey()
                    : (r.rule().getRuleName() != null ? r.rule().getRuleName() : r.rule().getId());
            if (r.outcome().error() == null) {
                anySuccess = true;
                Object out = r.outcome().outputs();
                mergedOutputs.put(key, out == null ? Map.of() : out);
                if (firstProcId == null) firstProcId = r.outcome().flowableProcId();
                if (r.outcome().fromCacheHit()) anyFromCacheHit = true;
            } else {
                anyFailure = true;
                mergedErrors.put(key, r.outcome().error());
                if (firstFailedTool == null && r.outcome().errorMeta() != null) {
                    firstFailedTool = r.outcome().errorMeta().get("failedTool");
                }
            }
        }

        // First rule's domainId is a reasonable parent identifier for the
        // composite outcome; the per-rule events carry the precise breakdown.
        String parentDomainId = results.get(0).rule().getId();

        if (anySuccess && !anyFailure) {
            return ExecutionOutcome.handled(parentDomainId, firstProcId, mergedOutputs,
                    anyFromCacheHit, totalLatencyMs);
        }
        if (anySuccess) {
            // Partial success — include both successful outputs AND errors map
            // under the special "_errors" key so the summarizer can mention
            // which rules failed without losing the data from the rest.
            mergedOutputs.put("_errors", mergedErrors);
            return ExecutionOutcome.handled(parentDomainId, firstProcId, mergedOutputs,
                    anyFromCacheHit, totalLatencyMs);
        }
        // All rules failed.
        Map<String, String> errorMeta = firstFailedTool != null
                ? Map.of("failedTool", firstFailedTool) : null;
        String combinedError = String.join("; ", mergedErrors.values());
        return ExecutionOutcome.failed(parentDomainId, firstProcId,
                combinedError, totalLatencyMs, errorMeta);
    }

    /** Cold path: no compiled rule matched. Compile a new monolithic rule
     *  using the existing prose-based BpmnCompiler. Phase 2 will replace this
     *  with the trace-based compiler once {@code AsyncTraceCompiler} lands. */
    private ExecutionOutcome compileAndExecute(SkillRouter.RoutedSkill routed,
                                               String userMessage,
                                               Map<String, Object> userInputs,
                                               String sessionId,
                                               String turnId) {
        var skill = routed.skill();
        log.info("[RuleDomain] MISS compiling new domain for skill={}", skill.getName());
        bus.emit("rule_domain.compile.start", Map.of(
                "turnId", safe(turnId), "skillName", skill.getName()));
        List<AgentTool> tools = toolRegistry.getEnabledTools();
        String intentLabel = intentLabeller.labelFor(skill.getName(), userMessage);

        RuleDomain compiled = compiler.compile(
                skill.getId(), skill.getName(), routed.markdown(),
                userMessage, intentLabel, tools);

        if (RuleDomain.STATUS_FAILED.equals(compiled.getStatus())) {
            log.warn("[RuleDomain] Compile failed: {}", compiled.getLastError());
            bus.emit("rule_domain.compile.failed", Map.of(
                    "turnId", safe(turnId),
                    "error", safe(compiled.getLastError())));
            return ExecutionOutcome.notHandled();
        }

        bus.emit("rule_domain.compile.done", Map.of(
                "turnId", safe(turnId),
                "domainId", compiled.getId(),
                "procKey", compiled.getFlowableProcKey()));

        Map<String, Object> inputs = effectiveInputs(userMessage, userInputs);
        bus.emit("rule_domain.execute.start", Map.of(
                "turnId", safe(turnId),
                "domainId", compiled.getId(),
                "fromCacheHit", false));
        ExecutionOutcome outcome = runtime.execute(compiled, inputs, sessionId, turnId, false);
        recordCircuitOutcome(compiled.getId(), outcome.error() == null);
        bus.emit("rule_domain.execute.done", Map.of(
                "turnId", safe(turnId),
                "domainId", compiled.getId(),
                "success", outcome.error() == null,
                "latencyMs", outcome.latencyMs(),
                "error", safe(outcome.error())));

        maybePromote(compiled);
        return outcome;
    }

    /** Phase 3 hook. Without a coverage evaluator wired (or with the rule's
     *  manifest empty), assume the rule covers the input. Once
     *  {@link CoverageEvaluator} ships, the check becomes meaningful. */
    private boolean coversInput(RuleDomain rule, Map<String, Object> inputs, String turnId) {
        if (coverageEvaluator == null) return true;
        CoverageEvaluator evaluator = coverageEvaluator.getIfAvailable();
        if (evaluator == null) return true;
        CoverageEvaluator.CoverageResult check = evaluator.check(rule, inputs);
        if (check.covered()) return true;
        bus.emit("rule_domain.coverage_miss", Map.of(
                "turnId", safe(turnId),
                "ruleId", safe(rule.getId()),
                "ruleName", safe(rule.getRuleName()),
                "missingForCondition", safe(check.firstMissingCondition())));
        return false;
    }

    private boolean isCircuitOpen(String domainId) {
        if (domainId == null) return false;
        FailureState st = circuitByDomainId.get(domainId);
        return st != null && st.openUntilMs > System.currentTimeMillis();
    }

    private void recordCircuitOutcome(String domainId, boolean success) {
        if (domainId == null) return;
        if (success) {
            circuitByDomainId.remove(domainId);
            return;
        }
        FailureState st = circuitByDomainId.computeIfAbsent(domainId, k -> new FailureState());
        synchronized (st) {
            st.consecutiveFailures++;
            int threshold = Math.max(1, props.getCircuitBreakerConsecutiveFailures());
            if (st.consecutiveFailures >= threshold) {
                long openSeconds = Math.max(30, props.getCircuitBreakerOpenSeconds());
                st.openUntilMs = System.currentTimeMillis() + (openSeconds * 1000L);
                log.info("[RuleDomain] Soft circuit-breaker opened for {} after {} consecutive failures (open {}s)",
                        domainId, st.consecutiveFailures, openSeconds);
            }
        }
    }

    private void maybePromote(RuleDomain domain) {
        if (!RuleDomain.STATUS_DRAFT.equals(domain.getStatus())) return;
        int needed = Math.max(1, props.getPromoteAfterSuccessfulRuns());
        int successes = executionRepo.countRecentSuccesses(domain.getId());
        if (successes >= needed) {
            int deprecated = repo.deactivateSiblings(domain.getId());
            repo.updateStatus(domain.getId(), RuleDomain.STATUS_ACTIVE, null);
            log.info("[RuleDomain] Promoted {} from DRAFT to ACTIVE ({} successful runs, {} sibling(s) deprecated)",
                    domain.getId(), successes, deprecated);
        }
    }

    private Map<String, Object> effectiveInputs(String userMessage,
                                                Map<String, Object> userInputs) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("userMessage", userMessage);
        String firstNumeric = userMessage == null ? null : firstLongRun(userMessage);
        if (firstNumeric != null) inputs.put("orderId", firstNumeric);
        if (userInputs != null) inputs.putAll(userInputs);
        return inputs;
    }

    private static String firstLongRun(String s) {
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) cur.append(c);
            else if (cur.length() >= 4) return cur.toString();
            else cur.setLength(0);
        }
        return cur.length() >= 4 ? cur.toString() : null;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private record RuleResult(RuleDomain rule, ExecutionOutcome outcome) {}

    private static final class FailureState {
        int consecutiveFailures;
        long openUntilMs;
    }
}
