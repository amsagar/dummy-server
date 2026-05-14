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

/**
 * Top-level entry point for the compiled-domain path. Called from
 * {@code AgentOrchestrator.streamTurn()} before the LLM loop.
 *
 * Flow:
 *   1. Skill router selects an allowlisted skill (or returns empty → caller falls back).
 *   2. Intent matcher checks pgvector for an existing domain.
 *   3. HIT  → execute the cached BPMN.
 *   4. MISS → compile (one LLM call), then execute.
 *   5. Promote DRAFT to ACTIVE after the configured number of successful runs.
 *   6. Return the {@link ExecutionOutcome} to the caller; the
 *      {@code ResponseSummarizer} turns it into prose.
 *
 * Any exception in the compiled path is caught and surfaced as
 * {@code ExecutionOutcome.notHandled()} so the caller falls back to the
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

    public RuleDomainOrchestrator(SkillRouter skillRouter,
                                  IntentMatcher intentMatcher,
                                  IntentLabeller intentLabeller,
                                  BpmnCompiler compiler,
                                  BpmnRuntime runtime,
                                  RuleDomainRepository repo,
                                  RuleExecutionRepository executionRepo,
                                  ToolRegistryService toolRegistry,
                                  RuleDomainProperties props) {
        this.skillRouter = skillRouter;
        this.intentMatcher = intentMatcher;
        this.intentLabeller = intentLabeller;
        this.compiler = compiler;
        this.runtime = runtime;
        this.repo = repo;
        this.executionRepo = executionRepo;
        this.toolRegistry = toolRegistry;
        this.props = props;
    }

    public ExecutionOutcome handleIfApplicable(String userMessage,
                                               Map<String, Object> userInputs,
                                               String sessionId,
                                               String turnId) {
        Optional<SkillRouter.RoutedSkill> routed = skillRouter.route(userMessage);
        if (routed.isEmpty()) return ExecutionOutcome.notHandled();
        var skill = routed.get().skill();

        try {
            // Try cache lookup first
            Optional<RuleDomainRepository.Match> match =
                    intentMatcher.findMatch(skill.getId(), userMessage);
            if (match.isPresent() && RuleDomain.STATUS_ACTIVE.equals(match.get().domain().getStatus())) {
                log.info("[RuleDomain] HIT skill={} domain={} similarity={}",
                        skill.getName(), match.get().domain().getId(), match.get().similarity());
                Map<String, Object> inputs = effectiveInputs(userMessage, userInputs);
                ExecutionOutcome outcome = runtime.execute(
                        match.get().domain(), inputs, sessionId, turnId, true);
                return outcome;
            }

            // Cache miss → compile
            log.info("[RuleDomain] MISS compiling new domain for skill={}", skill.getName());
            List<AgentTool> tools = toolRegistry.getEnabledTools();
            String intentLabel = intentLabeller.labelFor(skill.getName(), userMessage);

            RuleDomain compiled = compiler.compile(
                    skill.getId(), skill.getName(), routed.get().markdown(),
                    userMessage, intentLabel, tools);

            if (RuleDomain.STATUS_FAILED.equals(compiled.getStatus())) {
                log.warn("[RuleDomain] Compile failed: {}", compiled.getLastError());
                return ExecutionOutcome.notHandled();
            }

            Map<String, Object> inputs = effectiveInputs(userMessage, userInputs);
            ExecutionOutcome outcome = runtime.execute(
                    compiled, inputs, sessionId, turnId, false);

            // Promote DRAFT → ACTIVE after enough successful runs
            maybePromote(compiled);

            return outcome;
        } catch (Exception ex) {
            log.warn("[RuleDomain] Path failed for skill={}: {}", skill.getName(), ex.getMessage(), ex);
            return ExecutionOutcome.notHandled();
        }
    }

    private void maybePromote(RuleDomain domain) {
        if (!RuleDomain.STATUS_DRAFT.equals(domain.getStatus())) return;
        int needed = Math.max(1, props.getPromoteAfterSuccessfulRuns());
        int successes = executionRepo.countRecentSuccesses(domain.getId());
        if (successes >= needed) {
            log.info("[RuleDomain] Promoting domain {} from DRAFT to ACTIVE ({} successful runs)",
                    domain.getId(), successes);
            repo.updateStatus(domain.getId(), RuleDomain.STATUS_ACTIVE, null);
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
        // Best-effort numeric extract — saves the LLM compiler from having to
        // emit its own extractor step for the most common case.
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
}
