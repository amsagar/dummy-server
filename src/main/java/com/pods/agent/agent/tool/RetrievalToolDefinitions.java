package com.pods.agent.agent.tool;

import com.pods.agent.domain.AgentTool;

import java.util.List;

/**
 * Synthetic {@link AgentTool} definitions for the always-on retrieval tools
 * ({@code toolsearch}, {@code skillsearch}) that let the LLM look up
 * registered tools and skills by semantic + lexical ranking. These are
 * defensive duplicates of the {@code framework_default} rows seeded by
 * {@code FrameworkToolPackService}; the factory dedupes by name when the
 * DB-seeded version is present, so this is a fallback for environments
 * where the bootstrap seed has not run.
 * <p>
 * Dispatch routes through {@code ToolExecutionService.executeIntegration}
 * (case {@code "integration"}) and is keyed by tool name.
 */
public final class RetrievalToolDefinitions {

    public static final String EXECUTION_KIND = "integration";
    public static final String DOMAIN_ID = "framework";

    private static final String SCHEMA_TOOLSEARCH = """
            {"type":"object","properties":{"query":{"type":"string","description":"Free-text query to match against registered tool names and descriptions"},"topK":{"type":"integer","description":"Maximum results to return (default 8)"},"includeMcp":{"type":"boolean","description":"Include MCP tools (default true)"},"includeFramework":{"type":"boolean","description":"Include framework tools (default true)"}},"required":["query"]}""";

    private static final String SCHEMA_SKILLSEARCH = """
            {"type":"object","properties":{"query":{"type":"string","description":"Free-text query to match against skill names, descriptions, and SKILL.md content"},"topK":{"type":"integer","description":"Maximum results to return (default 8)"}},"required":["query"]}""";

    public static final AgentTool TOOLSEARCH = build(
            "toolsearch",
            "Search registered tools (framework, imported, and MCP) using semantic + lexical ranking. Call this when the user request mentions an action whose matching tool is not in the current tool list.",
            SCHEMA_TOOLSEARCH);

    public static final AgentTool SKILLSEARCH = build(
            "skillsearch",
            "Search available skills using semantic + lexical ranking over skill name, description, and SKILL.md content. Call this first when a request may match a registered skill; load matches with the `skill` tool.",
            SCHEMA_SKILLSEARCH);

    private static final List<AgentTool> ALL = List.of(TOOLSEARCH, SKILLSEARCH);

    private RetrievalToolDefinitions() {
    }

    public static List<AgentTool> all() {
        return ALL;
    }

    private static AgentTool build(String name, String description, String schema) {
        return AgentTool.builder()
                .id("builtin:framework:" + name)
                .domainId(DOMAIN_ID)
                .name(name)
                .description(description)
                .sourceType("builtin")
                .executionKind(EXECUTION_KIND)
                .requiresApproval(false)
                .experimental(false)
                .inputSchemaVersion(1)
                .requestSchema(schema)
                .enabled(true)
                .baseInjected(true)
                .build();
    }
}
