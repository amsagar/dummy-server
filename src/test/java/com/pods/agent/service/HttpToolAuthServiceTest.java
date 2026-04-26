package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ToolAuthProfile;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.ToolAuthProfileRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpToolAuthServiceTest {

    @Test
    void resolveHeaders_prefersToolOverrideOverProfile() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        AgentTool tool = AgentTool.builder()
                .id("t1")
                .domainId("d1")
                .authOverrideEnabled(true)
                .authType("api_key_header")
                .authConfig("{\"headerName\":\"X-Override\",\"apiKey\":\"override-key\"}")
                .authProfileId("p1")
                .build();

        ToolAuthProfile profile = ToolAuthProfile.builder()
                .id("p1")
                .domainId("d1")
                .authType("api_key_header")
                .authConfig("{\"headerName\":\"X-Profile\",\"apiKey\":\"profile-key\"}")
                .enabled(true)
                .build();
        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);

        Map<String, String> headers = service.resolveHeaders(tool);

        assertEquals("override-key", headers.get("X-Override"));
        assertTrue(!headers.containsKey("X-Profile"));
    }

    @Test
    void resolveHeaders_usesDomainProfileEvenWhenToolProfileIdExists() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        AgentTool tool = AgentTool.builder()
                .id("t2")
                .domainId("d1")
                .authOverrideEnabled(false)
                .authProfileId("p2")
                .build();

        ToolAuthProfile profile = ToolAuthProfile.builder()
                .id("p-domain")
                .domainId("d1")
                .authType("api_key_header")
                .authConfig("{\"headerName\":\"X-Domain\",\"apiKey\":\"domain-key\"}")
                .enabled(true)
                .build();
        when(profileRepository.findByDomainId("d1")).thenReturn(List.of(profile));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);

        Map<String, String> headers = service.resolveHeaders(tool);

        assertEquals("domain-key", headers.get("X-Domain"));
    }

    @Test
    void resolveHeaders_fallsBackToSingleEnabledDomainProfile() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        AgentTool tool = AgentTool.builder()
                .id("t3")
                .domainId("d1")
                .authOverrideEnabled(false)
                .build();

        ToolAuthProfile profile = ToolAuthProfile.builder()
                .id("p3")
                .domainId("d1")
                .authType("api_key_header")
                .authConfig("{\"headerName\":\"X-Domain\",\"apiKey\":\"domain-key\"}")
                .enabled(true)
                .build();
        when(profileRepository.findByDomainId("d1")).thenReturn(List.of(profile));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);

        Map<String, String> headers = service.resolveHeaders(tool);

        assertEquals("domain-key", headers.get("X-Domain"));
    }

    @Test
    void resolveHeaders_returnsEmptyWhenNoOverrideOrProfile() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        AgentTool tool = AgentTool.builder()
                .id("t4")
                .domainId("d1")
                .authOverrideEnabled(false)
                .build();
        when(profileRepository.findByDomainId("d1")).thenReturn(List.of());

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);

        Map<String, String> headers = service.resolveHeaders(tool);
        Map<String, String> queryParams = service.resolveQueryParams(tool);

        assertTrue(headers.isEmpty());
        assertTrue(queryParams.isEmpty());
    }

    @Test
    void resolveHeaders_clearOverrideFallsBackToDomainProfile() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        AgentTool tool = AgentTool.builder()
                .id("t5")
                .domainId("d1")
                .authOverrideEnabled(false)
                .authType("bearer_token")
                .build();

        ToolAuthProfile profile = ToolAuthProfile.builder()
                .id("p6")
                .domainId("d1")
                .authType("api_key_header")
                .authConfig("{\"headerName\":\"X-Domain\",\"apiKey\":\"domain-key\"}")
                .enabled(true)
                .build();
        when(profileRepository.findByDomainId("d1")).thenReturn(List.of(profile));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);
        Map<String, String> headers = service.resolveHeaders(tool);

        assertEquals("domain-key", headers.get("X-Domain"));
    }

    @Test
    void connectProfile_bearerTokenStoresAccessToken() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        ToolAuthProfile profile = ToolAuthProfile.builder()
                .id("p4")
                .domainId("d1")
                .authType("none")
                .authConfig("{}")
                .enabled(true)
                .build();
        when(profileRepository.findById("p4")).thenReturn(Optional.of(profile));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);

        Map<String, Object> result = service.connectProfile("p4", "bearer_token", Map.of("token", "abc123"));

        assertEquals(true, result.get("ok"));
        verify(profileRepository).update(any(ToolAuthProfile.class));
        assertEquals("bearer_token", profile.getAuthType());
        assertEquals("abc123", profile.getEncryptedAccessToken());
    }

    @Test
    void connectProfile_bearerTokenRequiresToken() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        ToolAuthProfile profile = ToolAuthProfile.builder()
                .id("p5")
                .domainId("d1")
                .authType("none")
                .enabled(true)
                .build();
        when(profileRepository.findById("p5")).thenReturn(Optional.of(profile));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.connectProfile("p5", "bearer_token", Map.of("token", "")));
        assertTrue(ex.getMessage().contains("token is required"));
    }

    @Test
    void connectToolOverride_allowsNoAuthOverride() {
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isConfigured()).thenReturn(false);

        AgentTool tool = AgentTool.builder()
                .id("t6")
                .domainId("d1")
                .authOverrideEnabled(false)
                .authType("bearer_token")
                .authConfig("{}")
                .enabled(true)
                .build();
        when(toolRepository.findById("t6")).thenReturn(Optional.of(tool));

        HttpToolAuthService service = new HttpToolAuthService(new ObjectMapper(), encryptionService, toolRepository, profileRepository);
        Map<String, Object> result = service.connectToolOverride("t6", "none", Map.of());

        assertEquals(true, result.get("ok"));
        assertEquals("none", tool.getAuthType());
        assertEquals(true, tool.getAuthOverrideEnabled());
        assertEquals("{}", tool.getAuthConfig());
        assertEquals(null, tool.getEncryptedAccessToken());
        assertEquals(null, tool.getEncryptedRefreshToken());
    }
}

