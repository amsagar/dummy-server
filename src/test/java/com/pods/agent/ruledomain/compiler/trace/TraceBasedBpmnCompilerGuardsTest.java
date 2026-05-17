package com.pods.agent.ruledomain.compiler.trace;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceBasedBpmnCompilerGuardsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void safeBpmnXmlForPersistenceConvertsNullToEmptyString() {
        assertEquals("", TraceBasedBpmnCompiler.safeBpmnXmlForPersistence(null));
    }

    @Test
    void safeBpmnXmlForPersistenceKeepsNonNullXml() {
        String xml = "<definitions/>";
        assertEquals(xml, TraceBasedBpmnCompiler.safeBpmnXmlForPersistence(xml));
    }

    @Test
    void validateLiteralGroundingAllowsCanonicalPodsItemCodes() {
        ObjectNode output = mapper.createObjectNode();
        output.put("ItemCode", "IDEL");
        ExecutionTrace trace = traceWithOutput(output);

        String bpmn = """
                <definitions>
                  <serviceTask id="t_extract" flowable:delegateExpression="${feelExtractDelegate}">
                    <extensionElements>
                      <flowable:field name="feelExpr">
                        <flowable:string><![CDATA[order.Lines[list contains(["IDEL","RETSC","LDT","REDEL","FPU"], ItemCode)]]]></flowable:string>
                      </flowable:field>
                      <flowable:field name="outputBinding"><flowable:string>legLines</flowable:string></flowable:field>
                    </extensionElements>
                  </serviceTask>
                </definitions>
                """;

        assertNull(TraceBasedBpmnCompiler.validateLiteralGrounding(bpmn, trace));
    }

    @Test
    void validateLiteralGroundingStillRejectsHardcodedTraceData() {
        ObjectNode output = mapper.createObjectNode();
        output.put("PostalCode", "64157");
        ExecutionTrace trace = traceWithOutput(output);

        String bpmn = """
                <definitions>
                  <serviceTask id="t_call" flowable:delegateExpression="${toolCallDelegate}">
                    <extensionElements>
                      <flowable:field name="argTemplate">
                        <flowable:string><![CDATA[{"zip":"\\"64157\\""}]]></flowable:string>
                      </flowable:field>
                      <flowable:field name="outputBinding"><flowable:string>resp</flowable:string></flowable:field>
                    </extensionElements>
                  </serviceTask>
                </definitions>
                """;

        String error = TraceBasedBpmnCompiler.validateLiteralGrounding(bpmn, trace);
        assertNotNull(error);
        assertTrue(error.contains("64157"), error);
    }

    private static ExecutionTrace traceWithOutput(tools.jackson.databind.JsonNode output) {
        ExecutionTrace.TraceStep step = new ExecutionTrace.TraceStep(
                0, 0L, "tool", "Get_OrderID", null, output, "success", 10L, null);
        return new ExecutionTrace("turn-1", "session-1", "validate order", List.of(step));
    }
}
