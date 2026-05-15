package com.pods.agent.ruledomain.compiler;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.SkillRouter;
import com.pods.agent.ruledomain.compiler.trace.ExecutionTrace;
import com.pods.agent.ruledomain.compiler.trace.ExecutionTraceReader;
import com.pods.agent.ruledomain.compiler.trace.RuleSlicer;
import com.pods.agent.ruledomain.compiler.trace.TraceBasedBpmnCompiler;
import com.pods.agent.ruledomain.invalidation.ToolSignatureHasher;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Post-turn, async, trace-grounded compile of a skill's rules.
 *
 * <p>After a successful LLM-loop run (especially a cold-path miss or a
 * coverage-miss fallback), this bean reads the per-turn execution log,
 * partitions it into per-rule slices via {@link RuleSlicer}, and compiles
 * each slice into its own BPMN via {@link TraceBasedBpmnCompiler}. The user
 * has already received their answer; this runs entirely in the background.
 *
 * <p>The compiled rules become {@code DRAFT} domain-grouped rows; the
 * existing {@code maybePromote} flow flips them to {@code ACTIVE} after N
 * successful runs. Future requests matching the rule's narrow intent
 * (rule-level) or the umbrella (fan-out) hit the compiled BPMN.
 *
 * <p>Failure handling: any error in this path is logged and swallowed. The
 * user's turn already succeeded; a failed trace compile just means future
 * requests keep going through the LLM loop until the next successful turn
 * produces a usable trace.
 */
@Component
@Slf4j
public class AsyncTraceCompiler {

    private final ExecutionTraceReader traceReader;
    private final RuleSlicer slicer;
    private final TraceBasedBpmnCompiler traceCompiler;
    private final RuleDomainRepository repo;
    private final ToolRegistryService toolRegistry;
    private final ToolSignatureHasher toolHasher;
    private final RuleDomainEventBus bus;

    public AsyncTraceCompiler(ExecutionTraceReader traceReader,
                              RuleSlicer slicer,
                              TraceBasedBpmnCompiler traceCompiler,
                              RuleDomainRepository repo,
                              ToolRegistryService toolRegistry,
                              ToolSignatureHasher toolHasher,
                              RuleDomainEventBus bus) {
        this.traceReader = traceReader;
        this.slicer = slicer;
        this.traceCompiler = traceCompiler;
        this.repo = repo;
        this.toolRegistry = toolRegistry;
        this.toolHasher = toolHasher;
        this.bus = bus;
    }

    /**
     * Schedule a trace-based compile for the rules declared by this skill.
     * Returns immediately — actual compile runs on a Spring async executor.
     *
     * <p>Idempotency: if every rule in the manifest already has a
     * {@code LLM_TRACE}-sourced row, this is a no-op (we don't recompile
     * rules that are already trace-grounded — Phase 3's CoverageMerger
     * handles widening coverage on existing rules).
     */
    @Async
    public void scheduleCompile(SkillRouter.RoutedSkill routed, String sessionId, String turnId) {
        if (routed == null || routed.manifest() == null || routed.manifest().isEmpty()) {
            log.debug("[AsyncTraceCompiler] Skill {} has no rule manifest — skipping",
                    routed == null ? "?" : routed.skill().getName());
            return;
        }
        try {
            doCompile(routed, sessionId, turnId);
        } catch (Exception ex) {
            log.warn("[AsyncTraceCompiler] compile failed for skill={} turn={}: {}",
                    routed.skill().getName(), turnId, ex.getMessage(), ex);
        }
    }

    private void doCompile(SkillRouter.RoutedSkill routed, String sessionId, String turnId) {
        var skill = routed.skill();
        SkillRuleManifest manifest = routed.manifest();

        Optional<ExecutionTrace> traceOpt = traceReader.read(sessionId, turnId);
        if (traceOpt.isEmpty() || traceOpt.get().steps().isEmpty()) {
            log.debug("[AsyncTraceCompiler] No usable trace for turn={}; skipping", turnId);
            return;
        }
        ExecutionTrace trace = traceOpt.get();

        Map<String, ExecutionTrace> slices = slicer.slice(trace, manifest);
        if (slices.isEmpty()) {
            log.debug("[AsyncTraceCompiler] Slicer produced no slices for skill={} turn={}",
                    skill.getName(), turnId);
            return;
        }

        // Skip rules that already have an LLM_TRACE row — first-trace wins.
        // Coverage widening of existing trace-grounded rules is the job of
        // Phase 3's CoverageMerger, not this initial-compile path.
        Set<String> alreadyTraceCompiled = existingTraceRuleNames(skill.getId(), manifest);

        // One group id per skill (UUID v4). Reuse the existing group id if
        // any rule for this skill is already grouped; otherwise mint.
        String domainGroupId = existingGroupIdForSkill(skill.getId())
                .orElseGet(() -> UUID.randomUUID().toString());
        String domainGroupName = skill.getName();
        List<AgentTool> tools = toolRegistry.getEnabledTools();
        String toolSignature = toolHasher.hash(tools);

        bus.emit("rule_domain.compile.trace_started", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "skillName", skill.getName(),
                "ruleCount", slices.size()));

        int compiled = 0;
        for (Map.Entry<String, ExecutionTrace> e : slices.entrySet()) {
            String ruleName = e.getKey();
            if (alreadyTraceCompiled.contains(ruleName)) {
                log.debug("[AsyncTraceCompiler] rule={} already trace-compiled — skipping", ruleName);
                continue;
            }
            SkillRuleManifest.Rule manifestRule = manifest.rules().stream()
                    .filter(r -> ruleName.equals(r.name()))
                    .findFirst().orElse(null);
            if (manifestRule == null) continue;

            try {
                RuleDomain saved = traceCompiler.compileFromTrace(
                        skill.getId(),
                        skill.getName(),
                        routed.markdown(),
                        domainGroupId,
                        domainGroupName,
                        manifestRule,
                        toolSignature,
                        e.getValue());
                if (RuleDomain.STATUS_FAILED.equals(saved.getStatus())) {
                    log.warn("[AsyncTraceCompiler] rule={} compile failed: {}",
                            ruleName, saved.getLastError());
                } else {
                    compiled++;
                }
            } catch (Exception ex) {
                log.warn("[AsyncTraceCompiler] rule={} compile threw: {}",
                        ruleName, ex.getMessage(), ex);
            }
        }

        bus.emit("rule_domain.compile.trace_finished", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "skillName", skill.getName(),
                "compiledCount", compiled));

        log.info("[AsyncTraceCompiler] skill={} turn={} compiled {} rule(s) from trace",
                skill.getName(), turnId, compiled);
    }

    private Set<String> existingTraceRuleNames(String skillId, SkillRuleManifest manifest) {
        Set<String> declared = new HashSet<>();
        manifest.rules().forEach(r -> declared.add(r.name()));
        Set<String> already = new HashSet<>();
        for (RuleDomain rd : repo.listBySkill(skillId)) {
            if (rd.getRuleName() == null) continue;
            if (!declared.contains(rd.getRuleName())) continue;
            if (RuleDomain.TRACE_LLM_TRACE.equals(rd.getTraceSource())
                    || RuleDomain.TRACE_HYBRID.equals(rd.getTraceSource())) {
                already.add(rd.getRuleName());
            }
        }
        return already;
    }

    private Optional<String> existingGroupIdForSkill(String skillId) {
        for (RuleDomain rd : repo.listBySkill(skillId)) {
            if (rd.getDomainGroupId() != null && !rd.getDomainGroupId().isBlank()) {
                return Optional.of(rd.getDomainGroupId());
            }
        }
        return Optional.empty();
    }
}
