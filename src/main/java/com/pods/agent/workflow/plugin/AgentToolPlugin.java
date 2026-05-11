package com.pods.agent.workflow.plugin;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Props;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class AgentToolPlugin implements ApplicationPlugin, DescribablePlugin {

    @Override
    public PluginDescriptor describe() {
        return PluginDescriptor.of(
                "AgentToolPlugin",
                "Agent Tool",
                "Invokes a registered agent tool by exact name.",
                "wrench",
                "Tool",
                List.of(
                        Props.optionsDynamic("toolName", "Tool", true, "agent-tools")
                                .withDescription("Name of an enabled tool in ToolRegistryService."),
                        Props.json("input", "Input", false)
                                .withDescription("Either a JSON object passed as-is, or a string used as the 'query' field.")
                                .withDefault("{}")
                ));
    }

    private final ToolRegistryService toolRegistryService;
    private final ToolExecutionService toolExecutionService;
    private final ObjectMapper objectMapper;

    public AgentToolPlugin(ToolRegistryService toolRegistryService,
                           ToolExecutionService toolExecutionService,
                           ObjectMapper objectMapper) {
        this.toolRegistryService = toolRegistryService;
        this.toolExecutionService = toolExecutionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Object execute(Map<String, Object> props) {
        String toolName = String.valueOf(props.getOrDefault("toolName", "")).trim();
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("AgentToolPlugin requires 'toolName' property");
        }
        AgentTool tool = toolRegistryService.getEnabledToolByName(toolName);
        if (tool == null) {
            throw new IllegalStateException("Unknown or disabled tool: " + toolName);
        }
        String input = normalizeInput(props.get("input"));
        ToolExecutionService.ExecutionResult result = toolExecutionService.execute(tool, input);
        if (!result.success()) {
            throw new IllegalStateException(result.error() == null ? "tool execution failed" : result.error());
        }
        return parseBody(result.body());
    }

    private Object parseBody(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return body;
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (Exception ignore) {
                // Fall through and return the raw string when the body isn't valid JSON.
            }
        }
        return body;
    }

    private String normalizeInput(Object raw) {
        if (raw == null) return "{}";
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return s;
            return "{\"query\":" + quote(s) + "}";
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            try {
                return objectMapper.writeValueAsString(normalized);
            } catch (Exception e) {
                return "{}";
            }
        }
        return "{\"query\":" + quote(String.valueOf(raw)) + "}";
    }

    private String quote(String text) {
        try {
            return objectMapper.writeValueAsString(text == null ? "" : text);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
