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
                .baseInjected(seed.baseInjected)
                .build();
    }

    private List<ToolSeed> coreSeeds() {
        return List.of(
                // Always-on (cross-cutting). baseInjected=true.
                seed("question", "Ask user clarification/approval question", "workflow", "workflow", false, true, Map.of("question", "string"), true),
                seed("plan_exit", "Exit plan mode", "workflow", "workflow", false, false, Map.of(), true),
                seed("todowrite", "Update todo list", "workflow", "workflow", false, false,
                        Map.of("todos", Map.of("type", "array", "items", Map.of("type", "object"))), true),
                seed("memoryview", "View memory index or file", "memory", "memory", false, false, Map.of("path", "string"), true),
                seed("memorycreate", "Create memory file", "memory", "memory", false, false,
                        Map.of("path", "string", "category", "string", "content", "string",
                                "tags", Map.of("type", "array", "items", Map.of("type", "string"))), true),
                seed("memorystrreplace", "Replace text in memory file", "memory", "memory", false, false, Map.of("path", "string", "old_text", "string", "new_text", "string"), true),
                seed("memoryinsert", "Insert text into memory file at line", "memory", "memory", false, false, Map.of("path", "string", "after_line", "number", "text", "string"), true),
                seed("memorydelete", "Delete memory file", "memory", "memory", false, false, Map.of("path", "string"), true),
                seed("memoryrename", "Rename memory file", "memory", "memory", false, false, Map.of("old_path", "string", "new_path", "string"), true),

                // Retrieval-eligible (semantic match required). baseInjected=false. Descriptions clarify scope so the model doesn't grab them for remote-hosted entities.
                seed("read", "Read content of a file in the local workspace. Local files only — does not access GitHub, remote repos, web URLs, or external systems; use the appropriate MCP/integration tool for those.", "filesystem", "filesystem", false, false, Map.of("path", "string"), false),
                seed("glob", "Glob files in the local workspace by path pattern. Local workspace only — does not list remote repos, GitHub contents, or external storage.", "filesystem", "filesystem", false, false, Map.of("path", "string", "glob", "string"), false),
                seed("grep", "Search text patterns in local workspace files. Local workspace only — does not search remote repos, GitHub, web, or external systems.", "filesystem", "filesystem", false, false, Map.of("path", "string", "pattern", "string"), false),
                seed("edit", "Edit existing file content in the local workspace. Local files only.", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string"), false),
                seed("write", "Write file content in the local workspace. Local files only.", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string"), false),
                seed("apply_patch", "Apply patch edits to a local workspace file. Local files only.", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string"), false),
                seed("bash", "Execute a shell command on the agent host. Use only when the user explicitly asks for a shell command; prefer domain-specific tools for everything else.", "shell", "shell", false, true, Map.of("command", "string"), false),
                seed("webfetch", "Fetch a public web page by URL. Use only when you have a specific URL to fetch and there is no MCP/integration tool for the underlying service.", "web", "web", false, false, Map.of("url", "string"), false),
                seed("websearch", "General web search. Prefer a domain-specific MCP/integration tool when the user's question is about a registered service (GitHub, Stripe, Linear, etc.).", "web", "web", false, false, Map.of("search_term", "string"), false),
                seed("codesearch", "Search code on the public web. Prefer a domain-specific MCP/integration tool when the user's question is about a known repo or service.", "web", "web", false, false, Map.of("search_term", "string"), false),
                seed("task", "Dispatch a background worker task within the agent runtime.", "workflow", "workflow", false, false, Map.of("task", "string"), false),
                seed("skill", "Run or inspect a registered skill by name.", "integration", "integration", false, false, Map.of("name", "string"), false)
        );
    }

    private List<ToolSeed> experimentalSeeds() {
        return List.of(
                seed("lsp", "Run language server operation", "integration", "integration", true, false, Map.of("operation", "string"), false),
                seed("batch", "Execute batch operations", "workflow", "workflow", true, false,
                        Map.of("tasks", Map.of("type", "array", "items", Map.of("type", "object"))), false),
                seed("parallel_task", "Run tasks in parallel", "workflow", "workflow", true, false,
                        Map.of("tasks", Map.of("type", "array", "items", Map.of("type", "object"))), false),
                seed("agent_send", "Send agent-to-agent message", "integration", "integration", true, false, Map.of("to", "string", "message", "string"), false),
                seed("agent_receive", "Receive agent message", "integration", "integration", true, false, Map.of(), false),
                seed("pipeline", "Run pipeline steps", "workflow", "workflow", true, false,
                        Map.of("steps", Map.of("type", "array", "items", Map.of("type", "object"))), false),
                seed("memory_save", "Save memory artifact", "memory", "memory", true, false, Map.of("text", "string"), false),
                seed("memory_search", "Search memory artifacts", "memory", "memory", true, false, Map.of("query", "string"), false)
        );
    }

    private ToolSeed seed(String name, String description, String executionKind, String permissionScope, boolean experimental, boolean requiresApproval, Map<String, Object> inputSchema, boolean baseInjected) {
        return new ToolSeed(name, description, executionKind, permissionScope, experimental, requiresApproval, inputSchema, baseInjected);
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
                            Map<String, Object> inputSchema,
                            boolean baseInjected) {
    }

    public record InstallResult(int created, int updated, int total) {}
}
