package com.pods.agent.ruledomain.compiler.agentic;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.RuleDomainEventBus;
import com.pods.agent.ruledomain.compiler.BpmnCompiler;
import com.pods.agent.ruledomain.compiler.CompilerPromptBuilder;
import com.pods.agent.ruledomain.compiler.trace.ExecutionTrace;
import com.pods.agent.ruledomain.compiler.trace.TraceBasedBpmnCompiler;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multi-turn, tool-using BPMN compile loop. Replaces the old single-shot
 * "stuff everything into one prompt" approach with an agent that:
 *
 * <ol>
 *   <li>Reads its instructions, the skill spec, and the recorded trace
 *       from files in a workspace via {@link CompileFileTools}.</li>
 *   <li>Decides which slices of the (potentially huge) order response it
 *       needs by calling list_files / grep / read_file.</li>
 *   <li>Calls {@code compile_write_bpmn} when it has the BPMN ready.</li>
 *   <li>Receives validator feedback on the next turn if anything fails,
 *       and revises. Max 5 attempts.</li>
 * </ol>
 *
 * <p>The result of {@link #compile} is either a validated BPMN string or
 * {@code null} (with {@code lastError} populated on the returned record).
 * The persistence / embedding / DB-save concerns stay in
 * {@link TraceBasedBpmnCompiler}; this class is only about producing a
 * passing BPMN.
 */
@Component
@Slf4j
public class AgenticTraceCompiler {

    /** Cap on the validate-and-revise loop. Each attempt is itself a
     *  Spring AI internal tool loop (the LLM may read many files inside
     *  one attempt), so this caps the outer REVISIONS only. */
    private static final int MAX_ATTEMPTS = 5;

    private final ModelProviderRouter modelProviderRouter;
    private final RuleDomainProperties props;
    private final TraceCompileWorkspace workspaceWriter;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final CompilerPromptBuilder promptBuilder;
    private final BpmnCompiler bpmnCompiler;
    private final RuleDomainEventBus bus;
    private final ObjectMapper objectMapper;

    public AgenticTraceCompiler(ModelProviderRouter modelProviderRouter,
                                RuleDomainProperties props,
                                TraceCompileWorkspace workspaceWriter,
                                SessionWorkspaceService sessionWorkspaceService,
                                CompilerPromptBuilder promptBuilder,
                                BpmnCompiler bpmnCompiler,
                                RuleDomainEventBus bus,
                                ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
        this.workspaceWriter = workspaceWriter;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.promptBuilder = promptBuilder;
        this.bpmnCompiler = bpmnCompiler;
        this.bus = bus;
        this.objectMapper = objectMapper;
    }

    /** Result of a compile attempt. {@code xml == null} means the loop
     *  exhausted its attempts without producing a passing BPMN; the most
     *  recent validator error is in {@code lastError}. */
    public record CompileResult(String xml,
                                String lastError,
                                int attempts,
                                long totalLatencyMs,
                                Path workspaceRoot) {
        public boolean ok() { return xml != null; }
    }

    public CompileResult compile(String sessionId,
                                 String turnId,
                                 String skillName,
                                 String skillMarkdown,
                                 SkillRuleManifest.Rule rule,
                                 ExecutionTrace slice) {
        long start = System.currentTimeMillis();
        bus.emit("rule_domain.compile.agentic.start", Map.of(
                "ruleName", rule.name(),
                "turnId", turnId == null ? "" : turnId,
                "steps", slice.toolSteps().size()));

        Path sessionRoot;
        try {
            sessionRoot = sessionWorkspaceService.getOrCreate(sessionId);
        } catch (Exception ex) {
            return failed(start, null, "Could not resolve session workspace: " + ex.getMessage());
        }

        Path compileRoot;
        try {
            compileRoot = workspaceWriter.materialize(
                    sessionRoot, safe(turnId), rule, skillName, skillMarkdown, slice,
                    buildInstructionsDoc());
        } catch (IOException ex) {
            return failed(start, null, "Could not write compile workspace: " + ex.getMessage());
        }

        ModelRef compilerRef = new ModelRef(
                props.getCompilerModel().getProviderId(),
                props.getCompilerModel().getModelId());
        ModelProviderRouter.Spec spec;
        try {
            spec = modelProviderRouter.resolve(compilerRef, true);
        } catch (Exception ex) {
            return failed(start, compileRoot, "Could not resolve compiler model: " + ex.getMessage());
        }
        ChatClient client = spec.client();

        CompileFileTools tools = new CompileFileTools(compileRoot, objectMapper);

        // Conversation history reused across revision attempts so the LLM
        // sees its own prior BPMN + the validator feedback when revising.
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(
                "Compile the BPMN for this rule. Your workspace is at: " + compileRoot + "\n\n"
                + "Start by calling `compile_read_file` on `index.md`, then `instructions.md`, "
                + "then `skill.md`. Use `compile_list_files` and `compile_grep` to explore the "
                + "`trace/` directory. The recorded `Get_OrderID` output is your authoritative "
                + "schema — every value in subsequent tool inputs must be derived from it via FEEL "
                + "paths, never hardcoded.\n\n"
                + "When you have the BPMN ready, call `compile_write_bpmn` with the complete XML."));

        String systemPrompt = promptBuilder.buildSystem() + "\n\n" + agenticAddendum();

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            bus.emit("rule_domain.compile.agentic.attempt", Map.of(
                    "ruleName", rule.name(),
                    "attempt", attempt));
            tools.clearWrittenBpmn();
            String text;
            try {
                var req = client.prompt()
                        .system(systemPrompt)
                        .messages(history)
                        .toolCallbacks(tools.callbacks());
                if (spec.options() != null) req = req.options(spec.options());
                text = req.call().content();
            } catch (Exception ex) {
                log.warn("[AgenticTraceCompiler] LLM call failed (attempt {}): {}", attempt, ex.getMessage());
                lastError = "LLM call failed: " + ex.getMessage();
                break;
            }

            // The model emitted some final assistant text; preserve it so
            // revisions can refer back to its prior reasoning.
            if (text != null && !text.isBlank()) {
                history.add(new AssistantMessage(text));
            }

            String xml = tools.writtenBpmn();
            if (xml == null) {
                lastError = "did not call compile_write_bpmn";
                history.add(new UserMessage(
                        "You finished the turn without calling `compile_write_bpmn`. "
                        + "Read `instructions.md` if you haven't, then submit a BPMN by "
                        + "calling `compile_write_bpmn` with the complete XML."));
                continue;
            }

            // Run sanitize + boundary injection + every validator. If any
            // check fails, feed the error back to the LLM for revision.
            String cleaned = BpmnCompiler.sanitize(xml);
            cleaned = BpmnCompiler.injectErrorBoundaries(cleaned);

            String err = TraceBasedBpmnCompiler.validateDelegateFieldNames(cleaned);
            if (err == null) err = TraceBasedBpmnCompiler.validateInputDiscipline(cleaned);
            if (err == null) err = TraceBasedBpmnCompiler.validateLiteralGrounding(cleaned, slice);
            if (err == null) err = bpmnCompiler.tryDeploy(cleaned, skillName, rule.name());

            if (err == null) {
                long elapsed = System.currentTimeMillis() - start;
                bus.emit("rule_domain.compile.agentic.success", Map.of(
                        "ruleName", rule.name(),
                        "attempts", attempt,
                        "latencyMs", elapsed,
                        "fileOps", tools.activityLog().size()));
                return new CompileResult(cleaned, null, attempt, elapsed, compileRoot);
            }

            lastError = err;
            bus.emit("rule_domain.compile.agentic.validator_failed", Map.of(
                    "ruleName", rule.name(),
                    "attempt", attempt,
                    "error", err));
            history.add(new UserMessage(
                    "Your submitted BPMN failed validation. Read the error carefully, fix the "
                    + "issue, then call `compile_write_bpmn` again with the corrected XML.\n\n"
                    + "Validator output:\n"
                    + err
                    + "\n\nDo NOT just resubmit the same XML — change the structure to address the "
                    + "specific problem above. Refer back to the trace files and the skill spec if needed."));
        }

        bus.emit("rule_domain.compile.agentic.exhausted", Map.of(
                "ruleName", rule.name(),
                "attempts", MAX_ATTEMPTS,
                "lastError", lastError == null ? "" : lastError));
        return failed(start, compileRoot, lastError);
    }

    private CompileResult failed(long start, Path root, String error) {
        return new CompileResult(null, error, 0, System.currentTimeMillis() - start, root);
    }

    private static String safe(String s) {
        return s == null ? "no-turn" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    private static String agenticAddendum() {
        return """
                ## Agentic compile mode

                You are running as an agent with filesystem tools. Instead of receiving the
                trace in your prompt, the trace and skill are written to files in a workspace
                you can inspect at will.

                Workflow:
                  1. `compile_read_file` `index.md` — overview of what's in the workspace.
                  2. `compile_read_file` `instructions.md` — the canonical compile contract
                     (delegate field names, FEEL syntax, error handling). Authoritative.
                  3. `compile_read_file` `skill.md` — the full skill spec. Tells you which
                     fields exist on the order, which ItemCodes count as legs, how ItemCode
                     maps to ServiceCode, etc.
                  4. `compile_list_files` `trace/**` — see every recorded tool step.
                  5. For each step, `compile_read_file` `trace/NN-ToolName.input.json` and
                     `.output.json`. These are FULL payloads — every field path you need is
                     in here.
                  6. For specific lookups, `compile_grep` with a regex across files.
                  7. When you have the BPMN ready, call `compile_write_bpmn` with the complete
                     XML.

                Validation rules (you will be re-prompted with the error if any fails, up to
                5 attempts):

                  - All `<flowable:field name>` values must match the delegate's contract
                    exactly: `toolName`/`argTemplate`/`outputBinding`/`postTransform` for
                    toolCallDelegate; `tableName`/`inputsTemplate`/`outputBinding` for
                    decisionTableDelegate; `feelExpr`/`outputBinding` for feelExtractDelegate.
                    Aliases like `inputBindings`, `expression`, `inputs` will be rejected.

                  - Any bare `${X}` reference is restricted to the user-provided identifiers:
                    `userMessage`, `orderId`, `customerId`, `sessionId`. All other values
                    must be FEEL paths into a variable written by an earlier outputBinding.

                  - Any quoted literal in argTemplate / inputsTemplate / feelExpr that also
                    appears as a value in any recorded tool response will be rejected. Use a
                    FEEL path instead. (Allowlist: protocol-level constants like `"US"`,
                    `"OK"`, `"Salesforce"`.)

                  - Do NOT emit boundary events or error end events for tool failures — the
                    post-processor adds them. Just use a single plain `<endEvent>` per scope.

                Style:
                  - Read what you need; don't dump every file. The big `trace/01-Get_OrderID.output.json`
                    is the schema you ground every later step against — read it carefully.
                  - When revising after a validator error, change the structure, don't just
                    re-submit the same XML.
                """;
    }

    /** The canonical compile contract, written into the workspace as
     *  `instructions.md` so the LLM reads it via the filesystem tool. It
     *  duplicates {@link #agenticAddendum} content intentionally — the
     *  addendum is in the system prompt for fast paths, and this file is
     *  the LLM's authoritative reference when revising. */
    private String buildInstructionsDoc() {
        return promptBuilder.buildSystem() + "\n\n" + agenticAddendum();
    }
}
