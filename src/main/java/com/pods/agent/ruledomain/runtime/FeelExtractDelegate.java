package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.RuleDomainEventBus;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper;

    public FeelExtractDelegate(FeelHelper feel, RuleDomainEventBus bus, ObjectMapper objectMapper) {
        this.feel = feel;
        this.bus = bus;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(DelegateExecution execution) {
        ActivityEventStaging staging = ActivityEventStaging.start(execution, "feelExtractDelegate");
        try {
            executeStaged(execution, staging);
            staging.stage();
        } catch (BpmnError be) {
            staging.error(be.getErrorCode(), be.getMessage()).stage();
            throw be;
        } catch (RuntimeException ex) {
            staging.error("UNEXPECTED", ex.getMessage()).stage();
            throw ex;
        }
    }

    private void executeStaged(DelegateExecution execution, ActivityEventStaging staging) {
        String expr = BpmnFieldReader.required(execution, "feelExpr");
        String outputBinding = BpmnFieldReader.required(execution, "outputBinding");

        String nodeId = execution.getCurrentActivityId();
        Object turnId = execution.getVariable("_turnId");
        bus.emit("rule_domain.feel.eval", Map.of(
                "turnId", turnId == null ? "" : turnId.toString(),
                "nodeId", nodeId == null ? "" : nodeId,
                "outputBinding", outputBinding));

        // The FEEL expression itself is the most useful "input" for
        // debugging. Variable scope is recorded transparently in the
        // surrounding execution context but stashing the literal text
        // makes postmortems direct.
        staging.input(expr);

        Map<String, Object> ctx = BpmnVariables.readContext(execution);
        Object value;
        try {
            value = feel.eval(expr, ctx);
        } catch (RuntimeException ex) {
            throw new BpmnError("FEEL_EVAL_FAILED", ex.getMessage());
        }

        log.debug("BPMN feel-extract: {} -> {}", expr, value);
        BpmnVariables.set(execution, outputBinding, value);
        try {
            staging.output(objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
        }
    }

}
