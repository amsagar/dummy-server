package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.ruledomain.model.SkillRuleManifest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Partitions an {@link ExecutionTrace} into per-rule slices given the skill's
 * {@link SkillRuleManifest}. Each rule in the manifest declares the tool
 * names it owns; this slicer copies every matching tool step from the parent
 * trace into that rule's slice (plus any reasoning step that immediately
 * preceded it, so the compiler can read the LLM's intent for context).
 *
 * <p>Shared tools (e.g. {@code Get_OrderID}, used by every rule) appear in
 * every slice that lists them. That's correct: each rule's BPMN must
 * independently fetch the shared data, and the {@code TurnToolCache} in
 * Phase 1 dedupes the actual HTTP call at execution time.
 *
 * <p>When a rule's manifest entry lists no {@code tools}, the slicer falls
 * back to a name-prefix heuristic: tools whose name contains the rule name
 * as a substring belong to it. Operators can always override by being
 * explicit in the manifest.
 */
@Component
public class RuleSlicer {

    /**
     * Slice a trace into one sub-trace per rule. Returns a map keyed by
     * rule name (preserves the manifest's declared order). Rules with no
     * matching steps are omitted from the map — the caller can decide
     * whether to skip them or emit a placeholder rule.
     */
    public Map<String, ExecutionTrace> slice(ExecutionTrace fullTrace, SkillRuleManifest manifest) {
        if (fullTrace == null || manifest == null || manifest.isEmpty()) {
            return Map.of();
        }
        Map<String, ExecutionTrace> out = new LinkedHashMap<>();
        for (SkillRuleManifest.Rule rule : manifest.rules()) {
            Set<String> ownedTools = ownedToolNames(rule);
            if (ownedTools.isEmpty()) continue;
            List<ExecutionTrace.TraceStep> sliceSteps = new ArrayList<>();
            ExecutionTrace.TraceStep lastReasoning = null;

            for (ExecutionTrace.TraceStep s : fullTrace.steps()) {
                if (s.isReasoning()) {
                    lastReasoning = s;
                    continue;
                }
                if (!s.isTool()) continue;
                if (!ownedByRule(s.name(), ownedTools, rule.name())) continue;

                // Attach the immediately-preceding reasoning (if any) so the
                // compiler sees the LLM's intent for this call. Reset so we
                // don't attach the same reasoning to two consecutive tools.
                if (lastReasoning != null) {
                    sliceSteps.add(lastReasoning);
                    lastReasoning = null;
                }
                sliceSteps.add(s);
            }
            if (!sliceSteps.isEmpty()) {
                out.put(rule.name(), new ExecutionTrace(
                        fullTrace.turnId(),
                        fullTrace.sessionId(),
                        fullTrace.userPrompt(),
                        sliceSteps));
            }
        }
        return out;
    }

    private static Set<String> ownedToolNames(SkillRuleManifest.Rule rule) {
        Set<String> set = new LinkedHashSet<>();
        if (rule.tools() != null) {
            for (String t : rule.tools()) if (t != null && !t.isBlank()) set.add(t);
        }
        return set;
    }

    /**
     * A tool step belongs to a rule when its name is in the rule's explicit
     * owned-tools list, OR (fallback heuristic) the rule name appears as a
     * substring of the tool name.
     */
    private static boolean ownedByRule(String toolName, Set<String> owned, String ruleName) {
        if (toolName == null) return false;
        if (owned.contains(toolName)) return true;
        // Heuristic fallback for skills that didn't enumerate `tools:` per
        // rule: match by name fragment ("leg-sequence-check" ↔ "dtLeg").
        // Keep this conservative — case-insensitive substring on the rule
        // name's distinctive tokens.
        if (ruleName == null) return false;
        String lcTool = toolName.toLowerCase();
        for (String token : ruleName.toLowerCase().split("[-_\\s]+")) {
            if (token.length() < 4) continue;
            if (lcTool.contains(token)) return true;
        }
        return false;
    }
}
