package com.pods.agent.ruledomain.compiler.agentic;

import com.pods.agent.ruledomain.compiler.trace.ExecutionTrace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the post-compile validators in {@link AgenticTraceCompiler}.
 *
 * <p>These exist because the upstream regression — wrong {@code argTemplate}
 * keys for {@code Get_OrderID} ({@code "orderId"} instead of {@code "ORD_ID"})
 * — was caused by both the LLM emitting a wrong shape AND the validator
 * silently skipping when the recorded trace input was a JSON-encoded string
 * rather than a JSON object. Both branches are pinned here.
 */
class AgenticTraceCompilerValidatorsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── validateArgTemplateKeys ─────────────────────────────────────

    @Test
    void argTemplateKeysValidatorRejectsBpmnWithKeysAbsentFromTrace() {
        // Trace recorded input keyed by ORD_ID; BPMN argTemplate uses orderId.
        ObjectNode goodInput = mapper.createObjectNode();
        goodInput.put("ORD_ID", "600030447");
        ExecutionTrace trace = traceWithStep("Get_OrderID", goodInput);

        String bpmn = toolCallTaskXml("Get_OrderID", "{\"orderId\":\"orderId\"}");

        String error = AgenticTraceCompiler.validateArgTemplateKeys(bpmn, trace, mapper);

        assertNotNull(error, "validator should flag the wrong key");
        assertTrue(error.contains("Get_OrderID"), error);
        assertTrue(error.contains("orderId") || error.contains("[orderId]"), error);
        assertTrue(error.contains("ORD_ID"), error);
    }

    @Test
    void argTemplateKeysValidatorAcceptsBpmnWhenKeysMatchTrace() {
        ObjectNode goodInput = mapper.createObjectNode();
        goodInput.put("ORD_ID", "600030447");
        ExecutionTrace trace = traceWithStep("Get_OrderID", goodInput);

        String bpmn = toolCallTaskXml("Get_OrderID", "{\"ORD_ID\":\"orderId\"}");

        assertNull(AgenticTraceCompiler.validateArgTemplateKeys(bpmn, trace, mapper));
    }

    @Test
    void argTemplateKeysValidatorCoercesTextualInputBeforeDiffing() {
        // Simulates the historical wire format: tool.call payload's `input`
        // field is a TextNode whose value is a JSON-encoded string. Pre-fix
        // the validator skipped this and never caught the wrong-key bug.
        JsonNode textualInput = mapper.getNodeFactory().textNode("{\"ORD_ID\":\"600030447\"}");
        ExecutionTrace trace = traceWithStep("Get_OrderID", textualInput);

        String bpmn = toolCallTaskXml("Get_OrderID", "{\"orderId\":\"orderId\"}");

        String error = AgenticTraceCompiler.validateArgTemplateKeys(bpmn, trace, mapper);

        assertNotNull(error, "validator must parse textual JSON input and still flag the mismatch");
        assertTrue(error.contains("ORD_ID"), error);
    }

    @Test
    void argTemplateKeysValidatorSkipsCleanlyOnNonJsonTextualInput() {
        JsonNode textualInput = mapper.getNodeFactory().textNode("not json at all");
        ExecutionTrace trace = traceWithStep("Get_OrderID", textualInput);

        String bpmn = toolCallTaskXml("Get_OrderID", "{\"orderId\":\"orderId\"}");

        // No usable trace keys → validator must NOT fabricate a complaint.
        assertNull(AgenticTraceCompiler.validateArgTemplateKeys(bpmn, trace, mapper));
    }

    // ── validateNoMetaToolsInToolCall ──────────────────────────────

    @Test
    void metaToolValidatorRejectsParallelTask() {
        String bpmn = toolCallTaskXml("parallel_task", "{}");

        String error = AgenticTraceCompiler.validateNoMetaToolsInToolCall(bpmn);

        assertNotNull(error);
        assertTrue(error.contains("parallel_task"), error);
        assertTrue(error.contains("multiInstanceLoopCharacteristics"), error);
    }

    @Test
    void metaToolValidatorAcceptsRealToolNames() {
        String bpmn = toolCallTaskXml("Serviceability", "{}");

        assertNull(AgenticTraceCompiler.validateNoMetaToolsInToolCall(bpmn));
    }

    @Test
    void metaToolValidatorListsEveryOffender() {
        String bpmn = "<definitions>"
                + toolCallTaskXml("parallel_task", "{}")
                + toolCallTaskXml("pipeline", "{}")
                + toolCallTaskXml("Serviceability", "{}")
                + "</definitions>";

        String error = AgenticTraceCompiler.validateNoMetaToolsInToolCall(bpmn);

        assertNotNull(error);
        assertTrue(error.contains("parallel_task"), error);
        assertTrue(error.contains("pipeline"), error);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static ExecutionTrace traceWithStep(String toolName, JsonNode input) {
        ExecutionTrace.TraceStep step = new ExecutionTrace.TraceStep(
                0, 0L, "tool", toolName, input, null, "success", 100L, null);
        return new ExecutionTrace("turn-1", "session-1", "validate <orderId>", List.of(step));
    }

    private static String toolCallTaskXml(String toolName, String argTemplate) {
        return "<serviceTask id=\"t_test\" name=\"" + toolName + "\""
                + " flowable:delegateExpression=\"${toolCallDelegate}\">"
                + "<extensionElements>"
                + "<flowable:field name=\"toolName\"><flowable:string><![CDATA[" + toolName + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"argTemplate\"><flowable:string><![CDATA[" + argTemplate + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"outputBinding\"><flowable:string>out</flowable:string></flowable:field>"
                + "</extensionElements>"
                + "</serviceTask>";
    }
}
