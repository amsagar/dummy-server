package com.pods.agent.service;

import com.pods.agent.domain.Memory;
import com.pods.agent.repository.MemoryRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MemoryService {
    private static final List<String> ALLOWED_CATEGORIES = List.of("user", "feedback", "project", "reference");

    private final MemoryRepository memoryRepository;
    private final SkillFileStorageService storageService;

    public MemoryService(MemoryRepository memoryRepository,
                         SkillFileStorageService storageService) {
        this.memoryRepository = memoryRepository;
        this.storageService = storageService;
    }

    public Memory saveOrUpdateMemory(String userId,
                                     String sessionId,
                                     String category,
                                     String memoryFilePath,
                                     String content,
                                     List<String> tags) {
        String normalizedCategory = normalizeCategory(category);
        String normalizedPath = normalizePath(memoryFilePath);
        long now = System.currentTimeMillis();
        Memory memory = memoryRepository.findByUserIdAndFilePath(userId, normalizedPath)
                .orElseGet(() -> Memory.builder()
                        .id(UUID.randomUUID().toString())
                        .userId(userId)
                        .createdAt(now)
                        .build());
        memory.setSessionId(sessionId);
        memory.setCategory(normalizedCategory);
        memory.setMemoryFilePath(normalizedPath);
        memory.setContent(content == null ? "" : content);
        memory.setTags(tags == null ? List.of() : tags);
        memory.setUpdatedAt(now);
        if (memory.getCreatedAt() <= 0) memory.setCreatedAt(now);

        if (memoryRepository.findByUserIdAndFilePath(userId, normalizedPath).isPresent()) {
            memoryRepository.update(memory);
        } else {
            memoryRepository.save(memory);
        }
        storageService.put(blobPath(userId, normalizedPath), memory.getContent().getBytes(StandardCharsets.UTF_8), "text/markdown");
        syncMemoryIndex(userId);
        return memory;
    }

    public Optional<Memory> getMemoryByPath(String userId, String path) {
        return memoryRepository.findByUserIdAndFilePath(userId, normalizePath(path));
    }

    public List<Memory> listMemories(String userId, String category) {
        if (category == null || category.isBlank()) {
            return memoryRepository.findByUserId(userId);
        }
        return memoryRepository.findByUserIdAndCategory(userId, normalizeCategory(category));
    }

    public List<Memory> searchMemories(String userId, String query, int limit) {
        if (query == null || query.isBlank()) return listMemories(userId, null).stream().limit(limit).toList();
        return memoryRepository.searchByContent(userId, query.trim(), limit);
    }

    public boolean deleteMemoryByPath(String userId, String path) {
        Optional<Memory> memory = memoryRepository.findByUserIdAndFilePath(userId, normalizePath(path));
        if (memory.isEmpty()) return false;
        memoryRepository.delete(userId, memory.get().getId());
        storageService.delete(blobPath(userId, memory.get().getMemoryFilePath()));
        syncMemoryIndex(userId);
        return true;
    }

    public boolean renameMemoryPath(String userId, String oldPath, String newPath) {
        Optional<Memory> memoryOpt = memoryRepository.findByUserIdAndFilePath(userId, normalizePath(oldPath));
        if (memoryOpt.isEmpty()) return false;
        Memory memory = memoryOpt.get();
        String content = memory.getContent();
        memory.setMemoryFilePath(normalizePath(newPath));
        memory.setUpdatedAt(System.currentTimeMillis());
        memoryRepository.update(memory);
        storageService.put(blobPath(userId, memory.getMemoryFilePath()), content.getBytes(StandardCharsets.UTF_8), "text/markdown");
        storageService.delete(blobPath(userId, normalizePath(oldPath)));
        syncMemoryIndex(userId);
        return true;
    }

    public String buildInjectionPrompt(String userId, String query, int maxChars) {
        List<Memory> selected = searchMemories(userId, query == null ? "" : query, 10);
        if (selected.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("### Long-Term Memory (User Preferences & Context)\n");
        int budget = Math.max(256, maxChars);
        for (Memory memory : selected) {
            if (sb.length() >= budget) break;
            sb.append("- [").append(memory.getCategory()).append("] ")
                    .append(memory.getMemoryFilePath()).append(": ")
                    .append(truncate(singleLine(memory.getContent()), 240))
                    .append("\n");
        }
        if (sb.length() > budget) {
            return sb.substring(0, budget - 16) + "\n...[truncated]";
        }
        return sb.toString().trim();
    }

    public String memoryView(String userId, String path) {
        if (path == null || path.isBlank() || "MEMORY.md".equalsIgnoreCase(path.trim())) {
            return renderMemoryIndexMarkdown(userId);
        }
        return memoryRepository.findByUserIdAndFilePath(userId, normalizePath(path))
                .map(Memory::getContent)
                .orElse("Memory file not found: " + path);
    }

    public String memoryCreate(String userId, String sessionId, String path, String category, String content, List<String> tags) {
        Memory memory = saveOrUpdateMemory(userId, sessionId, category, path, content, tags);
        return "Created memory file: " + memory.getMemoryFilePath();
    }

    public String memoryReplace(String userId, String path, String oldText, String newText) {
        Memory memory = memoryRepository.findByUserIdAndFilePath(userId, normalizePath(path))
                .orElseThrow(() -> new IllegalArgumentException("Memory file not found: " + path));
        String content = memory.getContent();
        if (oldText == null || oldText.isBlank() || !content.contains(oldText)) {
            throw new IllegalArgumentException("Target text not found in memory file");
        }
        String updated = content.replace(oldText, newText == null ? "" : newText);
        saveOrUpdateMemory(userId, memory.getSessionId(), memory.getCategory(), memory.getMemoryFilePath(), updated, memory.getTags());
        return "Updated memory file: " + memory.getMemoryFilePath();
    }

    public String memoryInsert(String userId, String path, Integer afterLine, String text) {
        Memory memory = memoryRepository.findByUserIdAndFilePath(userId, normalizePath(path))
                .orElseThrow(() -> new IllegalArgumentException("Memory file not found: " + path));
        List<String> lines = new ArrayList<>(List.of(memory.getContent().split("\\R", -1)));
        int line = afterLine == null ? lines.size() : Math.max(0, Math.min(afterLine, lines.size()));
        lines.add(line, text == null ? "" : text);
        String updated = String.join("\n", lines);
        saveOrUpdateMemory(userId, memory.getSessionId(), memory.getCategory(), memory.getMemoryFilePath(), updated, memory.getTags());
        return "Inserted text into: " + memory.getMemoryFilePath();
    }

    public String memoryDelete(String userId, String path) {
        boolean deleted = deleteMemoryByPath(userId, path);
        if (!deleted) throw new IllegalArgumentException("Memory file not found: " + path);
        return "Deleted memory file: " + normalizePath(path);
    }

    public String memoryRename(String userId, String oldPath, String newPath) {
        boolean renamed = renameMemoryPath(userId, oldPath, newPath);
        if (!renamed) throw new IllegalArgumentException("Memory file not found: " + oldPath);
        return "Renamed memory file to: " + normalizePath(newPath);
    }

    public String renderMemoryIndexMarkdown(String userId) {
        List<Memory> all = memoryRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(Memory::getUpdatedAt).reversed())
                .toList();
        if (all.isEmpty()) return "# MEMORY\n\n_No memories yet._";
        String lines = all.stream()
                .map(m -> "- [" + titleFromPath(m.getMemoryFilePath()) + "](" + m.getMemoryFilePath() + ") - "
                        + truncate(singleLine(m.getContent()), 120))
                .collect(Collectors.joining("\n"));
        return "# MEMORY\n\n" + lines + "\n";
    }

    private void syncMemoryIndex(String userId) {
        String index = renderMemoryIndexMarkdown(userId);
        storageService.put(blobPath(userId, "MEMORY.md"), index.getBytes(StandardCharsets.UTF_8), "text/markdown");
    }

    private String normalizeCategory(String category) {
        String low = category == null ? "reference" : category.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CATEGORIES.contains(low)) {
            return "reference";
        }
        return low;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("memory path is required");
        String normalized = path.replace("\\", "/").trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.contains("..")) throw new IllegalArgumentException("invalid memory path");
        return normalized;
    }

    private String blobPath(String userId, String filePath) {
        return "memories/" + userId + "/" + normalizePath(filePath);
    }

    private String titleFromPath(String path) {
        String base = normalizePath(path);
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        return base.replace(".md", "").replace('_', ' ').replace('-', ' ');
    }

    private String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
