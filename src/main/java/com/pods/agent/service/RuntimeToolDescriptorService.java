package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class RuntimeToolDescriptorService {
    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public RuntimeToolDescriptorService(ToolRegistryService toolRegistryService, ObjectMapper objectMapper) {
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> buildDescriptors() {
        return toolRegistryService.getEnabledTools().stream()
                .map(this::toDescriptor)
                .toList();
    }

    public List<Map<String, Object>> buildCompactDescriptors() {
        return toolRegistryService.getEnabledTools().stream()
                .map(this::toCompactDescriptor)
                .toList();
    }

    public Map<String, Object> toDescriptor(AgentTool tool) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", tool.getId());
        descriptor.put("name", tool.getName());
        descriptor.put("description", tool.getDescription());
        descriptor.put("executionKind", tool.getExecutionKind());
        descriptor.put("method", tool.getMethod());
        descriptor.put("host", tool.getHost());
        descriptor.put("endpoint", tool.getEndpoint());
        descriptor.put("permissionScope", tool.getPermissionScope());
        descriptor.put("requiresApproval", tool.isRequiresApproval());
        descriptor.put("experimental", tool.isExperimental());
        descriptor.put("modelGate", tool.getModelGate());
        descriptor.put("providerGate", tool.getProviderGate());
        descriptor.put("inputSchema", parseInputSchema(tool));
        descriptor.put("outputSchema", parse(tool.getResponseSchema()));
        return descriptor;
    }

    public Map<String, Object> toCompactDescriptor(AgentTool tool) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", tool.getId());
        descriptor.put("domainId", tool.getDomainId());
        descriptor.put("name", tool.getName());
        descriptor.put("description", truncate(tool.getDescription(), 180));
        descriptor.put("sourceType", tool.getSourceType());
        descriptor.put("executionKind", tool.getExecutionKind());
        descriptor.put("method", tool.getMethod());
        descriptor.put("permissionScope", tool.getPermissionScope());
        descriptor.put("requiresApproval", tool.isRequiresApproval());
        descriptor.put("experimental", tool.isExperimental());
        descriptor.put("endpoint", truncate(tool.getEndpoint(), 120));
        List<String> requiredArgs = extractRequiredArgs(tool);
        descriptor.put("requiredArgs", requiredArgs);
        descriptor.put("requiredArgCount", requiredArgs.size());
        return descriptor;
    }

    private Object parseInputSchema(AgentTool tool) {
        Object parsed = parse(tool.getRequestSchema());
        if (parsed instanceof Map<?, ?> map && map.get("inputSchema") != null) {
            return map.get("inputSchema");
        }
        return parsed;
    }

    private Object parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(rawJson, Object.class);
        } catch (Exception ignored) {
            return Map.of("raw", rawJson);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRequiredArgs(AgentTool tool) {
        Object parsed = parseInputSchema(tool);
        if (!(parsed instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object required = map.get("required");
        if (!(required instanceof List<?> list)) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (Object entry : list) {
            if (entry == null) continue;
            String value = String.valueOf(entry).trim();
            if (!value.isBlank()) args.add(value);
        }
        return args;
    }

    private String truncate(String value, int maxChars) {
        if (value == null) return null;
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}

