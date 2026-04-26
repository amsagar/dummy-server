package com.pods.agent.api;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ToolAuthProfile;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.ToolAuthProfileRepository;
import com.pods.agent.service.HttpToolAuthService;
import com.pods.agent.service.ToolRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolAuthControllerTest {

    @Test
    void bindTool_allowsOverrideWithNullProfileAndReturnsNormalizedState() {
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        HttpToolAuthService authService = mock(HttpToolAuthService.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);

        ToolAuthController controller = new ToolAuthController(
                domainRepository,
                toolRepository,
                profileRepository,
                authService,
                toolRegistryService,
                new ObjectMapper()
        );

        AgentTool tool = AgentTool.builder()
                .id("tool-1")
                .domainId("domain-1")
                .authProfileId("profile-1")
                .authOverrideEnabled(false)
                .enabled(true)
                .build();
        AgentTool refreshed = AgentTool.builder()
                .id("tool-1")
                .domainId("domain-1")
                .authProfileId(null)
                .authOverrideEnabled(true)
                .authType("bearer_token")
                .encryptedAccessToken("x:y:z")
                .tokenExpiresAt(null)
                .enabled(true)
                .build();

        when(toolRepository.findById("tool-1")).thenReturn(Optional.of(tool), Optional.of(refreshed));
        doNothing().when(toolRepository).updateAuthBinding(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        doNothing().when(toolRegistryService).refresh();

        ResponseEntity<?> response = controller.bindTool("domain-1", "tool-1", Map.of(
                "authProfileId", "",
                "authOverrideEnabled", true
        ));

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("tool_override", body.get("mode"));
        assertEquals(true, body.get("authOverrideEnabled"));
        assertEquals(null, body.get("authProfileId"));
        assertEquals(true, body.get("tokenConnected"));
    }

    @Test
    void bindTool_rejectsProfileFromDifferentDomain() {
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolAuthProfileRepository profileRepository = mock(ToolAuthProfileRepository.class);
        HttpToolAuthService authService = mock(HttpToolAuthService.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);

        ToolAuthController controller = new ToolAuthController(
                domainRepository,
                toolRepository,
                profileRepository,
                authService,
                toolRegistryService,
                new ObjectMapper()
        );

        AgentTool tool = AgentTool.builder()
                .id("tool-2")
                .domainId("domain-1")
                .enabled(true)
                .build();
        ToolAuthProfile foreignProfile = ToolAuthProfile.builder()
                .id("profile-foreign")
                .domainId("domain-2")
                .enabled(true)
                .build();

        when(toolRepository.findById("tool-2")).thenReturn(Optional.of(tool));
        when(profileRepository.findById("profile-foreign")).thenReturn(Optional.of(foreignProfile));

        ResponseEntity<?> response = controller.bindTool("domain-1", "tool-2", Map.of(
                "authProfileId", "profile-foreign",
                "authOverrideEnabled", false
        ));

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody() != null);
    }
}

