package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.repository.RuntimeEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolChainSuggestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsSystemSuggestionWhenTurnHasValidToolSequence() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainAuthoringService authoringService = mock(ToolChainAuthoringService.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        MappingValidator mappingValidator = mock(MappingValidator.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(
                runtimeEventRepository,
                toolChainService,
                authoringService,
                toolRegistryService,
                mappingValidator,
                objectMapper
        );
        Mockito.when(mappingValidator.validate(anyMap(), any(), anyMap()))
                .thenReturn(new MappingValidator.ValidationReport(List.of()));

        when(runtimeEventRepository.findByTurnId("turn-1")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"search_orders\",\"input\":\"{\\\"orderId\\\":\\\"123\\\"}\"}"),
                event("tool.done", "{\"toolName\":\"search_orders\",\"status\":\"success\"}"),
                event("tool.call", "{\"toolName\":\"update_order\",\"input\":\"{\\\"orderId\\\":\\\"123\\\",\\\"status\\\":\\\"closed\\\"}\"}"),
                event("tool.done", "{\"toolName\":\"update_order\",\"status\":\"success\"}")
        ));
        when(toolChainService.findBySignatures(anyString(), anyString())).thenReturn(Optional.empty());
        when(toolChainService.createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ToolChain.builder().id("tc-1").name("Suggested").build());

        Optional<ToolChain> created = service.createSuggestionFromTurn("session-1", "turn-1", "close order 123", "user-1");

        assertTrue(created.isPresent());
        ArgumentCaptor<ToolChainDtos.ToolChainVersionRequest> requestCaptor =
                ArgumentCaptor.forClass(ToolChainDtos.ToolChainVersionRequest.class);
        verify(toolChainService).createVersion(eq("tc-1"), requestCaptor.capture(), eq("user-1"));
        ToolChainDtos.ToolChainVersionRequest req = requestCaptor.getValue();
        assertFalse(String.valueOf(req.getIntentSignature()).isBlank());
        assertFalse(String.valueOf(req.getStructureSignature()).isBlank());
        assertTrue(req.getGraphJson().contains("\"tool_1\""));
        assertTrue(req.getGraphJson().contains("\"tool_2\""));
    }

    @Test
    void skipsCreationWhenSignatureAlreadyExists() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainAuthoringService authoringService = mock(ToolChainAuthoringService.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        MappingValidator mappingValidator = mock(MappingValidator.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(
                runtimeEventRepository,
                toolChainService,
                authoringService,
                toolRegistryService,
                mappingValidator,
                objectMapper
        );
        Mockito.when(mappingValidator.validate(anyMap(), any(), anyMap()))
                .thenReturn(new MappingValidator.ValidationReport(List.of()));

        when(runtimeEventRepository.findByTurnId("turn-2")).thenReturn(List.of(
                event("tool.call", "{\"toolName\":\"a\",\"input\":\"{}\"}"),
                event("tool.call", "{\"toolName\":\"b\",\"input\":\"{}\"}")
        ));
        when(toolChainService.findBySignatures(anyString(), anyString()))
                .thenReturn(Optional.of(ToolChain.builder().id("existing").build()));

        Optional<ToolChain> created = service.createSuggestionFromTurn("session-1", "turn-2", "run flow", "user-1");

        assertTrue(created.isPresent());
        verify(toolChainService, never()).createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(toolChainService, never()).createVersion(anyString(), any(), anyString());
    }

    @Test
    void createsSuggestionFromArchitectArtifact() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(
                runtimeEventRepository,
                toolChainService,
                mock(ToolChainAuthoringService.class),
                mock(ToolRegistryService.class),
                mock(MappingValidator.class),
                objectMapper
        );
        when(toolChainService.findBySignatures(anyString(), anyString())).thenReturn(Optional.empty());
        when(toolChainService.createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ToolChain.builder().id("sys-1").build());
        SystemToolChainArtifact artifact = SystemToolChainArtifact.builder()
                .name("Architected Chain")
                .description("Generated from trace")
                .intents(List.of("check order status"))
                .referencedSkills(List.of("Billing Rules", "D365 Rules"))
                .graphJson("{\"nodes\":[{\"id\":\"start\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}")
                .responseMode("hybrid")
                .synthesisPrompt("Summarize outputs.")
                .ragConfig(Map.of())
                .build();

        Optional<ToolChain> out = service.createSuggestionFromArchitectArtifact(
                artifact,
                "session-1",
                "turn-1",
                ".pods-agent/turns/turn-1/toolchain-trace.json",
                "user-1",
                new ModelRef("openai", "gpt-4o")
        );

        assertTrue(out.isPresent());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolChainService).createSystemSuggested(
                eq("Architected Chain"),
                eq("Generated from trace"),
                eq("user-1"),
                anyString(),
                anyString(),
                metadataCaptor.capture()
        );
        assertEquals(List.of("Billing Rules", "D365 Rules"), metadataCaptor.getValue().get("referencedSkills"));

        ArgumentCaptor<ToolChainDtos.ToolChainVersionRequest> versionCaptor =
                ArgumentCaptor.forClass(ToolChainDtos.ToolChainVersionRequest.class);
        verify(toolChainService).createVersion(eq("sys-1"), versionCaptor.capture(), eq("user-1"));
        assertTrue(String.valueOf(versionCaptor.getValue().getSynthesisPrompt()).contains("Billing Rules"));
        verify(toolChainService).createVersion(eq("sys-1"), any(ToolChainDtos.ToolChainVersionRequest.class), eq("user-1"));
    }

    @Test
    void rejectsInvalidArchitectArtifactFailClosed() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(
                runtimeEventRepository,
                toolChainService,
                mock(ToolChainAuthoringService.class),
                mock(ToolRegistryService.class),
                mock(MappingValidator.class),
                objectMapper
        );
        SystemToolChainArtifact artifact = SystemToolChainArtifact.builder()
                .name("Broken")
                .description("Missing graph")
                .intents(List.of("run"))
                .graphJson("")
                .inputSchema("{\"type\":\"object\"}")
                .outputSchema("{\"type\":\"object\"}")
                .build();

        Optional<ToolChain> out = service.createSuggestionFromArchitectArtifact(
                artifact, "session-1", "turn-1", "trace.json", "user-1", null);

        assertTrue(out.isEmpty());
        verify(toolChainService, never()).createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void rejectsArchitectArtifactContainingSkillNode() {
        RuntimeEventRepository runtimeEventRepository = mock(RuntimeEventRepository.class);
        ToolChainService toolChainService = mock(ToolChainService.class);
        ToolChainSuggestionService service = new ToolChainSuggestionService(
                runtimeEventRepository,
                toolChainService,
                mock(ToolChainAuthoringService.class),
                mock(ToolRegistryService.class),
                mock(MappingValidator.class),
                objectMapper
        );
        SystemToolChainArtifact artifact = SystemToolChainArtifact.builder()
                .name("Bad Chain")
                .description("contains skill node")
                .intents(List.of("run"))
                .referencedSkills(List.of("Billing Rules"))
                .graphJson("{\"nodes\":[{\"id\":\"skill_1\",\"type\":\"skill\"}],\"edges\":[]}")
                .inputSchema("{\"type\":\"object\"}")
                .outputSchema("{\"type\":\"object\"}")
                .responseMode("hybrid")
                .synthesisPrompt("x")
                .ragConfig(Map.of())
                .build();

        Optional<ToolChain> out = service.createSuggestionFromArchitectArtifact(
                artifact, "session-1", "turn-1", "trace.json", "user-1", null);

        assertTrue(out.isEmpty());
        verify(toolChainService, never()).createSystemSuggested(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    private RuntimeEvent event(String type, String payload) {
        return RuntimeEvent.builder()
                .eventType(type)
                .payload(payload)
                .build();
    }
}
