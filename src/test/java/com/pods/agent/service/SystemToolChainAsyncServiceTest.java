package com.pods.agent.service;

import com.pods.agent.agent.AgentSessionManager;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.SystemToolChainProposal;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.repository.SystemToolChainProposalRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemToolChainAsyncServiceTest {

    @Test
    void processFailsClosedWhenModelMissing() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        ToolChainTraceFileService traceService = mock(ToolChainTraceFileService.class);
        ToolChainArchitectAgentService architectService = mock(ToolChainArchitectAgentService.class);
        ToolChainSuggestionService suggestionService = mock(ToolChainSuggestionService.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        SystemToolChainDurableTraceService durableTraceService = mock(SystemToolChainDurableTraceService.class);
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService service = new SystemToolChainAsyncService(
                runtimeEventRepository,
                workspaceService,
                traceService,
                architectService,
                suggestionService,
                toolChainService,
                durableTraceService,
                sessionManager,
                proposalRepository,
                new ObjectMapper()
        );
        when(toolChainService.findBestIntentMatchForDedup(anyString(), anyDouble())).thenReturn(Optional.empty());

        service.process(new SystemToolChainAsyncService.Job(
                "session-1", "turn-1", "prompt", "assistant", "user-1", null, Path.of("/tmp/test")
        ));

        verify(traceService, never()).writeTurnTrace(any(), anyString(), anyString(), anyString(), anyString());
        verify(suggestionService, never()).createSuggestionFromArchitectArtifact(any(), anyString(), anyString(), anyString(), anyString(), any());
        ArgumentCaptor<RuntimeEvent> captor = ArgumentCaptor.forClass(RuntimeEvent.class);
        verify(runtimeEventRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        List<String> types = captor.getAllValues().stream().map(RuntimeEvent::getEventType).toList();
        assertTrue(types.contains("system_toolchain_job.started"));
        assertTrue(types.contains("system_toolchain_job.failed"));
    }

    @Test
    void processCreatesPendingProposalWhenEligible() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        ToolChainTraceFileService traceService = mock(ToolChainTraceFileService.class);
        ToolChainArchitectAgentService architectService = mock(ToolChainArchitectAgentService.class);
        ToolChainSuggestionService suggestionService = mock(ToolChainSuggestionService.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        SystemToolChainDurableTraceService durableTraceService = mock(SystemToolChainDurableTraceService.class);
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService service = new SystemToolChainAsyncService(
                runtimeEventRepository,
                workspaceService,
                traceService,
                architectService,
                suggestionService,
                toolChainService,
                durableTraceService,
                sessionManager,
                proposalRepository,
                new ObjectMapper()
        );
        when(toolChainService.findBestIntentMatchForDedup(anyString(), anyDouble())).thenReturn(Optional.empty());
        when(proposalRepository.findActiveByUser(eq("user-1"))).thenReturn(List.of());
        Path workspace = Path.of("/tmp/test");
        when(traceService.writeTurnTrace(eq(workspace), eq("session-1"), eq("turn-1"), anyString(), anyString()))
                .thenReturn(".pods-agent/turns/turn-1/toolchain-trace.json");
        when(durableTraceService.persistAsJson(eq(workspace), eq(".pods-agent/turns/turn-1/toolchain-trace.json")))
                .thenReturn(Optional.of("{\"traceVersion\":1}"));
        when(architectService.evaluateEligibilityFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                anyString(),
                anyString(),
                any(),
                eq("turn-1:toolchain-eligibility")
        )).thenReturn(Optional.of(SystemToolChainEligibility.builder()
                .toolChainNeeded(true)
                .simpleTurn(false)
                .confidence("high")
                .reason("Multi-step reusable workflow")
                .referencedSkills(List.of("Billing Rules"))
                .build()));
        when(proposalRepository.findBySessionTurn(eq("session-1"), eq("turn-1")))
                .thenReturn(Optional.empty());
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.process(new SystemToolChainAsyncService.Job(
                "session-1",
                "turn-1",
                "prompt",
                "assistant",
                "user-1",
                new ModelRef("openai", "gpt-4o"),
                workspace
        ));

        verify(proposalRepository).save(any(SystemToolChainProposal.class));
        verify(suggestionService, never()).createSuggestionFromArchitectArtifact(any(), anyString(), anyString(), anyString(), anyString(), any());
        ArgumentCaptor<RuntimeEvent> captor = ArgumentCaptor.forClass(RuntimeEvent.class);
        verify(runtimeEventRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        List<String> types = captor.getAllValues().stream().map(RuntimeEvent::getEventType).toList();
        assertTrue(types.contains("system_toolchain_job.started"));
        assertTrue(types.contains("system_toolchain_job.proposal_created"));
        assertTrue(types.contains("system_toolchain_job.awaiting_approval"));
    }

    @Test
    void processSkipsWhenSimpleTurn() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        ToolChainTraceFileService traceService = mock(ToolChainTraceFileService.class);
        ToolChainArchitectAgentService architectService = mock(ToolChainArchitectAgentService.class);
        ToolChainSuggestionService suggestionService = mock(ToolChainSuggestionService.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        SystemToolChainDurableTraceService durableTraceService = mock(SystemToolChainDurableTraceService.class);
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService service = new SystemToolChainAsyncService(
                runtimeEventRepository,
                workspaceService,
                traceService,
                architectService,
                suggestionService,
                toolChainService,
                durableTraceService,
                sessionManager,
                proposalRepository,
                new ObjectMapper()
        );
        when(toolChainService.findBestIntentMatchForDedup(anyString(), anyDouble())).thenReturn(Optional.empty());
        when(proposalRepository.findActiveByUser(eq("user-1"))).thenReturn(List.of());
        Path workspace = Path.of("/tmp/test");
        when(traceService.writeTurnTrace(eq(workspace), eq("session-1"), eq("turn-1"), anyString(), anyString()))
                .thenReturn(".pods-agent/turns/turn-1/toolchain-trace.json");
        when(architectService.evaluateEligibilityFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                anyString(),
                anyString(),
                any(),
                eq("turn-1:toolchain-eligibility")
        )).thenReturn(Optional.of(SystemToolChainEligibility.builder()
                .toolChainNeeded(false)
                .simpleTurn(true)
                .confidence("high")
                .reason("Greeting")
                .referencedSkills(List.of())
                .build()));

        service.process(new SystemToolChainAsyncService.Job(
                "session-1",
                "turn-1",
                "hi",
                "hello",
                "user-1",
                new ModelRef("openai", "gpt-4o"),
                workspace
        ));

        verify(proposalRepository, never()).save(any(SystemToolChainProposal.class));
        verify(architectService, never()).generateFromTrace(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(suggestionService, never()).createSuggestionFromArchitectArtifact(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void processRetriesEligibilityOnceBeforeFailingOrProceeding() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        ToolChainTraceFileService traceService = mock(ToolChainTraceFileService.class);
        ToolChainArchitectAgentService architectService = mock(ToolChainArchitectAgentService.class);
        ToolChainSuggestionService suggestionService = mock(ToolChainSuggestionService.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        SystemToolChainDurableTraceService durableTraceService = mock(SystemToolChainDurableTraceService.class);
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService service = new SystemToolChainAsyncService(
                runtimeEventRepository,
                workspaceService,
                traceService,
                architectService,
                suggestionService,
                toolChainService,
                durableTraceService,
                sessionManager,
                proposalRepository,
                new ObjectMapper()
        );
        when(toolChainService.findBestIntentMatchForDedup(anyString(), anyDouble())).thenReturn(Optional.empty());
        when(proposalRepository.findActiveByUser(eq("user-1"))).thenReturn(List.of());
        Path workspace = Path.of("/tmp/test");
        when(traceService.writeTurnTrace(eq(workspace), eq("session-1"), eq("turn-1"), anyString(), anyString()))
                .thenReturn(".pods-agent/turns/turn-1/toolchain-trace.json");
        when(durableTraceService.persistAsJson(eq(workspace), eq(".pods-agent/turns/turn-1/toolchain-trace.json")))
                .thenReturn(Optional.of("{\"traceVersion\":1}"));
        when(architectService.evaluateEligibilityFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                anyString(),
                anyString(),
                any(),
                eq("turn-1:toolchain-eligibility")
        )).thenReturn(Optional.empty());
        when(architectService.evaluateEligibilityFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                anyString(),
                anyString(),
                any(),
                eq("turn-1:toolchain-eligibility:retry-1")
        )).thenReturn(Optional.of(SystemToolChainEligibility.builder()
                .toolChainNeeded(true)
                .simpleTurn(false)
                .confidence("high")
                .reason("retry succeeded")
                .referencedSkills(List.of())
                .build()));
        when(proposalRepository.findBySessionTurn(eq("session-1"), eq("turn-1")))
                .thenReturn(Optional.empty());
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.process(new SystemToolChainAsyncService.Job(
                "session-1",
                "turn-1",
                "prompt",
                "assistant",
                "user-1",
                new ModelRef("openai", "gpt-4o"),
                workspace
        ));

        verify(architectService, times(1)).evaluateEligibilityFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                anyString(),
                anyString(),
                any(),
                eq("turn-1:toolchain-eligibility")
        );
        verify(architectService, times(1)).evaluateEligibilityFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                anyString(),
                anyString(),
                any(),
                eq("turn-1:toolchain-eligibility:retry-1")
        );
        verify(proposalRepository).save(any(SystemToolChainProposal.class));
    }

    @Test
    void processSkipsWhenSimilarToolChainAlreadyExists() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        ToolChainTraceFileService traceService = mock(ToolChainTraceFileService.class);
        ToolChainArchitectAgentService architectService = mock(ToolChainArchitectAgentService.class);
        ToolChainSuggestionService suggestionService = mock(ToolChainSuggestionService.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        SystemToolChainDurableTraceService durableTraceService = mock(SystemToolChainDurableTraceService.class);
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService service = new SystemToolChainAsyncService(
                runtimeEventRepository,
                workspaceService,
                traceService,
                architectService,
                suggestionService,
                toolChainService,
                durableTraceService,
                sessionManager,
                proposalRepository,
                new ObjectMapper()
        );
        when(toolChainService.findBestIntentMatchForDedup(anyString(), anyDouble())).thenReturn(Optional.of(
                new ToolChainService.IntentMatch("tc-123", 2, "Validate Order", 0.94, List.of("validate order"))
        ));

        service.process(new SystemToolChainAsyncService.Job(
                "session-1",
                "turn-1",
                "validate order 123 with checks",
                "assistant",
                "user-1",
                new ModelRef("openai", "gpt-4o"),
                Path.of("/tmp/test")
        ));

        verify(traceService, never()).writeTurnTrace(any(), anyString(), anyString(), anyString(), anyString());
        verify(proposalRepository, never()).save(any(SystemToolChainProposal.class));
        verify(architectService, never()).evaluateEligibilityFromTrace(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void materializeApprovedProposalPersistsToolChainAndMarksMaterialized() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        SessionWorkspaceService workspaceService = mock(SessionWorkspaceService.class);
        ToolChainTraceFileService traceService = mock(ToolChainTraceFileService.class);
        ToolChainArchitectAgentService architectService = mock(ToolChainArchitectAgentService.class);
        ToolChainSuggestionService suggestionService = mock(ToolChainSuggestionService.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        SystemToolChainDurableTraceService durableTraceService = mock(SystemToolChainDurableTraceService.class);
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        SystemToolChainProposalRepository proposalRepository = mock(SystemToolChainProposalRepository.class);
        SystemToolChainAsyncService service = new SystemToolChainAsyncService(
                runtimeEventRepository,
                workspaceService,
                traceService,
                architectService,
                suggestionService,
                toolChainService,
                durableTraceService,
                sessionManager,
                proposalRepository,
                new ObjectMapper()
        );
        when(toolChainService.findBestIntentMatchForDedup(anyString(), anyDouble())).thenReturn(Optional.empty());
        Path workspace = Path.of("/tmp/test");
        SystemToolChainProposal proposal = SystemToolChainProposal.builder()
                .id("proposal-1")
                .sessionId("session-1")
                .turnId("turn-1")
                .userId("user-1")
                .status("approved")
                .tracePath(".pods-agent/turns/turn-1/toolchain-trace.json")
                .durableTraceJson("{\"traceVersion\":1}")
                .userPrompt("prompt")
                .assistantResponse("assistant")
                .modelProviderId("openai")
                .modelId("gpt-4o")
                .build();
        when(proposalRepository.findById("proposal-1")).thenReturn(Optional.of(proposal));
        when(workspaceService.getOrCreate("session-1")).thenReturn(workspace);
        when(durableTraceService.restoreToWorkspace(
                eq(workspace),
                eq("{\"traceVersion\":1}"),
                eq("turn-1")
        )).thenReturn(Optional.of(".pods-agent/turns/turn-1/toolchain-trace.json"));
        SystemToolChainArtifact artifact = SystemToolChainArtifact.builder()
                .name("Architected")
                .description("desc")
                .intents(List.of("intent"))
                .graphJson("{\"nodes\":[{\"id\":\"start\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}")
                .inputSchema("{\"type\":\"object\"}")
                .outputSchema("{\"type\":\"object\"}")
                .responseMode("hybrid")
                .synthesisPrompt("sum")
                .ragConfig(Map.of())
                .build();
        when(architectService.generateFromTrace(
                eq(workspace),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("session-1"),
                eq("turn-1"),
                eq("user-1"),
                eq("prompt"),
                eq("assistant"),
                any(),
                eq("turn-1:toolchain-architect")
        )).thenReturn(Optional.of(artifact));
        when(suggestionService.createSuggestionFromArchitectArtifact(
                eq(artifact),
                eq("session-1"),
                eq("turn-1"),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("user-1"),
                any()
        )).thenReturn(Optional.of(ToolChain.builder().id("tc-1").build()));

        service.materializeApprovedProposal("proposal-1");

        verify(suggestionService).createSuggestionFromArchitectArtifact(
                eq(artifact),
                eq("session-1"),
                eq("turn-1"),
                eq(".pods-agent/turns/turn-1/toolchain-trace.json"),
                eq("user-1"),
                any()
        );
        verify(proposalRepository, org.mockito.Mockito.atLeastOnce()).update(any(SystemToolChainProposal.class));
    }
}
