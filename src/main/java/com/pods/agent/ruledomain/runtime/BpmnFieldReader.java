package com.pods.agent.ruledomain.runtime;

import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;

import java.util.List;

/**
 * Reads {@code <flowable:field>} values from the current activity's BPMN-DI
 * rather than from process variables.
 *
 * When a delegate is wired via {@code flowable:delegateExpression="${beanName}"}
 * (a Spring singleton), Flowable does NOT copy field-extension values into
 * {@code execution.getVariables()}. They are carried on the FlowElement itself
 * as {@code FieldExtension} entries. This helper looks them up there.
 *
 * Falls back to {@code execution.getVariable(name)} only as a courtesy for
 * callers that may have set the value directly in the variable scope (e.g.
 * inside a subprocess multi-instance scope).
 */
public final class BpmnFieldReader {

    private BpmnFieldReader() {}

    public static String required(DelegateExecution execution, String fieldName) {
        String value = optional(execution, fieldName);
        if (value == null || value.isBlank()) {
            throw new BpmnError("MISSING_VARIABLE",
                    "Required BPMN field `" + fieldName + "` on activity `"
                            + activityIdOf(execution) + "`");
        }
        return value;
    }

    public static String optional(DelegateExecution execution, String fieldName) {
        FlowElement fe = execution.getCurrentFlowElement();
        if (fe instanceof Activity activity) {
            List<FieldExtension> fields = activity.getFieldExtensions();
            if (fields != null) {
                for (FieldExtension f : fields) {
                    if (!fieldName.equals(f.getFieldName())) continue;
                    String s = f.getStringValue();
                    if (s != null && !s.isBlank()) return s.trim();
                    String e = f.getExpression();
                    if (e != null && !e.isBlank()) return e.trim();
                }
            }
        }
        Object v = execution.getVariable(fieldName);
        return v == null ? null : v.toString();
    }

    private static String activityIdOf(DelegateExecution execution) {
        FlowElement fe = execution.getCurrentFlowElement();
        return fe == null ? "?" : fe.getId();
    }
}
