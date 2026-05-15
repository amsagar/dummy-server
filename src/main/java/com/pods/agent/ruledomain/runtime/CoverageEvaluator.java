package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.model.RuleDomain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Evaluates whether a request's inputs fall inside a rule's
 * {@code coverage_manifest}. Used by the orchestrator before invoking a rule
 * — if the input is outside coverage, the orchestrator skips that rule and
 * (optionally) falls back to the LLM loop for that branch only.
 *
 * <p>Coverage manifest schema (stored in {@code rule_domains.coverage_manifest}):
 * <pre>
 * {
 *   "schema_version": 1,
 *   "observed_inputs": {
 *     "order_has_idel_lines":  { "observed_values": [true, false] },
 *     "leg_count":             { "observed_min": 1, "observed_max": 5 },
 *     "order_orderType":       { "observed_values": ["Long Distance"] }
 *   },
 *   "open_questions": [...]
 * }
 * </pre>
 *
 * <p>Each entry in {@code observed_inputs} declares a derived predicate
 * over the input map. The current input is checked: if its derived value
 * isn't in {@code observed_values}, or falls outside
 * {@code [observed_min, observed_max]}, the rule is a coverage miss.
 *
 * <p>When the manifest is absent / empty / unparseable, the rule is treated
 * as covering everything (vacuously true). This preserves back-compat for
 * legacy rules without manifests.
 */
@Component
@Slf4j
public class CoverageEvaluator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public CoverageResult check(RuleDomain rule, Map<String, Object> inputs) {
        if (rule == null) return CoverageResult.covered();
        String manifestJson = rule.getCoverageManifest();
        if (manifestJson == null || manifestJson.isBlank()) {
            // No manifest = legacy or freshly-prose-compiled rule — vacuously covered.
            return CoverageResult.covered();
        }
        if (RuleDomain.COVERAGE_COMPLETE.equals(rule.getCoverageState())) {
            return CoverageResult.covered();
        }

        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(manifestJson);
        } catch (Exception ex) {
            log.debug("[CoverageEvaluator] manifest parse failed for rule {} — treating as covered: {}",
                    rule.getId(), ex.getMessage());
            return CoverageResult.covered();
        }

        JsonNode observed = manifest.path("observed_inputs");
        if (observed.isMissingNode() || !observed.isObject() || observed.isEmpty()) {
            return CoverageResult.covered();
        }

        // Each child describes a derived condition. We look up the derived
        // value by name from the inputs map (simple lookup — if Phase 3
        // grows complex derived predicates, swap this for a registry of
        // condition extractors).
        var fields = observed.propertyStream().toList();
        for (var entry : fields) {
            String condName = entry.getKey();
            JsonNode condSpec = entry.getValue();
            Object actual = inputs == null ? null : inputs.get(condName);

            if (condSpec.has("observed_values")) {
                JsonNode values = condSpec.get("observed_values");
                if (values.isArray() && !valueInSet(actual, values)) {
                    return CoverageResult.missing(condName,
                            "value '" + actual + "' not in observed set");
                }
            }
            if (condSpec.has("observed_min") || condSpec.has("observed_max")) {
                Double n = toDouble(actual);
                if (n == null) {
                    return CoverageResult.missing(condName, "expected numeric, got " + actual);
                }
                if (condSpec.has("observed_min") && n < condSpec.get("observed_min").doubleValue()) {
                    return CoverageResult.missing(condName, "value " + n + " below observed min");
                }
                if (condSpec.has("observed_max") && n > condSpec.get("observed_max").doubleValue()) {
                    return CoverageResult.missing(condName, "value " + n + " above observed max");
                }
            }
        }

        return CoverageResult.covered();
    }

    private static boolean valueInSet(Object actual, JsonNode set) {
        if (actual == null) {
            for (JsonNode v : set) if (v.isNull()) return true;
            return false;
        }
        String s = actual.toString();
        for (JsonNode v : set) {
            if (v.isNull()) continue;
            if (v.isBoolean() && actual instanceof Boolean b && v.asBoolean() == b) return true;
            if (v.isNumber()) {
                Double d = toDouble(actual);
                if (d != null && Math.abs(d - v.doubleValue()) < 1e-9) return true;
            }
            if (v.asString().equals(s)) return true;
        }
        return false;
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException ex) { return null; }
    }

    public record CoverageResult(boolean covered, String firstMissingCondition, String reason) {
        public static CoverageResult covered() {
            return new CoverageResult(true, null, null);
        }

        public static CoverageResult missing(String condition, String reason) {
            return new CoverageResult(false, condition, reason);
        }
    }
}
