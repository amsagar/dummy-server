package com.pods.agent.ruledomain.runtime;

import com.pods.agent.dmn.EvaluationResult;
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

    public DecisionTableDelegate(DecisionTableService decisionTableService,
                                 ObjectMapper objectMapper,
                                 FeelHelper feel) {
        this.decisionTableService = decisionTableService;
        this.objectMapper = objectMapper;
        this.feel = feel;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String tableName = requireString(execution, "tableName");
        String inputsTemplateJson = requireString(execution, "inputsTemplate");
        String outputBinding = requireString(execution, "outputBinding");

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

        log.debug("BPMN decision table eval: table={} inputs={}", tableName, inputs);
        EvaluationResult result;
        try {
            result = decisionTableService.evaluate(tableName, inputs);
        } catch (Exception ex) {
            throw new BpmnError("DECISION_TABLE_FAILED",
                    "Decision table " + tableName + " failed: " + ex.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", result.matched());
        out.put("rows", result.matchedRows());
        out.put("outputs", result.outputs());

        execution.setVariable(outputBinding, out);
    }

    private static String requireString(DelegateExecution exec, String name) {
        Object v = exec.getVariable(name);
        if (v == null || v.toString().isBlank()) {
            throw new BpmnError("MISSING_VARIABLE", "Required BPMN variable: " + name);
        }
        return v.toString();
    }
}
