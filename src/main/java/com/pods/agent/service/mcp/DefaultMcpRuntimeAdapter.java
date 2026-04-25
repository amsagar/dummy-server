package com.pods.agent.service.mcp;

import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.repository.McpRegistryRepository;
import com.pods.agent.service.McpClientService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultMcpRuntimeAdapter implements McpRuntimeAdapter {
    private final McpRegistryRepository mcpRegistryRepository;
    private final McpClientService mcpClientService;

    public DefaultMcpRuntimeAdapter(McpRegistryRepository mcpRegistryRepository,
                                    McpClientService mcpClientService) {
        this.mcpRegistryRepository = mcpRegistryRepository;
        this.mcpClientService = mcpClientService;
    }

    @Override
    public List<Map<String, Object>> listRuntimeTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpRegistryEntry server : mcpRegistryRepository.findAll()) {
            if (!server.isEnabled()) continue;
            for (Map<String, Object> t : mcpClientService.getCachedDiscoveredTools(server.getId())) {
                boolean enabled = !(t.get("enabled") instanceof Boolean b) || b;
                if (!enabled) continue;
                String remoteName = String.valueOf(t.getOrDefault("name", "tool"));
                String runtimeName = "mcp_" + remoteName.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
                Map<String, Object> row = new LinkedHashMap<>(t);
                row.put("remoteName", remoteName);
                row.put("name", runtimeName);
                row.put("runtimeToolName", runtimeName);
                row.put("serverId", server.getId());
                row.put("serverName", server.getName());
                row.put("source", "mcp");
                tools.add(row);
            }
        }
        return tools;
    }

    @Override
    public List<Map<String, Object>> listRuntimeResources() {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> listRuntimePrompts() {
        return List.of();
    }

    @Override
    public String callTool(String serverId, String toolName, String payload) {
        return mcpClientService.callTool(serverId, toolName, payload);
    }
}

