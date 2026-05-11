package com.pods.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.workflow.proposal.WorkflowClassifierService;
import com.pods.agent.workflow.proposal.WorkflowProposalService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase-1 dispatcher tests. Cover the no-op branches (chitchat /
 * duplicate intent / missing turn file) and the happy-path classifier
 * call ending in a {@code WorkflowProposalService.upsertPending} write.
 */
class WorkflowProposalAsyncServiceTest {

    @Test
    void processSkipsWhenClassifierSaysWorkflowNotNeeded(@TempDir Path tmp) throws IOException {
        Path turnFile = createEmptyExecutionLog(tmp, "turn-skip");
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        WorkflowProposalService proposalService = mock(WorkflowProposalService.class);
        WorkflowClassifierService classifierService = mock(WorkflowClassifierService.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        when(workspaceService.get("s-1")).thenReturn(tmp);
        when(proposalService.isDuplicateIntent(anyString(), anyString())).thenReturn(false);
        when(runtimeEventRepository.findByTurnId("turn-skip")).thenReturn(List.of());
        when(classifierService.classify(any())).thenReturn(Optional.of(
                new WorkflowClassifierService.ClassifierDecision(false, "", "chitchat", "")));

        WorkflowProposalAsyncService service = new WorkflowProposalAsyncService(
                runtimeEventRepository,
                proposalService,
                classifierService,
                workspaceService,
                new ObjectMapper());

        service.process(new WorkflowProposalAsyncService.Job(
                "s-1", "turn-skip", "say hi", "hi back",
                "u-1", new ModelRef("openai", "gpt-4o"), turnFile));

        verify(classifierService).classify(any());
        verify(proposalService, never()).upsertPending(any());
    }

    @Test
    void processSkipsWhenIntentIsDuplicate(@TempDir Path tmp) throws IOException {
        Path turnFile = createEmptyExecutionLog(tmp, "turn-dup");
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        WorkflowProposalService proposalService = mock(WorkflowProposalService.class);
        WorkflowClassifierService classifierService = mock(WorkflowClassifierService.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        when(proposalService.isDuplicateIntent(anyString(), anyString())).thenReturn(true);

        WorkflowProposalAsyncService service = new WorkflowProposalAsyncService(
                runtimeEventRepository,
                proposalService,
                classifierService,
                workspaceService,
                new ObjectMapper());

        service.process(new WorkflowProposalAsyncService.Job(
                "s-1", "turn-dup", "validate order",
                "ok", "u-1", new ModelRef("openai", "gpt-4o"), turnFile));

        verify(classifierService, never()).classify(any());
        verify(proposalService, never()).upsertPending(any());
    }

    @Test
    void processSkipsWhenExecutionLogMissing(@TempDir Path tmp) {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        WorkflowProposalService proposalService = mock(WorkflowProposalService.class);
        WorkflowClassifierService classifierService = mock(WorkflowClassifierService.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        when(workspaceService.get("s-1")).thenReturn(tmp);
        when(proposalService.isDuplicateIntent(anyString(), anyString())).thenReturn(false);

        WorkflowProposalAsyncService service = new WorkflowProposalAsyncService(
                runtimeEventRepository,
                proposalService,
                classifierService,
                workspaceService,
                new ObjectMapper());

        service.process(new WorkflowProposalAsyncService.Job(
                "s-1", "turn-no-log", "validate order", "ok",
                "u-1", new ModelRef("openai", "gpt-4o"), tmp.resolve("missing.json")));

        verify(classifierService, never()).classify(any());
        verify(proposalService, never()).upsertPending(any());
    }

    @Test
    void processCreatesPendingWhenClassifierApprovesAndCollectsSkills(@TempDir Path tmp) throws IOException {
        Path turnFile = createEmptyExecutionLog(tmp, "turn-ok");
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        WorkflowProposalService proposalService = mock(WorkflowProposalService.class);
        WorkflowClassifierService classifierService = mock(WorkflowClassifierService.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);

        when(workspaceService.get("s-1")).thenReturn(tmp);
        when(proposalService.isDuplicateIntent(anyString(), anyString())).thenReturn(false);
        when(proposalService.normalizePrompt(anyString())).thenReturn("validate order");
        when(classifierService.classify(any())).thenReturn(Optional.of(
                new WorkflowClassifierService.ClassifierDecision(
                        true,
                        "Validate Order Workflow",
                        "Two deterministic tools form a clean reusable graph.",
                        "validate order leg sequence")));

        // Two tool.call events: one real tool, one skill load. The dispatcher
        // should pull the real tool into matchedToolNamesJson and the skill
        // name into skillNamesJson.
        RuntimeEvent toolEvent = new RuntimeEvent();
        toolEvent.setEventType("tool.call");
        toolEvent.setPayload("{\"toolName\":\"validate_order\",\"input\":{\"orderId\":\"x\"}}");
        RuntimeEvent skillEvent = new RuntimeEvent();
        skillEvent.setEventType("tool.call");
        skillEvent.setPayload("{\"toolName\":\"skill\",\"input\":{\"name\":\"order-validation\"}}");
        when(runtimeEventRepository.findByTurnId("turn-ok"))
                .thenReturn(List.of(toolEvent, skillEvent));

        WorkflowProposalAsyncService service = new WorkflowProposalAsyncService(
                runtimeEventRepository,
                proposalService,
                classifierService,
                workspaceService,
                new ObjectMapper());

        service.process(new WorkflowProposalAsyncService.Job(
                "s-1", "turn-ok", "validate order leg sequence",
                "verdict ok", "u-1",
                new ModelRef("openai", "gpt-4o"), turnFile));

        ArgumentCaptor<WorkflowProposalService.PendingProposal> captor =
                ArgumentCaptor.forClass(WorkflowProposalService.PendingProposal.class);
        verify(proposalService, times(1)).upsertPending(captor.capture());
        WorkflowProposalService.PendingProposal pending = captor.getValue();
        assertEquals("Validate Order Workflow", pending.suggestedName());
        assertEquals("validate order leg sequence", pending.intentSignature());
        assertEquals("[\"validate_order\"]", pending.matchedToolNamesJson());
        assertEquals("[\"order-validation\"]", pending.skillNamesJson());
        assertEquals("openai", pending.modelProviderId());
        assertEquals("gpt-4o", pending.modelId());
    }

    private Path createEmptyExecutionLog(Path workspace, String turnId) throws IOException {
        Path workflowDir = workspace.resolve(".pods-agent/workflow");
        Files.createDirectories(workflowDir);
        Path file = workflowDir.resolve("execution-log-" + turnId + ".json");
        Files.writeString(file, "{\"turnId\":\"" + turnId + "\",\"steps\":[]}");
        return file;
    }
}
