package com.pods.agent.workflow.proposal;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.WorkflowProposalProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.workspace.ExecutionLogService;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import com.pods.agent.workflow.proposal.WorkflowAlignmentJudge.Verdict;
import com.pods.agent.workflow.proposal.WorkflowJsonValidator.ValidationError;
import com.pods.agent.workflow.proposal.WorkflowJsonValidator.ValidationReport;
import com.pods.agent.workflow.proposal.agent.FilesystemToolCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase-2 builder agent. Runs only after a human approves a Phase-1
 * proposal. The agent:
 *
 * <ol>
 *   <li>Loads the {@code workflow-architect} skill plus every skill the
 *       chat agent loaded in the source turn (allowlisted via
 *       {@link WorkflowProposal#getSkillNamesJson()}).</li>
 *   <li>Reads the typed execution log from the session VFS.</li>
 *   <li>{@code write}s the workflow JSON into a draft file at
 *       {@code .pods-agent/workflow/proposals/&lt;proposalId&gt;.draft.json}.</li>
 *   <li>For every subsequent retry, the agent is told to {@code edit} the
 *       same file in place — no full regeneration. This keeps token cost
 *       bounded by the size of the diff rather than the size of the JSON.</li>
 * </ol>
 *
 * <p>Per attempt we run structural validation against the file and, on
 * pass, ask {@link WorkflowAlignmentJudge} for skills-first alignment.
 * Failures of either kind feed the next attempt's resume prompt. The total
 * retry budget is {@link WorkflowProposalProperties#getMaxBuildAttempts()}.
 *
 * <p>On success we persist the draft as a {@code ProcessDefDto} via
 * {@link ProcessDefService#save(ProcessDefDto)}, mirror it next to the
 * draft for observability, and stamp the proposal as {@code materialized}.
 */
@Service
@Slf4j
public class WorkflowBuilderService {

    private static final String ARCHITECT_SKILL = "workflow-architect";

    private final WorkflowProposalRepository repo;
    private final ProcessDefService processDefService;
    private final ModelProviderRouter modelProviderRouter;
    private final ToolExecutionService toolExecutionService;
    private final SkillRegistryService skillRegistryService;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final ExecutionLogService executionLogService;
    private final WorkflowJsonValidator validator;
    private final WorkflowAlignmentJudge alignmentJudge;
    private final WorkflowProposalProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Package-private hook that lets tests substitute the LLM agent
     * invocation with a deterministic fake (e.g. one that writes a
     * pre-canned draft to the file system). Production sets this to
     * {@link #runAgent} via the static factory below.
     */
    private AgentInvoker agentInvoker = this::runAgent;

    public WorkflowBuilderService(WorkflowProposalRepository repo,
                                  ProcessDefService processDefService,
                                  ModelProviderRouter modelProviderRouter,
                                  ToolExecutionService toolExecutionService,
                                  SkillRegistryService skillRegistryService,
                                  SessionWorkspaceService sessionWorkspaceService,
                                  ExecutionLogService executionLogService,
                                  WorkflowJsonValidator validator,
                                  WorkflowAlignmentJudge alignmentJudge,
                                  WorkflowProposalProperties properties,
                                  ObjectMapper objectMapper) {
        this.repo = repo;
        this.processDefService = processDefService;
        this.modelProviderRouter = modelProviderRouter;
        this.toolExecutionService = toolExecutionService;
        this.skillRegistryService = skillRegistryService;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.executionLogService = executionLogService;
        this.validator = validator;
        this.alignmentJudge = alignmentJudge;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Run the builder loop for one approved proposal. Updates the proposal
     * row in place ({@code building} → {@code materialized}/{@code failed})
     * and returns the final value.
     */
    public WorkflowProposal build(WorkflowProposal proposal) {
        if (proposal == null) {
            throw new IllegalArgumentException("proposal must not be null");
        }
        long startedAt = System.currentTimeMillis();
        log.info("[WorkflowBuilder] build started for proposal {} session={} turn={} name='{}'",
                proposal.getId(), proposal.getSessionId(), proposal.getTurnId(),
                proposal.getSuggestedName());
        proposal.setStatus("building");
        proposal.setErrorMessage(null);
        repo.update(proposal);

        Path workspace = resolveWorkspace(proposal.getSessionId());
        if (workspace == null) {
            return markFailed(proposal, "session_workspace_unavailable");
        }
        Path draftFile = sessionWorkspaceService.ensureFile(
                workspace, ".pods-agent/workflow/proposals/" + proposal.getId() + ".draft.json");
        Path executionLogFile = workspace.resolve(".pods-agent/workflow")
                .resolve("execution-log-" + proposal.getTurnId() + ".json");
        ModelRef modelRef = resolveModel(proposal);
        if (modelRef == null) {
            return markFailed(proposal, "missing_model_ref");
        }

        List<String> skillNames = parseSkillNames(proposal.getSkillNamesJson());
        Set<String> skillAllowlist = new LinkedHashSet<>();
        skillAllowlist.add(ARCHITECT_SKILL);
        skillAllowlist.addAll(skillNames);

        int maxAttempts = Math.max(1, properties.getMaxBuildAttempts());
        int maxNoopRetries = Math.max(0, properties.getMaxNoopRetries());
        log.debug("[WorkflowBuilder] proposal {} model={} maxAttempts={} maxNoopRetries={} skillAllowlist={}",
                proposal.getId(), modelRef, maxAttempts, maxNoopRetries, skillAllowlist);
        String lastFailureSummary = null;

        // One ChatMemory per build run keeps the conversation history (system
        // prompt + every prior user/assistant turn) visible to retry attempts.
        // Without this, attempt 1's skill_load output and reasoning would be
        // lost on attempt 2, which is exactly how the builder used to forget
        // the workflow-architect loop rules between retries. Memory is local
        // to this build and dropped when the loop exits.
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(40)
                .build();

        // Two separate counters. productiveAttempts is the budget we want to
        // protect — only iterations that actually mutated the draft count.
        // noopAttempts catches the failure mode where the model replies
        // claiming edits but never calls edit/apply_patch (its own prior
        // confident message in ChatMemory then keeps misleading subsequent
        // attempts into the same pattern). Capping no-ops separately means a
        // hallucinating model can't starve the productive budget, while still
        // bounding the loop so a totally broken model terminates.
        int productiveAttempts = 0;
        int noopAttempts = 0;
        while (productiveAttempts < maxAttempts && noopAttempts <= maxNoopRetries) {
            boolean initialAttempt = productiveAttempts == 0;
            int displayAttempt = productiveAttempts + noopAttempts + 1;
            log.info("[WorkflowBuilder] proposal {} attempt {} (productive={}/{}, noop={}/{}) ({})",
                    proposal.getId(), displayAttempt,
                    productiveAttempts, maxAttempts,
                    noopAttempts, maxNoopRetries,
                    initialAttempt ? "initial draft" : "edit-in-place");
            // SHA-256 of the draft before the agent runs. Defense-in-depth
            // against a rare case where edits net out to byte-identical
            // content — the primary no-op signal is now the per-attempt
            // ToolCallCounters below, which is impossible for the model to
            // fake (it counts successful tool invocations from inside the
            // PathRestrictedFsCallback).
            String hashBefore = hashFileQuiet(draftFile);
            ToolCallCounters counters = new ToolCallCounters();
            try {
                String resumeFeedback = lastFailureSummary;
                agentInvoker.invoke(
                        proposal,
                        workspace,
                        draftFile,
                        executionLogFile,
                        modelRef,
                        skillAllowlist,
                        initialAttempt,
                        resumeFeedback,
                        chatMemory,
                        counters);
            } catch (Exception e) {
                log.warn("[WorkflowBuilder] proposal {} attempt {} agent invocation failed: {}",
                        proposal.getId(), displayAttempt, e.getMessage());
                lastFailureSummary = "agent_invocation_failed: " + e.getMessage();
                repo.incrementBuildAttempts(proposal.getId());
                productiveAttempts++;
                continue;
            }
            repo.incrementBuildAttempts(proposal.getId());

            String hashAfter = hashFileQuiet(draftFile);
            boolean noMutatingCalls = counters.mutatingCalls() == 0;
            boolean hashUnchanged = hashBefore != null && hashBefore.equals(hashAfter);
            // Only treat the initial attempt as a no-op if BOTH signals fire
            // (zero mutating tool calls AND file unchanged) — a brand-new
            // draft file may have been hash-null before, hash-something
            // after, which we don't want to misclassify.
            boolean isNoop = !initialAttempt && (noMutatingCalls || hashUnchanged);
            if (isNoop) {
                // Retry that didn't touch the file. The agent claimed edits
                // in its reply but never actually called a mutating tool —
                // forwarding the prior validation/alignment feedback again
                // is pointless because the file hasn't changed. Wipe the
                // poisoned conversation history (the model's own false
                // success claims are now in there, and seeing them on the
                // next attempt is what keeps producing the same no-op),
                // then prepend a tool-call audit block so the next attempt
                // sees verifiable counts instead of prose.
                chatMemory.clear(proposal.getId());
                lastFailureSummary = renderDraftUnchangedFeedback(lastFailureSummary, counters);
                noopAttempts++;
                log.warn("[WorkflowBuilder] proposal {} attempt {} produced NO file change"
                                + " (edit={}, apply_patch={}, write={}, rejected_write={});"
                                + " cleared ChatMemory; noop {}/{}",
                        proposal.getId(), displayAttempt,
                        counters.edit.get(), counters.applyPatch.get(),
                        counters.write.get(), counters.rejectedWriteOnRetry.get(),
                        noopAttempts, maxNoopRetries);
                continue;
            }

            // Past this point the attempt mutated the draft — count it
            // against the productive budget regardless of whether
            // structural / alignment validation passes.
            productiveAttempts++;

            String draftJson = readDraftQuiet(draftFile);
            if (draftJson == null || draftJson.isBlank()) {
                log.warn("[WorkflowBuilder] proposal {} attempt {} produced empty draft file {}",
                        proposal.getId(), displayAttempt, draftFile.getFileName());
                lastFailureSummary = "draft_file_empty:" + draftFile.getFileName();
                continue;
            }

            ValidationReport report = validator.validate(draftJson, proposal.getUserPrompt());
            if (!report.ok()) {
                lastFailureSummary = renderStructuralFeedback(report.errors());
                log.info("[WorkflowBuilder] proposal {} attempt {} structural validation FAILED ({} error(s)): {}",
                        proposal.getId(), displayAttempt, report.errors().size(),
                        report.errors().stream().map(ValidationError::code).toList());
                continue;
            }
            log.debug("[WorkflowBuilder] proposal {} attempt {} structural validation passed",
                    proposal.getId(), displayAttempt);

            String executionLogContent = readExecutionLogQuiet(executionLogFile);
            Verdict verdict = alignmentJudge.judge(
                    draftJson,
                    skillNames,
                    executionLogContent,
                    proposal.getReason(),
                    modelRef);
            if (!verdict.aligned()) {
                lastFailureSummary = renderAlignmentFeedback(verdict);
                log.info("[WorkflowBuilder] proposal {} attempt {} alignment FAILED (severity={}): {}",
                        proposal.getId(), displayAttempt, verdict.severity(), verdict.critique());
                continue;
            }
            log.info("[WorkflowBuilder] proposal {} attempt {} alignment passed (severity={}); finalizing",
                    proposal.getId(), displayAttempt, verdict.severity());

            WorkflowProposal finalized = finalizeSuccess(proposal, report.dto(), draftJson, workspace);
            log.info("[WorkflowBuilder] build SUCCESS for proposal {} -> process_def {} after {} productive + {} noop attempt(s) in {}ms",
                    finalized.getId(), finalized.getMaterializedDefId(),
                    productiveAttempts, noopAttempts, System.currentTimeMillis() - startedAt);
            return finalized;
        }
        log.warn("[WorkflowBuilder] build EXHAUSTED for proposal {} after {} productive + {} noop attempt(s) in {}ms; last failure: {}",
                proposal.getId(), productiveAttempts, noopAttempts,
                System.currentTimeMillis() - startedAt, lastFailureSummary);
        return markFailed(proposal,
                "build_loop_exhausted: " + (lastFailureSummary == null ? "unknown" : lastFailureSummary));
    }

    private Path resolveWorkspace(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Path workspace = sessionWorkspaceService.get(sessionId);
        if (workspace != null) return workspace;
        try {
            return sessionWorkspaceService.getOrCreate(sessionId);
        } catch (Exception e) {
            log.warn("[WorkflowBuilder] failed to resolve workspace for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private ModelRef resolveModel(WorkflowProposal proposal) {
        WorkflowProposalProperties.ModelOverride override = properties.getBuilderModel();
        if (override != null && override.isPresent()) {
            return new ModelRef(override.getProviderId(), override.getModelId());
        }
        if (proposal.getModelProviderId() == null || proposal.getModelId() == null) return null;
        return new ModelRef(proposal.getModelProviderId(), proposal.getModelId());
    }

    private List<String> parseSkillNames(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    if (item == null) continue;
                    String s = String.valueOf(item).trim();
                    if (!s.isEmpty()) out.add(s);
                }
                return List.copyOf(out);
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private void runAgent(WorkflowProposal proposal,
                          Path workspace,
                          Path draftFile,
                          Path executionLogFile,
                          ModelRef modelRef,
                          Set<String> skillAllowlist,
                          boolean initialAttempt,
                          String resumeFeedback,
                          ChatMemory chatMemory,
                          ToolCallCounters counters) throws Exception {
        ModelProviderRouter.Spec spec = modelProviderRouter.resolve(modelRef, true);
        ChatClient client = spec.client();

        Path draftRel = relativizeQuiet(workspace, draftFile);
        Path logRel = relativizeQuiet(workspace, executionLogFile);

        String draftRelString = draftRel.toString().replace('\\', '/');

        List<ToolCallback> tools = new ArrayList<>();
        tools.add(new FilesystemToolCallback("read",
                "Read a UTF-8 text file from the session workspace.",
                """
                {"type":"object",
                 "required":["path"],
                 "properties":{
                   "path":{"type":"string","description":"Path relative to session workspace root"}
                 }}
                """,
                toolExecutionService));
        tools.add(new FilesystemToolCallback("glob",
                "List files in the session workspace matching a glob pattern.",
                """
                {"type":"object",
                 "required":["glob"],
                 "properties":{
                   "glob":{"type":"string"},
                   "path":{"type":"string"}
                 }}
                """,
                toolExecutionService));
        tools.add(new FilesystemToolCallback("grep",
                "Search session-workspace files for a regex.",
                """
                {"type":"object",
                 "required":["pattern"],
                 "properties":{
                   "pattern":{"type":"string"},
                   "path":{"type":"string"}
                 }}
                """,
                toolExecutionService));
        // Path-restricted write/edit/apply_patch tools — all three must
        // resolve to the proposal's draft file. write is only registered on
        // the initial attempt; on retries it's replaced by a rejecting
        // callback so the agent can't regenerate-from-scratch and erase
        // surgical edits made during prior attempts (we observed gpt-5.2-chat
        // dropping required fields like trigger / maxIterations / outputVariables
        // when allowed to write on a retry, producing 18 structural errors).
        if (initialAttempt) {
            tools.add(new PathRestrictedFsCallback("write",
                    "Write the full workflow JSON to the proposal draft file. ONLY available on the initial attempt; on retries the tool is replaced and rejects all calls.",
                    """
                    {"type":"object",
                     "required":["path","content"],
                     "properties":{
                       "path":{"type":"string","description":"MUST equal '%s'"},
                       "content":{"type":"string","description":"Full JSON document to write"}
                     }}
                    """.formatted(draftRelString),
                    toolExecutionService, draftFile,
                    counters == null ? null : counters.write));
        } else {
            tools.add(new RejectingToolCallback("write",
                    "Forbidden on retry attempts. Use `edit` (surgical replace) or `apply_patch` (multi-hunk diff) instead — they preserve fields you've already set correctly. A full rewrite is rejected to prevent dropping required fields like trigger / maxIterations / outputVariables.",
                    "{\"success\":false,\"error\":\"write_forbidden_on_retry\","
                            + "\"hint\":\"write is only available on the initial attempt; "
                            + "on retries you MUST use edit(old_text, new_text) for surgical "
                            + "changes or apply_patch for multi-hunk diffs. Regenerating the file "
                            + "from scratch would erase the structural fields (triggers, "
                            + "maxIterations, outputVariables) you've already set correctly.\"}",
                    counters == null ? null : counters.rejectedWriteOnRetry));
        }
        tools.add(new PathRestrictedFsCallback("edit",
                "Surgically replace one occurrence of old_text with new_text in the proposal draft file. Preferred for retries.",
                """
                {"type":"object",
                 "required":["path"],
                 "properties":{
                   "path":{"type":"string","description":"MUST equal '%s'"},
                   "old_text":{"type":"string"},
                   "new_text":{"type":"string"},
                   "content":{"type":"string","description":"Full-rewrite fallback only — prefer old_text/new_text"}
                 }}
                """.formatted(draftRelString),
                toolExecutionService, draftFile,
                counters == null ? null : counters.edit));
        tools.add(new PathRestrictedFsCallback("apply_patch",
                "Apply a multi-hunk diff to the proposal draft file (ORIGINAL/UPDATED markers).",
                """
                {"type":"object",
                 "required":["path","content"],
                 "properties":{
                   "path":{"type":"string","description":"MUST equal '%s'"},
                   "content":{"type":"string"}
                 }}
                """.formatted(draftRelString),
                toolExecutionService, draftFile,
                counters == null ? null : counters.applyPatch));
        tools.add(new SkillLoadCallback(skillRegistryService, objectMapper, skillAllowlist));

        String system = buildSystemPrompt(skillAllowlist, draftRelString, logRel.toString().replace('\\', '/'));
        String user = initialAttempt
                ? buildInitialUserPrompt(proposal, draftRelString, logRel)
                : buildResumeUserPrompt(proposal, draftRelString, resumeFeedback);

        // The memory advisor prepends the prior conversation to every prompt
        // it sees, keyed by conversationId. Using the proposal id as the
        // conversation id means attempts 2..N inherit attempt 1's
        // system/user/assistant turns.
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(proposal.getId())
                .build();

        WorkspaceContextHolder.withWorkspace(workspace, () -> {
            var promptSpec = client.prompt()
                    .system(system)
                    .user(user)
                    .toolCallbacks(tools)
                    .advisors(memoryAdvisor);
            if (spec.options() != null) {
                promptSpec = promptSpec.options(spec.options());
            }
            String reply = promptSpec.call().content();
            log.debug("[WorkflowBuilder] agent reply for proposal {} (attempt initial={}): {}",
                    proposal.getId(), initialAttempt, reply == null ? "(empty)" : reply);
            return null;
        });
    }

    private String buildSystemPrompt(Set<String> skillAllowlist, String draftPath, String executionLogPath) {
        return """
                You are the Workflow Builder. You convert one chat turn's
                execution log + the skills the chat agent used into a
                deterministic, reusable workflow definition (a ProcessDefDto JSON).

                You operate ONLY on a single draft file:

                    %s

                You MUST not write to any other path. The write/edit/apply_patch
                tools will reject any other path with a clear error.

                Your toolbelt:
                  - skill_load(name) — allowed names: %s. Call workflow-architect
                    first; then load every skill the chat agent used.
                  - read(path)   — read any file under the session workspace
                  - glob(glob)   — list workspace files
                  - grep(pattern)— search workspace file contents
                  - write(path,content) — full file rewrite of the draft.
                    INITIAL ATTEMPT ONLY. On retries the tool is replaced
                    by a rejecting stub that returns
                    `write_forbidden_on_retry`; you cannot call it then.
                  - edit(path,old_text,new_text) — surgical edit. REQUIRED on retries.
                  - apply_patch(path,content)    — multi-hunk diff against the draft.

                Skill priority: skills are the source of truth. If a skill
                rule conflicts with what the execution log appears to imply,
                obey the skill. The execution log is informational — it shows
                which tools were actually called, with what input shape — but
                cannot override an explicit skill rule.

                Hard rules for the workflow JSON:
                  - Match ProcessDefDto exactly: id, name, version, packageId,
                    description, variables, activities, transitions.
                  - Activity "type" must be one of: normal | tool | route |
                    subflow | foreach | while | batch | ai_reasoning.
                  - Use "route" with isStart=true / isEnd=true for entry / exit;
                    route nodes are transition-only (no plugin).
                  - One activity per distinct call-site, NOT per recorded log
                    line. Workflows are reusable templates: the count of
                    AgentToolPlugin "tool" activities you emit MUST equal the
                    count of distinct call-sites in the source turn (one per
                    loop body, even if the turn invoked it many times).
                  - HARD anti-enumeration rule (this is the most common
                    failure mode): if the execution log invokes the SAME tool
                    3 or more times where the inputs share the same JSON keys
                    and only the values vary (e.g. {"id":1}, {"id":2}, ...,
                    {"id":20}), you MUST emit ONE "foreach" activity with a
                    SINGLE "tool" activity inside its body whose
                    properties.input reads the varying value via
                    #{#currentItem.<key>} (or #{#currentItem} if items are
                    scalars). NEVER produce N enumerated activities like
                    `call_getProductById_1`, `call_getProductById_2`, ...,
                    `call_getProductById_20`. The structural validator hard-
                    fails on that pattern — your draft will be rejected.
                  - For every distinct tool call-site appearing in the
                    execution log, emit a "tool" activity with pluginName
                    "AgentToolPlugin" and properties { "toolName":"<name>",
                    "input":"#{ ... }" }. Preserve the EXACT top-level keys
                    of step.input from the log; never collapse {tableName,
                    inputs} into a single variable reference.
                  - Loop activities (foreach/while/batch) need maxIterations
                    and three edges (forward / no-match exit / back-edge).
                    See `templates/foreach-accumulate.json` for the canonical
                    "fetch list -> foreach -> per-item tool -> accumulate"
                    shape.
                  - Judgement / synthesis steps must be ai_reasoning, not
                    AiChatPlugin tool nodes. ai_reasoning needs
                    properties.prompt, at least one outputVariables entry,
                    pluginName=null. Output is a Map; downstream uses
                    #yourVar.text — never #yourVar alone.
                  - Every #variable referenced anywhere must be declared in
                    the top-level "variables" array.
                  - SpEL collection literals: empty-map = "{:}", empty-list
                    = "{}". Map variables get defaultExpression: null
                    (preferred) or "{:}" — NEVER "{}".
                  - inputSchema.required must list ONLY fields the underlying
                    tool actually requires. For tools that take no input set
                    inputSchema={}; do NOT mark "input" required.
                  - No run-specific literals (UUIDs / order numbers / dates
                    from the source turn). Parameterize via variables.

                Mandatory step order on the FIRST attempt:
                  1. skill_load(name="workflow-architect"). Read the
                     "Reading patterns" + "AgentToolPlugin call-sites" rules
                     and any template files it surfaces (especially
                     `templates/foreach-accumulate.json` and
                     `references/workflow-patterns.md`) BEFORE you write any
                     JSON. If the Phase-1 classifier reasoning passed in the
                     user message mentions loop / for each / batch /
                     parallel, the foreach-accumulate template is your
                     starting skeleton — adapt it; do NOT enumerate calls.
                  2. read("%s") — the typed execution log. While reading,
                     classify each tool name as either a "single call-site"
                     or a "looped call-site" (i.e. invoked 3+ times with
                     varying values for the same key) — the latter must
                     become a foreach body, never N enumerated activities.
                  3. For EACH skill name in the allowlist (excluding
                     workflow-architect), call skill_load(name="<that skill>").
                  4. Compose the JSON and `write` it to the draft path
                     above. Before writing, sanity-check: does any tool name
                     appear on more than 2 of your activities with the same
                     input keys? If yes, fold them into a foreach body. Stop
                     with a one-line confirmation.

                On RETRY attempts:
                  - The user message will list precise validation errors or an
                    alignment critique.
                  - Use `read` to see the current draft contents; then use
                    `edit(old_text,new_text)` to make the smallest fix that
                    addresses every reported issue.
                  - `write` is FORBIDDEN on retry attempts. The tool is
                    deregistered after the initial attempt — calling it
                    returns `write_forbidden_on_retry`. A full rewrite
                    erases the structural fields you've already set
                    correctly (triggers, maxIterations, outputVariables
                    were observed dropping in production) and forces you
                    to redo work the validator already accepted. Use
                    `edit` for surgical changes and `apply_patch` for
                    multi-hunk diffs.
                  - HARD RULE — your reply MUST be preceded by at least ONE
                    successful call to `edit` or `apply_patch`. A reply
                    alone does NOT modify the file; the build loop
                    SHA-256-hashes the draft before and after every
                    attempt and will detect a no-op. If your hashes
                    match, the attempt is rejected with a
                    `draft_unchanged` failure and the same feedback is
                    re-issued on the next attempt (with this rule
                    restated more loudly). Do not claim "Edits applied"
                    or "Applied structural fixes" if you have not
                    actually called `edit` / `apply_patch` — that is a
                    hallucination, not a fix. If the feedback is unclear
                    enough that you cannot decide what to edit, prefer
                    making one small edit that addresses the most
                    concrete issue over replying with no edit at all.
                  - Only AFTER at least one successful edit/apply_patch
                    call, stop calling tools and reply with a one-line
                    confirmation describing what you actually changed.
                """.formatted(draftPath, skillAllowlist, executionLogPath);
    }

    private String buildInitialUserPrompt(WorkflowProposal proposal, String draftPath, Path executionLogPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build the workflow for proposal `").append(proposal.getId()).append("`.\n\n");
        sb.append("Suggested name (from Phase-1 classifier): ")
                .append(proposal.getSuggestedName() == null ? "" : proposal.getSuggestedName()).append("\n");
        sb.append("Original user request:\n").append(proposal.getUserPrompt() == null ? "" : proposal.getUserPrompt())
                .append("\n\n");
        // The classifier already inspected the execution log and recorded an
        // intent + control-flow hint in proposal.reason (e.g. "...then looped
        // getProductById for each ID..."). Surface it verbatim and instruct
        // the builder to honor any loop / parallel / batch words it contains
        // — otherwise it's prone to enumerate every recorded call literally.
        String classifierReason = proposal.getReason();
        sb.append("Phase-1 classifier reasoning (loop / parallel hints to honor):\n  ")
                .append(classifierReason == null || classifierReason.isBlank()
                        ? "(none)"
                        : classifierReason)
                .append("\n\n");
        sb.append("If this hint mentions \"loop\", \"for each\", \"foreach\", \"each\",\n")
                .append("\"batch\", \"parallel\", or any synonym of repetition, you MUST encode\n")
                .append("the matching control-flow construct (foreach / while / batch /\n")
                .append("and-split) and emit ONE tool activity in the loop body — never\n")
                .append("enumerate the calls as N separate tool activities.\n\n");
        sb.append("Tools the chat agent used (informational; emit AgentToolPlugin steps for each call-site):\n");
        sb.append(proposal.getMatchedToolNamesJson() == null ? "(none)\n" : proposal.getMatchedToolNamesJson() + "\n");
        sb.append("\nSkills the chat agent loaded (load each one via skill_load):\n");
        sb.append(proposal.getSkillNamesJson() == null ? "(none)\n" : proposal.getSkillNamesJson() + "\n");
        sb.append("\nExecution log file: ").append(executionLogPath).append("\n");
        sb.append("Draft target file: ").append(draftPath).append("\n\n");
        sb.append("Begin by calling skill_load(name=\"workflow-architect\"). Then read the\n");
        sb.append("execution log, then load each remaining skill, then write the workflow\n");
        sb.append("JSON to the draft file. Stop with a one-line confirmation.\n");
        return sb.toString();
    }

    private String buildResumeUserPrompt(WorkflowProposal proposal, String draftPath, String feedback) {
        return """
                The previous attempt produced a draft that did not pass validation.

                Draft file: %s
                Proposal id: %s

                ============================================================
                MANDATORY: you MUST call edit / apply_patch
                (write is FORBIDDEN on retries — the tool is deregistered
                 and returns `write_forbidden_on_retry`)
                ============================================================
                A reply alone does NOT modify the file. The build loop
                SHA-256-hashes the draft before and after this attempt; if
                the hash is unchanged your attempt is rejected as a no-op
                and the same feedback is re-issued. Do NOT claim "Edits
                applied" or "Applied structural fixes" without actually
                calling edit / apply_patch — that is a hallucination, not
                a fix. Make at least ONE successful edit / apply_patch
                call before replying. Do NOT regenerate the file from
                scratch with `write`: it's blocked, and even if it
                weren't, a full rewrite drops fields the validator
                already accepted (triggers, maxIterations,
                outputVariables) and forces you to redo work.
                ============================================================

                Feedback to address (every item must be fixed in this attempt):

                %s

                Procedure:
                  1. Call read("%s") to load the current draft.
                  2. If the feedback mentions `enumeration_antipattern`, your
                     fix is structural (not cosmetic): delete the duplicated
                     tool activities, add ONE foreach activity with a single
                     parameterized tool body, and rewire the transitions per
                     the workflow-architect skill's foreach-accumulate
                     template. If your context for the workflow-architect
                     loop rules feels stale, call
                     skill_load(name="workflow-architect") again and re-read
                     `templates/foreach-accumulate.json` and the "Reading
                     patterns" section of SKILL.md before editing.
                  3. For each issue above, use edit(path, old_text, new_text)
                     to make the smallest change that addresses the root
                     cause. Include enough surrounding context in old_text to
                     make the match unique. Multiple edits are fine for
                     structural fixes (e.g. deleting an enumeration); use
                     apply_patch for large multi-hunk rewrites.
                  4. `write` is BLOCKED on retries (the tool returns
                     `write_forbidden_on_retry`). Edits compound across
                     attempts because your earlier conversation is
                     preserved in memory — keep building on the prior
                     context with edit / apply_patch only.
                  5. ONLY after at least one successful edit/apply_patch
                     call, stop calling tools and reply with a one-line
                     confirmation that names the specific changes you made.
                """.formatted(
                        draftPath,
                        proposal.getId(),
                        feedback == null ? "(no specific feedback)" : feedback,
                        draftPath);
    }

    private String renderStructuralFeedback(List<ValidationError> errors) {
        StringBuilder sb = new StringBuilder("Structural validation failures:\n");
        for (ValidationError e : errors) {
            sb.append("  - [").append(e.code()).append("] ");
            if (e.path() != null) sb.append("at ").append(e.path()).append(": ");
            sb.append(e.message()).append("\n");
        }
        return sb.toString();
    }

    private String renderAlignmentFeedback(Verdict verdict) {
        return "Alignment judge rejected the draft (severity=" + verdict.severity() + "):\n  - "
                + (verdict.critique() == null ? "(no critique provided)" : verdict.critique());
    }

    /**
     * Build the resume feedback shown when the previous attempt produced no
     * file change (agent claimed edits but never called edit / apply_patch).
     * The original validator/alignment feedback is preserved verbatim
     * because it's still the actual problem to fix — but it's prefixed with
     * a clear "you didn't actually edit anything" warning so the next
     * attempt cannot interpret the message as an acknowledgement. Note we
     * intentionally do NOT mention `write` as an option: it's deregistered
     * on retries (returns `write_forbidden_on_retry`) and a full rewrite
     * tends to drop required structural fields the validator already
     * accepted.
     *
     * <p>Counters from the previous attempt are surfaced verbatim as a
     * tool-call audit block. Concrete numbers are harder for the model to
     * argue with than prose: the model's self-narration says "I added a
     * foreach"; the audit says "edit: 0 successful calls". The audit
     * always wins.
     */
    private String renderDraftUnchangedFeedback(String priorFeedback, ToolCallCounters counters) {
        StringBuilder sb = new StringBuilder();
        sb.append("DRAFT NOT MODIFIED.\n\n");
        if (counters != null) {
            sb.append("Tool-call audit for the previous attempt (from the runtime, not your reply):\n");
            sb.append("  edit:        ").append(counters.edit.get()).append(" successful calls\n");
            sb.append("  apply_patch: ").append(counters.applyPatch.get()).append(" successful calls\n");
            sb.append("  write:       ").append(counters.write.get()).append(" successful calls\n");
            sb.append("  write (rejected on retry): ").append(counters.rejectedWriteOnRetry.get()).append(" attempts\n\n");
            sb.append("The audit is the authoritative record — it's incremented inside the\n");
            sb.append("tool callback after a successful invocation. Zero mutating calls\n");
            sb.append("means the draft on disk is byte-identical to before this attempt,\n");
            sb.append("regardless of what your reply text claimed.\n\n");
        }
        sb.append("Your previous reply claimed structural edits, but the draft file on disk\n");
        sb.append("is byte-identical to before the attempt — you did NOT call any of:\n");
        sb.append("  - edit(path, old_text, new_text)\n");
        sb.append("  - apply_patch(path, content)\n\n");
        sb.append("(`write` is deregistered on retries and returns `write_forbidden_on_retry`;\n");
        sb.append(" do not try to fall back to it. Use edit / apply_patch only.)\n\n");
        sb.append("Replying alone does NOT modify the file. The validator and the alignment\n");
        sb.append("judge re-ran against the unchanged draft and produced the same failure.\n");
        sb.append("This attempt is a no-op and was rejected automatically.\n\n");
        sb.append("On THIS attempt you MUST:\n");
        sb.append("  1. Call read(<draft>) to load the current contents.\n");
        sb.append("  2. For every issue listed below, call edit(...) or apply_patch(...)\n");
        sb.append("     to actually mutate the file. Multiple edit calls are fine.\n");
        sb.append("  3. Only after at least one successful edit / apply_patch call,\n");
        sb.append("     reply with a one-line confirmation.\n\n");
        sb.append("The original feedback to address is unchanged:\n\n");
        sb.append(priorFeedback == null || priorFeedback.isBlank()
                ? "(no specific feedback was recorded for the previous attempt)"
                : priorFeedback);
        return sb.toString();
    }

    /**
     * SHA-256 hex digest of {@code path}, or {@code null} when the file
     * doesn't exist or cannot be read. Used purely for cross-attempt
     * change detection — never compared against an external value, so
     * SHA-256 is overkill but deterministic and dependency-free.
     */
    private String hashFileQuiet(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) return null;
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            log.debug("[WorkflowBuilder] failed to hash draft file {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String readDraftQuiet(Path draftFile) {
        try {
            if (!Files.isRegularFile(draftFile)) return null;
            return Files.readString(draftFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[WorkflowBuilder] failed to read draft file {}: {}", draftFile, e.getMessage());
            return null;
        }
    }

    private String readExecutionLogQuiet(Path executionLogFile) {
        try {
            if (executionLogFile == null || !Files.isRegularFile(executionLogFile)) return null;
            return Files.readString(executionLogFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("[WorkflowBuilder] failed to read execution log {}: {}", executionLogFile, e.getMessage());
            return null;
        }
    }

    private WorkflowProposal finalizeSuccess(WorkflowProposal proposal,
                                             ProcessDefDto dto,
                                             String draftJson,
                                             Path workspace) {
        try {
            String baseName = chooseBaseName(proposal, dto);
            String uniqueName = uniqueName(baseName);
            ProcessDefDto normalized = new ProcessDefDto(
                    dto.id(),
                    uniqueName,
                    dto.version() == null || dto.version().isBlank() ? "1" : dto.version(),
                    dto.packageId(),
                    dto.description(),
                    dto.variables(),
                    dto.activities(),
                    dto.transitions());
            ProcessDefDto saved = processDefService.save(normalized);
            proposal.setStatus("materialized");
            proposal.setMaterializedDefId(saved.id());
            proposal.setProposedWorkflowJson(draftJson);
            proposal.setErrorMessage(null);
            mirrorApprovedProposalToVfs(proposal, saved, workspace);
            return repo.update(proposal);
        } catch (Exception e) {
            log.warn("[WorkflowBuilder] save failed for proposal {}: {}", proposal.getId(), e.getMessage());
            return markFailed(proposal, "save_failed:" + e.getMessage());
        }
    }

    private String chooseBaseName(WorkflowProposal proposal, ProcessDefDto dto) {
        if (dto.name() != null && !dto.name().isBlank()) return dto.name();
        if (proposal.getSuggestedName() != null && !proposal.getSuggestedName().isBlank()) {
            return proposal.getSuggestedName();
        }
        return "Workflow Proposal " + proposal.getId();
    }

    private String uniqueName(String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (!processDefService.findByName(candidate).isEmpty()) {
            candidate = baseName + " (" + suffix++ + ")";
        }
        return candidate;
    }

    /**
     * Mirror the canonical workflow JSON to the session VFS so the approved
     * artifact sits next to the per-turn execution log + draft. Failures
     * are logged and swallowed — the workflow is already durable in
     * agent.process_def.
     */
    private void mirrorApprovedProposalToVfs(WorkflowProposal proposal, ProcessDefDto saved, Path workspace) {
        if (saved == null || saved.id() == null || saved.id().isBlank()) return;
        if (proposal.getSessionId() == null || proposal.getSessionId().isBlank()) return;
        try {
            Path target = executionLogService.approvedProposalPath(proposal.getSessionId(), saved.id());
            String json = objectMapper.writeValueAsString(saved);
            Files.writeString(target, json, StandardCharsets.UTF_8);
            log.debug("[WorkflowBuilder] mirrored approved proposal {} to {}", saved.id(), target);
        } catch (Exception e) {
            log.warn("[WorkflowBuilder] failed to mirror approved proposal {} to VFS: {}",
                    saved.id(), e.getMessage());
        }
    }

    private WorkflowProposal markFailed(WorkflowProposal proposal, String message) {
        log.warn("[WorkflowBuilder] marking proposal {} FAILED: {}", proposal.getId(), message);
        proposal.setStatus("failed");
        proposal.setErrorMessage(message);
        return repo.update(proposal);
    }

    private static Path relativizeQuiet(Path root, Path target) {
        try {
            return root.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize());
        } catch (Exception ignored) {
            return target;
        }
    }

    /** Test-only seam for substituting the LLM agent invocation. */
    @FunctionalInterface
    interface AgentInvoker {
        void invoke(WorkflowProposal proposal,
                    Path workspace,
                    Path draftFile,
                    Path executionLogFile,
                    ModelRef modelRef,
                    Set<String> skillAllowlist,
                    boolean initialAttempt,
                    String resumeFeedback,
                    ChatMemory chatMemory,
                    ToolCallCounters counters) throws Exception;
    }

    /** Test-only setter for swapping in a deterministic fake. */
    void setAgentInvoker(AgentInvoker invoker) {
        this.agentInvoker = invoker == null ? this::runAgent : invoker;
    }

    /**
     * Per-attempt counters for the path-restricted draft-mutating tools.
     * Created fresh by {@link #build} before each invocation and inspected
     * after the agent returns to definitively distinguish a productive
     * attempt (≥1 successful mutating call) from a no-op (model replied
     * without calling any). The SHA-256 hash check remains as a defense-
     * in-depth: edits that net out byte-identical (rare but possible) are
     * still caught.
     */
    static final class ToolCallCounters {
        final AtomicInteger write = new AtomicInteger();
        final AtomicInteger edit = new AtomicInteger();
        final AtomicInteger applyPatch = new AtomicInteger();
        final AtomicInteger rejectedWriteOnRetry = new AtomicInteger();

        int mutatingCalls() {
            return write.get() + edit.get() + applyPatch.get();
        }
    }

    // --- inner tool callbacks ----------------------------------------------

    /**
     * Path-restricted wrapper around {@link FilesystemToolCallback}. Verifies
     * the {@code path} arg in the JSON input resolves to the same canonical
     * file as the proposal's draft path before delegating to
     * {@link ToolExecutionService}.
     */
    private static final class PathRestrictedFsCallback implements ToolCallback {

        private final String name;
        private final String description;
        private final String inputSchema;
        private final ToolExecutionService toolExecutionService;
        private final Path allowedAbsoluteFile;
        private final AtomicInteger successCounter;

        PathRestrictedFsCallback(String name,
                                 String description,
                                 String inputSchema,
                                 ToolExecutionService toolExecutionService,
                                 Path allowedAbsoluteFile,
                                 AtomicInteger successCounter) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.toolExecutionService = toolExecutionService;
            this.allowedAbsoluteFile = allowedAbsoluteFile.toAbsolutePath().normalize();
            this.successCounter = successCounter;
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
            String requested = extractPath(jsonInput);
            if (requested == null) {
                return "{\"success\":false,\"error\":\"path is required\"}";
            }
            Path workspaceRoot = WorkspaceContextHolder.current();
            Path resolved = workspaceRoot == null
                    ? Path.of(requested).toAbsolutePath().normalize()
                    : workspaceRoot.resolve(requested).toAbsolutePath().normalize();
            if (!resolved.equals(allowedAbsoluteFile)) {
                return "{\"success\":false,\"error\":\"path_not_allowed\","
                        + "\"hint\":\"only the proposal draft file may be written; received '"
                        + requested + "'\"}";
            }
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
            if (successCounter != null) {
                successCounter.incrementAndGet();
            }
            return result.body() == null ? "{\"success\":true}" : result.body();
        }

        @SuppressWarnings("unchecked")
        private String extractPath(String jsonInput) {
            if (jsonInput == null || jsonInput.isBlank()) return null;
            try {
                Map<String, Object> parsed = new ObjectMapper().readValue(jsonInput, Map.class);
                Object value = parsed.get("path");
                return value == null ? null : String.valueOf(value);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String escape(String value) {
            if (value == null) return "";
            return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
        }
    }

    /**
     * Stand-in for a tool that we want to deregister mid-build but still
     * surface the refusal politely if the model tries to call it. Used to
     * deny `write` on retry attempts so the agent can't regenerate the
     * draft from scratch and erase fields it had already corrected.
     */
    static final class RejectingToolCallback implements ToolCallback {

        private final String name;
        private final String description;
        private final String responseBody;
        private final AtomicInteger rejectionCounter;

        RejectingToolCallback(String name,
                              String description,
                              String responseBody,
                              AtomicInteger rejectionCounter) {
            this.name = name;
            this.description = description;
            this.responseBody = responseBody;
            this.rejectionCounter = rejectionCounter;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String jsonInput) {
            return call(jsonInput, null);
        }

        @Override
        public String call(String jsonInput, ToolContext toolContext) {
            if (rejectionCounter != null) {
                rejectionCounter.incrementAndGet();
            }
            return responseBody;
        }
    }

    /**
     * Loads any skill in the supplied allowlist. Anything else returns a
     * polite refusal so the model self-corrects.
     */
    private static final class SkillLoadCallback implements ToolCallback {

        private static final String NAME = "skill_load";

        private final SkillRegistryService skillRegistryService;
        private final ObjectMapper objectMapper;
        private final Set<String> allowlist;
        private final String description;
        private final String inputSchema;

        SkillLoadCallback(SkillRegistryService skillRegistryService,
                          ObjectMapper objectMapper,
                          Set<String> allowlist) {
            this.skillRegistryService = skillRegistryService;
            this.objectMapper = objectMapper;
            this.allowlist = Collections.unmodifiableSet(new LinkedHashSet<>(allowlist));
            this.description = "Load a skill bundle (SKILL.md + references) as one text blob. "
                    + "Allowed names: " + allowlist + ".";
            // Build a JSON-Schema enum dynamically so the model only sees
            // the names it can actually load.
            StringBuilder enumValues = new StringBuilder();
            int i = 0;
            for (String n : allowlist) {
                if (i++ > 0) enumValues.append(", ");
                enumValues.append('"').append(n.replace("\"", "\\\"")).append('"');
            }
            this.inputSchema = """
                    {"type":"object",
                     "required":["name"],
                     "properties":{"name":{"type":"string","enum":[%s]}}}
                    """.formatted(enumValues.toString());
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(NAME)
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
            String requested = parseName(jsonInput);
            if (requested == null || requested.isBlank()) {
                return "skill_load: name is required.";
            }
            String matched = null;
            for (String allowed : allowlist) {
                if (allowed.equalsIgnoreCase(requested.trim())) {
                    matched = allowed;
                    break;
                }
            }
            if (matched == null) {
                return "Skill \"" + requested + "\" is not loadable for this proposal. "
                        + "Allowed: " + allowlist + ".";
            }
            SkillRegistryService.SkillSnapshot snapshot = skillRegistryService.getEnabledSkillByName(matched);
            if (snapshot == null || snapshot.files() == null || snapshot.files().isEmpty()) {
                return "Skill \"" + matched + "\" is not enabled or has no files.";
            }
            List<Map.Entry<String, String>> entries = new ArrayList<>(snapshot.files().entrySet());
            entries.sort((a, b) -> {
                boolean aIsSkill = "SKILL.md".equalsIgnoreCase(a.getKey());
                boolean bIsSkill = "SKILL.md".equalsIgnoreCase(b.getKey());
                if (aIsSkill && !bIsSkill) return -1;
                if (!aIsSkill && bIsSkill) return 1;
                return a.getKey().compareToIgnoreCase(b.getKey());
            });
            StringBuilder sb = new StringBuilder();
            sb.append("<skill_content name=\"").append(matched).append("\">\n");
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
}
