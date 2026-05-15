package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.model.RuleDomain;
import com.pods.agent.ruledomain.repository.RuleDomainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Merges a new trace observation into an existing rule's
 * {@code coverage_manifest}. Triggered by Phase 3's coverage-miss path:
 * when a runtime input falls outside a rule's manifest, the orchestrator
 * routes that branch to the LLM loop, and the resulting sub-trace is
 * folded back into the rule's manifest here.
 *
 * <p>v1 implements the <b>easy case</b> only — additive widening of
 * {@code observed_inputs} and {@code exercised_tools}. When a new trace
 * <i>contradicts</i> the existing BPMN (different tool ordering, different
 * arg shapes for the same tool), the rule is flipped to
 * {@code coverage_state=MERGE_CONFLICT} and surfaced for human review.
 * Auto-reconciliation reintroduces the very hallucination risk we tried
 * to escape, so we accept "stays partial until reviewed" as a working
 * terminal state per the plan-agent's recommendation.
 */
@Component
@Slf4j
public class CoverageMerger {

    private final RuleDomainRepository repo;
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CoverageMerger(RuleDomainRepository repo, RuleDomainEventBus bus) {
        this.repo = repo;
        this.bus = bus;
    }

    /**
     * Merge a new trace slice into the given rule's manifest. The rule's
     * status is updated in-place; the new manifest JSON is written back to
     * {@code rule_domains.coverage_manifest}.
     *
     * @return the new {@code coverage_state} after merge
     */
    public String mergeTraceIntoRule(RuleDomain rule, ExecutionTrace slice, Map<String, Object> inputs) {
        if (rule == null || slice == null) return rule == null ? null : rule.getCoverageState();

        JsonNode existing;
        try {
            existing = rule.getCoverageManifest() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rule.getCoverageManifest());
        } catch (Exception ex) {
            log.warn("[CoverageMerger] existing manifest unreadable for rule={}: {}",
                    rule.getId(), ex.getMessage());
            existing = objectMapper.createObjectNode();
        }
        if (!existing.isObject()) existing = objectMapper.createObjectNode();
        ObjectNode root = (ObjectNode) existing;

        // 1. Tool set — additive.
        Set<String> exercisedTools = readToolNames(root);
        Set<String> newToolNames = new LinkedHashSet<>();
        for (ExecutionTrace.TraceStep s : slice.toolSteps()) {
            if (s.name() != null) newToolNames.add(s.name());
        }
        boolean toolsetWidened = exercisedTools.addAll(newToolNames);
        if (toolsetWidened) {
            ArrayNode arr = root.putArray("exercised_tools");
            for (String t : exercisedTools) arr.addObject().put("name", t);
        }

        // 2. observed_inputs — additive. For each input field present in
        //    the request that triggered this trace, record its value into
        //    the observed_values set.
        ObjectNode observed = root.has("observed_inputs") && root.get("observed_inputs").isObject()
                ? (ObjectNode) root.get("observed_inputs")
                : root.putObject("observed_inputs");
        boolean inputsWidened = false;
        if (inputs != null) {
            for (Map.Entry<String, Object> e : inputs.entrySet()) {
                String name = e.getKey();
                Object v = e.getValue();
                if (v == null) continue;
                ObjectNode cond = observed.has(name) && observed.get(name).isObject()
                        ? (ObjectNode) observed.get(name)
                        : observed.putObject(name);
                ArrayNode values = cond.has("observed_values") && cond.get("observed_values").isArray()
                        ? (ArrayNode) cond.get("observed_values")
                        : cond.putArray("observed_values");
                String s = v.toString();
                boolean already = false;
                for (JsonNode existingVal : values) {
                    if (s.equals(existingVal.asString(""))) { already = true; break; }
                }
                if (!already) {
                    values.add(s);
                    inputsWidened = true;
                }
            }
        }

        // 3. Conflict detection — if the new trace introduces a tool the
        //    existing manifest exercised but with structurally-different
        //    args, we'd need a v2 BPMN. We can't compile that here, so
        //    flip the rule into MERGE_CONFLICT for a human to review.
        //    Detection heuristic: same tool name appears in both with
        //    differing top-level arg key sets.
        boolean conflict = detectArgShapeConflict(root, slice);

        // 4. Decide new coverage_state:
        //    - conflict → MERGE_CONFLICT (terminal, requires human review)
        //    - widened (tools or inputs) → PARTIAL (we made progress but
        //      can't be sure we're COMPLETE without manual confirmation)
        //    - no change → keep existing state
        String newState;
        if (conflict) {
            newState = RuleDomain.COVERAGE_MERGE_CONFLICT;
            bus.emit("rule_domain.merge_conflict", Map.of(
                    "ruleId", rule.getId() == null ? "" : rule.getId(),
                    "ruleName", rule.getRuleName() == null ? "" : rule.getRuleName()));
            log.warn("[CoverageMerger] merge conflict for rule={} — needs human review",
                    rule.getRuleName());
        } else if (toolsetWidened || inputsWidened) {
            newState = RuleDomain.COVERAGE_PARTIAL;
            bus.emit("rule_domain.coverage_extended", Map.of(
                    "ruleId", rule.getId() == null ? "" : rule.getId(),
                    "ruleName", rule.getRuleName() == null ? "" : rule.getRuleName(),
                    "toolsWidened", toolsetWidened,
                    "inputsWidened", inputsWidened));
        } else {
            newState = rule.getCoverageState();
        }

        // 5. Persist. We write the manifest by going through save() with
        //    the existing embedding null (embedding doesn't change on a
        //    coverage merge — embed text is the rule's intent, not its
        //    inputs).
        rule.setCoverageManifest(toJsonString(root));
        rule.setCoverageState(newState);
        rule.setUpdatedAt(System.currentTimeMillis());
        repo.save(rule, null);
        return newState;
    }

    private static Set<String> readToolNames(JsonNode root) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode arr = root.path("exercised_tools");
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            String name = n.path("name").asString(null);
            if (name != null) out.add(name);
        }
        return out;
    }

    /**
     * Conflict heuristic: for every tool that appears in both the existing
     * manifest's exercised_tools and the new slice, compare the top-level
     * arg keys. A different set of keys is a structural shift the v1 BPMN
     * almost certainly can't accommodate.
     */
    private boolean detectArgShapeConflict(JsonNode root, ExecutionTrace slice) {
        // For each tool in the new slice, gather its arg keys.
        java.util.Map<String, Set<String>> newKeysByTool = new java.util.LinkedHashMap<>();
        for (ExecutionTrace.TraceStep s : slice.toolSteps()) {
            if (s.name() == null) continue;
            if (s.input() == null || !s.input().isObject()) continue;
            Set<String> keys = newKeysByTool.computeIfAbsent(s.name(), k -> new HashSet<>());
            s.input().propertyNames().forEachRemaining(keys::add);
        }
        // existing manifest may not have arg shapes (v1 didn't record them);
        // in that case nothing to compare → no conflict.
        JsonNode tools = root.path("exercised_tools");
        if (!tools.isArray()) return false;
        for (JsonNode t : tools) {
            String name = t.path("name").asString("");
            JsonNode shapes = t.path("argShapes");
            if (!shapes.isArray() || shapes.isEmpty()) continue;
            Set<String> newKeys = newKeysByTool.get(name);
            if (newKeys == null) continue;
            // Pull the first observed shape — a flat parser of "{k1:type,k2:type,...}"
            String firstShape = shapes.get(0).asString("");
            Set<String> oldKeys = parseShapeKeys(firstShape);
            if (oldKeys.isEmpty()) continue;
            if (!oldKeys.equals(newKeys)) return true;
        }
        return false;
    }

    private static Set<String> parseShapeKeys(String shape) {
        Set<String> out = new HashSet<>();
        if (shape == null) return out;
        String s = shape.replaceAll("[{}\\s]", "");
        if (s.isEmpty()) return out;
        for (String pair : s.split(",")) {
            int colon = pair.indexOf(':');
            String key = colon < 0 ? pair : pair.substring(0, colon);
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }

    private String toJsonString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
