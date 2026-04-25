package com.pods.agent.api;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.service.FrameworkToolPackService;
import com.pods.agent.service.ToolImportService;
import com.pods.agent.service.ToolRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolControllerTest {

    @Test
    void listDomainsHidesFrameworkAndMcpDomains() {
        AgentDomainRepository domainRepository = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepository = mock(AgentToolRepository.class);
        ToolImportService toolImportService = mock(ToolImportService.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        FrameworkToolPackService frameworkToolPackService = mock(FrameworkToolPackService.class);

        when(domainRepository.findAll()).thenReturn(List.of(
                AgentDomain.builder().id("d1").name("Inventory").enabled(true).build(),
                AgentDomain.builder().id("d2").name("Framework Core Tools").enabled(true).build(),
                AgentDomain.builder().id("d3").name("MCP Github").enabled(true).build()
        ));

        ToolController controller = new ToolController(
                domainRepository,
                toolRepository,
                toolImportService,
                toolRegistryService,
                frameworkToolPackService
        );

        ResponseEntity<?> response = controller.listDomains();
        @SuppressWarnings("unchecked")
        List<AgentDomain> domains = (List<AgentDomain>) response.getBody();

        assertEquals(1, domains.size());
        assertEquals("Inventory", domains.get(0).getName());
        assertTrue(domains.stream().noneMatch(d -> d.getName().toLowerCase().startsWith("framework ")));
        assertTrue(domains.stream().noneMatch(d -> d.getName().toLowerCase().startsWith("mcp ")));
    }
}
