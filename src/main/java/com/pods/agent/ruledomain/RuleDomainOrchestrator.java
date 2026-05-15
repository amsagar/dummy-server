package com.pods.agent.ruledomain;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.matcher.IntentLabeller;
import com.pods.agent.ruledomain.matcher.IntentMatcher;
import com.pods.agent.ruledomain.model.ExecutionOutcome;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.ruledomain.repository.RuleExecutionRepository;
import com.pods.agent.ruledomain.runtime.BpmnRuntime;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level entry point for the compiled-domain path. Called from
 * {@code AgentOrchestrator.streamTurn()} before the LLM loop.
 *
 * Flow:
 *   1. Skill router selects an allowlisted skill (or returns empty → caller falls back).
 *   2. Intent matcher checks pgvector for an existing domain.
 *   3. HIT  → execute the cached BPMN, unless the soft circuit-breaker has tripped.
 *   4. MISS (or circuit open) → compile (one LLM call), then execute.
 *   5. Promote DRAFT to ACTIVE after the configured number of successful runs.
 *   6. Return the {@link ExecutionOutcome} to the caller; the
 *      {@code ResponseSummarizer} turns it into prose.
 *
 * Any exception in the compiled path is caught and surfaced as
 * {@code ExecutionOutcome.notHandled()} so the caller falls back to the
 * existing LLM loop without an HTTP 500 escaping to the user.
 *
 * <p><b>Soft circuit breaker</b>: a permanently-broken domain stays {@code ACTIVE}
 * (we no longer auto-deprecate on error rate), so we maintain an in-memory
 * per-domain counter. After N consecutive failures we skip the cached domain
 * for {@code circuitBreakerOpenSeconds} and fall through as if it were a cache
 * miss. The next successful execution resets the counter. No DB writes.
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
                                  RuleDomainEventBus bus) {
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
    }

    public ExecutionOutcome handleIfApplicable(String userMessage,
                                               Map<String, Object> userInputs,
                                               String sessionId,
                                               String turnId) {
        Optional<SkillRouter.RoutedSkill> routed = skillRouter.route(userMessage);
        if (routed.isEmpty()) return ExecutionOutcome.notHandled();
        var skill = routed.get().skill();

        bus.emit("rule_domain.routed", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "skillId", skill.getId() == null ? "" : skill.getId(),
                "skillName", skill.getName() == null ? "" : skill.getName()));

        try {
            bus.emit("rule_domain.cache_lookup", Map.of(
                    "turnId", turnId == null ? "" : turnId));
            Optional<RuleDomainRepository.Match> match =
                    intentMatcher.findMatch(skill.getId(), userMessage);

            if (match.isPresent() && RuleDomain.STATUS_ACTIVE.equals(match.get().domain().getStatus())) {
                RuleDomain hitDomain = match.get().domain();
                if (isCircuitOpen(hitDomain.getId())) {
                    log.info("[RuleDomain] HIT but circuit open — recompiling skill={} domain={}",
                            skill.getName(), hitDomain.getId());
                    bus.emit("rule_domain.circuit_open", Map.of(
                            "turnId", turnId == null ? "" : turnId,
                            "domainId", hitDomain.getId(),
                            "skillName", skill.getName()));
                    // Fall through to the compile path below.
                } else {
                    log.info("[RuleDomain] HIT skill={} domain={} similarity={}",
                            skill.getName(), hitDomain.getId(), match.get().similarity());
                    bus.emit("rule_domain.cache_hit", Map.of(
                            "turnId", turnId == null ? "" : turnId,
                            "domainId", hitDomain.getId(),
                            "similarity", match.get().similarity()));
                    Map<String, Object> inputs = effectiveInputs(userMessage, userInputs);
                    bus.emit("rule_domain.execute.start", Map.of(
                            "turnId", turnId == null ? "" : turnId,
                            "domainId", hitDomain.getId(),
                            "fromCacheHit", true));
                    ExecutionOutcome outcome = runtime.execute(hitDomain, inputs, sessionId, turnId, true);
                    recordCircuitOutcome(hitDomain.getId(), outcome.error() == null);
                    bus.emit("rule_domain.execute.done", Map.of(
                            "turnId", turnId == null ? "" : turnId,
                            "domainId", hitDomain.getId(),
                            "success", outcome.error() == null,
                            "latencyMs", outcome.latencyMs(),
                            "error", outcome.error() == null ? "" : outcome.error()));
                    return outcome;
                }
            } else {
                bus.emit("rule_domain.cache_miss", Map.of(
                        "turnId", turnId == null ? "" : turnId,
                        "skillName", skill.getName()));
            }

            log.info("[RuleDomain] MISS compiling new domain for skill={}", skill.getName());
            bus.emit("rule_domain.compile.start", Map.of(
                    "turnId", turnId == null ? "" : turnId,
                    "skillName", skill.getName()));
            List<AgentTool> tools = toolRegistry.getEnabledTools();
            String intentLabel = intentLabeller.labelFor(skill.getName(), userMessage);

            RuleDomain compiled = compiler.compile(
                    skill.getId(), skill.getName(), routed.get().markdown(),
                    userMessage, intentLabel, tools);

            if (RuleDomain.STATUS_FAILED.equals(compiled.getStatus())) {
                log.warn("[RuleDomain] Compile failed: {}", compiled.getLastError());
                bus.emit("rule_domain.compile.failed", Map.of(
                        "turnId", turnId == null ? "" : turnId,
                        "error", compiled.getLastError() == null ? "" : compiled.getLastError()));
                return ExecutionOutcome.notHandled();
            }

            bus.emit("rule_domain.compile.done", Map.of(
                    "turnId", turnId == null ? "" : turnId,
                    "domainId", compiled.getId(),
                    "procKey", compiled.getFlowableProcKey()));

            Map<String, Object> inputs = effectiveInputs(userMessage, userInputs);
            bus.emit("rule_domain.execute.start", Map.of(
                    "turnId", turnId == null ? "" : turnId,
                    "domainId", compiled.getId(),
                    "fromCacheHit", false));
            ExecutionOutcome outcome = runtime.execute(compiled, inputs, sessionId, turnId, false);
            recordCircuitOutcome(compiled.getId(), outcome.error() == null);
            bus.emit("rule_domain.execute.done", Map.of(
                    "turnId", turnId == null ? "" : turnId,
                    "domainId", compiled.getId(),
                    "success", outcome.error() == null,
                    "latencyMs", outcome.latencyMs(),
                    "error", outcome.error() == null ? "" : outcome.error()));

            maybePromote(compiled);

            return outcome;
        } catch (Exception ex) {
            log.warn("[RuleDomain] Path failed for skill={}: {}", skill.getName(), ex.getMessage(), ex);
            return ExecutionOutcome.notHandled();
        }
    }

    /** True when the in-memory circuit-breaker for this domain is currently open. */
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
                log.info("[RuleDomain] Soft circuit-breaker opened for domain {} after {} consecutive failures (open {}s)",
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
            log.info("[RuleDomain] Promoted domain {} from DRAFT to ACTIVE ({} successful runs, {} sibling(s) deprecated)",
                    domain.getId(), successes, deprecated);
        }
    }

    /**
     * Merge any caller-provided inputs (e.g. extracted entities from the chat
     * controller) with a default that always exposes the raw user message and
     * a best-effort numeric "orderId" parsed out of the message.
     */
    private Map<String, Object> effectiveInputs(String userMessage,
                                                Map<String, Object> userInputs) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("userMessage", userMessage);
        String firstNumeric = userMessage == null ? null : firstLongRun(userMessage);
        if (firstNumeric != null) {
            inputs.put("orderId", firstNumeric);
        }
        if (userInputs != null) inputs.putAll(userInputs);
        return inputs;
    }

    private static String firstLongRun(String s) {
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                cur.append(c);
            } else if (cur.length() >= 4) {
                return cur.toString();
            } else {
                cur.setLength(0);
            }
        }
        return cur.length() >= 4 ? cur.toString() : null;
    }

    private static final class FailureState {
        int consecutiveFailures;
        long openUntilMs;
    }
}
