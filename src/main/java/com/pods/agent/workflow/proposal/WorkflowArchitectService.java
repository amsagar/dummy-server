package com.pods.agent.workflow.proposal;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The Workflow Architect: a small read-only LLM subagent that converts a
 * single chat turn's execution log into a deterministic, reusable workflow
 * graph (a {@code ProcessDefDto} JSON).
 *
 * <p>This is the planning-layer counterpart to the deterministic runtime in
 * {@link com.pods.agent.workflow.engine.WorkflowManager}. The architect runs
 * once per chat turn after the turn completes, consumes the typed execution
 * log written to the session VFS by {@link
 * com.pods.agent.service.workspace.WorkflowTurnTraceService}, and emits a
 * draft workflow that can later be approved + materialized into
 * {@code agent.process_def}.
 *
 * <p>Tools available to the architect (curated allowlist — read-only):
 * <ul>
 *   <li>{@code read} — read a file under the session VFS root.</li>
 *   <li>{@code glob} — list files matching a glob pattern.</li>
 *   <li>{@code grep} — search files for a regex.</li>
 *   <li>{@code skill_load} — fetch the {@code workflow-architect} skill bundle
 *       (SKILL.md + references/templates) as a single text blob.</li>
 * </ul>
 *
 * <p>Sandboxing: the entire run executes inside {@link
 * WorkspaceContextHolder#withWorkspace(Path, java.util.function.Supplier)} so
 * the underlying {@link ToolExecutionService} restricts every read/glob/grep
 * to the session's {@code .pods-agent/} directory tree. There are no write
 * tools — the architect gathers context, returns the JSON document, and exits.
 *
 * <p>Termination: Spring AI's {@link ChatClient#prompt()} drives the tool
 * calling loop internally; the architect stops when the model emits a final
 * assistant message with no further tool calls. We keep the prompt narrow
 * ("after exploring, return ONLY the JSON") so the loop converges quickly.
 */
@Service
@Slf4j
public class WorkflowArchitectService {

    private static final String SKILL_NAME = "workflow-architect";

    private final ModelProviderRouter modelProviderRouter;
    private final ToolExecutionService toolExecutionService;
    private final SkillRegistryService skillRegistryService;
    private final ObjectMapper objectMapper;

    public WorkflowArchitectService(ModelProviderRouter modelProviderRouter,
                                    ToolExecutionService toolExecutionService,
                                    SkillRegistryService skillRegistryService,
                                    ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.toolExecutionService = toolExecutionService;
        this.skillRegistryService = skillRegistryService;
        this.objectMapper = objectMapper;
    }

    /** Inputs handed to the architect for one workflow generation. */
    public record GenerationContext(String sessionId,
                                    String turnId,
                                    String userPrompt,
                                    String assistantResponse,
                                    List<String> toolNames,
                                    ModelRef modelRef,
                                    Path workspaceRoot,
                                    Path turnFilePath) {}

    /**
     * Run the architect. Returns the model's final assistant message (which
     * should be a JSON document); the caller is responsible for extracting,
     * parsing, and validating it.
     */
    public Optional<String> draftWorkflowJson(GenerationContext ctx) {
        if (ctx == null || ctx.modelRef() == null || ctx.workspaceRoot() == null) {
            return Optional.empty();
        }
        try {
            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(ctx.modelRef(), true);
            ChatClient client = spec.client();

            String system = buildSystemPrompt();
            String user = buildUserPrompt(ctx);

            List<ToolCallback> tools = List.of(
                    new FilesystemToolCallback("read", "Read a UTF-8 text file from the session workspace.",
                            """
                            {"type":"object",
                             "required":["path"],
                             "properties":{
                               "path":{"type":"string","description":"Path relative to the session workspace root, e.g. '.pods-agent/workflow/execution-log-<id>.json'"}
                             }}
                            """,
                            toolExecutionService),
                    new FilesystemToolCallback("glob", "List files in the session workspace matching a glob pattern.",
                            """
                            {"type":"object",
                             "required":["glob"],
                             "properties":{
                               "glob":{"type":"string","description":"Ant-style glob, e.g. '.pods-agent/skills/**/*.md'"},
                               "path":{"type":"string","description":"Optional sub-root; defaults to '.'"}
                             }}
                            """,
                            toolExecutionService),
                    new FilesystemToolCallback("grep", "Search files in the session workspace for a regex.",
                            """
                            {"type":"object",
                             "required":["pattern"],
                             "properties":{
                               "pattern":{"type":"string"},
                               "path":{"type":"string","description":"Optional sub-root; defaults to '.'"}
                             }}
                            """,
                            toolExecutionService),
                    new SkillLoadCallback(skillRegistryService, objectMapper)
            );

            return WorkspaceContextHolder.withWorkspace(ctx.workspaceRoot(), () -> {
                var promptSpec = client.prompt()
                        .system(system)
                        .user(user)
                        .toolCallbacks(tools);
                if (spec.options() != null) {
                    promptSpec = promptSpec.options(spec.options());
                }
                String content = promptSpec.call().content();
                if (content == null || content.isBlank()) {
                    log.warn("[WorkflowArchitect] returned empty content for turn {}", ctx.turnId());
                    return Optional.<String>empty();
                }
                return Optional.of(content);
            });
        } catch (Exception e) {
            log.warn("[WorkflowArchitect] generation failed for turn {}: {}", ctx.turnId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String buildSystemPrompt() {
        return """
                You are the Workflow Architect. Your job is to convert one chat turn's
                execution log into a single deterministic, reusable workflow graph
                expressed as a ProcessDefDto JSON document.

                ========================================================================
                ZERO-TOLERANCE PROHIBITION — NO GHOST ACTIVITIES
                ========================================================================
                Before emitting ANY activity, run this filter:

                  for each AgentToolPlugin activity you are about to emit:
                      if properties.toolName ∉ the "Tools used in this turn" list:
                          DELETE the activity. Do not rationalize. Do not paraphrase.

                You MUST NOT emit activities named (case-insensitive prefix match):
                    "Load ...", "Init...", "Setup...", "Prepare...", "Bootstrap...",
                    "Warmup...", "Validate Input", "Normalize...", "Parse Request",
                    "Authenticate", "Get Token", "Refresh Session", "Log Result",
                    "Audit ...", "Track ...", "Finalize", "Cleanup", "Teardown",
                    "Notify", "Emit Event"
                unless the underlying toolName is literally in the tools-used list.

                There is NO tool called "skill". There is NO tool called
                "workflow-architect". There is NO tool called "loadSkill". Skills are
                baked into the chat-agent's prompt — they are NOT runtime tools.
                Activities like "Load Workflow Architect" or "Load Order Validation
                Skill" are 100% hallucinations and will be rejected.

                A workflow that does nothing (start → end) is correct if the turn
                had no tool calls. A workflow with fictional steps is broken.

                ========================================================================
                ZERO-TOLERANCE PROHIBITION — PRESERVE INPUT SHAPE FROM execution-log
                ========================================================================
                For every AgentToolPlugin activity you emit, you MUST look up the
                matching step in execution-log.steps (the one where step.tool ==
                your toolName) and reproduce the top-level keys of step.input
                VERBATIM in properties.input.

                If step.input had two top-level keys {tableName, inputs}, your
                properties.input must be a SecureSpel inline-map literal with
                BOTH keys:

                    "input": "#{ {'tableName': 'leg-sequence', 'inputs': #order} }"

                You MUST NOT collapse a multi-key input to a single variable
                reference like "#{#order}". That's the #1 way tools fail with
                "<key> is required" errors on first dispatch.

                Specific tools with known multi-key contracts:
                  - decisionTableEvaluate: requires {tableName, inputs}
                  - search/query tools: usually {query, limit?, filter?}
                  - update/mutate tools: usually {id, body} or {id, ...fields}
                  - paginated reads: {offset, limit, ...filter}

                Default rule: if step.input has N top-level keys, your
                properties.input literal MUST have N top-level keys with the
                same names. Replace ONLY the leaf values with variable refs
                (#someVar). Preserve the keys.
                ========================================================================

                You have a tiny toolbelt and must use it deliberately:

                Mandatory steps before writing JSON:
                  1. Call skill_load with name="workflow-architect" to load SKILL.md and
                     learn the canonical activity types, transition triggers, schema
                     rules, loop wiring, and templates.
                  2. Call read on the session's typed execution log (its path is given
                     in the user message under "Execution log file"). The log contains
                     typed steps (tool, loop, condition, parallel, ai_reasoning), the
                     planner state transitions, every tool call with input + output, the
                     architect notes the chat agent left, and the final response.
                  3. Optionally call glob / grep / read for any other files under
                     .pods-agent/ that look useful (e.g. skill reference docs).

                Hard rules for the JSON output:
                  - Return ONLY the JSON, no prose, no markdown fences.
                  - Match ProcessDefDto exactly: keys are id, name, version, packageId,
                    description, variables, activities, transitions.
                  - Separate deterministic logic (tool / loop / route) from AI reasoning
                    (ai_reasoning). The runtime is BARRED from calling the LLM unless
                    an ai_reasoning node is reached. Never use AiChatPlugin as a tool
                    activity for a judgement step \u2014 promote it to ai_reasoning.
                  - For EVERY tool name listed under "Tools used in this turn" you MUST
                    emit a tool activity with pluginName="AgentToolPlugin" and
                    properties {"toolName":"<exact name>", "input":"#{#someVar}"}.
                  - Activity "type" must be exactly one of: normal | tool | route |
                    subflow | foreach | while | batch | ai_reasoning. Never use BPMN
                    names. Use "route" with isStart=true / isEnd=true for entry / exit.
                  - Loop activities (foreach/while/batch) need maxIterations and three
                    edges: ON_SUCCESS to body entry, ON_NO_MATCH (or isDefault=true) to
                    exit, ON_SUCCESS back-edge from body exit to the loop activity.
                  - ai_reasoning activities require properties.prompt, at least one
                    outputVariables entry, and pluginName=null. Optional invokeWhen
                    SpEL boolean to gate the LLM call. Output is a Map; downstream uses
                    #yourVar.text \u2014 never #yourVar.
                  - Populate inputSchema and outputSchema for tool activities whenever
                    the shape is knowable from the execution log. Empty {} is a fallback
                    only.
                  - inputSchema.required must list ONLY properties the underlying tool
                    truly requires. If the tool takes no input (e.g. a list-all
                    endpoint), set inputSchema={} and DO NOT mark "input" as required.
                    Marking "input" required forces the engine to validate it against
                    a type, which then fails on the very first activity.
                  - SpEL collection literals are NOT JSON. The empty-map literal is
                    "{:}" \u2014 "{}" is an empty LIST. For java.util.Map variables,
                    ALWAYS use "defaultExpression": null (preferred) or "{:}". Never
                    "{}" \u2014 it produces an UnmodifiableRandomAccessList at runtime
                    and dead-ends every workflow that touches it.
                  - Pure output-target variables (those an activity will write into)
                    take "defaultExpression": null. Do not invent defaults to "fill
                    the field in".
                  - Every "#name" referenced anywhere must be declared in the top-level
                    "variables" array.
                  - No run-specific literals (UUIDs, order numbers, ids from this turn).
                    Parameterize via variables.

                You may issue multiple tool calls; when you have enough context, emit
                the final JSON in one assistant message and stop calling tools.
                """;
    }

    private String buildUserPrompt(GenerationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original user request:\n").append(ctx.userPrompt() == null ? "" : ctx.userPrompt()).append("\n\n");
        sb.append("Final assistant response (for context, may be empty):\n")
          .append(ctx.assistantResponse() == null ? "" : ctx.assistantResponse()).append("\n\n");
        sb.append("Tools used in this turn (emit AgentToolPlugin steps for each):\n");
        if (ctx.toolNames() == null || ctx.toolNames().isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String t : ctx.toolNames()) sb.append("- ").append(t).append("\n");
        }
        sb.append("\nSession workspace root: ").append(ctx.workspaceRoot()).append("\n");
        if (ctx.turnFilePath() != null) {
            Path rel = relativizeQuiet(ctx.workspaceRoot(), ctx.turnFilePath());
            sb.append("Execution log file (read this first after skill_load): ").append(rel).append("\n");
        }
        sb.append("\nBegin by calling skill_load(name=\"workflow-architect\").\n");
        return sb.toString();
    }

    private static Path relativizeQuiet(Path root, Path target) {
        try {
            return root.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize());
        } catch (Exception ignored) {
            return target;
        }
    }

    // --- Tool callbacks ----------------------------------------------------

    /**
     * Generic filesystem read/glob/grep callback that delegates to {@link
     * ToolExecutionService}. The synthetic {@link AgentTool} only carries the
     * fields the executor needs ({@code name}, {@code executionKind}, {@code
     * enabled}); it is never persisted.
     */
    private static final class FilesystemToolCallback implements ToolCallback {

        private final String name;
        private final String description;
        private final String inputSchema;
        private final ToolExecutionService toolExecutionService;

        FilesystemToolCallback(String name,
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

    /**
     * Loads the {@code workflow-architect} skill bundle and returns it as one
     * concatenated text blob. Restricted to a single skill — the architect has
     * no business loading arbitrary user skills during workflow drafting.
     */
    private static final class SkillLoadCallback implements ToolCallback {

        private static final String NAME = "skill_load";
        private static final String DESCRIPTION =
                "Load the workflow-architect skill bundle (SKILL.md + references/templates) as text. "
                        + "Pass name=\"workflow-architect\". Other skills are not accessible from this agent.";
        private static final String INPUT_SCHEMA = """
                {"type":"object",
                 "required":["name"],
                 "properties":{"name":{"type":"string","enum":["workflow-architect"]}}}
                """;

        private final SkillRegistryService skillRegistryService;
        private final ObjectMapper objectMapper;

        SkillLoadCallback(SkillRegistryService skillRegistryService, ObjectMapper objectMapper) {
            this.skillRegistryService = skillRegistryService;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(NAME)
                    .description(DESCRIPTION)
                    .inputSchema(INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String jsonInput) {
            return call(jsonInput, null);
        }

        @Override
        public String call(String jsonInput, ToolContext toolContext) {
            String requested = parseName(jsonInput);
            if (requested == null || !SKILL_NAME.equals(requested.trim().toLowerCase(Locale.ROOT))) {
                return "Only \"workflow-architect\" is loadable from this agent. Pass name=\"workflow-architect\".";
            }
            SkillRegistryService.SkillSnapshot snapshot = skillRegistryService.getEnabledSkillByName(SKILL_NAME);
            if (snapshot == null || snapshot.files() == null || snapshot.files().isEmpty()) {
                return "Skill \"workflow-architect\" is not enabled or has no files.";
            }
            // Anchor SKILL.md first so the model sees the contract before refs.
            List<Map.Entry<String, String>> entries = new ArrayList<>(snapshot.files().entrySet());
            entries.sort((a, b) -> {
                boolean aIsSkill = "SKILL.md".equalsIgnoreCase(a.getKey());
                boolean bIsSkill = "SKILL.md".equalsIgnoreCase(b.getKey());
                if (aIsSkill && !bIsSkill) return -1;
                if (!aIsSkill && bIsSkill) return 1;
                return a.getKey().compareToIgnoreCase(b.getKey());
            });
            StringBuilder sb = new StringBuilder();
            sb.append("<skill_content name=\"workflow-architect\">\n");
            for (Map.Entry<String, String> e : entries) {
                sb.append("\n## File: ").append(e.getKey()).append("\n\n").append(e.getValue()).append("\n");
            }
            sb.append("</skill_content>\n");
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private String parseName(String json) {
            if (json == null || json.isBlank()) return null;
            try {
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                Object value = parsed.get("name");
                return value == null ? null : String.valueOf(value);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** Test-only accessor for the single skill the architect is allowed to load. */
    static String allowlistedSkill() {
        return SKILL_NAME;
    }
}
