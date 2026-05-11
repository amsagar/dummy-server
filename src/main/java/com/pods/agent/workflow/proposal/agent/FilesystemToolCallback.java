package com.pods.agent.workflow.proposal.agent;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.service.ToolExecutionService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Generic filesystem read/glob/grep/write/edit/apply_patch callback that
 * delegates to {@link ToolExecutionService}. Sandboxing comes from the caller
 * wrapping invocation in
 * {@link com.pods.agent.service.workspace.WorkspaceContextHolder#withWorkspace}
 * — the underlying service then resolves every path under the session's
 * workspace.
 *
 * <p>The synthetic {@link AgentTool} carries only the fields the executor
 * needs ({@code name}, {@code executionKind}, {@code enabled}); it is never
 * persisted.
 */
public final class FilesystemToolCallback implements ToolCallback {

    private final String name;
    private final String description;
    private final String inputSchema;
    private final ToolExecutionService toolExecutionService;

    public FilesystemToolCallback(String name,
                                  String description,
                                  String inputSchema,
                                  ToolExecutionService toolExecutionService) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.toolExecutionService = toolExecutionService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String jsonInput) {
        return call(jsonInput, null);
    }

    @Override
    public String call(String jsonInput, ToolContext toolContext) {
        AgentTool tool = AgentTool.builder()
                .name(name)
                .executionKind("filesystem")
                .enabled(true)
                .build();
        ToolExecutionService.ExecutionResult result = toolExecutionService.execute(tool,
                jsonInput == null || jsonInput.isBlank() ? "{}" : jsonInput);
        if (result == null) {
            return "{\"success\":false,\"error\":\"no_result\"}";
        }
        if (!result.success()) {
            return "{\"success\":false,\"error\":\"" + escape(result.error()) + "\"}";
        }
        return result.body() == null ? "" : result.body();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
