package com.pods.agent.service;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ToolRegistryService {
    private final AgentDomainRepository domainRepository;
    private final AgentToolRepository toolRepository;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final ObjectProvider<ToolEmbeddingIndexService> toolEmbeddingIndexServiceProvider;
    private final Map<String, AgentTool> enabledToolCache = new ConcurrentHashMap<>();

    public ToolRegistryService(AgentDomainRepository domainRepository,
                               AgentToolRepository toolRepository,
                               RuntimeTuningProperties runtimeTuningProperties,
                               ObjectProvider<ToolEmbeddingIndexService> toolEmbeddingIndexServiceProvider) {
        this.domainRepository = domainRepository;
        this.toolRepository = toolRepository;
        this.runtimeTuningProperties = runtimeTuningProperties;
        this.toolEmbeddingIndexServiceProvider = toolEmbeddingIndexServiceProvider;
        refresh();
    }

    public synchronized void refresh() {
        enabledToolCache.clear();
        Map<String, AgentDomain> domains = domainRepository.findAll().stream()
                .collect(Collectors.toMap(AgentDomain::getId, d -> d));
        for (AgentTool tool : toolRepository.findAll()) {
            AgentDomain domain = domains.get(tool.getDomainId());
            if (tool.isEnabled() && domain != null && domain.isEnabled() && isRuntimeEnabled(tool)) {
                enabledToolCache.put(tool.getId(), tool);
            }
        }
        try {
            ToolEmbeddingIndexService idx = toolEmbeddingIndexServiceProvider == null ? null : toolEmbeddingIndexServiceProvider.getIfAvailable();
            if (idx != null) {
                idx.syncFromCache(List.copyOf(enabledToolCache.values()));
            }
        } catch (Exception ignored) {
        }
    }

    public List<AgentTool> getEnabledTools() {
        return List.copyOf(enabledToolCache.values());
    }

    /**
     * Base tools injected by default each turn.
     * Policy: framework defaults (includes memory tools) only.
     */
    public List<AgentTool> getBaseInjectedTools() {
        return enabledToolCache.values().stream()
                .filter(this::isFrameworkDefault)
                .toList();
    }

    /**
     * Non-default tools (manual/imported/MCP/etc.) used via catalog-gated flow.
     */
    public List<AgentTool> getNonDefaultEnabledTools() {
        return enabledToolCache.values().stream()
                .filter(t -> !isFrameworkDefault(t))
                .toList();
    }

    public List<AgentTool> getEnabledToolsByDomain(String domainId) {
        return enabledToolCache.values().stream()
                .filter(t -> domainId.equals(t.getDomainId()))
                .toList();
    }

    public AgentTool getEnabledToolByName(String name) {
        return enabledToolCache.values().stream()
                .filter(t -> t.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private boolean isRuntimeEnabled(AgentTool tool) {
        if (tool.isExperimental()
                && !runtimeTuningProperties.isEnableExperimentalTools()
                && runtimeTuningProperties.getEnabledExperimentalToolNames().stream().noneMatch(n -> n.equalsIgnoreCase(tool.getName()))) {
            return false;
        }
        if (tool.getPermissionScope() != null && !tool.getPermissionScope().isBlank()) {
            return runtimeTuningProperties.getAllowedPermissionScopes().stream()
                    .anyMatch(s -> s.equalsIgnoreCase(tool.getPermissionScope()));
        }
        return true;
    }

    private boolean isFrameworkDefault(AgentTool tool) {
        if (tool == null) return false;
        String sourceType = tool.getSourceType();
        return sourceType != null && sourceType.equalsIgnoreCase("framework_default");
    }
}
