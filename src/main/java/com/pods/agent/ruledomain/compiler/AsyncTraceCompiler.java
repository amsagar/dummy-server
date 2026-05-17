package com.pods.agent.ruledomain.compiler;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.SkillRouter;
import com.pods.agent.ruledomain.compiler.trace.ExecutionTrace;
import com.pods.agent.ruledomain.compiler.trace.ExecutionTraceReader;
import com.pods.agent.ruledomain.compiler.trace.RuleSlicer;
import com.pods.agent.ruledomain.compiler.trace.SkillManifestDeriver;
import com.pods.agent.ruledomain.compiler.trace.TraceBasedBpmnCompiler;
import com.pods.agent.ruledomain.invalidation.SkillSourceHasher;
import com.pods.agent.ruledomain.invalidation.ToolSignatureHasher;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
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
    private final SkillManifestDeriver manifestDeriver;
    private final SkillRepository skillRepository;
    private final SkillSourceHasher skillSourceHasher;

    public AsyncTraceCompiler(ExecutionTraceReader traceReader,
                              RuleSlicer slicer,
                              TraceBasedBpmnCompiler traceCompiler,
                              RuleDomainRepository repo,
                              ToolRegistryService toolRegistry,
                              ToolSignatureHasher toolHasher,
                              RuleDomainEventBus bus,
                              SkillManifestDeriver manifestDeriver,
                              SkillRepository skillRepository,
                              SkillSourceHasher skillSourceHasher) {
        this.traceReader = traceReader;
        this.slicer = slicer;
        this.traceCompiler = traceCompiler;
        this.repo = repo;
        this.toolRegistry = toolRegistry;
        this.toolHasher = toolHasher;
        this.bus = bus;
        this.manifestDeriver = manifestDeriver;
        this.skillRepository = skillRepository;
        this.skillSourceHasher = skillSourceHasher;
    }

    /**
     * Run a trace-based compile for the rules declared by this skill.
     *
     * <p>Caller dispatches this onto {@code ForkJoinPool.commonPool()}: Spring
     * AI's Azure SDK Netty client only binds correctly when invoked from FJ
     * commonPool threads on this setup. Spring's {@code @Async} pool and
     * Reactor's {@code boundedElastic} both fail with "channel not registered
     * to an event loop". See {@code AgentOrchestrator.scheduleTraceCompile}.
     *
     * <p>Idempotency: if every rule in the manifest already has a
     * {@code LLM_TRACE}-sourced row, this is a no-op (we don't recompile
     * rules that are already trace-grounded — Phase 3's CoverageMerger
     * handles widening coverage on existing rules).
     */
    public void scheduleCompile(SkillRouter.RoutedSkill routed, String sessionId, String turnId) {
        if (routed == null) return;
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

        // Gate: do not compile rules from a trace whose tool calls
        // failed. A trace where Get_OrderID timed out 3 times and the LLM
        // returned "validation_failed" is not a successful run we want to
        // generalize from — the BPMN would encode nothing but timeouts.
        // Require at least one successful tool call AND that the failure
        // rate not be overwhelming.
        if (!traceLooksRunnable(trace, skill.getName(), turnId)) {
            return;
        }

        // Option B: derive a manifest from prose + trace if the skill didn't
        // ship one. Persisted on the skill row so we only pay this LLM call
        // once per (skill, markdown-revision) pair.
        if (manifest == null || manifest.isEmpty()) {
            manifest = deriveAndPersistManifest(routed, trace, turnId);
            if (manifest.isEmpty()) {
                log.debug("[AsyncTraceCompiler] Skill {} manifest derivation produced nothing — leaving as legacy",
                        skill.getName());
                return;
            }
        }

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

        // Compile rules in parallel on FJ commonPool. Each agentic
        // compile is independent (separate workspace, separate
        // conversation history). FJ commonPool is the validated thread
        // context for Azure SDK Netty calls — same pool the chat turn
        // and manifest deriver use. Wall time drops from sum(per-rule)
        // to max(per-rule).
        final String capturedSessionId = sessionId;
        final String capturedSkillId = skill.getId();
        final String capturedSkillName = skill.getName();
        final String capturedMarkdown = routed.markdown();
        final String capturedGroupId = domainGroupId;
        final String capturedGroupName = domainGroupName;
        final String capturedToolSig = toolSignature;

        java.util.List<java.util.concurrent.CompletableFuture<RuleDomain>> futures = new java.util.ArrayList<>();
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
            final ExecutionTrace ruleSlice = e.getValue();
            // Per-rule gate: skip slices whose required tool calls all
            // failed. A rule's BPMN can't be derived from a trace that
            // only saw timeouts and errors for that rule's tools.
            if (!sliceLooksRunnable(ruleSlice, ruleName)) {
                continue;
            }
            final SkillRuleManifest.Rule capturedRule = manifestRule;

            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return traceCompiler.compileFromTrace(
                            capturedSessionId,
                            capturedSkillId,
                            capturedSkillName,
                            capturedMarkdown,
                            capturedGroupId,
                            capturedGroupName,
                            capturedRule,
                            capturedToolSig,
                            ruleSlice);
                } catch (Exception ex) {
                    log.warn("[AsyncTraceCompiler] rule={} compile threw: {}",
                            ruleName, ex.getMessage(), ex);
                    return null;
                }
            }, java.util.concurrent.ForkJoinPool.commonPool()));
        }

        int compiled = 0;
        for (var f : futures) {
            try {
                RuleDomain saved = f.join();
                if (saved == null) continue;
                if (RuleDomain.STATUS_FAILED.equals(saved.getStatus())) {
                    log.warn("[AsyncTraceCompiler] rule={} compile failed: {}",
                            saved.getRuleName(), saved.getLastError());
                } else {
                    compiled++;
                }
            } catch (Exception ex) {
                log.warn("[AsyncTraceCompiler] compile future join failed: {}", ex.getMessage(), ex);
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

    /**
     * Ask the LLM to derive a rule manifest from the skill's prose + the
     * recorded trace, then persist it on the skill row so subsequent
     * requests find it via {@code SkillRouter.resolveManifest}.
     *
     * <p>Returns {@link SkillRuleManifest#EMPTY} on any failure — caller
     * treats that as "leave the skill un-manifested for now".
     */
    private SkillRuleManifest deriveAndPersistManifest(SkillRouter.RoutedSkill routed,
                                                       ExecutionTrace trace,
                                                       String turnId) {
        var skill = routed.skill();
        bus.emit("rule_domain.manifest_derivation.start", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "skillName", skill.getName()));

        SkillRuleManifest derived = manifestDeriver.derive(skill.getName(), routed.markdown(), trace);
        if (derived == null || derived.isEmpty()) {
            bus.emit("rule_domain.manifest_derivation.failed", Map.of(
                    "turnId", turnId == null ? "" : turnId,
                    "skillName", skill.getName()));
            return SkillRuleManifest.EMPTY;
        }

        String json = manifestDeriver.toJson(derived);
        String sourceHash = skillSourceHasher.hash(routed.markdown());
        try {
            skillRepository.updateDerivedManifest(skill.getId(), json, sourceHash);
        } catch (Exception ex) {
            log.warn("[AsyncTraceCompiler] failed to persist derived manifest for skill={}: {}",
                    skill.getName(), ex.getMessage());
        }

        bus.emit("rule_domain.manifest_derived", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "skillName", skill.getName(),
                "ruleCount", derived.rules().size(),
                "ruleNames", derived.rules().stream().map(SkillRuleManifest.Rule::name).toList()));

        log.info("[AsyncTraceCompiler] Derived manifest for skill={}: {} rules ({})",
                skill.getName(), derived.rules().size(),
                derived.rules().stream().map(SkillRuleManifest.Rule::name).toList());
        return derived;
    }

    private Optional<String> existingGroupIdForSkill(String skillId) {
        for (RuleDomain rd : repo.listBySkill(skillId)) {
            if (rd.getDomainGroupId() != null && !rd.getDomainGroupId().isBlank()) {
                return Optional.of(rd.getDomainGroupId());
            }
        }
        return Optional.empty();
    }

    /**
     * Whole-trace gate. Reject runs that don't look like a successful
     * end-to-end execution we want to generalize from.
     *
     * <p>Heuristics:
     * <ul>
     *   <li>At least one tool step exists.</li>
     *   <li>At least one tool step succeeded (status="success").</li>
     *   <li>Failure rate is below 50% — a trace where most tool calls
     *       failed isn't a teaching example.</li>
     * </ul>
     *
     * <p>Emits {@code rule_domain.compile.trace_skipped} so operators
     * can see why a turn produced no rules. Returns true when the trace
     * is worth compiling from.
     */
    private boolean traceLooksRunnable(ExecutionTrace trace, String skillName, String turnId) {
        List<ExecutionTrace.TraceStep> toolSteps = trace.toolSteps();
        if (toolSteps.isEmpty()) {
            emitSkip(skillName, turnId, "no_tool_steps",
                    "Trace contains no tool calls — nothing to compile from.");
            return false;
        }
        int succeeded = 0;
        int failed = 0;
        for (ExecutionTrace.TraceStep s : toolSteps) {
            if ("success".equals(s.status())) succeeded++;
            else if ("failed".equals(s.status())) failed++;
        }
        if (succeeded == 0) {
            emitSkip(skillName, turnId, "no_successful_tool_calls",
                    "Trace has " + failed + " tool calls but none succeeded — the run "
                    + "didn't produce a usable schema. Re-run when upstream tools are healthy.");
            return false;
        }
        if (failed * 2 >= toolSteps.size()) {
            emitSkip(skillName, turnId, "tool_failure_rate_too_high",
                    "Trace tool failure rate " + failed + "/" + toolSteps.size()
                    + " ≥ 50% — refusing to compile from a mostly-broken run.");
            return false;
        }
        return true;
    }

    /** Per-rule slice gate. After whole-trace passed, individual rules may
     *  still have a slice whose required tools all failed. */
    private boolean sliceLooksRunnable(ExecutionTrace slice, String ruleName) {
        List<ExecutionTrace.TraceStep> toolSteps = slice.toolSteps();
        if (toolSteps.isEmpty()) {
            log.debug("[AsyncTraceCompiler] rule={} slice has no tool steps — skipping", ruleName);
            return false;
        }
        boolean anySuccess = toolSteps.stream().anyMatch(s -> "success".equals(s.status()));
        if (!anySuccess) {
            log.warn("[AsyncTraceCompiler] rule={} slice has only failed tool calls — skipping compile", ruleName);
            return false;
        }
        return true;
    }

    private void emitSkip(String skillName, String turnId, String reason, String message) {
        log.warn("[AsyncTraceCompiler] skipping compile for skill={} turn={}: {}",
                skillName, turnId, message);
        bus.emit("rule_domain.compile.trace_skipped", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "skillName", skillName,
                "reason", reason,
                "message", message));
    }
}
