package com.pods.agent.workflow.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pods.agent.config.WorkflowProposalProperties;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.workspace.ExecutionLogService;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import com.pods.agent.workflow.proposal.WorkflowAlignmentJudge.Verdict;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import tools.jackson.databind.ObjectMapper;

/**
 * Builder loop tests. The LLM agent invocation is replaced with a
 * deterministic fake via {@link WorkflowBuilderService#setAgentInvoker} so
 * we exercise the validation + retry + persistence machinery without
 * needing a live ChatClient. Each test asserts the proposal status
 * transitions and that the builder consumes its retry budget correctly.
 */
class WorkflowBuilderServiceTest {

    private static final String VALID_DRAFT = """
            {
              "id": null,
              "name": "Validate Order Leg Sequence",
              "version": "1",
              "packageId": null,
              "description": "Generated workflow",
              "variables": [
                { "name": "orderId", "javaClass": "java.lang.String", "defaultExpression": null, "required": true }
              ],
              "activities": [
                { "id": "start", "name": "Start", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": true, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "validate", "name": "Validate", "type": "tool", "pluginName": "AgentToolPlugin", "properties": {"toolName":"validate_order","input":"#{#orderId}"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "end", "name": "End", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": true, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
              ],
              "transitions": [
                { "id": "t1", "fromActivityId": "start", "toActivityId": "validate", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false },
                { "id": "t2", "fromActivityId": "validate", "toActivityId": "end", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false }
              ]
            }
            """;

    /** Same as VALID_DRAFT but with an invalid trigger string so structural validation fails. */
    private static final String STRUCTURALLY_INVALID_DRAFT =
            VALID_DRAFT.replace("\"ON_SUCCESS\"", "\"NOT_A_TRIGGER\"");

    /**
     * Reproduces the user's failing-run shape: N enumerated tool activities
     * with the same toolName + same input keys + varying values. Three is
     * the validator's enumeration threshold; this draft must hard-fail with
     * code {@code enumeration_antipattern}.
     */
    private static final String ENUMERATION_DRAFT_20 = """
            {
              "id": null,
              "name": "Retrieve All Products And Details",
              "version": "1",
              "packageId": null,
              "description": "naive enumeration",
              "variables": [],
              "activities": [
                { "id": "start", "name": "Start", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": true,  "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "call_getProductById_1", "name": "call_getProductById_1", "type": "tool", "pluginName": "AgentToolPlugin", "properties": {"toolName":"getProductById","input":"{\\"id\\":1}"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "call_getProductById_2", "name": "call_getProductById_2", "type": "tool", "pluginName": "AgentToolPlugin", "properties": {"toolName":"getProductById","input":"{\\"id\\":2}"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "call_getProductById_3", "name": "call_getProductById_3", "type": "tool", "pluginName": "AgentToolPlugin", "properties": {"toolName":"getProductById","input":"{\\"id\\":3}"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "end", "name": "End", "type": "route", "pluginName": null, "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": true,  "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
              ],
              "transitions": [
                { "id": "t1", "fromActivityId": "start",                  "toActivityId": "call_getProductById_1", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false },
                { "id": "t2", "fromActivityId": "call_getProductById_1", "toActivityId": "call_getProductById_2", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false },
                { "id": "t3", "fromActivityId": "call_getProductById_2", "toActivityId": "call_getProductById_3", "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false },
                { "id": "t4", "fromActivityId": "call_getProductById_3", "toActivityId": "end",                   "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS", "priority": 1, "isDefault": false }
              ]
            }
            """;

    /**
     * Generic loop-shaped replacement for {@link #ENUMERATION_DRAFT_20}: one
     * batch activity with a single tool body that reads its varying input
     * from a SpEL variable. Mirrors the foreach-accumulate template shape so
     * structural validation passes.
     */
    private static final String FOREACH_VALID_DRAFT = """
            {
              "id": null,
              "name": "Retrieve All Products And Details",
              "version": "1",
              "packageId": null,
              "description": "loop variant — one tool activity per call-site",
              "variables": [
                { "name": "items", "javaClass": "java.util.List", "defaultExpression": null, "required": true }
              ],
              "activities": [
                { "id": "start",      "name": "Start",      "type": "route", "pluginName": null,              "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": true,  "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "iterate",    "name": "For each",   "type": "batch", "pluginName": null,              "properties": { "collection": "#{#items}", "batchSize": 10, "maxIterations": 1000 }, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "fetchOne",   "name": "Fetch one",  "type": "tool",  "pluginName": "AgentToolPlugin", "properties": {"toolName":"getProductById","input":"#{#currentItem.id}"}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": false, "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null },
                { "id": "endNode",    "name": "End",        "type": "route", "pluginName": null,              "properties": {}, "inputSchema": {}, "outputSchema": {}, "deadlineExpression": null, "isStart": false, "isEnd": true,  "subflowDefId": null, "subflowInputs": {}, "subflowOutputs": {}, "outputVariables": [], "andJoin": false, "errorPolicy": null }
              ],
              "transitions": [
                { "id": "t1", "fromActivityId": "start",    "toActivityId": "iterate",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS",  "priority": 1,   "isDefault": false },
                { "id": "t2", "fromActivityId": "iterate",  "toActivityId": "fetchOne", "condition": "#__loop_continue_iterate == true", "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS",  "priority": 1,   "isDefault": false },
                { "id": "t3", "fromActivityId": "iterate",  "toActivityId": "endNode",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_NO_MATCH", "priority": 100, "isDefault": true  },
                { "id": "t4", "fromActivityId": "fetchOne", "toActivityId": "iterate",  "condition": null, "isErrorEdge": false, "matchesErrorClass": null, "trigger": "ON_SUCCESS",  "priority": 1,   "isDefault": false }
              ]
            }
            """;

    @Test
    void buildHappyPathPersistsProcessDefAndMarksMaterialized(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);
        WorkflowProposal proposal = fx.persistedPending();

        // Fake agent: write a valid draft on first invocation; never edit.
        AtomicInteger invocations = new AtomicInteger();
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            invocations.incrementAndGet();
            assertTrue(initial, "happy path should only need the initial attempt");
            Files.writeString(draft, VALID_DRAFT, StandardCharsets.UTF_8);
        });
        fx.judgeAligned();

        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("materialized", result.getStatus());
        assertEquals("def-saved", result.getMaterializedDefId());
        assertNotNull(result.getProposedWorkflowJson());
        assertNull(result.getErrorMessage());
        assertEquals(1, invocations.get());
        verify(fx.processDefService, times(1)).save(any());
        verify(fx.repo, atLeast(2)).update(any()); // building + materialized
    }

    @Test
    void buildRetriesViaEditAfterStructuralFailure(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);
        WorkflowProposal proposal = fx.persistedPending();

        AtomicInteger invocations = new AtomicInteger();
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                // Initial: write a structurally-broken draft.
                assertTrue(initial);
                Files.writeString(draft, STRUCTURALLY_INVALID_DRAFT, StandardCharsets.UTF_8);
            } else {
                // Retry: must NOT be marked initial; must carry feedback.
                assertTrue(!initial, "retry must not flag initial=true");
                assertNotNull(feedback);
                assertTrue(feedback.contains("transition_trigger_invalid"),
                        "feedback should name the failing validator code, was: " + feedback);
                // Simulate an in-place edit producing the valid draft.
                Files.writeString(draft, VALID_DRAFT, StandardCharsets.UTF_8);
            }
        });
        fx.judgeAligned();

        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("materialized", result.getStatus());
        assertEquals(2, invocations.get());
        verify(fx.repo, times(2)).incrementBuildAttempts(proposal.getId());
    }

    @Test
    void buildMarksFailedAfterExhaustingMaxAttempts(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);
        // Lower the attempt budget for a fast test.
        fx.properties.setMaxBuildAttempts(2);
        WorkflowProposal proposal = fx.persistedPending();

        AtomicInteger invocations = new AtomicInteger();
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            invocations.incrementAndGet();
            // Always emit broken JSON so structural validation never passes.
            Files.writeString(draft, STRUCTURALLY_INVALID_DRAFT, StandardCharsets.UTF_8);
        });
        // judge never called when structural always fails — no stub needed.

        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("failed", result.getStatus());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().startsWith("build_loop_exhausted:"),
                "expected error_message to begin with build_loop_exhausted, was: " + result.getErrorMessage());
        assertEquals(2, invocations.get());
        verify(fx.processDefService, never()).save(any());
        verify(fx.repo, times(2)).incrementBuildAttempts(proposal.getId());
    }

    @Test
    void buildRetriesAfterAlignmentRejection(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);
        WorkflowProposal proposal = fx.persistedPending();

        AtomicInteger invocations = new AtomicInteger();
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            int n = invocations.incrementAndGet();
            if (n == 2) {
                assertNotNull(feedback);
                assertTrue(feedback.contains("Alignment judge rejected"),
                        "expected alignment critique in resume feedback, was: " + feedback);
            }
            Files.writeString(draft, VALID_DRAFT, StandardCharsets.UTF_8);
        });

        // First call: misaligned. Second: aligned.
        when(fx.alignmentJudge.judge(anyString(), any(), any(), any(), any()))
                .thenReturn(new Verdict(false, "Skill X mandates extra step", "high"))
                .thenReturn(new Verdict(true, "", "low"));

        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("materialized", result.getStatus());
        assertEquals(2, invocations.get());
    }

    @Test
    void buildReusesSameChatMemoryAcrossAttemptsWithinOneBuild(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);
        WorkflowProposal proposal = fx.persistedPending();

        // Capture the ChatMemory instance handed to the agent on each
        // attempt; assert it's reference-identical across the loop so the
        // builder isn't allocating fresh memory per attempt (which would
        // discard the prior conversation).
        Set<ChatMemory> seenMemories = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        AtomicInteger invocations = new AtomicInteger();
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            int n = invocations.incrementAndGet();
            assertNotNull(memory, "ChatMemory must be passed to the agent invoker");
            seenMemories.add(memory);
            if (n == 1) {
                Files.writeString(draft, STRUCTURALLY_INVALID_DRAFT, StandardCharsets.UTF_8);
            } else {
                Files.writeString(draft, VALID_DRAFT, StandardCharsets.UTF_8);
            }
        });
        fx.judgeAligned();

        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("materialized", result.getStatus());
        assertEquals(2, invocations.get());
        assertEquals(1, seenMemories.size(),
                "expected the same ChatMemory instance to be reused across attempts within one build");
    }

    @Test
    void buildAllocatesFreshChatMemoryPerBuild(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);

        Set<ChatMemory> seenMemories = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            seenMemories.add(memory);
            Files.writeString(draft, VALID_DRAFT, StandardCharsets.UTF_8);
        });
        fx.judgeAligned();

        // Two independent build() calls => two distinct ChatMemory instances.
        fx.builder.build(fx.persistedPending("p-1"));
        fx.builder.build(fx.persistedPending("p-2"));

        assertEquals(2, seenMemories.size(),
                "each build() invocation must allocate its own ChatMemory so state never leaks across proposals");
    }

    @Test
    void buildFlipsEnumerationDraftToForeachAfterAntipatternFeedback(@TempDir Path workspace) throws IOException {
        Fixture fx = new Fixture(workspace);
        WorkflowProposal proposal = fx.persistedPending();

        AtomicInteger invocations = new AtomicInteger();
        Set<String> sawEnumerationFeedback = new HashSet<>();
        fx.builder.setAgentInvoker((p, ws, draft, log, model, allowlist, initial, feedback, memory) -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                // Initial: write the user's actual failing-run JSON shape — 20
                // enumerated `getProductById` activities. The validator must
                // hard-fail this with `enumeration_antipattern`.
                Files.writeString(draft, ENUMERATION_DRAFT_20, StandardCharsets.UTF_8);
            } else {
                // The retry feedback must include the enumeration_antipattern
                // code so the agent knows to switch to a foreach skeleton.
                assertNotNull(feedback);
                if (feedback.contains("enumeration_antipattern")) {
                    sawEnumerationFeedback.add(feedback);
                }
                Files.writeString(draft, FOREACH_VALID_DRAFT, StandardCharsets.UTF_8);
            }
        });
        fx.judgeAligned();

        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("materialized", result.getStatus(),
                "build should materialize after the agent flips to a foreach draft");
        assertTrue(!sawEnumerationFeedback.isEmpty(),
                "retry feedback must surface the enumeration_antipattern validator code");
        assertEquals(2, invocations.get());
    }

    @Test
    void buildFailsWhenWorkspaceUnresolvable() {
        Path workspace = Path.of(System.getProperty("java.io.tmpdir"), "pods-builder-test-" + System.nanoTime());
        Fixture fx = new Fixture(workspace);
        // Force workspaceService to return null AND fail to create.
        when(fx.sessionWorkspaceService.get(anyString())).thenReturn(null);
        when(fx.sessionWorkspaceService.getOrCreate(anyString()))
                .thenThrow(new IllegalStateException("simulated"));

        WorkflowProposal proposal = fx.persistedPending();
        WorkflowProposal result = fx.builder.build(proposal);

        assertEquals("failed", result.getStatus());
        assertEquals("session_workspace_unavailable", result.getErrorMessage());
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Encapsulates the mocked dependencies and a real
     * {@link WorkflowBuilderService} pointed at a temp workspace. Each
     * test mutates the agentInvoker / judge stub and asserts on the
     * captured state.
     */
    private static final class Fixture {
        final Path workspace;
        final WorkflowProposalRepository repo = mock(WorkflowProposalRepository.class);
        final ProcessDefService processDefService = mock(ProcessDefService.class);
        final SessionWorkspaceService sessionWorkspaceService = mock(SessionWorkspaceService.class);
        final ExecutionLogService executionLogService = mock(ExecutionLogService.class);
        final WorkflowAlignmentJudge alignmentJudge = mock(WorkflowAlignmentJudge.class);
        final SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        final ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        final WorkflowProposalProperties properties = new WorkflowProposalProperties();
        final WorkflowBuilderService builder;

        Fixture(Path workspace) {
            this.workspace = workspace;
            ObjectMapper mapper = new ObjectMapper();
            WorkflowJsonValidator validator = new WorkflowJsonValidator(mapper);
            // The builder asks the workspaceService for the workspace and the
            // ensureFile call for the draft path. Everything else (LLM,
            // alignment) is mocked.
            when(sessionWorkspaceService.get(anyString())).thenReturn(workspace);
            when(sessionWorkspaceService.getOrCreate(anyString())).thenReturn(workspace);
            when(sessionWorkspaceService.ensureFile(any(), anyString())).thenAnswer(inv -> {
                Path ws = inv.getArgument(0);
                String rel = inv.getArgument(1);
                Path target = ws.resolve(rel).normalize();
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                return target;
            });
            when(executionLogService.approvedProposalPath(anyString(), anyString())).thenAnswer(inv -> {
                String defId = inv.getArgument(1);
                Path target = workspace.resolve(".pods-agent/workflow/proposals/" + defId + ".json");
                Files.createDirectories(target.getParent());
                return target;
            });
            when(processDefService.findByName(anyString())).thenReturn(List.of());
            when(processDefService.save(any())).thenAnswer(inv -> {
                ProcessDefDto in = inv.getArgument(0);
                return new ProcessDefDto(
                        "def-saved",
                        in.name(),
                        in.version(),
                        in.packageId(),
                        in.description(),
                        in.variables(),
                        in.activities(),
                        in.transitions());
            });
            when(repo.update(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repo.findById(anyString())).thenAnswer(inv ->
                    Optional.of(persistedPending(inv.getArgument(0))));
            // Default judge behaviour: aligned. Tests override per-test.
            when(alignmentJudge.judge(anyString(), any(), any(), any(), any()))
                    .thenReturn(new Verdict(true, "", "low"));

            this.builder = new WorkflowBuilderService(
                    repo,
                    processDefService,
                    null, // ModelProviderRouter — unused with fake agent invoker
                    toolExecutionService,
                    skillRegistryService,
                    sessionWorkspaceService,
                    executionLogService,
                    validator,
                    alignmentJudge,
                    properties,
                    mapper);
        }

        WorkflowProposal persistedPending() {
            return persistedPending("p-1");
        }

        WorkflowProposal persistedPending(String id) {
            WorkflowProposal proposal = new WorkflowProposal();
            proposal.setId(id);
            proposal.setSessionId("s-1");
            proposal.setTurnId("t-1");
            proposal.setUserId("u-1");
            proposal.setStatus("approved");
            proposal.setUserPrompt("validate order leg sequence");
            proposal.setSuggestedName("Validate Order Workflow");
            proposal.setModelProviderId("openai");
            proposal.setModelId("gpt-4o");
            proposal.setSkillNamesJson("[\"order-validation\"]");
            return proposal;
        }

        void judgeAligned() {
            when(alignmentJudge.judge(anyString(), any(), any(), any(), any()))
                    .thenReturn(new Verdict(true, "", "low"));
        }
    }

    /** Convenience captor read in case future tests want to assert on save args. */
    @SuppressWarnings("unused")
    private static ProcessDefDto savedDef(WorkflowBuilderServiceTest.Fixture fx) {
        ArgumentCaptor<ProcessDefDto> captor = ArgumentCaptor.forClass(ProcessDefDto.class);
        verify(fx.processDefService).save(captor.capture());
        return captor.getValue();
    }
}
