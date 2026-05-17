package com.pods.agent.ruledomain.runtime;

import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.ruledomain.RuleDomainEventBus;
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
 *
 * <p><b>Timeout retries</b>: when {@link ToolExecutionService} returns an error
 * prefixed with {@code "TIMEOUT:"}, this delegate retries up to
 * {@link RuleDomainProperties.ToolRetry#getToolTimeoutMaxAttempts()} times with
 * backoff. Non-timeout errors fail fast — the LLM tool-loop can self-retry
 * those, and the BPMN's auto-injected boundary error event catches the
 * resulting {@code TOOL_EXECUTION_FAILED} for graceful process termination.
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
    private final RuleDomainProperties props;
    private final RuleDomainEventBus bus;

    public ToolCallDelegate(ToolRegistryService toolRegistry,
                            ToolExecutionService toolExecutor,
                            ObjectMapper objectMapper,
                            FeelHelper feel,
                            RuleDomainProperties props,
                            RuleDomainEventBus bus) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.feel = feel;
        this.props = props;
        this.bus = bus;
    }

    @Override
    public void execute(DelegateExecution execution) {
        ActivityEventStaging staging = ActivityEventStaging.start(execution, "toolCallDelegate");
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
        String toolName = BpmnFieldReader.required(execution, "toolName");
        String argTemplateJson = BpmnFieldReader.required(execution, "argTemplate");
        String outputBinding = BpmnFieldReader.required(execution, "outputBinding");
        String postTransformJson = BpmnFieldReader.optional(execution, "postTransform");

        String nodeId = execution.getCurrentActivityId();
        String turnId = stringVar(execution, "_turnId");

        AgentTool tool = toolRegistry.getEnabledToolByName(toolName);
        if (tool == null) {
            throw new BpmnError("TOOL_NOT_FOUND", "No enabled tool named: " + toolName);
        }

        Map<String, Object> feelCtx = BpmnVariables.readContext(execution);
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
        staging.input(payload);

        bus.emit("rule_domain.tool.call", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "nodeId", nodeId == null ? "" : nodeId,
                "toolName", toolName,
                "args", truncate(payload, 400)));
        log.debug("BPMN tool call: tool={} args={}", toolName, payload);

        ToolExecutionService.ExecutionResult result = executeWithTimeoutRetry(tool, payload, toolName, nodeId, turnId);

        bus.emit("rule_domain.tool.result", Map.of(
                "turnId", turnId == null ? "" : turnId,
                "nodeId", nodeId == null ? "" : nodeId,
                "toolName", toolName,
                "success", result.success(),
                "error", result.error() == null ? "" : result.error()));

        if (!result.success()) {
            // Record the failed tool name so the boundary-error path can read it
            // back into the orchestrator's advisory message on the fall-through.
            try { execution.setVariable("_failedTool", toolName); } catch (Exception ignored) {}
            throw new BpmnError("TOOL_EXECUTION_FAILED",
                    "Tool " + toolName + " failed: " + result.error());
        }

        Object parsed = parseToolBody(result.body());

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

        BpmnVariables.set(execution, outputBinding, finalValue);

        // Record what we bound for postmortem inspection.
        try {
            staging.output(objectMapper.writeValueAsString(finalValue));
        } catch (Exception ignored) {
            // serialization failure is non-fatal for activity logging
        }
    }

    private ToolExecutionService.ExecutionResult executeWithTimeoutRetry(
            AgentTool tool, String payload, String toolName, String nodeId, String turnId) {
        RuleDomainProperties.ToolRetry rp = props.getToolRetry();
        int maxAttempts = Math.max(1, rp == null ? 3 : rp.getToolTimeoutMaxAttempts());
        long[] backoff = rp == null || rp.getToolTimeoutBackoffMs() == null
                ? new long[]{0L, 2000L, 5000L}
                : rp.getToolTimeoutBackoffMs();

        ToolExecutionService.ExecutionResult result = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) {
                long delay = backoff.length == 0 ? 0L : backoff[Math.min(attempt, backoff.length - 1)];
                try { if (delay > 0) Thread.sleep(delay); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                bus.emit("rule_domain.tool.retry", Map.of(
                        "turnId", turnId == null ? "" : turnId,
                        "nodeId", nodeId == null ? "" : nodeId,
                        "toolName", toolName,
                        "attempt", attempt + 1,
                        "reason", result == null ? "" : (result.error() == null ? "" : result.error())));
                log.info("[ToolCallDelegate] retrying {} after timeout (attempt {}/{})",
                        toolName, attempt + 1, maxAttempts);
            }
            // Route through the turn-scoped cache. Within a single turn,
            // parallel rules requesting the same (tool, canonical-args) share
            // one in-flight call. When turnId is null or the cache is
            // disabled (test contexts / mutation tools), executeCached
            // transparently falls back to a direct execute.
            ToolExecutionService.CachedExecutionResult cached =
                    toolExecutor.executeCachedWithMeta(tool, payload, turnId);
            result = cached.result();
            if (cached.cacheHit()) {
                bus.emit("rule_domain.tool.cached", Map.of(
                        "turnId", turnId == null ? "" : turnId,
                        "nodeId", nodeId == null ? "" : nodeId,
                        "toolName", toolName));
            }
            if (result.success() || !isTimeoutError(result.error())) break;
        }
        return result;
    }

    private static boolean isTimeoutError(String error) {
        return error != null && error.startsWith("TIMEOUT:");
    }

    private static String stringVar(DelegateExecution execution, String name) {
        Object v = execution.getVariable(name);
        return v == null ? null : v.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private Object parseToolBody(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception ex) {
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
