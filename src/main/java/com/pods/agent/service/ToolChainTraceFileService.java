package com.pods.agent.service;

import com.pods.agent.domain.RuntimeEvent;
import com.pods.agent.repository.RuntimeEventRepository;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ToolChainTraceFileService {
    private final RuntimeEventRepository runtimeEventRepository;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final ObjectMapper objectMapper;

    public ToolChainTraceFileService(RuntimeEventRepository runtimeEventRepository,
                                     SessionWorkspaceService sessionWorkspaceService,
                                     ObjectMapper objectMapper) {
        this.runtimeEventRepository = runtimeEventRepository;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.objectMapper = objectMapper;
    }

    public String writeTurnTrace(Path workspace,
                                 String sessionId,
                                 String turnId,
                                 String userPrompt,
                                 String assistantResponse) {
        List<RuntimeEvent> events = runtimeEventRepository.findByTurnId(turnId);
        List<Map<String, Object>> eventRows = new ArrayList<>();
        for (RuntimeEvent event : events) {
            if (event == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", event.getId());
            row.put("sessionId", event.getSessionId());
            row.put("turnId", event.getTurnId());
            row.put("eventType", event.getEventType());
            row.put("createdAt", event.getCreatedAt());
            row.put("payload", parseFlexible(event.getPayload()));
            eventRows.add(row);
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceVersion", 1);
        trace.put("sessionId", sessionId);
        trace.put("turnId", turnId);
        trace.put("generatedAt", System.currentTimeMillis());
        trace.put("userPrompt", userPrompt == null ? "" : userPrompt);
        trace.put("assistantResponse", assistantResponse == null ? "" : assistantResponse);
        trace.put("events", eventRows);

        String relativePath = ".pods-agent/turns/" + sanitize(turnId) + "/toolchain-trace.json";
        sessionWorkspaceService.writeText(workspace, relativePath, toPrettyJson(trace));
        return relativePath;
    }

    private Object parseFlexible(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown-turn";
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}
