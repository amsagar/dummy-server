package com.pods.agent.ruledomain.runtime;

import com.pods.agent.ruledomain.model.RuleDomain;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link BpmnRuntime#shapeOutputs}'s contract: it must NEVER return
 * the raw process-variable dump (which used to leak {@code order},
 * {@code line}, {@code legLines}, etc. on every test run). The shape rules:
 *
 * <ul>
 *   <li>Map → returned as-is.</li>
 *   <li>List / scalar → wrapped under {@code domain.resultKey} (or
 *       {@code "result"} when the domain has none).</li>
 *   <li>{@code null} → empty map. The caller's {@code failed} flag
 *       distinguishes "BPMN didn't write result" from "BPMN explicitly
 *       returned empty".</li>
 * </ul>
 */
class BpmnRuntimeOutputShapeTest {

    @Test
    void mapResultIsReturnedAsIs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", "600030447");

        Map<String, Object> out = BpmnRuntime.shapeOutputs(result, domain("anything"));

        assertEquals(2, out.size());
        assertEquals(true, out.get("success"));
        assertEquals("600030447", out.get("orderId"));
    }

    @Test
    void listResultIsWrappedUnderDomainResultKey() {
        List<Map<String, Object>> result = List.of(
                Map.of("legId", 1, "ok", true),
                Map.of("legId", 2, "ok", false));

        Map<String, Object> out = BpmnRuntime.shapeOutputs(result, domain("serviceability"));

        assertEquals(1, out.size());
        assertEquals(result, out.get("serviceability"));
    }

    @Test
    void listResultWithoutDomainResultKeyFallsBackToResultKey() {
        List<String> result = List.of("a", "b");

        Map<String, Object> out = BpmnRuntime.shapeOutputs(result, domain(null));

        assertEquals(1, out.size());
        assertEquals(result, out.get("result"));
    }

    @Test
    void scalarResultIsWrappedUnderDomainResultKey() {
        Map<String, Object> out = BpmnRuntime.shapeOutputs(42, domain("count"));

        assertEquals(1, out.size());
        assertEquals(42, out.get("count"));
    }

    @Test
    void blankDomainResultKeyFallsBackToResult() {
        Map<String, Object> out = BpmnRuntime.shapeOutputs(List.of("x"), domain("   "));

        assertTrue(out.containsKey("result"));
    }

    @Test
    void nullResultReturnsEmptyMap() {
        Map<String, Object> out = BpmnRuntime.shapeOutputs(null, domain("anything"));

        assertTrue(out.isEmpty());
    }

    @Test
    void nullDomainStillSafelyWrapsScalar() {
        Map<String, Object> out = BpmnRuntime.shapeOutputs("hello", null);

        assertEquals(1, out.size());
        assertEquals("hello", out.get("result"));
    }

    private static RuleDomain domain(String resultKey) {
        RuleDomain d = new RuleDomain();
        d.setResultKey(resultKey);
        return d;
    }
}
