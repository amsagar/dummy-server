package com.pods.agent.workflow.proposal;

import com.pods.agent.workflow.api.dto.ProcessDefDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Coverage for the parse + validation utility extracted from the old
 * {@code WorkflowProposalService}. Naming kept to match the original test
 * file so existing CI history continues to track the same assertions.
 */
class WorkflowProposalServiceTest {

    private final WorkflowJsonValidator validator = new WorkflowJsonValidator(new ObjectMapper());

    @Test
    void validateGenericWorkflowRejectsHardcodedRunLiteral() {
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Validate Order Workflow",
                "1",
                null,
                "test",
                List.of(),
                List.of(
                        new ProcessDefDto.ActivityDto(
                                "start", "Start", "route", null, Map.of(), Map.of(), Map.of(), null,
                                true, false, null, Map.of(), Map.of(), List.of(), false, null
                        ),
                        new ProcessDefDto.ActivityDto(
                                "tool1", "Call Tool", "tool", "AgentToolPlugin",
                                Map.of("toolName", "validate_order", "input", "{\"orderId\":\"123456\"}"),
                                Map.of(), Map.of(), null, false, true, null, Map.of(), Map.of(), List.of(), false, null
                        )
                ),
                List.of(new ProcessDefDto.TransitionDto("t1", "start", "tool1", null, false, null, "ON_SUCCESS", null, false))
        );
        Assertions.assertFalse(validator.validateGenericWorkflow(dto, "validate order 123456"));
    }

    @Test
    void parseProcessDefDtoFlexibleNormalizesBpmnTypes() throws Exception {
        String bpmn = """
                {
                  "id": null,
                  "name": "Generated From BPMN",
                  "version": "1",
                  "packageId": null,
                  "description": "test",
                  "variables": [],
                  "activities": [
                    { "id": "a-start", "name": "Start", "type": "startEvent",      "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "a-call",  "name": "Call",  "type": "serviceTask",     "pluginName": "AgentToolPlugin", "properties": {"toolName":"x"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "a-route", "name": "Decide","type": "exclusiveGateway","pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "a-end",   "name": "End",   "type": "endEvent",        "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
                  ],
                  "transitions": [
                    { "id": "t1", "fromActivityId": "a-start", "toActivityId": "a-call",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger":"success", "priority": null, "isDefault": false },
                    { "id": "t2", "fromActivityId": "a-call",  "toActivityId": "a-route", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger":"ON_SUCCESS", "priority": null, "isDefault": false },
                    { "id": "t3", "fromActivityId": "a-route", "toActivityId": "a-end",   "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger":"on_no_match", "priority": null, "isDefault": true }
                  ]
                }
                """;
        ProcessDefDto dto = validator.parseProcessDefDtoFlexible(bpmn);
        Map<String, ProcessDefDto.ActivityDto> byId = new java.util.HashMap<>();
        for (ProcessDefDto.ActivityDto a : dto.activities()) byId.put(a.id(), a);
        Assertions.assertEquals("route", byId.get("a-start").type());
        Assertions.assertEquals(Boolean.TRUE, byId.get("a-start").isStart());
        Assertions.assertEquals("tool",  byId.get("a-call").type());
        Assertions.assertEquals("route", byId.get("a-route").type());
        Assertions.assertEquals("route", byId.get("a-end").type());
        Assertions.assertEquals(Boolean.TRUE, byId.get("a-end").isEnd());
        Assertions.assertEquals("ON_SUCCESS", dto.transitions().get(0).trigger());
        Assertions.assertEquals("ON_NO_MATCH", dto.transitions().get(2).trigger());
    }

    @Test
    void parseProcessDefDtoFlexibleLeavesCanonicalTypesUntouched() throws Exception {
        String canonical = """
                {
                  "id": null,
                  "name": "Canonical",
                  "version": "1",
                  "packageId": null,
                  "description": "test",
                  "variables": [],
                  "activities": [
                    { "id": "s", "name": "S", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": true,  "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "t", "name": "T", "type": "tool",  "pluginName": "AgentToolPlugin", "properties": {"toolName":"x"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "e", "name": "E", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": true,  "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
                  ],
                  "transitions": [
                    { "id": "t1", "fromActivityId": "s", "toActivityId": "t", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger":"ON_SUCCESS", "priority":null, "isDefault":false },
                    { "id": "t2", "fromActivityId": "t", "toActivityId": "e", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger":"ON_SUCCESS", "priority":null, "isDefault":false }
                  ]
                }
                """;
        ProcessDefDto dto = validator.parseProcessDefDtoFlexible(canonical);
        for (ProcessDefDto.ActivityDto a : dto.activities()) {
            Assertions.assertTrue(
                    Set.of("normal", "tool", "route", "subflow", "foreach", "while", "batch").contains(a.type()),
                    "Unexpected type: " + a.type());
        }
    }

    @Test
    void validateGenericWorkflowAcceptsParameterizedArtifact() {
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Validate Order Workflow",
                "1",
                null,
                "test",
                List.of(new ProcessDefDto.VariableSpecDto("orderId", "java.lang.String", null, true)),
                List.of(
                        new ProcessDefDto.ActivityDto(
                                "start", "Start", "route", null, Map.of(), Map.of(), Map.of(), null,
                                true, false, null, Map.of(), Map.of(), List.of(), false, null
                        ),
                        new ProcessDefDto.ActivityDto(
                                "tool1", "Call Tool", "tool", "AgentToolPlugin",
                                Map.of("toolName", "validate_order", "input", "#{#orderId}"),
                                Map.of(), Map.of(), null, false, true, null, Map.of(), Map.of(), List.of(), false, null
                        )
                ),
                List.of(new ProcessDefDto.TransitionDto("t1", "start", "tool1", null, false, null, "ON_SUCCESS", null, false))
        );
        Assertions.assertTrue(validator.validateGenericWorkflow(dto, "validate order 123456"));
    }

    @Test
    void assertWorkflowStructureRejectsRouteDecisionPlugin() {
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Bad Route",
                "1",
                null,
                "test",
                List.of(),
                List.of(
                        new ProcessDefDto.ActivityDto(
                                "start", "Start", "route", "SomeDecisionPlugin", Map.of(), Map.of(), Map.of(),
                                null, true, false, null, Map.of(), Map.of(), List.of(), false, null
                        ),
                        new ProcessDefDto.ActivityDto(
                                "end", "End", "route", null, Map.of(), Map.of(), Map.of(),
                                null, false, true, null, Map.of(), Map.of(), List.of(), false, null
                        )
                ),
                List.of(new ProcessDefDto.TransitionDto("t1", "start", "end", null, false, null, "ON_SUCCESS", null, false))
        );
        Assertions.assertThrows(IllegalStateException.class, () -> validator.assertWorkflowStructure(dto));
    }

    @Test
    void assertWorkflowStructureRejectsMissingLoopGuard() {
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Loop Missing Guard",
                "1",
                null,
                "test",
                List.of(),
                List.of(
                        new ProcessDefDto.ActivityDto(
                                "start", "Start", "route", null, Map.of(), Map.of(), Map.of(),
                                null, true, false, null, Map.of(), Map.of(), List.of(), false, null
                        ),
                        new ProcessDefDto.ActivityDto(
                                "loop", "Loop", "foreach", null, Map.of("collection", "#{#items}"), Map.of(), Map.of(),
                                null, false, false, null, Map.of(), Map.of(), List.of(), false, null
                        ),
                        new ProcessDefDto.ActivityDto(
                                "end", "End", "route", null, Map.of(), Map.of(), Map.of(),
                                null, false, true, null, Map.of(), Map.of(), List.of(), false, null
                        )
                ),
                List.of(
                        new ProcessDefDto.TransitionDto("t1", "start", "loop", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t2", "loop", "end", null, false, null, "ON_NO_MATCH", null, true)
                )
        );
        Assertions.assertThrows(IllegalStateException.class, () -> validator.assertWorkflowStructure(dto));
    }

    @Test
    void assertWorkflowStructureRejectsMissingTransitionTrigger() {
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Missing Trigger",
                "1",
                null,
                "test",
                List.of(),
                List.of(
                        new ProcessDefDto.ActivityDto(
                                "start", "Start", "route", null, Map.of(), Map.of(), Map.of(),
                                null, true, false, null, Map.of(), Map.of(), List.of(), false, null
                        ),
                        new ProcessDefDto.ActivityDto(
                                "end", "End", "route", null, Map.of(), Map.of(), Map.of(),
                                null, false, true, null, Map.of(), Map.of(), List.of(), false, null
                        )
                ),
                List.of(new ProcessDefDto.TransitionDto("t1", "start", "end", null, false, null, null, null, false))
        );
        Assertions.assertThrows(IllegalStateException.class, () -> validator.assertWorkflowStructure(dto));
    }

    @Test
    void enumerationAntipatternFlagsThreeTimesSameToolWithVaryingValues() {
        ProcessDefDto dto = enumerationDto(3, "getProductById", "id");
        WorkflowJsonValidator.ValidationReport report =
                validator.validate(serialize(dto), "Get all products and get each of details by there ID");

        Assertions.assertFalse(report.ok(), "validator must reject the 3-copy enumeration");
        boolean hasCode = report.errors().stream()
                .anyMatch(e -> "enumeration_antipattern".equals(e.code()));
        Assertions.assertTrue(hasCode,
                "expected enumeration_antipattern in: " + report.errors());
    }

    @Test
    void enumerationAntipatternToleratesTwoCopiesUnderThreshold() {
        ProcessDefDto dto = enumerationDto(2, "getProductById", "id");
        WorkflowJsonValidator.ValidationReport report =
                validator.validate(serialize(dto), "fetch two products");

        boolean hasCode = report.errors().stream()
                .anyMatch(e -> "enumeration_antipattern".equals(e.code()));
        Assertions.assertFalse(hasCode,
                "two enumerated calls is a manual fan-out, not the antipattern; got: " + report.errors());
    }

    @Test
    void enumerationAntipatternIgnoresActivitiesWithDifferentInputKeys() {
        // Same tool name, but the inputs have different shapes — that's a
        // legitimate non-loop fan-out (e.g., calling the same admin tool
        // with three structurally distinct payloads), not enumeration.
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Mixed Calls",
                "1",
                null,
                "test",
                List.of(),
                List.of(
                        routeStart(),
                        toolActivity("call_a", "adminTool", "{\"id\":1}"),
                        toolActivity("call_b", "adminTool", "{\"name\":\"x\"}"),
                        toolActivity("call_c", "adminTool", "{\"region\":\"us\"}"),
                        routeEnd()
                ),
                List.of(
                        new ProcessDefDto.TransitionDto("t1", "start",  "call_a", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t2", "call_a", "call_b", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t3", "call_b", "call_c", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t4", "call_c", "end",    null, false, null, "ON_SUCCESS", null, false)
                )
        );
        WorkflowJsonValidator.ValidationReport report = validator.validate(serialize(dto), "");

        boolean hasCode = report.errors().stream()
                .anyMatch(e -> "enumeration_antipattern".equals(e.code()));
        Assertions.assertFalse(hasCode,
                "differing input shapes must not trigger the antipattern: " + report.errors());
    }

    @Test
    void enumerationAntipatternIgnoresSpELParameterizedInputs() {
        // SpEL templates ("#{...}") are already parameterized; a workflow
        // that legitimately calls the same tool 3+ times with different
        // expressions must NOT be flagged.
        ProcessDefDto dto = new ProcessDefDto(
                null,
                "Templated Calls",
                "1",
                null,
                "test",
                List.of(new ProcessDefDto.VariableSpecDto("a", "java.lang.String", null, false),
                        new ProcessDefDto.VariableSpecDto("b", "java.lang.String", null, false),
                        new ProcessDefDto.VariableSpecDto("c", "java.lang.String", null, false)),
                List.of(
                        routeStart(),
                        toolActivity("call_a", "fetchById", "#{#a}"),
                        toolActivity("call_b", "fetchById", "#{#b}"),
                        toolActivity("call_c", "fetchById", "#{#c}"),
                        routeEnd()
                ),
                List.of(
                        new ProcessDefDto.TransitionDto("t1", "start",  "call_a", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t2", "call_a", "call_b", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t3", "call_b", "call_c", null, false, null, "ON_SUCCESS", null, false),
                        new ProcessDefDto.TransitionDto("t4", "call_c", "end",    null, false, null, "ON_SUCCESS", null, false)
                )
        );
        WorkflowJsonValidator.ValidationReport report = validator.validate(serialize(dto), "");

        boolean hasCode = report.errors().stream()
                .anyMatch(e -> "enumeration_antipattern".equals(e.code()));
        Assertions.assertFalse(hasCode,
                "SpEL-templated inputs are already parameterized — no antipattern: " + report.errors());
    }

    @Test
    void enumerationAntipatternIgnoresLoopBodyWithSingleToolActivity() {
        // The canonical fix: ONE foreach with a SINGLE tool body — even if
        // the runtime executes it many times — passes validation cleanly.
        String foreachJson = """
                {
                  "id": null,
                  "name": "Retrieve All Products And Details",
                  "version": "1",
                  "packageId": null,
                  "description": "test",
                  "variables": [
                    { "name": "items", "javaClass": "java.util.List", "defaultExpression": null, "required": true }
                  ],
                  "activities": [
                    { "id": "start",    "name": "Start",     "type": "route", "pluginName": null,              "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": true,  "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "iterate",  "name": "For each",  "type": "batch", "pluginName": null,              "properties": { "collection": "#{#items}", "batchSize": 10, "maxIterations": 1000 }, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "fetchOne", "name": "Fetch one", "type": "tool",  "pluginName": "AgentToolPlugin", "properties": {"toolName":"getProductById","input":"#{#currentItem.id}"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "endNode",  "name": "End",       "type": "route", "pluginName": null,              "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": true,  "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
                  ],
                  "transitions": [
                    { "id": "t1", "fromActivityId": "start",    "toActivityId": "iterate",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS",  "priority": 1,   "isDefault": false },
                    { "id": "t2", "fromActivityId": "iterate",  "toActivityId": "fetchOne", "condition": "#__loop_continue_iterate == true", "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS",  "priority": 1,   "isDefault": false },
                    { "id": "t3", "fromActivityId": "iterate",  "toActivityId": "endNode",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_NO_MATCH", "priority": 100, "isDefault": true  },
                    { "id": "t4", "fromActivityId": "fetchOne", "toActivityId": "iterate",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS",  "priority": 1,   "isDefault": false }
                  ]
                }
                """;
        WorkflowJsonValidator.ValidationReport report = validator.validate(foreachJson, "");
        boolean hasCode = report.errors().stream()
                .anyMatch(e -> "enumeration_antipattern".equals(e.code()));
        Assertions.assertFalse(hasCode,
                "single foreach body with one tool activity is the canonical pattern, not the antipattern: "
                        + report.errors());
    }

    @Test
    void parseAndValidateListProcessingWorkflowWithLoopGuards() throws Exception {
        String llmJson = """
                {
                  "id": null,
                  "name": "List Processing Workflow",
                  "version": "1",
                  "packageId": null,
                  "description": "process items in batches",
                  "variables": [
                    { "name": "items", "javaClass": "java.util.List", "defaultExpression": null, "required": true }
                  ],
                  "activities": [
                    { "id": "start", "name": "Start", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": true, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                    { "id": "batchLoop", "name": "Batch Loop", "type": "batch", "pluginName": null, "properties": { "collection": "#{#items}", "batchSize": 10, "maxIterations": 50 }, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": { "retryCount": 0, "backoffMs": 0, "timeoutMs": null, "failFast": false, "continueOnError": false } },
                    { "id": "handleBatch", "name": "Handle Batch", "type": "tool", "pluginName": "AgentToolPlugin", "properties": { "toolName": "process_batch", "input": "#{#batchItems}" }, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": { "retryCount": 1, "backoffMs": 100, "timeoutMs": 10000, "failFast": false, "continueOnError": false } },
                    { "id": "endNode", "name": "End", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": true, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
                  ],
                  "transitions": [
                    { "id": "t1", "fromActivityId": "start", "toActivityId": "batchLoop", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "on success", "priority": 1, "isDefault": false },
                    { "id": "t2", "fromActivityId": "batchLoop", "toActivityId": "handleBatch", "condition": "#__loop_continue_batchLoop == true", "isErrorEdge": false, "matchesErrorClass": null, "trigger": "on_success", "priority": 1, "isDefault": false },
                    { "id": "t3", "fromActivityId": "batchLoop", "toActivityId": "endNode", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "no_match", "priority": 100, "isDefault": true },
                    { "id": "t4", "fromActivityId": "handleBatch", "toActivityId": "batchLoop", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false }
                  ]
                }
                """;

        ProcessDefDto dto = validator.parseProcessDefDtoFlexible(llmJson);
        Assertions.assertEquals("ON_SUCCESS", dto.transitions().get(0).trigger());
        Assertions.assertEquals("ON_NO_MATCH", dto.transitions().get(2).trigger());
        Assertions.assertDoesNotThrow(() -> validator.assertWorkflowStructure(dto));
    }

    // --- helpers for the enumeration-antipattern tests ---------------------

    /**
     * Build a process def with N tool activities all calling the same tool
     * name with hardcoded inputs differing only by {@code keyName}'s value.
     * Used to trip / test the {@code enumeration_antipattern} validator.
     */
    private ProcessDefDto enumerationDto(int copies, String toolName, String keyName) {
        List<ProcessDefDto.ActivityDto> activities = new ArrayList<>();
        List<ProcessDefDto.TransitionDto> transitions = new ArrayList<>();
        activities.add(routeStart());
        String prev = "start";
        for (int i = 1; i <= copies; i++) {
            String id = "call_" + toolName + "_" + i;
            String input = "{\"" + keyName + "\":" + i + "}";
            activities.add(toolActivity(id, toolName, input));
            transitions.add(new ProcessDefDto.TransitionDto(
                    "t" + i, prev, id, null, false, null, "ON_SUCCESS", null, false));
            prev = id;
        }
        activities.add(routeEnd());
        transitions.add(new ProcessDefDto.TransitionDto(
                "tEnd", prev, "end", null, false, null, "ON_SUCCESS", null, false));
        return new ProcessDefDto(
                null, "Enumerated " + toolName + " x" + copies, "1", null, "test",
                List.of(), activities, transitions);
    }

    private ProcessDefDto.ActivityDto routeStart() {
        return new ProcessDefDto.ActivityDto(
                "start", "Start", "route", null, Map.of(), Map.of(), Map.of(),
                null, true, false, null, Map.of(), Map.of(), List.of(), false, null);
    }

    private ProcessDefDto.ActivityDto routeEnd() {
        return new ProcessDefDto.ActivityDto(
                "end", "End", "route", null, Map.of(), Map.of(), Map.of(),
                null, false, true, null, Map.of(), Map.of(), List.of(), false, null);
    }

    private ProcessDefDto.ActivityDto toolActivity(String id, String toolName, String input) {
        return new ProcessDefDto.ActivityDto(
                id, id, "tool", "AgentToolPlugin",
                Map.of("toolName", toolName, "input", input),
                Map.of(), Map.of(), null, false, false, null, Map.of(), Map.of(),
                List.of(), false, null);
    }

    private String serialize(ProcessDefDto dto) {
        try {
            return new ObjectMapper().writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
