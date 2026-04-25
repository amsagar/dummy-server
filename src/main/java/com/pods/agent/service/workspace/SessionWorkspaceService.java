package com.pods.agent.service.workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SessionWorkspaceService {
    private final Map<String, Path> workspaceBySession = new ConcurrentHashMap<>();
    private final Path root;

    public SessionWorkspaceService() {
        this.root = Path.of(System.getProperty("java.io.tmpdir"), "pods-agent-vfs");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize workspace root", e);
        }
    }

    public Path getOrCreate(String sessionId) {
        return workspaceBySession.computeIfAbsent(sessionId, this::initWorkspace);
    }

    public Path get(String sessionId) {
        return workspaceBySession.get(sessionId);
    }

    public void evict(String sessionId) {
        Path p = workspaceBySession.remove(sessionId);
        if (p != null) {
            tryDelete(p);
        }
    }

    public Path ensureFile(Path workspace, String relativePath) {
        try {
            Path file = workspace.resolve(relativePath).normalize();
            if (!file.startsWith(workspace)) {
                throw new IllegalArgumentException("Workspace path escapes root");
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare workspace file: " + relativePath, e);
        }
    }

    public void writeText(Path workspace, String relativePath, String content) {
        try {
            Path file = ensureFile(workspace, relativePath);
            Files.writeString(file, content == null ? "" : content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing workspace file: " + relativePath, e);
        }
    }

    public void copyFileIntoWorkspace(Path workspace, Path source, String relativePath) {
        try {
            Path target = ensureFile(workspace, relativePath);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed copying file into workspace: " + relativePath, e);
        }
    }

    private Path initWorkspace(String sessionId) {
        Path dir = root.resolve(sessionId).normalize();
        try {
            Files.createDirectories(dir);
            Files.createDirectories(dir.resolve(".pods-agent"));
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create session workspace: " + sessionId, e);
        }
    }

    private void tryDelete(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (Exception e) {
            log.debug("[Workspace] Failed deleting {}: {}", path, e.getMessage());
        }
    }
}

