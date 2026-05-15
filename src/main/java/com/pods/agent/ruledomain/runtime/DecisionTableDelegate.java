package com.pods.agent.ruledomain.runtime;

import com.pods.agent.dmn.EvaluationResult;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.service.DecisionTableService;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BPMN BusinessRuleTask (or ServiceTask) that evaluates a registered decision
 * table via the existing {@link DecisionTableService}. Process variables:
 *
 *   <ul>
 *     <li><b>tableName</b>     — the decision table name to evaluate</li>
 *     <li><b>inputsTemplate</b>— JSON map of {inputColumn -> FEEL expression}</li>
 *     <li><b>outputBinding</b> — process variable name to receive {@link EvaluationResult}</li>
 *   </ul>
 *
 * The resulting variable is a plain Map shaped:
 *   <pre>{ matched: boolean, rows: List, outputs: Map, message: String? }</pre>
 */
@Component("decisionTableDelegate")
@Slf4j
public class DecisionTableDelegate implements JavaDelegate {

    private static final TypeReference<Map<String, String>> STR_MAP =
            new TypeReference<>() {};

    private final DecisionTableService decisionTableService;
    private final ObjectMapper objectMapper;
    private final FeelHelper feel;
    private final RuleDomainEventBus bus;

    public DecisionTableDelegate(DecisionTableService decisionTableService,
                                 ObjectMapper objectMapper,
                                 FeelHelper feel,
                                 RuleDomainEventBus bus) {
        this.decisionTableService = decisionTableService;
        this.objectMapper = objectMapper;
        this.feel = feel;
        this.bus = bus;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String tableName = BpmnFieldReader.required(execution, "tableName");
        String inputsTemplateJson = BpmnFieldReader.required(execution, "inputsTemplate");
        String outputBinding = BpmnFieldReader.required(execution, "outputBinding");

        Map<String, String> template;
        try {
            template = objectMapper.readValue(inputsTemplateJson, STR_MAP);
        } catch (Exception ex) {
            throw new BpmnError("BAD_VARIABLE",
                    "inputsTemplate must be {column: feelExpr}: " + ex.getMessage());
        }

        Map<String, Object> feelCtx = new LinkedHashMap<>(execution.getVariables());
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : template.entrySet()) {
            try {
                inputs.put(e.getKey(), feel.eval(e.getValue(), feelCtx));
            } catch (RuntimeException ex) {
                throw new BpmnError("FEEL_EVAL_FAILED",
                        "Decision-table input `" + e.getKey() + "` failed: " + ex.getMessage());
            }
        }

        String nodeId = execution.getCurrentActivityId();
        Object turnId = execution.getVariable("_turnId");
        bus.emit("rule_domain.decision.call", Map.of(
                "turnId", turnId == null ? "" : turnId.toString(),
                "nodeId", nodeId == null ? "" : nodeId,
                "tableName", tableName,
                "inputs", inputs));

        log.debug("BPMN decision table eval: table={} inputs={}", tableName, inputs);
        EvaluationResult result;
        try {
            result = decisionTableService.evaluate(tableName, inputs);
        } catch (Exception ex) {
            bus.emit("rule_domain.decision.result", Map.of(
                    "turnId", turnId == null ? "" : turnId.toString(),
                    "nodeId", nodeId == null ? "" : nodeId,
                    "tableName", tableName,
                    "success", false,
                    "error", ex.getMessage() == null ? "" : ex.getMessage()));
            throw new BpmnError("DECISION_TABLE_FAILED",
                    "Decision table " + tableName + " failed: " + ex.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", result.matched());
        out.put("rows", result.matchedRows());
        out.put("outputs", result.outputs());

        bus.emit("rule_domain.decision.result", Map.of(
                "turnId", turnId == null ? "" : turnId.toString(),
                "nodeId", nodeId == null ? "" : nodeId,
                "tableName", tableName,
                "success", true,
                "matched", result.matched()));

        execution.setVariable(outputBinding, out);
    }

}
