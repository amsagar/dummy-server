package com.pods.agent.service;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FrameworkToolPackService {
    private final AgentDomainRepository domainRepository;
    private final AgentToolRepository toolRepository;

    public FrameworkToolPackService(AgentDomainRepository domainRepository,
                                    AgentToolRepository toolRepository) {
        this.domainRepository = domainRepository;
        this.toolRepository = toolRepository;
    }

    public InstallResult installDefaults() {
        AgentDomain coreDomain = ensureDomain("Framework Core Tools", "Built-in AI-agent core framework tools");
        AgentDomain expDomain = ensureDomain("Framework Experimental Tools", "Built-in AI-agent experimental framework tools");

        int created = 0;
        int updated = 0;
        List<ToolSeed> seeds = new ArrayList<>(coreSeeds());
        seeds.addAll(experimentalSeeds());

        for (ToolSeed seed : seeds) {
            AgentDomain targetDomain = seed.experimental ? expDomain : coreDomain;
            var existing = toolRepository.findByDomainId(targetDomain.getId()).stream()
                    .filter(t -> t.getName().equalsIgnoreCase(seed.name))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                toolRepository.save(toTool(targetDomain.getId(), seed));
                created++;
            } else {
                AgentTool merged = toTool(targetDomain.getId(), seed);
                merged.setId(existing.getId());
                merged.setCreatedAt(existing.getCreatedAt());
                merged.setEnabled(existing.isEnabled());
                toolRepository.update(merged);
                updated++;
            }
        }
        return new InstallResult(created, updated, seeds.size());
    }

    private AgentDomain ensureDomain(String name, String description) {
        return domainRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> domainRepository.save(AgentDomain.builder()
                        .name(name)
                        .description(description)
                        .enabled(true)
                        .build()));
    }

    private AgentTool toTool(String domainId, ToolSeed seed) {
        return AgentTool.builder()
                .domainId(domainId)
                .name(seed.name)
                .description(seed.description)
                .sourceType("framework_default")
                .executionKind(seed.executionKind)
                .permissionScope(seed.permissionScope)
                .requiresApproval(seed.requiresApproval)
                .experimental(seed.experimental)
                .inputSchemaVersion(1)
                .method("POST")
                .endpoint("/framework/" + seed.name)
                .requestSchema(toJson(seed.inputSchema))
                .responseSchema("{}")
                .sampleRequest("{}")
                .sampleResponse("{}")
                .enabled(true)
                .build();
    }

    private List<ToolSeed> coreSeeds() {
        return List.of(
                seed("question", "Ask user clarification/approval question", "workflow", "workflow", false, true, Map.of("question", "string")),
                seed("bash", "Execute shell command", "shell", "shell", false, true, Map.of("command", "string")),
                seed("read", "Read file content", "filesystem", "filesystem", false, false, Map.of("path", "string")),
                seed("glob", "Glob files in workspace", "filesystem", "filesystem", false, false, Map.of("path", "string", "glob", "string")),
                seed("grep", "Search text pattern in files", "filesystem", "filesystem", false, false, Map.of("path", "string", "pattern", "string")),
                seed("edit", "Edit existing file content", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string")),
                seed("write", "Write file content", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string")),
                seed("task", "Dispatch worker task", "workflow", "workflow", false, false, Map.of("task", "string")),
                seed("webfetch", "Fetch page content by URL", "web", "web", false, false, Map.of("url", "string")),
                seed("todowrite", "Update todo list", "workflow", "workflow", false, false,
                        Map.of("todos", Map.of("type", "array", "items", Map.of("type", "object")))),
                seed("websearch", "Search web snippets", "web", "web", false, false, Map.of("search_term", "string")),
                seed("codesearch", "Search code on web", "web", "web", false, false, Map.of("search_term", "string")),
                seed("skill", "Run or inspect skills", "integration", "integration", false, false, Map.of("name", "string")),
                seed("memoryview", "View memory index or file", "memory", "memory", false, false, Map.of("path", "string")),
                seed("memorycreate", "Create memory file", "memory", "memory", false, false,
                        Map.of("path", "string", "category", "string", "content", "string",
                                "tags", Map.of("type", "array", "items", Map.of("type", "string")))),
                seed("memorystrreplace", "Replace text in memory file", "memory", "memory", false, false, Map.of("path", "string", "old_text", "string", "new_text", "string")),
                seed("memoryinsert", "Insert text into memory file at line", "memory", "memory", false, false, Map.of("path", "string", "after_line", "number", "text", "string")),
                seed("memorydelete", "Delete memory file", "memory", "memory", false, false, Map.of("path", "string")),
                seed("memoryrename", "Rename memory file", "memory", "memory", false, false, Map.of("old_path", "string", "new_path", "string")),
                seed("apply_patch", "Apply patch edits", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string")),
                seed("plan_exit", "Exit plan mode", "workflow", "workflow", false, false, Map.of())
        );
    }

    private List<ToolSeed> experimentalSeeds() {
        return List.of(
                seed("lsp", "Run language server operation", "integration", "integration", true, false, Map.of("operation", "string")),
                seed("batch", "Execute batch operations", "workflow", "workflow", true, false,
                        Map.of("tasks", Map.of("type", "array", "items", Map.of("type", "object")))),
                seed("parallel_task", "Run tasks in parallel", "workflow", "workflow", true, false,
                        Map.of("tasks", Map.of("type", "array", "items", Map.of("type", "object")))),
                seed("agent_send", "Send agent-to-agent message", "integration", "integration", true, false, Map.of("to", "string", "message", "string")),
                seed("agent_receive", "Receive agent message", "integration", "integration", true, false, Map.of()),
                seed("pipeline", "Run pipeline steps", "workflow", "workflow", true, false,
                        Map.of("steps", Map.of("type", "array", "items", Map.of("type", "object")))),
                seed("memory_save", "Save memory artifact", "memory", "memory", true, false, Map.of("text", "string")),
                seed("memory_search", "Search memory artifacts", "memory", "memory", true, false, Map.of("query", "string"))
        );
    }

    private ToolSeed seed(String name, String description, String executionKind, String permissionScope, boolean experimental, boolean requiresApproval, Map<String, Object> inputSchema) {
        return new ToolSeed(name, description, executionKind, permissionScope, experimental, requiresApproval, inputSchema);
    }

    private String toJson(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ToolSeed(String name,
                            String description,
                            String executionKind,
                            String permissionScope,
                            boolean experimental,
                            boolean requiresApproval,
                            Map<String, Object> inputSchema) {
    }

    public record InstallResult(int created, int updated, int total) {}
}
