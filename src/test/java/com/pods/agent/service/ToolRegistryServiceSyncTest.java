package com.pods.agent.service;

import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRegistryServiceSyncTest {

    @Test
    @SuppressWarnings("unchecked")
    void refresh_callsSyncFromCacheWithEnabledTools() {
        AgentDomainRepository domains = mock(AgentDomainRepository.class);
        AgentToolRepository tools = mock(AgentToolRepository.class);
        AgentDomain d = AgentDomain.builder().id("d1").name("dom").enabled(true).build();
        when(domains.findAll()).thenReturn(List.of(d));
        when(tools.findAll()).thenReturn(List.of(
                AgentTool.builder().id("t1").domainId("d1").name("a").enabled(true).sourceType("framework_default").build(),
                AgentTool.builder().id("t2").domainId("d1").name("b").enabled(false).sourceType("framework_default").build()
        ));
        ToolEmbeddingIndexService idx = mock(ToolEmbeddingIndexService.class);
        ObjectProvider<ToolEmbeddingIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(idx);

        ToolRegistryService svc = new ToolRegistryService(domains, tools, new RuntimeTuningProperties(), provider);
        svc.refresh();

        ArgumentCaptor<Collection<AgentTool>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(idx, atLeastOnce()).syncFromCache(cap.capture());
        Collection<AgentTool> sent = cap.getValue();
        assert sent.stream().anyMatch(t -> "t1".equals(t.getId()));
        assert sent.stream().noneMatch(t -> "t2".equals(t.getId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refresh_isNoOpWhenIndexProviderEmpty() {
        AgentDomainRepository domains = mock(AgentDomainRepository.class);
        AgentToolRepository tools = mock(AgentToolRepository.class);
        when(domains.findAll()).thenReturn(List.of());
        when(tools.findAll()).thenReturn(List.of());
        ObjectProvider<ToolEmbeddingIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ToolRegistryService svc = new ToolRegistryService(domains, tools, new RuntimeTuningProperties(), provider);
        svc.refresh();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refresh_acceptsNullProvider() {
        AgentDomainRepository domains = mock(AgentDomainRepository.class);
        AgentToolRepository tools = mock(AgentToolRepository.class);
        when(domains.findAll()).thenReturn(List.of());
        when(tools.findAll()).thenReturn(List.of());
        ToolRegistryService svc = new ToolRegistryService(domains, tools, new RuntimeTuningProperties(), null);
        svc.refresh();
    }
}
