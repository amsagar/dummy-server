package com.pods.agent.service;

import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryServiceTest {

    @Test
    void includesWebfetchWhenWebScopeIsAllowed() {
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        RuntimeTuningProperties runtimeTuningProperties = new RuntimeTuningProperties();

        AgentDomain domain = AgentDomain.builder().id("d-core").name("Framework Core Tools").enabled(true).build();
        AgentTool webfetch = AgentTool.builder()
                .id("t-webfetch")
                .domainId("d-core")
                .name("webfetch")
                .permissionScope("web")
                .enabled(true)
                .build();

        when(domainRepository.findAll()).thenReturn(List.of(domain));
        when(toolRepository.findAll()).thenReturn(List.of(webfetch));

        ToolRegistryService service = new ToolRegistryService(domainRepository, toolRepository, runtimeTuningProperties, null);

        assertNotNull(service.getEnabledToolByName("webfetch"));
    }

    @Test
    void excludesWebfetchWhenWebScopeIsNotAllowed() {
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        RuntimeTuningProperties runtimeTuningProperties = new RuntimeTuningProperties();
        runtimeTuningProperties.setAllowedPermissionScopes(List.of("filesystem", "shell"));

        AgentDomain domain = AgentDomain.builder().id("d-core").name("Framework Core Tools").enabled(true).build();
        AgentTool webfetch = AgentTool.builder()
                .id("t-webfetch")
                .domainId("d-core")
                .name("webfetch")
                .permissionScope("web")
                .enabled(true)
                .build();

        when(domainRepository.findAll()).thenReturn(List.of(domain));
        when(toolRepository.findAll()).thenReturn(List.of(webfetch));

        ToolRegistryService service = new ToolRegistryService(domainRepository, toolRepository, runtimeTuningProperties, null);

        assertNull(service.getEnabledToolByName("webfetch"));
    }
}
