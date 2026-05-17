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

    // ── validateBoundVariableReferences ────────────────────────────

    @Test
    void boundVarsValidatorRejectsOrderReferenceWithoutGetOrderId() {
        // No outputBinding=order anywhere — `order.OrderIdentity` is unbound.
        String bpmn = "<definitions>"
                + feelExtractTaskXml("t_assemble", "{orderId: order.OrderIdentity}", "result")
                + "</definitions>";

        String error = AgenticTraceCompiler.validateBoundVariableReferences(bpmn);

        assertNotNull(error);
        assertTrue(error.contains("'order'"), error);
        assertTrue(error.contains("Get_OrderID"), error);
    }

    @Test
    void boundVarsValidatorAcceptsOrderReferenceWhenUpstreamBindsIt() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("Get_OrderID", "{\"ORD_ID\":\"orderId\"}", "order")
                + feelExtractTaskXml("t_assemble", "{orderId: order.OrderIdentity}", "result")
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateBoundVariableReferences(bpmn));
    }

    @Test
    void boundVarsValidatorAcceptsOrchestratorSeeds() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("Get_OrderID", "{\"ORD_ID\":\"orderId\"}", "order")
                + "</definitions>";

        // `orderId` is a seed — referenced inside argTemplate, no binding needed.
        assertNull(AgenticTraceCompiler.validateBoundVariableReferences(bpmn));
    }

    @Test
    void boundVarsValidatorAllowsMultiInstanceElementVariable() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("Get_OrderID", "{\"ORD_ID\":\"orderId\"}", "order")
                + feelExtractTaskXml("t_legs", "order.Lines[ItemCode = \"IDEL\"]", "legLines")
                + "<subProcess id=\"sp_each_leg\">"
                + "<multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"${legLines}\" flowable:elementVariable=\"leg\"/>"
                + feelExtractTaskXml("t_leg_extract", "leg.ItemCode", "code")
                + "</subProcess>"
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateBoundVariableReferences(bpmn));
    }

    @Test
    void boundVarsValidatorAllowsRespInsidePostTransform() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithPostTransform("Serviceability",
                        "{\"OrigZip\":\"\\\"12345\\\"\"}",
                        "result",
                        "{code: _resp.Result.Code}")
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateBoundVariableReferences(bpmn));
    }

    @Test
    void boundVarsValidatorAllowsForLoopLocalsInFeel() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("Get_OrderID", "{\"ORD_ID\":\"orderId\"}", "order")
                + feelExtractTaskXml(
                        "t_seq",
                        "for l in sort(order.Lines, function(a,b) a.Sequence < b.Sequence)"
                                + " return l.ServiceCode",
                        "sequence")
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateBoundVariableReferences(bpmn));
    }

    @Test
    void boundVarsValidatorAllowsAggregationTargetDownstream() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("Get_OrderID", "{\"ORD_ID\":\"orderId\"}", "order")
                + feelExtractTaskXml("t_legs", "order.Lines[ItemCode = \"IDEL\"]", "legLines")
                + "<subProcess id=\"sp\">"
                + "<multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"${legLines}\" flowable:elementVariable=\"leg\">"
                + "<flowable:variableAggregation target=\"agg\">"
                + "<variable source=\"code\"/>"
                + "</flowable:variableAggregation>"
                + "</multiInstanceLoopCharacteristics>"
                + feelExtractTaskXml("t_leg_extract", "leg.ItemCode", "code")
                + "</subProcess>"
                + feelExtractTaskXml("t_assemble", "{summary: agg.code}", "result")
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateBoundVariableReferences(bpmn));
    }

    // ── validateFeelLiteralSyntax ───────────────────────────────────

    @Test
    void feelLiteralValidatorFlagsSingleQuotedString() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("ContainerAvailability",
                        "{\"channel\":\"'Salesforce'\"}",
                        "containers")
                + "</definitions>";

        String error = AgenticTraceCompiler.validateFeelLiteralSyntax(bpmn);

        assertNotNull(error);
        assertTrue(error.contains("'Salesforce'"), error);
        assertTrue(error.contains("double quote"), error);
    }

    @Test
    void feelLiteralValidatorAcceptsEscapedDoubleQuotedString() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("ContainerAvailability",
                        "{\"channel\":\"\\\"Salesforce\\\"\"}",
                        "containers")
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateFeelLiteralSyntax(bpmn));
    }

    @Test
    void feelLiteralValidatorFlagsIdenticalBranchIf() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("ContainerAvailability",
                        "{\"customerType\":\"if order.AccountNumber != null"
                                + " then \\\"COMMERCIAL\\\" else \\\"COMMERCIAL\\\"\"}",
                        "containers")
                + "</definitions>";

        String error = AgenticTraceCompiler.validateFeelLiteralSyntax(bpmn);

        assertNotNull(error);
        assertTrue(error.contains("COMMERCIAL"), error);
        assertTrue(error.contains("branches"), error);
    }

    @Test
    void feelLiteralValidatorAcceptsDifferentiatedIfBranches() {
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("ContainerAvailability",
                        "{\"customerType\":\"if order.AccountNumber != null"
                                + " then \\\"COMMERCIAL\\\" else \\\"RESIDENTIAL\\\"\"}",
                        "containers")
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateFeelLiteralSyntax(bpmn));
    }

    // ── validateMultiInstanceCollectionFilter ───────────────────────

    @Test
    void miFilterValidatorFlagsBareOrderLines() {
        String bpmn = "<definitions>"
                + feelExtractTaskXml("t_legs", "order.Lines", "legLines")
                + "<subProcess id=\"sp\">"
                + "<multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"${legLines}\" flowable:elementVariable=\"leg\"/>"
                + "</subProcess>"
                + "</definitions>";

        String error = AgenticTraceCompiler.validateMultiInstanceCollectionFilter(bpmn);

        assertNotNull(error);
        assertTrue(error.contains("legLines"), error);
        assertTrue(error.contains("order.Lines"), error);
    }

    @Test
    void miFilterValidatorAcceptsFilteredCollection() {
        String bpmn = "<definitions>"
                + feelExtractTaskXml(
                        "t_legs",
                        "order.Lines[list contains([\"IDEL\",\"RETSC\",\"LDT\"], ItemCode)]",
                        "legLines")
                + "<subProcess id=\"sp\">"
                + "<multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"${legLines}\" flowable:elementVariable=\"leg\"/>"
                + "</subProcess>"
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateMultiInstanceCollectionFilter(bpmn));
    }

    @Test
    void miFilterValidatorIgnoresCollectionBoundByToolCall() {
        // When the collection variable is bound by a tool call (not a
        // feelExtractDelegate), the validator must say nothing — it can't
        // see the bare-Lines bug because there's no feelExpr to inspect.
        String bpmn = "<definitions>"
                + toolCallTaskXmlWithBinding("ListLegs", "{}", "legLines")
                + "<subProcess id=\"sp\">"
                + "<multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"${legLines}\" flowable:elementVariable=\"leg\"/>"
                + "</subProcess>"
                + "</definitions>";

        assertNull(AgenticTraceCompiler.validateMultiInstanceCollectionFilter(bpmn));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static ExecutionTrace traceWithStep(String toolName, JsonNode input) {
        ExecutionTrace.TraceStep step = new ExecutionTrace.TraceStep(
                0, 0L, "tool", toolName, input, null, "success", 100L, null);
        return new ExecutionTrace("turn-1", "session-1", "validate <orderId>", List.of(step));
    }

    private static String toolCallTaskXml(String toolName, String argTemplate) {
        return toolCallTaskXmlWithBinding(toolName, argTemplate, "out");
    }

    private static String toolCallTaskXmlWithBinding(String toolName, String argTemplate, String binding) {
        return "<serviceTask id=\"t_" + binding + "\" name=\"" + toolName + "\""
                + " flowable:delegateExpression=\"${toolCallDelegate}\">"
                + "<extensionElements>"
                + "<flowable:field name=\"toolName\"><flowable:string><![CDATA[" + toolName + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"argTemplate\"><flowable:string><![CDATA[" + argTemplate + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"outputBinding\"><flowable:string>" + binding + "</flowable:string></flowable:field>"
                + "</extensionElements>"
                + "</serviceTask>";
    }

    private static String toolCallTaskXmlWithPostTransform(String toolName,
                                                           String argTemplate,
                                                           String binding,
                                                           String postTransform) {
        return "<serviceTask id=\"t_" + binding + "\" name=\"" + toolName + "\""
                + " flowable:delegateExpression=\"${toolCallDelegate}\">"
                + "<extensionElements>"
                + "<flowable:field name=\"toolName\"><flowable:string><![CDATA[" + toolName + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"argTemplate\"><flowable:string><![CDATA[" + argTemplate + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"outputBinding\"><flowable:string>" + binding + "</flowable:string></flowable:field>"
                + "<flowable:field name=\"postTransform\"><flowable:string><![CDATA[" + postTransform + "]]></flowable:string></flowable:field>"
                + "</extensionElements>"
                + "</serviceTask>";
    }

    private static String feelExtractTaskXml(String id, String feelExpr, String binding) {
        return "<serviceTask id=\"" + id + "\""
                + " flowable:delegateExpression=\"${feelExtractDelegate}\">"
                + "<extensionElements>"
                + "<flowable:field name=\"feelExpr\"><flowable:string><![CDATA[" + feelExpr + "]]></flowable:string></flowable:field>"
                + "<flowable:field name=\"outputBinding\"><flowable:string>" + binding + "</flowable:string></flowable:field>"
                + "</extensionElements>"
                + "</serviceTask>";
    }
}
