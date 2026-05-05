package com.pods.agent.dmn;

import com.pods.agent.dmn.feel.FeelExpressionEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DmnDecisionTable {
    private final String name;
    private final HitPolicy hitPolicy;
    private final List<InputColumn> inputs;
    private final List<OutputColumn> outputs;
    private final List<Rule> rules;

    DmnDecisionTable(String name,
                     HitPolicy hitPolicy,
                     List<InputColumn> inputs,
                     List<OutputColumn> outputs,
                     List<Rule> rules) {
        this.name = name;
        this.hitPolicy = hitPolicy;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static DmnDecisionTable fromJsonString(String name, String json) {
        return DmnJsonParser.parse(name, json);
    }

    public EvaluationResult evaluate(Map<String, Object> vars) {
        Map<String, Object> input = vars == null ? Map.of() : vars;
        List<MatchedRow> matchedRows = new ArrayList<>();
        Map<String, Object> mergedOutputs = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (!ruleMatches(rule, input)) continue;
            matchedRows.add(new MatchedRow(i + 1, rule.id(), new LinkedHashMap<>(rule.outputs())));
            mergedOutputs.putAll(rule.outputs());
            if (hitPolicy == HitPolicy.FIRST || hitPolicy == HitPolicy.UNIQUE) {
                break;
            }
        }
        return new EvaluationResult(!matchedRows.isEmpty(), matchedRows, mergedOutputs);
    }

    private boolean ruleMatches(Rule rule, Map<String, Object> vars) {
        for (InputColumn column : inputs) {
            String expression = rule.inputs().getOrDefault(column.name(), "");
            Object actual = vars.get(column.name());
            if (!FeelExpressionEvaluator.matches(expression, actual)) {
                return false;
            }
        }
        return true;
    }

    public String getName() {
        return name;
    }

    public HitPolicy getHitPolicy() {
        return hitPolicy;
    }

    public List<InputColumn> getInputs() {
        return inputs;
    }

    public List<OutputColumn> getOutputs() {
        return outputs;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public record InputColumn(String name, String type, String label) {
    }

    public record OutputColumn(String name, String type, String label) {
    }

    public record Rule(String id, Map<String, String> inputs, Map<String, Object> outputs) {
    }
}
