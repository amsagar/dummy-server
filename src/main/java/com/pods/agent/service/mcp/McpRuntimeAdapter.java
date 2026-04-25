package com.pods.agent.service.mcp;

import java.util.List;
import java.util.Map;

public interface McpRuntimeAdapter {
    List<Map<String, Object>> listRuntimeTools();

    List<Map<String, Object>> listRuntimeResources();

    List<Map<String, Object>> listRuntimePrompts();

    String callTool(String serverId, String toolName, String payload);
}

