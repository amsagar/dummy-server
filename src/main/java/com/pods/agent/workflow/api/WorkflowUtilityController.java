package com.pods.agent.workflow.api;

import com.pods.agent.service.codeexec.CodeExecutionResult;
import com.pods.agent.service.codeexec.CodeExecutionService;
import com.pods.agent.workflow.joget.expression.SecureSpelEvaluator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parity helper endpoints used by the workflow-first UI.
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowUtilityController {

    private final CodeExecutionService codeExecutionService;

    public WorkflowUtilityController(CodeExecutionService codeExecutionService) {
        this.codeExecutionService = codeExecutionService;
    }

    @PostMapping("/expressions/validate")
    public ResponseEntity<Map<String, Object>> validateExpression(@RequestBody(required = false) Map<String, Object> body) {
        String expression = body == null ? "" : String.valueOf(body.getOrDefault("expression", ""));
        Map<String, Object> bindings = extractBindings(body);
        SecureSpelEvaluator.Result r = SecureSpelEvaluator.evaluate(expression, bindings);
        return ResponseEntity.ok(Map.of(
                "valid", r.ok(),
                "error", r.error(),
                "value", r.value()
        ));
    }

    @PostMapping("/mappings/test")
    public ResponseEntity<Map<String, Object>> testMapping(@RequestBody(required = false) Map<String, Object> body) {
        String expression = body == null ? "" : String.valueOf(body.getOrDefault("expression", ""));
        Map<String, Object> bindings = extractBindings(body);
        SecureSpelEvaluator.Result r = SecureSpelEvaluator.evaluate(expression, bindings);
        return ResponseEntity.ok(Map.of(
                "ok", r.ok(),
                "error", r.error(),
                "result", r.value()
        ));
    }

    @PostMapping("/code/preview")
    public ResponseEntity<Map<String, Object>> previewCode(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        String language = String.valueOf(body.getOrDefault("language", ""));
        String code = String.valueOf(body.getOrDefault("code", ""));
        Map<String, Object> input = toStringKeyMap(body.get("input"));
        Long timeoutMs = body.get("timeoutMs") instanceof Number n ? n.longValue() : null;
        Integer memoryLimitMb = body.get("memoryLimitMb") instanceof Number n ? n.intValue() : null;
        CodeExecutionResult result = codeExecutionService.execute(language, code, input, timeoutMs, memoryLimitMb);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        payload.put("output", result.output());
        payload.put("stdout", result.stdout());
        payload.put("stderr", result.stderr());
        payload.put("error", result.error());
        payload.put("timedOut", result.timedOut());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/runs/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable("id") String id) {
        return ResponseEntity.status(409).body(Map.of(
                "ok", false,
                "instanceId", id,
                "error", "Workflow manual approvals are not enabled yet for this process."
        ));
    }

    @PostMapping("/runs/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable("id") String id) {
        return ResponseEntity.status(409).body(Map.of(
                "ok", false,
                "instanceId", id,
                "error", "Workflow manual approvals are not enabled yet for this process."
        ));
    }

    private static Map<String, Object> extractBindings(Map<String, Object> body) {
        if (body == null) return Map.of();
        return toStringKeyMap(body.get("bindings"));
    }

    private static Map<String, Object> toStringKeyMap(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
