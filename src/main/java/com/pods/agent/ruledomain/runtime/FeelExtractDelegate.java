package com.pods.agent.ruledomain.runtime;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BPMN ServiceTask that evaluates a single FEEL expression and writes the result
 * to a process variable. Used by the compiler for:
 *
 *   <ul>
 *     <li>Filtering arrays out of upstream tool responses (e.g. extracting leg lines from an order)</li>
 *     <li>Mapping/transforming data between tool calls</li>
 *     <li>Building structured output objects for the summarizer</li>
 *   </ul>
 *
 * Variables:
 *   <ul>
 *     <li><b>feelExpr</b>       — the FEEL expression to evaluate</li>
 *     <li><b>outputBinding</b>  — process variable name to receive the result</li>
 *   </ul>
 */
@Component("feelExtractDelegate")
@Slf4j
public class FeelExtractDelegate implements JavaDelegate {

    private final FeelHelper feel;

    public FeelExtractDelegate(FeelHelper feel) {
        this.feel = feel;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String expr = requireString(execution, "feelExpr");
        String outputBinding = requireString(execution, "outputBinding");

        Map<String, Object> ctx = new LinkedHashMap<>(execution.getVariables());
        Object value;
        try {
            value = feel.eval(expr, ctx);
        } catch (RuntimeException ex) {
            throw new BpmnError("FEEL_EVAL_FAILED", ex.getMessage());
        }

        log.debug("BPMN feel-extract: {} -> {}", expr, value);
        execution.setVariable(outputBinding, value);
    }

    private static String requireString(DelegateExecution exec, String name) {
        Object v = exec.getVariable(name);
        if (v == null || v.toString().isBlank()) {
            throw new BpmnError("MISSING_VARIABLE", "Required BPMN variable: " + name);
        }
        return v.toString();
    }
}
