package com.pods.agent.service;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FrameworkToolPackServiceTest {

    @Test
    void installsCoreAndExperimentalDefaults() {
        AgentDomainRepository domainRepo = mock(AgentDomainRepository.class);
        AgentToolRepository toolRepo = mock(AgentToolRepository.class);
        when(domainRepo.findAll()).thenReturn(List.of());
        when(domainRepo.save(any(AgentDomain.class))).thenAnswer(i -> {
            AgentDomain d = i.getArgument(0);
            if (d.getId() == null) d.setId(d.getName().replace(" ", "_"));
            return d;
        });
        when(toolRepo.findByDomainId(anyString())).thenReturn(List.of());
        when(toolRepo.save(any(AgentTool.class))).thenAnswer(i -> i.getArgument(0));

        FrameworkToolPackService service = new FrameworkToolPackService(domainRepo, toolRepo);
        var result = service.installDefaults();

        assertTrue(result.total() >= 20);
        assertTrue(result.created() >= 20);
        verify(toolRepo, atLeast(20)).save(any(AgentTool.class));
    }
}
