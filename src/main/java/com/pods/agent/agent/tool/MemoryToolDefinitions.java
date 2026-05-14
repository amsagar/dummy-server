package com.pods.agent.agent.tool;

import com.pods.agent.domain.AgentTool;

import java.util.List;

/**
 * Synthetic {@link AgentTool} definitions for the six always-on memory operations.
 * These are not persisted in {@code agent.agent_tools}; they are built in-memory and
 * appended by {@link AgentToolCallbackFactory} every turn so the LLM always has
 * access to long-term memory. Names match the dispatch in
 * {@code ToolExecutionService.executeMemory(...)} so calls route through the
 * existing {@code AgentToolCallback} machinery without any change.
 */
public final class MemoryToolDefinitions {

    public static final String EXECUTION_KIND = "memory";
    public static final String DOMAIN_ID = "memory";

    private static final String SCHEMA_VIEW = """
            {"type":"object","properties":{"path":{"type":"string","description":"Memory file path, or 'MEMORY.md' for the index. Default: MEMORY.md"}},"required":[]}""";

    private static final String SCHEMA_CREATE = """
            {"type":"object","properties":{"path":{"type":"string","description":"Relative memory file path, e.g. 'user/profile.md'"},"category":{"type":"string","enum":["user","feedback","project","reference"],"description":"Memory category"},"content":{"type":"string","description":"Full markdown body of the memory file"},"tags":{"type":"array","items":{"type":"string"},"description":"Optional tags"},"session_id":{"type":"string","description":"Optional originating session id"}},"required":["path","category","content"]}""";

    private static final String SCHEMA_STR_REPLACE = """
            {"type":"object","properties":{"path":{"type":"string","description":"Memory file path to edit"},"old_text":{"type":"string","description":"Exact substring to find"},"new_text":{"type":"string","description":"Replacement text"}},"required":["path","old_text","new_text"]}""";

    private static final String SCHEMA_INSERT = """
            {"type":"object","properties":{"path":{"type":"string","description":"Memory file path to edit"},"after_line":{"type":"integer","description":"Line index to insert after (0-based). Omit to append at end."},"text":{"type":"string","description":"Text to insert"}},"required":["path","text"]}""";

    private static final String SCHEMA_DELETE = """
            {"type":"object","properties":{"path":{"type":"string","description":"Memory file path to delete"}},"required":["path"]}""";

    private static final String SCHEMA_RENAME = """
            {"type":"object","properties":{"old_path":{"type":"string","description":"Current memory file path"},"new_path":{"type":"string","description":"New memory file path"}},"required":["old_path","new_path"]}""";

    public static final AgentTool MEMORY_VIEW = build(
            "memoryview",
            "Read a memory file by path, or read 'MEMORY.md' for the index of all stored memories for the current user. Call this at session start to discover what you already know about the user.",
            SCHEMA_VIEW);

    public static final AgentTool MEMORY_CREATE = build(
            "memorycreate",
            "Create or overwrite a memory file with full markdown content. Use category 'user' for stable user facts (name, role, preferences), 'feedback' for corrections to apply going forward, 'project' for active-work context, 'reference' for pointers to external systems. Always update 'MEMORY.md' index after non-trivial additions via memorystrreplace.",
            SCHEMA_CREATE);

    public static final AgentTool MEMORY_STR_REPLACE = build(
            "memorystrreplace",
            "Replace an exact substring inside an existing memory file. Use for small edits and for updating the MEMORY.md index.",
            SCHEMA_STR_REPLACE);

    public static final AgentTool MEMORY_INSERT = build(
            "memoryinsert",
            "Insert text into a memory file after a specific line index (or append when after_line is omitted).",
            SCHEMA_INSERT);

    public static final AgentTool MEMORY_DELETE = build(
            "memorydelete",
            "Delete a memory file. The MEMORY.md index is auto-synced after deletion.",
            SCHEMA_DELETE);

    public static final AgentTool MEMORY_RENAME = build(
            "memoryrename",
            "Rename or move a memory file to a new path. The MEMORY.md index is auto-synced after rename.",
            SCHEMA_RENAME);

    private static final List<AgentTool> ALL = List.of(
            MEMORY_VIEW, MEMORY_CREATE, MEMORY_STR_REPLACE,
            MEMORY_INSERT, MEMORY_DELETE, MEMORY_RENAME);

    private MemoryToolDefinitions() {
    }

    public static List<AgentTool> all() {
        return ALL;
    }

    private static AgentTool build(String name, String description, String schema) {
        return AgentTool.builder()
                .id("builtin:memory:" + name)
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
