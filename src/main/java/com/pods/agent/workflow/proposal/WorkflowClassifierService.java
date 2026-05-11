package com.pods.agent.workflow.proposal;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.WorkflowProposalProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import com.pods.agent.workflow.proposal.agent.FilesystemToolCallback;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase-1 of the two-phase proposal pipeline: a cheap, read-only classifier
 * that runs immediately after every chat turn and decides whether the turn
 * is worth proposing as a reusable workflow.
 *
 * <p>Hard contract:
 * <ul>
 *   <li>Tools available: {@code read}, {@code glob}, {@code grep} only — all
 *       read-only and sandboxed to the session VFS.</li>
 *   <li>NO {@code skill_load}, NO write tools, NO process_def access.</li>
 *   <li>Output: a tiny structured JSON object — no workflow JSON. The
 *       expensive workflow drafting happens in
 *       {@link WorkflowBuilderService} only after a human approves.</li>
 * </ul>
 *
 * <p>This is the planning-layer counterpart to the deterministic runtime in
 * {@link com.pods.agent.workflow.engine.WorkflowManager}. By restricting the
 * agent's view to the typed execution log, we keep the per-turn classifier
 * cost low and reserve the model + skill budget for turns that pass review.
 */
@Service
@Slf4j
public class WorkflowClassifierService {

    private final ModelProviderRouter modelProviderRouter;
    private final ToolExecutionService toolExecutionService;
    private final WorkflowProposalProperties properties;
    private final ObjectMapper objectMapper;

    public WorkflowClassifierService(ModelProviderRouter modelProviderRouter,
                                     ToolExecutionService toolExecutionService,
                                     WorkflowProposalProperties properties,
                                     ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.toolExecutionService = toolExecutionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Run the classifier for one chat turn. Returns empty when the workspace
     * or model cannot be resolved, the LLM call fails, or the model returns
     * malformed JSON. Callers should treat empty as "skip Phase-1
     * persistence" — never as "needed".
     */
    public Optional<ClassifierDecision> classify(ClassificationContext ctx) {
        if (ctx == null || ctx.workspaceRoot() == null) {
            log.debug("[WorkflowClassifier] skip: missing context or workspace");
            return Optional.empty();
        }
        ModelRef modelRef = resolveModel(ctx.modelRef());
        if (modelRef == null) {
            log.warn("[WorkflowClassifier] skip turn {}: no model available (no override and no chat-turn modelRef)",
                    ctx.turnId());
            return Optional.empty();
        }
        log.debug("[WorkflowClassifier] classifying turn {} session={} model={} prompt='{}'",
                ctx.turnId(), ctx.sessionId(), modelRef,
                ctx.userPrompt() == null ? "" : ctx.userPrompt().substring(0, Math.min(80, ctx.userPrompt().length())));
        try {
            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(modelRef, true);
            ChatClient client = spec.client();
            String system = buildSystemPrompt();
            String user = buildUserPrompt(ctx);

            List<org.springframework.ai.tool.ToolCallback> tools = List.of(
                    new FilesystemToolCallback("read",
                            "Read a UTF-8 text file from the session workspace.",
                            """
                            {"type":"object",
                             "required":["path"],
                             "properties":{
                               "path":{"type":"string","description":"Path relative to the session workspace root, e.g. '.pods-agent/workflow/execution-log-<id>.json'"}
                             }}
                            """,
                            toolExecutionService),
                    new FilesystemToolCallback("glob",
                            "List files in the session workspace matching a glob pattern.",
                            """
                            {"type":"object",
                             "required":["glob"],
                             "properties":{
                               "glob":{"type":"string"},
                               "path":{"type":"string","description":"Optional sub-root; defaults to '.'"}
                             }}
                            """,
                            toolExecutionService),
                    new FilesystemToolCallback("grep",
                            "Search files in the session workspace for a regex.",
                            """
                            {"type":"object",
                             "required":["pattern"],
                             "properties":{
                               "pattern":{"type":"string"},
                               "path":{"type":"string"}
                             }}
                            """,
                            toolExecutionService)
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
                    log.warn("[WorkflowClassifier] empty content for turn {}", ctx.turnId());
                    return Optional.<ClassifierDecision>empty();
                }
                return parseDecision(content, ctx.turnId());
            });
        } catch (Exception e) {
            log.warn("[WorkflowClassifier] classification failed for turn {}: {}", ctx.turnId(), e.getMessage());
            return Optional.empty();
        }
    }

    private ModelRef resolveModel(ModelRef fallback) {
        WorkflowProposalProperties.ModelOverride override = properties.getClassifierModel();
        if (override != null && override.isPresent()) {
            return new ModelRef(override.getProviderId(), override.getModelId());
        }
        return fallback;
    }

    private String buildSystemPrompt() {
        return """
                You are the Workflow Classifier. Your only job is to decide whether
                a single chat turn is worth proposing as a reusable, parameterized
                workflow, and to propose a short workflow name when it is.

                You have a deliberately tiny toolbelt:
                  - read(path)  — read a file under the session workspace
                  - glob(glob)  — list files matching a pattern
                  - grep(pattern)— search file contents

                You have NO skill loader, NO write tools, NO database. You cannot
                draft the workflow itself. That is a separate phase that runs
                only if a human approves your proposal.

                Mandatory steps:
                  1. Read the typed execution log file at the path given in the
                     user message ("Execution log file"). It contains the typed
                     steps (tool, state_transition, architect_note, reasoning),
                     each tool call's input and output, and the final assistant
                     response.
                  2. Optionally use glob/grep to peek at adjacent files for
                     context, but do NOT read whole skill bundles — they are
                     out of scope for this phase.
                  3. Decide: is this turn a clean, repeatable pattern worth
                     turning into a deterministic workflow?

                Heuristics for "needed = true":
                  - The turn invoked one or more deterministic tools to fetch /
                    transform / validate data toward a clear outcome.
                  - The user's request is parameterizable (an order id, a
                    customer id, a date range — not a one-off "tell me about X").
                  - The same shape of request would plausibly be issued again.

                Heuristics for "needed = false":
                  - Pure chat / Q&A / brainstorming with no tool calls or with
                    tool calls that are not part of a repeatable pipeline.
                  - The turn errored out before producing useful output.
                  - The turn is exploratory / one-off / debugging in nature.

                Output contract — return ONLY this JSON object, no prose, no
                markdown fences:

                {
                  "needed": true|false,
                  "suggestedName": "Short Title Case Name (only when needed=true)",
                  "reason": "One sentence explaining the decision.",
                  "intentHint": "lowercase 3-8 word phrase capturing the reusable intent"
                }

                Rules for suggestedName:
                  - Title case, 3-8 words.
                  - Verb-led when possible ("Validate Order Against Leg Sequence
                    Rules", "Reconcile Daily Settlement Batch").
                  - No run-specific literals (no UUIDs, no order numbers, no
                    dates, no customer ids from this turn).
                  - When needed=false you may omit suggestedName or set it to "".
                """;
    }

    private String buildUserPrompt(ClassificationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original user request:\n").append(ctx.userPrompt() == null ? "" : ctx.userPrompt()).append("\n\n");
        sb.append("Final assistant response (for context):\n")
          .append(ctx.assistantResponse() == null ? "" : ctx.assistantResponse()).append("\n\n");
        sb.append("Tools used in this turn (informational; you cannot call them):\n");
        if (ctx.toolNames() == null || ctx.toolNames().isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String t : ctx.toolNames()) sb.append("- ").append(t).append("\n");
        }
        sb.append("\nSkills the chat agent loaded during this turn (informational only;\n")
          .append("you cannot load skills in Phase 1):\n");
        if (ctx.skillNames() == null || ctx.skillNames().isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String s : ctx.skillNames()) sb.append("- ").append(s).append("\n");
        }
        sb.append("\nSession workspace root: ").append(ctx.workspaceRoot()).append("\n");
        if (ctx.turnFilePath() != null) {
            Path rel = relativizeQuiet(ctx.workspaceRoot(), ctx.turnFilePath());
            sb.append("Execution log file (read this first): ").append(rel).append("\n");
        }
        sb.append("\nReturn the JSON decision now.\n");
        return sb.toString();
    }

    private Optional<ClassifierDecision> parseDecision(String raw, String turnId) {
        try {
            String text = raw.trim();
            int first = text.indexOf('{');
            int last = text.lastIndexOf('}');
            if (first < 0 || last <= first) {
                log.warn("[WorkflowClassifier] no JSON object in response for turn {}", turnId);
                return Optional.empty();
            }
            String json = text.substring(first, last + 1);
            JsonNode node = objectMapper.readTree(json);
            boolean needed = node.path("needed").asBoolean(false);
            String name = node.path("suggestedName").asText("");
            String reason = node.path("reason").asText("");
            String intentHint = node.path("intentHint").asText("");
            if (needed && (name == null || name.isBlank())) {
                // Defensive: a "needed" verdict with no name is useless to a
                // reviewer. Treat as not-needed rather than persist a row that
                // forces the UI to show a blank title.
                log.warn("[WorkflowClassifier] needed=true but suggestedName missing for turn {}; dropping", turnId);
                return Optional.empty();
            }
            return Optional.of(new ClassifierDecision(
                    needed,
                    name == null ? "" : name.trim(),
                    reason == null ? "" : reason.trim(),
                    intentHint == null ? "" : intentHint.trim().toLowerCase(Locale.ROOT)));
        } catch (Exception e) {
            log.warn("[WorkflowClassifier] parse failed for turn {}: {}", turnId, e.getMessage());
            return Optional.empty();
        }
    }

    private static Path relativizeQuiet(Path root, Path target) {
        try {
            return root.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize());
        } catch (Exception ignored) {
            return target;
        }
    }

    /** Inputs handed to the classifier. */
    public record ClassificationContext(String sessionId,
                                        String turnId,
                                        String userPrompt,
                                        String assistantResponse,
                                        List<String> toolNames,
                                        List<String> skillNames,
                                        ModelRef modelRef,
                                        Path workspaceRoot,
                                        Path turnFilePath) {}

    /** Structured output of one classifier run. */
    public record ClassifierDecision(boolean needed,
                                     String suggestedName,
                                     String reason,
                                     String intentHint) {}
}
