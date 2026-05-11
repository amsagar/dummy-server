package com.pods.agent.workflow.proposal;

import com.pods.agent.workflow.api.dto.ProcessDefDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowProposalServiceTest {

    private final WorkflowProposalService service =
            new WorkflowProposalService(null, null, null, null, null, new ObjectMapper());

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
        Assertions.assertFalse(service.validateGenericWorkflow(dto, "validate order 123456"));
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
        ProcessDefDto dto = service.parseProcessDefDtoFlexible(bpmn);
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
        ProcessDefDto dto = service.parseProcessDefDtoFlexible(canonical);
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
        Assertions.assertTrue(service.validateGenericWorkflow(dto, "validate order 123456"));
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
        Assertions.assertThrows(IllegalStateException.class, () -> service.assertWorkflowStructure(dto));
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
        Assertions.assertThrows(IllegalStateException.class, () -> service.assertWorkflowStructure(dto));
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
        Assertions.assertThrows(IllegalStateException.class, () -> service.assertWorkflowStructure(dto));
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

        ProcessDefDto dto = service.parseProcessDefDtoFlexible(llmJson);
        Assertions.assertEquals("ON_SUCCESS", dto.transitions().get(0).trigger());
        Assertions.assertEquals("ON_NO_MATCH", dto.transitions().get(2).trigger());
        Assertions.assertDoesNotThrow(() -> service.assertWorkflowStructure(dto));
    }
}
