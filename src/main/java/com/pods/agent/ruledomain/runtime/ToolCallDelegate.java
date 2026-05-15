package com.pods.agent.ruledomain.runtime;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
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
 * BPMN ServiceTask that invokes a registered agent tool through the existing
 * {@link ToolExecutionService}. The compiled BPMN passes three variables in
 * via field injection or local variables:
 *
 *   <ul>
 *     <li><b>toolName</b>      — name of the AgentTool to call</li>
 *     <li><b>argTemplate</b>   — JSON map of {argName -> FEEL expression}</li>
 *     <li><b>outputBinding</b> — process variable name to receive the parsed JSON result</li>
 *   </ul>
 *
 * Optional:
 *   <ul>
 *     <li><b>postTransform</b> — JSON map of {fieldName -> FEEL expression}, evaluated
 *         after the tool returns with the raw response bound to {@code _resp}.
 *         When present, the *transformed* object is what gets written to {@code outputBinding}.</li>
 *   </ul>
 *
 * Process variables visible to FEEL are the union of the BPMN execution's
 * variables plus the local execution variables in scope (which makes
 * multi-instance subprocess work cleanly — the per-item variable is visible).
 */
@Component("toolCallDelegate")
@Slf4j
public class ToolCallDelegate implements JavaDelegate {

    private static final TypeReference<Map<String, String>> STR_MAP =
            new TypeReference<>() {};

    private final ToolRegistryService toolRegistry;
    private final ToolExecutionService toolExecutor;
    private final ObjectMapper objectMapper;
    private final FeelHelper feel;

    public ToolCallDelegate(ToolRegistryService toolRegistry,
                            ToolExecutionService toolExecutor,
                            ObjectMapper objectMapper,
                            FeelHelper feel) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.feel = feel;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String toolName = BpmnFieldReader.required(execution, "toolName");
        String argTemplateJson = BpmnFieldReader.required(execution, "argTemplate");
        String outputBinding = BpmnFieldReader.required(execution, "outputBinding");
        String postTransformJson = BpmnFieldReader.optional(execution, "postTransform");

        AgentTool tool = toolRegistry.getEnabledToolByName(toolName);
        if (tool == null) {
            throw new BpmnError("TOOL_NOT_FOUND", "No enabled tool named: " + toolName);
        }

        // Snapshot the variable scope (includes parent process variables + local loop vars)
        Map<String, Object> feelCtx = new LinkedHashMap<>(execution.getVariables());

        // Resolve every arg through FEEL → assemble a JSON payload
        Map<String, String> argTemplate = parseStringMap(argTemplateJson, "argTemplate");
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : argTemplate.entrySet()) {
            try {
                Object value = feel.eval(e.getValue(), feelCtx);
                resolved.put(e.getKey(), value);
            } catch (RuntimeException ex) {
                throw new BpmnError("FEEL_EVAL_FAILED",
                        "Failed to resolve arg `" + e.getKey() + "` for tool " + toolName + ": " + ex.getMessage());
            }
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(resolved);
        } catch (Exception ex) {
            throw new BpmnError("PAYLOAD_SERIALIZE_FAILED",
                    "Could not serialize tool args: " + ex.getMessage());
        }

        log.debug("BPMN tool call: tool={} args={}", toolName, payload);
        ToolExecutionService.ExecutionResult result = toolExecutor.execute(tool, payload);

        if (!result.success()) {
            throw new BpmnError("TOOL_EXECUTION_FAILED",
                    "Tool " + toolName + " failed: " + result.error());
        }

        Object parsed = parseToolBody(result.body());

        // Apply optional post-transform with the raw response bound to `_resp`
        Object finalValue = parsed;
        if (postTransformJson != null && !postTransformJson.isBlank()) {
            Map<String, String> transform = parseStringMap(postTransformJson, "postTransform");
            Map<String, Object> transformCtx = new LinkedHashMap<>(feelCtx);
            transformCtx.put("_resp", parsed);
            Map<String, Object> transformed = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : transform.entrySet()) {
                try {
                    transformed.put(e.getKey(), feel.eval(e.getValue(), transformCtx));
                } catch (RuntimeException ex) {
                    throw new BpmnError("POST_TRANSFORM_FAILED",
                            "Post-transform `" + e.getKey() + "` failed: " + ex.getMessage());
                }
            }
            finalValue = transformed;
        }

        execution.setVariable(outputBinding, finalValue);
    }

    private Object parseToolBody(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception ex) {
            // Tools sometimes return plain text — preserve as string under "raw"
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("raw", body);
            return wrapper;
        }
    }

    private Map<String, String> parseStringMap(String json, String paramName) {
        try {
            return objectMapper.readValue(json, STR_MAP);
        } catch (Exception ex) {
            throw new BpmnError("BAD_VARIABLE",
                    paramName + " must be a JSON object of {string: string}: " + ex.getMessage());
        }
    }

}
