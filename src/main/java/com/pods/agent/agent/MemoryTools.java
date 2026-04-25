package com.pods.agent.agent;

import com.pods.agent.service.MemoryService;
import com.pods.agent.service.UserContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemoryTools {
    private final MemoryService memoryService;

    public MemoryTools(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public String memoryView(String path) {
        return memoryService.memoryView(requireUserId(), path);
    }

    public String memoryCreate(String path, String category, String content, List<String> tags, String sessionId) {
        return memoryService.memoryCreate(requireUserId(), sessionId, path, category, content, tags);
    }

    public String memoryStrReplace(String path, String oldText, String newText) {
        return memoryService.memoryReplace(requireUserId(), path, oldText, newText);
    }

    public String memoryInsert(String path, Integer afterLine, String text) {
        return memoryService.memoryInsert(requireUserId(), path, afterLine, text);
    }

    public String memoryDelete(String path) {
        return memoryService.memoryDelete(requireUserId(), path);
    }

    public String memoryRename(String oldPath, String newPath) {
        return memoryService.memoryRename(requireUserId(), oldPath, newPath);
    }

    private String requireUserId() {
        String userId = UserContextHolder.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Memory tools require authenticated user context");
        }
        return userId;
    }
}
