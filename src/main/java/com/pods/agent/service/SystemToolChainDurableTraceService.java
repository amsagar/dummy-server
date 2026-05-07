package com.pods.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
@Slf4j
public class SystemToolChainDurableTraceService {

    public Optional<String> persistAsJson(Path workspace, String traceRelativePath) {
        if (workspace == null || traceRelativePath == null || traceRelativePath.isBlank()) return Optional.empty();
        try {
            Path source = workspace.resolve(traceRelativePath).normalize();
            if (!source.startsWith(workspace) || !Files.exists(source)) return Optional.empty();
            return Optional.of(Files.readString(source));
        } catch (Exception e) {
            log.warn("[SystemToolChainDurableTraceService] Failed persisting trace as JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> restoreToWorkspace(Path workspace,
                                               String durableTraceJson,
                                               String turnId) {
        if (workspace == null || durableTraceJson == null || durableTraceJson.isBlank()) return Optional.empty();
        try {
            String relative = ".pods-agent/turns/" + sanitize(turnId) + "/toolchain-trace.json";
            Path target = workspace.resolve(relative).normalize();
            if (!target.startsWith(workspace)) return Optional.empty();
            Files.createDirectories(target.getParent());
            Files.writeString(target, durableTraceJson);
            return Optional.of(relative);
        } catch (Exception e) {
            log.warn("[SystemToolChainDurableTraceService] Failed restoring trace JSON to workspace: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}
