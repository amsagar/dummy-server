package com.pods.agent.service;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        Set<String> activeSeedNames = seeds.stream()
                .map(seed -> seed.name.toLowerCase())
                .collect(Collectors.toSet());

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

        // Disable framework-default tools that are no longer present in the seed list.
        // This prevents removed defaults from lingering in existing environments.
        disableStaleFrameworkDefaults(coreDomain.getId(), activeSeedNames);
        disableStaleFrameworkDefaults(expDomain.getId(), activeSeedNames);

        return new InstallResult(created, updated, seeds.size());
    }

    private void disableStaleFrameworkDefaults(String domainId, Set<String> activeSeedNames) {
        for (AgentTool tool : toolRepository.findByDomainId(domainId)) {
            if (!"framework_default".equalsIgnoreCase(tool.getSourceType())) continue;
            String name = tool.getName() == null ? "" : tool.getName().toLowerCase();
            if (activeSeedNames.contains(name)) continue;
            if (!tool.isEnabled()) continue;
            tool.setEnabled(false);
            toolRepository.update(tool);
        }
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
                seed("dtEvaluate",
                        "Evaluate a stored decision table by name with structured inputs. If you don't know the exact table name or its required inputs, call `dtSearch` (find by name/description) or `dtMetadata` (inspect a table's input/output shape) first. The `inputs` object must use the keys reported by `dtMetadata.requiredInputs`.",
                        "integration", "workflow", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "tableName", Map.of("type", "string"),
                                        "inputs", Map.of("type", "object")
                                ),
                                "required", List.of("tableName")
                        ), true),
                seed("dtList",
                        "List stored decision tables (name, description, hitPolicy, updatedAt). Use this when the user asks what tables exist or wants a catalog overview. For name lookup by partial match, prefer `dtSearch`.",
                        "integration", "workflow", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of("limit", Map.of("type", "number")),
                                "required", List.of()
                        ), true),
                seed("dtSearch",
                        "Find decision tables by free-text query against name and description (lexical scoring, no embeddings). Use this to resolve a user-phrased table name to a real stored one before calling `dtEvaluate`.",
                        "integration", "workflow", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string"),
                                        "topK", Map.of("type", "number")
                                ),
                                "required", List.of("query")
                        ), true),
                seed("dtMetadata",
                        "Describe a stored decision table: input columns, output columns, required inputs, hit policy, rule count. Call this before `dtEvaluate` to know exactly which keys the `inputs` map must contain. Pass `includeRules: true` to also receive the full rule rows.",
                        "integration", "workflow", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "name", Map.of("type", "string"),
                                        "includeRules", Map.of("type", "boolean")
                                ),
                                "required", List.of("name")
                        ), true),
                seed("toolsearch", "Search registered tools (framework, imported, and MCP) using semantic + lexical ranking.", "integration", "workflow", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string"),
                                        "topK", Map.of("type", "number"),
                                        "includeMcp", Map.of("type", "boolean"),
                                        "includeFramework", Map.of("type", "boolean")
                                ),
                                "required", List.of("query")
                        ), true),
                seed("skillsearch", "Search available skills using semantic + lexical ranking over skill name/description and SKILL.md content.", "integration", "workflow", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string"),
                                        "topK", Map.of("type", "number")
                                ),
                                "required", List.of("query")
                        ), true),

                // Retrieval-eligible (semantic match required). baseInjected=false. Descriptions clarify scope so the model doesn't grab them for remote-hosted entities.
                seed("read",
                        "Read content of a file in the local workspace. Supports line-based pagination via `offset` (1-based starting line) and `limit` (max lines to return); when the file extends past the returned range, the response footer reports the next offset. Local files only — does not access GitHub, remote repos, web URLs, or external systems; use the appropriate MCP/integration tool for those.",
                        "filesystem", "filesystem", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "Workspace-relative file path."),
                                        "offset", Map.of("type", "number", "description", "1-based line number to start reading from. Default 1."),
                                        "limit", Map.of("type", "number", "description", "Maximum number of lines to return. Default 2000, capped at 5000.")
                                ),
                                "required", List.of("path")
                        ), false),
                seed("glob",
                        "Glob files in the local workspace by path pattern. Supports pagination via `offset` (0-based index of the first match to return) and `limit` (max matches per call); the response carries `total` and a `nextOffset` hint when more results exist. Local workspace only — does not list remote repos, GitHub contents, or external storage.",
                        "filesystem", "filesystem", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "Workspace-relative root directory to search from. Defaults to the workspace root."),
                                        "glob", Map.of("type", "string", "description", "Glob pattern (e.g. `**/*.json`, `orders/*/run.json`). Defaults to `**/*`."),
                                        "offset", Map.of("type", "number", "description", "0-based index of the first match to return. Default 0."),
                                        "limit", Map.of("type", "number", "description", "Maximum number of matches to return. Default 200, capped at 2000.")
                                ),
                                "required", List.of()
                        ), false),
                seed("grep",
                        "Search text patterns in local workspace files. Supports pagination via `offset` (0-based index of the first hit to return) and `limit` (max hits per call); the response carries `total` and a `nextOffset` hint when more results exist. Local workspace only — does not search remote repos, GitHub, web, or external systems.",
                        "filesystem", "filesystem", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "Workspace-relative root directory to search under. Defaults to the workspace root."),
                                        "pattern", Map.of("type", "string", "description", "Regex pattern (case-insensitive) to match against each file line."),
                                        "offset", Map.of("type", "number", "description", "0-based index of the first hit to return. Default 0."),
                                        "limit", Map.of("type", "number", "description", "Maximum number of hits to return. Default 300, capped at 2000.")
                                ),
                                "required", List.of("pattern")
                        ), false),
                seed("edit", "Surgically edit a local workspace file by replacing one occurrence of `old_text` with `new_text`. The match must be UNIQUE in the file — include enough surrounding context to disambiguate. Use this for one-shot string replacements. For multi-hunk edits, use apply_patch instead. Local files only.", "filesystem", "filesystem", false, true, Map.of("path", "string", "old_text", "string", "new_text", "string"), false),
                seed("write", "Overwrite a local workspace file with the entire `content` (whole-file rewrite). Use sparingly — prefer `edit` or `apply_patch` for targeted changes. Local files only.", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string"), false),
                seed("apply_patch", "Apply one or more diff hunks to a local workspace file. `content` must contain blocks of:\n<<<<<<< ORIGINAL\n<exact existing text>\n=======\n<replacement text>\n>>>>>>> UPDATED\nORIGINAL must match the file byte-for-byte (including indentation) and must be unique. Multiple hunks are supported by stacking blocks. Local files only.", "filesystem", "filesystem", false, true, Map.of("path", "string", "content", "string"), false),
                seed("bash", "Execute a shell command on the agent host. Use only when the user explicitly asks for a shell command; prefer domain-specific tools for everything else.", "shell", "shell", false, true, Map.of("command", "string"), false),
                seed("webfetch", "Fetch a public web page by URL. Use only when you have a specific URL to fetch and there is no MCP/integration tool for the underlying service.", "web", "web", false, false, Map.of("url", "string"), false),
                seed("websearch", "General web search. Prefer a domain-specific MCP/integration tool when the user's question is about a registered service (GitHub, Stripe, Linear, etc.).", "web", "web", false, false, Map.of("search_term", "string"), false),
                seed("codesearch", "Search code on the public web. Prefer a domain-specific MCP/integration tool when the user's question is about a known repo or service.", "web", "web", false, false, Map.of("search_term", "string"), false),
                seed("task", "Dispatch a background worker task within the agent runtime.", "workflow", "workflow", false, false, Map.of("task", "string"), false),
                seed("skill", "Run or inspect a registered skill by name.", "integration", "integration", false, false, Map.of("name", "string"), false),
                // Order-validation companion tool. Materializes one order's
                // run data into the session workspace under
                // orders/<orderId>/ so the model can read/glob/grep it.
                // Available only to the ov-* agent profiles — outside
                // those, AgentRuntimeService strips it from the catalog.
                seed("ovLoadOrder",
                        "Load all available data for ONE order's validation run into the workspace under orders/<orderId>/ so file-system tools (read, glob, grep) can analyze it. Call this BEFORE answering any detailed question about a specific order. Idempotent — calling twice refreshes the cache (2-hour TTL).",
                        "integration", "integration", false, false,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "orderId", Map.of("type", "string",
                                                "description", "Order id like 600030447, or a synthetic run id (sessionId__turnId).")
                                ),
                                "required", List.of("orderId")
                        ), true)
        );
    }

    private List<ToolSeed> experimentalSeeds() {
        return List.of(
                seed("lsp", "Run language server operation", "integration", "integration", true, false, Map.of("operation", "string"), false),
                seed("batch", "Execute batch operations", "workflow", "workflow", true, false,
                        Map.of("tasks", Map.of("type", "array", "items", Map.of("type", "object"))), false),
                // parallel_task removed — the LLM kept hallucinating it as a
                // toolName inside trace-compiled BPMNs (it's a workflow
                // primitive, not a real registered tool). Disabling here lets
                // disableStaleFrameworkDefaults() auto-disable existing rows
                // on next startup.
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
