package com.pods.agent.agent.tool;

import com.pods.agent.agent.SseEventSender;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.GuardrailPolicyEngine;
import com.pods.agent.service.PendingInteractionService;
import com.pods.agent.service.ToolExecutionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code runtime_events.payload} wire shape produced by
 * {@link AgentToolCallback} for {@code tool.call} and {@code tool.done}. The
 * compiler validators (see {@code AgenticTraceCompiler.validateArgTemplateKeys})
 * gate on {@code step.input().isObject()}; if this contract regresses the
 * validator silently passes and bad BPMNs ship to runtime.
 */
class AgentToolCallbackTraceShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolCallInputIsPersistedAsParsedJsonObject() throws Exception {
        List<RuntimeEvent> saved = invokeAndCaptureEvents(
                "{\"ORD_ID\":\"600030447\"}",
                new ToolExecutionService.ExecutionResult(true, "{\"OrderIdentity\":\"x\"}", null));

        RuntimeEvent call = findFirst(saved, "tool.call");
        JsonNode payload = mapper.readTree(call.getPayload());
        JsonNode input = payload.get("input");

        assertTrue(input.isObject(),
                "tool.call.input must be a JSON object, not a string. actual=" + input);
        assertEquals("600030447", input.get("ORD_ID").asString());
    }

    @Test
    void toolDoneOutputIsPersistedAsParsedJsonObjectWhenBodyIsJson() throws Exception {
        List<RuntimeEvent> saved = invokeAndCaptureEvents(
                "{\"ORD_ID\":\"x\"}",
                new ToolExecutionService.ExecutionResult(true, "{\"OrderIdentity\":\"600030447\"}", null));

        RuntimeEvent done = findFirst(saved, "tool.done");
        JsonNode payload = mapper.readTree(done.getPayload());
        JsonNode output = payload.get("output");

        assertTrue(output.isObject(),
                "tool.done.output should be parsed JSON when the body parses as JSON. actual=" + output);
        assertEquals("600030447", output.get("OrderIdentity").asString());
        assertEquals("success", payload.get("status").asString());
    }

    @Test
    void toolDoneOutputFallsBackToTextNodeWhenBodyIsNotJson() throws Exception {
        List<RuntimeEvent> saved = invokeAndCaptureEvents(
                "{}",
                new ToolExecutionService.ExecutionResult(true, "plain text body, not JSON", null));

        RuntimeEvent done = findFirst(saved, "tool.done");
        JsonNode payload = mapper.readTree(done.getPayload());
        JsonNode output = payload.get("output");

        assertTrue(output.isTextual(),
                "non-JSON bodies must fall back to a TextNode. actual=" + output);
        assertEquals("plain text body, not JSON", output.asString());
    }

    @Test
    void toolCallInputFallsBackToTextNodeWhenInputIsNotJson() throws Exception {
        // The model can technically pass a bare string. We still want the
        // event to record it without throwing.
        List<RuntimeEvent> saved = invokeAndCaptureEvents(
                "bare-string-input",
                new ToolExecutionService.ExecutionResult(true, "{}", null));

        RuntimeEvent call = findFirst(saved, "tool.call");
        JsonNode payload = mapper.readTree(call.getPayload());
        JsonNode input = payload.get("input");

        assertTrue(input.isTextual(),
                "non-JSON inputs fall back to a TextNode rather than crashing. actual=" + input);
        assertEquals("bare-string-input", input.asString());
    }

    // ── helpers ─────────────────────────────────────────────────────

    private List<RuntimeEvent> invokeAndCaptureEvents(String jsonInput,
                                                       ToolExecutionService.ExecutionResult result) {
        AgentTool tool = AgentTool.builder().id("t1").name("Get_OrderID")
                .description("fetch order").enabled(true).build();

        ToolExecutionService executionService = mock(ToolExecutionService.class);
        when(executionService.execute(any(), any())).thenReturn(result);

        GuardrailPolicyEngine policyEngine = mock(GuardrailPolicyEngine.class);
        when(policyEngine.evaluateTool(any())).thenReturn(new GuardrailPolicyEngine.Decision("allow", ""));

        PendingInteractionService pending = mock(PendingInteractionService.class);
        SseEventSender sender = mock(SseEventSender.class);
        RuntimeEventRepository eventRepo = mock(RuntimeEventRepository.class);

        List<RuntimeEvent> captured = new ArrayList<>();
        ArgumentCaptor<RuntimeEvent> captor = ArgumentCaptor.forClass(RuntimeEvent.class);
        when(eventRepo.save(captor.capture())).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        // skill gate disabled so the tool actually executes
        SkillExecutionGate gate = new SkillExecutionGate(false);

        AgentToolCallback callback = new AgentToolCallback(
                tool,
                executionService,
                policyEngine,
                pending,
                sender,
                "session-1",
                "turn-1",
                null,
                1000L,
                mapper,
                eventRepo,
                gate);

        callback.call(jsonInput);
        return captured;
    }

    private static RuntimeEvent findFirst(List<RuntimeEvent> events, String type) {
        return events.stream()
                .filter(e -> type.equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " event saved; saw " + events));
    }
}
