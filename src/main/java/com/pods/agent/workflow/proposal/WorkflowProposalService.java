package com.pods.agent.workflow.proposal;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.workspace.ExecutionLogService;
import com.pods.agent.workflow.api.ProcessDefinitionMapper;
import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WorkflowProposalService {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b");
    private static final Pattern LONG_NUMBER_PATTERN = Pattern.compile("\\b\\d{4,}\\b");

    private final WorkflowProposalRepository repo;
    private final ProcessDefService processDefService;
    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final ObjectMapper objectMapper;

    /**
     * When an approval succeeds, the canonical workflow JSON is mirrored back
     * into the originating session's VFS at {@code .pods-agent/workflow/
     * proposals/<defId>.json}. The dependency is wired through the
     * constructor; failures during the mirror step are logged and swallowed
     * so they never block materialization.
     */
    private final ExecutionLogService executionLogService;

    public WorkflowProposalService(WorkflowProposalRepository repo,
                                   ProcessDefService processDefService,
                                   ModelProviderRouter modelProviderRouter,
                                   SkillRegistryService skillRegistryService,
                                   ExecutionLogService executionLogService,
                                   ObjectMapper objectMapper) {
        this.repo = repo;
        this.processDefService = processDefService;
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.executionLogService = executionLogService;
        this.objectMapper = objectMapper;
    }

    public WorkflowProposal upsertGenerated(GeneratedProposal generated) {
        Optional<WorkflowProposal> existing = repo.findBySessionTurn(generated.sessionId(), generated.turnId());
        WorkflowProposal proposal = existing.orElseGet(WorkflowProposal::new);
        proposal.setSessionId(generated.sessionId());
        proposal.setTurnId(generated.turnId());
        proposal.setUserId(generated.userId());
        proposal.setStatus("pending");
        proposal.setReason(generated.reason());
        proposal.setConfidence(generated.confidence());
        proposal.setIntentSignature(generated.intentSignature());
        proposal.setTraceRef(generated.traceRef());
        proposal.setUserPrompt(generated.userPrompt());
        proposal.setModelProviderId(generated.modelProviderId());
        proposal.setModelId(generated.modelId());
        proposal.setProposedWorkflowJson(generated.proposedWorkflowJson());
        proposal.setMatchedToolNamesJson(generated.matchedToolNamesJson());
        proposal.setDecisionComment(null);
        proposal.setDecidedBy(null);
        proposal.setDecidedAt(null);
        proposal.setMaterializedDefId(null);
        proposal.setErrorMessage(null);
        if (existing.isPresent()) return repo.update(proposal);
        return repo.save(proposal);
    }

    public List<WorkflowProposal> listPendingByUser(String userId) {
        return repo.findPendingByUser(userId);
    }

    public Optional<WorkflowProposal> getById(String id) {
        return repo.findById(id);
    }

    public List<IntentMatch> findIntentMatches(String userId, String prompt, int limit) {
        if (userId == null || userId.isBlank() || prompt == null || prompt.isBlank()) return List.of();
        List<WorkflowProposal> materialized = repo.findMaterializedByUser(userId);
        if (materialized.isEmpty()) return List.of();
        List<IntentMatch> ranked = new ArrayList<>();
        Set<String> seenDefs = new LinkedHashSet<>();
        String normalizedPrompt = normalizePrompt(prompt);
        Set<String> promptTokens = tokenize(normalizedPrompt);
        for (WorkflowProposal proposal : materialized) {
            if (proposal.getMaterializedDefId() == null || proposal.getMaterializedDefId().isBlank()) continue;
            double score = scoreIntent(promptTokens, normalizedPrompt, proposal.getIntentSignature());
            if (score < 0.34d) continue;
            if (!seenDefs.add(proposal.getMaterializedDefId())) continue;
            String name = processDefService.findById(proposal.getMaterializedDefId())
                    .map(ProcessDefDto::name)
                    .orElse(proposal.getMaterializedDefId());
            ranked.add(new IntentMatch(
                    proposal.getMaterializedDefId(),
                    name,
                    proposal.getId(),
                    score));
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        return ranked.stream().limit(Math.max(1, limit)).toList();
    }

    public Optional<WorkflowProposal> approve(String id, String approver, String comment) {
        Optional<WorkflowProposal> proposalOpt = repo.findById(id);
        if (proposalOpt.isEmpty()) return Optional.empty();
        WorkflowProposal proposal = proposalOpt.get();
        if (!"pending".equalsIgnoreCase(proposal.getStatus())) return Optional.of(proposal);
        proposal.setStatus("approved");
        proposal.setDecisionComment(comment);
        proposal.setDecidedBy(approver);
        proposal.setDecidedAt(System.currentTimeMillis());
        repo.update(proposal);
        return Optional.of(materialize(proposal));
    }

    public Optional<WorkflowProposal> reject(String id, String approver, String comment) {
        Optional<WorkflowProposal> proposalOpt = repo.findById(id);
        if (proposalOpt.isEmpty()) return Optional.empty();
        WorkflowProposal proposal = proposalOpt.get();
        if (!"pending".equalsIgnoreCase(proposal.getStatus())) return Optional.of(proposal);
        proposal.setStatus("rejected");
        proposal.setDecisionComment(comment);
        proposal.setDecidedBy(approver);
        proposal.setDecidedAt(System.currentTimeMillis());
        repo.update(proposal);
        return Optional.of(proposal);
    }

    public boolean isDuplicateIntent(String userId, String prompt) {
        if (userId == null || userId.isBlank() || prompt == null || prompt.isBlank()) return false;
        String normalized = normalizePrompt(prompt);
        Set<String> tokens = tokenize(normalized);
        for (WorkflowProposal p : repo.findActiveByUser(userId)) {
            double score = scoreIntent(tokens, normalized, p.getIntentSignature());
            if (score >= 0.90d) return true;
        }
        return false;
    }

    public boolean validateGenericWorkflow(ProcessDefDto dto, String sourcePrompt) {
        if (dto == null) return false;
        try {
            String json = objectMapper.writeValueAsString(dto).toLowerCase(Locale.ROOT);
            for (String literal : extractRunSpecificLiterals(sourcePrompt)) {
                if (json.contains(literal.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Phase 3 hook: write the approved/canonical workflow JSON into the
     * originating session's VFS. The file is browsable from the same workspace
     * as the per-turn trace, which closes the loop you asked for: chat writes
     * a turn JSON, approval writes the workflow JSON beside it.
     *
     * <p>Failures are logged and swallowed — VFS mirroring is observability,
     * not correctness; the workflow is already persisted in {@code
     * agent.process_def}.
     */
    private void mirrorApprovedProposalToVfs(WorkflowProposal proposal, ProcessDefDto saved) {
        if (executionLogService == null) return;
        if (proposal.getSessionId() == null || proposal.getSessionId().isBlank()) return;
        if (saved == null || saved.id() == null || saved.id().isBlank()) return;
        try {
            Path target = executionLogService.approvedProposalPath(proposal.getSessionId(), saved.id());
            String json = objectMapper.writeValueAsString(saved);
            Files.writeString(target, json, StandardCharsets.UTF_8);
            log.debug("[WorkflowProposalService] mirrored approved proposal {} to {}", saved.id(), target);
        } catch (Exception e) {
            log.warn("[WorkflowProposalService] failed to mirror approved proposal {} to VFS: {}",
                    saved.id(), e.getMessage());
        }
    }

    private WorkflowProposal materialize(WorkflowProposal proposal) {
        try {
            ProcessDefDto draft = objectMapper.readValue(proposal.getProposedWorkflowJson(), ProcessDefDto.class);
            ProcessDefDto dto = llmMaterializeWorkflow(draft, proposal);
            if (!validateGenericWorkflow(dto, proposal.getUserPrompt())) {
                proposal.setStatus("failed");
                proposal.setErrorMessage("proposal_not_generic");
                return repo.update(proposal);
            }
            validateWorkflowStructure(dto);
            String baseName = dto.name() == null || dto.name().isBlank() ? "Workflow Proposal" : dto.name();
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
            proposal.setErrorMessage(null);
            mirrorApprovedProposalToVfs(proposal, saved);
            return repo.update(proposal);
        } catch (Exception e) {
            log.warn("[WorkflowProposalService] materialization failed for {}: {}", proposal.getId(), e.getMessage());
            proposal.setStatus("failed");
            proposal.setErrorMessage(e.getMessage());
            return repo.update(proposal);
        }
    }

    /**
     * Parse + validate a workflow JSON document produced by the
     * {@link WorkflowArchitectService} subagent and turn it into a
     * {@link GeneratedProposal}. Returns empty when the JSON is missing,
     * malformed, fails the generic-vs-run-specific check, or fails structural
     * validation. Mirrors {@link #generateByLlm} but skips the giant
     * single-shot LLM call — the subagent already did that work.
     */
    public Optional<GeneratedProposal> generateFromAgentDraft(String sessionId,
                                                              String turnId,
                                                              String userId,
                                                              String userPrompt,
                                                              List<String> toolNames,
                                                              ModelRef modelRef,
                                                              String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return Optional.empty();
        if (modelRef == null) return Optional.empty();
        try {
            String json = extractJson(rawJson);
            ProcessDefDto dto = parseProcessDefDtoFlexible(json);
            if (!validateGenericWorkflow(dto, userPrompt)) {
                log.warn("[WorkflowProposalService] subagent draft for turn {} failed generic check", turnId);
                return Optional.empty();
            }
            validateWorkflowStructure(dto);
            String artifactJson = objectMapper.writeValueAsString(dto);
            String toolsJson = objectMapper.writeValueAsString(toolNames == null ? List.of() : toolNames);
            String normalizedPrompt = normalizePrompt(userPrompt);
            String reason = "Subagent drafted reusable workflow for repeated intent and tools: "
                    + (toolNames == null || toolNames.isEmpty() ? "(none)" : String.join(", ", toolNames));
            double confidence = 0.85d;
            return Optional.of(new GeneratedProposal(
                    sessionId,
                    turnId,
                    userId,
                    reason,
                    confidence,
                    normalizedPrompt,
                    "turn:" + turnId,
                    userPrompt,
                    modelRef.providerID(),
                    modelRef.modelID(),
                    artifactJson,
                    toolsJson));
        } catch (Exception e) {
            log.warn("[WorkflowProposalService] subagent draft parse/validate failed for turn {}: {}",
                    turnId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<GeneratedProposal> generateByLlm(String sessionId,
                                                     String turnId,
                                                     String userId,
                                                     String userPrompt,
                                                     String assistantResponse,
                                                     List<String> toolNames,
                                                     ModelRef modelRef) {
        if (userPrompt == null || userPrompt.isBlank() || toolNames == null || toolNames.isEmpty()) return Optional.empty();
        if (modelRef == null) return Optional.empty();
        try {
            String prompt = """
                    Generate a reusable workflow JSON for this user request.
                    Return ONLY valid JSON matching ProcessDefDto shape:
                    {
                      "id": null,
                      "name": "...",
                      "version": "1",
                      "packageId": null,
                      "description": "...",
                      "variables": [{"name":"...","javaClass":"java.lang.String","defaultExpression":null,"required":true}],
                      "activities": [...],
                      "transitions": [...]
                    }

                    Rules:
                    - Must be generic and parameterized.
                    - Emit inputSchema/outputSchema on executable activities whenever possible,
                      but ONLY using shapes the engine actually validates (see schema rules below).
                      When in doubt leave them as {} — the engine then skips validation.
                    - For list processing, prefer activity types foreach/while/batch instead of ad-hoc route loops.
                    - Loop activities (foreach/while/batch) MUST include maxIterations in properties.
                    - Loop activities MUST be wired with three edges:
                        (1) loopActivity --ON_SUCCESS, condition null--> bodyEntry
                        (2) loopActivity --ON_NO_MATCH (or isDefault=true)--> exitTarget
                        (3) bodyExit --ON_SUCCESS--> loopActivity   (back-edge)
                      The engine writes __loop_continue_<loopActivityId> into the scope on
                      every dispatch and automatically suppresses edge (1) once the loop is
                      exhausted, so edge (2) fires exactly once at the end. Never route a loop
                      with a single outgoing edge — it will never terminate.
                    - Never hardcode run-specific literals (ids/order numbers/UUIDs from this turn).
                    - Use variables and expressions instead.
                    - Prefer deterministic tool steps plus optional synthesis step.
                    - Include clear start/end route nodes.
                    - Activity "type" MUST be exactly one of: "normal" | "tool" | "route" | "subflow" | "foreach" | "while" | "batch" | "ai_reasoning".
                      Never use BPMN names like startEvent, endEvent, task, userTask, serviceTask,
                      scriptTask, exclusiveGateway, parallelGateway, subProcess, callActivity.
                      Use type "route" with isStart=true for the entry node and isEnd=true for the exit node.
                    - For transitions, set trigger to one of ON_SUCCESS|ON_NO_MATCH|ON_ERROR|ON_TIMEOUT|ON_VALIDATION_ERROR.
                      Use ON_NO_MATCH or isDefault=true for fallback routes.
                    - Route nodes must be transition-only; do not attach decision plugins to route activities.
                    - Include activity errorPolicy where execution can fail/time out:
                      { "retryCount": n, "backoffMs": n, "timeoutMs": n|null, "failFast": bool, "continueOnError": bool }.
                    - Each activity "properties" MUST be a JSON object {}, never an array.
                    - For EVERY tool name listed under "Tool names used in turn" you MUST emit a
                      tool activity with pluginName "AgentToolPlugin" and properties
                      { "toolName": "<exact name from the list>", "input": "#{#someVariable}" }.
                      Do NOT replace those tool calls with HttpRequestPlugin or invent URLs.
                      HttpRequestPlugin / McpToolPlugin / CodeExecPlugin are only for steps
                      that are NOT on the tool-names list and are NOT judgement-style (e.g.
                      a deterministic data transformation tail).
                    - For judgement / classification / summarization / verdict steps, use an
                      ai_reasoning activity (pluginName=null, properties.prompt required,
                      optional properties.invokeWhen SpEL guard, optional properties.providerID
                      / properties.modelID per-node override). The runtime is BARRED from
                      calling the LLM through any other node type. Never use AiChatPlugin as
                      a tool for these steps; promote them to ai_reasoning.
                    - Every "#name" referenced in any property value or transition condition MUST be
                      declared in the top-level "variables" array with a name, javaClass, and (where
                      sensible) a defaultExpression. Empty variables[] is only valid when no #name
                      is referenced anywhere.
                    - SpEL collection literals are NOT JSON. The empty-map literal is "{:}";
                      "{}" is an empty LIST. For java.util.Map variables ALWAYS use
                      "defaultExpression": null (preferred) or "{:}". NEVER "{}" — it produces
                      an UnmodifiableRandomAccessList at runtime and the schema validator will
                      kill the workflow on the first activity that reads the variable.
                    - Output-target variables (those an activity will WRITE into via
                      outputVariables) take "defaultExpression": null. Don't invent defaults.

                    Schema rules (these run at every activity dispatch):
                    - DEFAULT TO POPULATING SCHEMAS for every tool activity. Empty schemas
                      ({}) skip validation but produce a low-quality proposal that reviewers
                      reject. Only use {} when the shape is truly unknown (e.g. free-form
                      synthesis output).
                    - inputSchema is validated against the resolved properties Map the plugin
                      receives. Either use an object schema describing the property keys
                      (e.g. {"type":"object","required":["toolName","input"],
                      "properties":{"toolName":{"type":"string"},"input":{"type":"string"}}})
                      OR a single-value schema (e.g. {"type":"string"}) which is then
                      validated against properties.input. Never describe a Map with a
                      non-object schema that doesn't have the single-value escape hatch.
                    - inputSchema.required must list ONLY properties the underlying tool truly
                      requires. For tools that take no input (list-all endpoints, etc.), set
                      inputSchema={} — DO NOT mark "input" as required. Required-but-absent
                      input fails validation on the very first call.
                    - outputSchema is validated against the RAW plugin return value (not the
                      wrapped {varName: out} form). Write it as the shape the tool returns:
                        * tool that lists items → {"type":"array","items":{"type":"object"}}
                        * tool that fetches one entity → {"type":"object"} (add a
                          "properties" block when you know the field names)
                        * tool that returns a primitive → {"type":"string"|"number"|"boolean"}
                      Only use {"type":"object","properties":{"<varName>":...}} when you
                      intentionally want to validate the wrapped form.
                    - For loop activities (foreach/while/batch) the dispatcher's bookkeeping
                      output is {continue, index, size}; keep their schemas as {}.
                    - CodeExecPlugin returns {success, output, stdout, stderr}; the entire
                      wrapper lands in the output variable, so reference #yourVar.output
                      downstream (not #yourVar) when you only want the script's return.
                      For accumulator/reducer steps, prefer this outputSchema:
                        {"type":"object","required":["success","output"],
                         "properties":{"success":{"type":"boolean"},
                                       "output":{"type":"array","items":{"type":"object"}}}}.

                    User prompt:
                    %s

                    Assistant response:
                    %s

                    Tool names used in turn:
                    %s

                    Workflow architect skill (loaded via skill tool):
                    %s
                    """.formatted(
                    userPrompt,
                    assistantResponse == null ? "" : assistantResponse,
                    String.join(", ", toolNames),
                    workflowSkillContent());
            var spec = modelProviderRouter.resolve(modelRef, true);
            String raw = spec.client()
                    .prompt()
                    .system("You design reusable workflow definitions. Output JSON only.")
                    .user(prompt)
                    .call()
                    .content();
            String json = extractJson(raw);
            ProcessDefDto dto = parseProcessDefDtoFlexible(json);
            if (!validateGenericWorkflow(dto, userPrompt)) return Optional.empty();
            validateWorkflowStructure(dto);
            String artifactJson = objectMapper.writeValueAsString(dto);
            String toolsJson = objectMapper.writeValueAsString(toolNames);
            String normalizedPrompt = normalizePrompt(userPrompt);
            String reason = "LLM proposed reusable workflow for repeated intent and tools: " + String.join(", ", toolNames);
            double confidence = 0.82d;
            return Optional.of(new GeneratedProposal(
                    sessionId,
                    turnId,
                    userId,
                    reason,
                    confidence,
                    normalizedPrompt,
                    "turn:" + turnId,
                    userPrompt,
                    modelRef.providerID(),
                    modelRef.modelID(),
                    artifactJson,
                    toolsJson
            ));
        } catch (Exception e) {
            log.warn("[WorkflowProposalService] LLM generation failed for turn {}: {}", turnId, e.getMessage());
            return Optional.empty();
        }
    }

    private ProcessDefDto llmMaterializeWorkflow(ProcessDefDto draft, WorkflowProposal proposal) throws Exception {
        ModelRef modelRef = proposal.getModelProviderId() == null || proposal.getModelId() == null
                ? null
                : new ModelRef(proposal.getModelProviderId(), proposal.getModelId());
        if (modelRef == null) {
            throw new IllegalStateException("missing_model_ref");
        }
        String draftJson = objectMapper.writeValueAsString(draft);
        String prompt = """
                Refine this workflow draft into a final ProcessDefDto JSON.
                Return ONLY JSON with the same schema.
                Keep it parameterized and generic.
                Do not include run-specific literals.
                Keep version as "1".
                Activity "type" MUST be exactly one of: "normal" | "tool" | "route" | "subflow" | "foreach" | "while" | "batch" | "ai_reasoning".
                Never emit BPMN names (startEvent, endEvent, task, userTask, serviceTask, gateways, subProcess).
                Use "route" with isStart=true for the entry node and isEnd=true for the exit node.
                Force transition-only decisioning (no decision plugins on route nodes).
                Emit transition trigger semantics and include explicit no-match/default edge where branching occurs.
                Ensure loop activities (foreach/while/batch) include maxIterations guard in properties.
                Loop activities MUST be wired with three edges:
                  (1) loopActivity --ON_SUCCESS, condition null--> bodyEntry
                  (2) loopActivity --ON_NO_MATCH (or isDefault=true)--> exitTarget
                  (3) bodyExit --ON_SUCCESS--> loopActivity (back-edge)
                The engine writes __loop_continue_<loopActivityId> and automatically suppresses
                edge (1) once the loop is exhausted, so edge (2) fires once at the end.
                ai_reasoning activities MUST have properties.prompt set, at least one
                outputVariables entry, and pluginName=null. Optional invokeWhen SpEL boolean
                gates the LLM call. Output is a Map; downstream uses #yourVar.text.
                Never use AiChatPlugin tool activities for judgement steps \u2014 promote them
                to ai_reasoning.
                Never refactor a loop down to a single outgoing edge.
                Include activity errorPolicy where tool/loop execution can fail or timeout.
                Each activity "properties" MUST be a JSON object {}, never an array.
                Preserve every existing AgentToolPlugin tool activity from the draft. Do NOT replace
                an AgentToolPlugin step with HttpRequestPlugin or any other plugin — those activities
                represent tools the assistant actually used in the source turn and the workflow must
                replay them.
                Every "#name" referenced in any expression MUST be declared in the top-level
                "variables" array.
                Include inputSchema/outputSchema on activities where inputs or outputs are structured,
                following the Schema rules in the workflow-architect skill (outputSchema validates the
                RAW plugin return, not the wrapped {varName: out} map; inputSchema validates the
                resolved properties Map or — for non-object schemas — properties.input).
                When unsure of a schema, leave it as {} so validation is skipped.
                Draft JSON:
                %s

                Workflow architect skill (loaded via skill tool):
                %s
                """.formatted(draftJson, workflowSkillContent());
        var spec = modelProviderRouter.resolve(modelRef, true);
        String raw = spec.client()
                .prompt()
                .system("You output final workflow definitions as strict JSON.")
                .user(prompt)
                .call()
                .content();
        String json = extractJson(raw);
        return parseProcessDefDtoFlexible(json);
    }

    private String extractJson(String raw) {
        if (raw == null) throw new IllegalStateException("empty_llm_response");
        String text = raw.trim();
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) return text.substring(first, last + 1);
        throw new IllegalStateException("invalid_json_response");
    }

    private String workflowSkillContent() {
        if (skillRegistryService == null) return "No skill content available.";
        SkillRegistryService.SkillSnapshot snapshot = skillRegistryService.getEnabledSkillByName("workflow-architect");
        if (snapshot == null || snapshot.files() == null || snapshot.files().isEmpty()) {
            return "No skill content available.";
        }
        // Concatenate every file in the bundle so references/, templates/, and doc/
        // all reach the LLM. SKILL.md is anchored first; everything else is sorted by
        // path for deterministic ordering.
        List<Map.Entry<String, String>> entries = new ArrayList<>(snapshot.files().entrySet());
        entries.sort((a, b) -> {
            boolean aIsSkill = "SKILL.md".equalsIgnoreCase(a.getKey());
            boolean bIsSkill = "SKILL.md".equalsIgnoreCase(b.getKey());
            if (aIsSkill && !bIsSkill) return -1;
            if (!aIsSkill && bIsSkill) return 1;
            return a.getKey().compareToIgnoreCase(b.getKey());
        });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entries) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## File: ").append(e.getKey()).append("\n\n").append(e.getValue());
        }
        return sb.length() == 0 ? "No skill content available." : sb.toString();
    }

    ProcessDefDto parseProcessDefDtoFlexible(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!(root instanceof ObjectNode objectRoot)) {
            throw new IllegalStateException("workflow_json_must_be_object");
        }
        JsonNode activitiesNode = objectRoot.get("activities");
        if (activitiesNode instanceof ArrayNode activities) {
            for (JsonNode activityNode : activities) {
                if (!(activityNode instanceof ObjectNode activityObj)) continue;
                JsonNode properties = activityObj.get("properties");
                if (!(properties == null || properties.isNull() || properties.isObject())) {
                    ObjectNode normalized = objectMapper.createObjectNode();
                    if (properties.isArray()) {
                        ArrayNode arr = (ArrayNode) properties;
                        int idx = 0;
                        for (JsonNode item : arr) {
                            if (item != null && item.isObject()
                                    && item.has("key")
                                    && item.has("value")
                                    && item.get("key").isTextual()) {
                                normalized.set(item.get("key").asText(), item.get("value"));
                            } else if (item != null && item.isObject() && item.size() == 1) {
                                item.properties().forEach(entry -> normalized.set(entry.getKey(), entry.getValue()));
                            } else {
                                normalized.set("item" + idx++, item);
                            }
                        }
                    } else {
                        normalized.set("value", properties);
                    }
                    activityObj.set("properties", normalized);
                }
            }
            normalizeActivityTypes(activities);
        }
        JsonNode transitionsNode = objectRoot.get("transitions");
        if (transitionsNode instanceof ArrayNode transitions) {
            normalizeTransitionFields(transitions);
        }
        return objectMapper.treeToValue(objectRoot, ProcessDefDto.class);
    }

    /**
     * Maps BPMN-style activity types (startEvent, endEvent, serviceTask, gateways, ...)
     * onto the four canonical types accepted by ActivityDef. The LLM occasionally
     * regresses to BPMN vocabulary; this layer keeps a single drift from killing the
     * whole proposal. Canonical values pass through untouched.
     */
    private void normalizeActivityTypes(ArrayNode activities) {
        Map<String, String> remapped = new LinkedHashMap<>();
        boolean anyStartFlagged = false;
        for (JsonNode activityNode : activities) {
            if (!(activityNode instanceof ObjectNode obj)) continue;
            if (obj.path("isStart").asBoolean(false)) anyStartFlagged = true;
        }
        for (JsonNode activityNode : activities) {
            if (!(activityNode instanceof ObjectNode obj)) continue;
            JsonNode typeNode = obj.get("type");
            if (typeNode == null || !typeNode.isTextual()) continue;
            String original = typeNode.asText();
            String key = original.trim().toLowerCase(Locale.ROOT);
            String mapped = switch (key) {
                case "normal", "tool", "route", "subflow", "foreach", "while", "batch", "ai_reasoning" -> null;
                case "startevent", "start", "event", "none" -> "route";
                case "endevent", "end", "terminateendevent" -> "route";
                case "task", "servicetask", "scripttask", "businessruletask" -> "tool";
                case "usertask", "manualtask", "humantask" -> "normal";
                case "exclusivegateway", "inclusivegateway", "eventbasedgateway" -> "route";
                case "parallelgateway" -> "route";
                case "subprocess", "callactivity" -> "subflow";
                default -> "route";
            };
            if (mapped == null) continue;
            obj.put("type", mapped);
            remapped.put(obj.path("id").asText(original), original + " -> " + mapped);

            if (("startevent".equals(key) || "start".equals(key))
                    && !obj.path("isStart").asBoolean(false) && !anyStartFlagged) {
                obj.put("isStart", true);
                anyStartFlagged = true;
            }
            if ("endevent".equals(key) || "end".equals(key) || "terminateendevent".equals(key)) {
                if (!obj.path("isEnd").asBoolean(false)) obj.put("isEnd", true);
            }
            if ("parallelgateway".equals(key) && !obj.path("andJoin").asBoolean(false)) {
                obj.put("andJoin", true);
            }
        }
        if (!remapped.isEmpty()) {
            log.warn("[WorkflowProposalService] normalized {} non-canonical activity type(s): {}",
                    remapped.size(), remapped);
        }
    }

    private void normalizeTransitionFields(ArrayNode transitions) {
        for (JsonNode transitionNode : transitions) {
            if (!(transitionNode instanceof ObjectNode obj)) continue;
            JsonNode triggerNode = obj.get("trigger");
            if (triggerNode == null || triggerNode.isNull() || !triggerNode.isTextual()) continue;
            String raw = triggerNode.asText().trim();
            if (raw.isEmpty()) continue;
            String normalized = raw
                    .replace("-", "_")
                    .replace(" ", "_")
                    .toUpperCase(Locale.ROOT);
            if (!normalized.startsWith("ON_")) {
                normalized = "ON_" + normalized;
            }
            obj.put("trigger", normalized);
        }
    }

    private void validateWorkflowStructure(ProcessDefDto dto) {
        // Reuse engine-side structural validation and enum/graph checks.
        // Proposal-generation flow typically uses id=null (assigned at save time),
        // so provide an ephemeral id for pre-save validation.
        ProcessDefDto candidate = dto;
        if (dto != null && (dto.id() == null || dto.id().isBlank())) {
            candidate = new ProcessDefDto(
                    UUID.randomUUID().toString(),
                    dto.name(),
                    dto.version(),
                    dto.packageId(),
                    dto.description(),
                    dto.variables(),
                    dto.activities(),
                    dto.transitions());
        }
        ProcessDefinitionMapper.toDomain(candidate);
        if (dto.activities() == null) return;
        for (ProcessDefDto.ActivityDto a : dto.activities()) {
            if ("route".equalsIgnoreCase(a.type())
                    && a.pluginName() != null
                    && !a.pluginName().isBlank()) {
                throw new IllegalStateException("route_activity_plugin_not_allowed");
            }
            if (isLoopType(a.type())) {
                Object maxIterations = a.properties() == null ? null : a.properties().get("maxIterations");
                if (!(maxIterations instanceof Number) || ((Number) maxIterations).intValue() <= 0) {
                    throw new IllegalStateException("loop_activity_missing_max_iterations:" + a.id());
                }
            }
            if ("ai_reasoning".equalsIgnoreCase(a.type())) {
                Map<String, Object> props = a.properties();
                Object prompt = props == null ? null : props.get("prompt");
                if (!(prompt instanceof String s) || s.isBlank()) {
                    throw new IllegalStateException("ai_reasoning_missing_prompt:" + a.id());
                }
                if (a.outputVariables() == null || a.outputVariables().isEmpty()) {
                    throw new IllegalStateException("ai_reasoning_missing_output_variable:" + a.id());
                }
                if (a.pluginName() != null && !a.pluginName().isBlank()) {
                    throw new IllegalStateException("ai_reasoning_plugin_not_allowed:" + a.id());
                }
            }
        }
        if (dto.transitions() == null) return;
        for (ProcessDefDto.TransitionDto t : dto.transitions()) {
            if (t.trigger() == null || t.trigger().isBlank()) {
                throw new IllegalStateException("transition_trigger_required:" + t.id());
            }
            String normalized = t.trigger().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ON_SUCCESS", "ON_NO_MATCH", "ON_ERROR", "ON_TIMEOUT", "ON_VALIDATION_ERROR")
                    .contains(normalized)) {
                throw new IllegalStateException("transition_trigger_invalid:" + t.id() + ":" + t.trigger());
            }
        }
    }

    void assertWorkflowStructure(ProcessDefDto dto) {
        validateWorkflowStructure(dto);
    }

    private boolean isLoopType(String type) {
        if (type == null) return false;
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return "foreach".equals(normalized) || "while".equals(normalized) || "batch".equals(normalized);
    }

    private String uniqueName(String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (!processDefService.findByName(candidate).isEmpty()) {
            candidate = baseName + " (" + suffix++ + ")";
        }
        return candidate;
    }

    private String normalizePrompt(String prompt) {
        if (prompt == null) return "";
        String normalized = UUID_PATTERN.matcher(prompt).replaceAll(" ");
        normalized = LONG_NUMBER_PATTERN.matcher(normalized).replaceAll(" ");
        return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]+", " ").replaceAll("\\s+", " ").trim();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String[] parts = text.split("\\s+");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() > 2) out.add(part);
        }
        return out;
    }

    private double scoreIntent(Set<String> promptTokens, String normalizedPrompt, String signature) {
        if (signature == null || signature.isBlank()) return 0.0d;
        Set<String> sigTokens = tokenize(signature);
        if (promptTokens.isEmpty() || sigTokens.isEmpty()) return 0.0d;
        int overlap = 0;
        for (String token : promptTokens) {
            if (sigTokens.contains(token)) overlap++;
        }
        Set<String> union = new LinkedHashSet<>(promptTokens);
        union.addAll(sigTokens);
        double jaccard = union.isEmpty() ? 0.0d : (double) overlap / (double) union.size();
        boolean contains = signature.contains(normalizedPrompt) || normalizedPrompt.contains(signature);
        return Math.min(1.0d, jaccard + (contains ? 0.2d : 0.0d));
    }

    private List<String> extractRunSpecificLiterals(String sourcePrompt) {
        if (sourcePrompt == null || sourcePrompt.isBlank()) return List.of();
        List<String> literals = new ArrayList<>();
        var uuidMatcher = UUID_PATTERN.matcher(sourcePrompt);
        while (uuidMatcher.find()) {
            literals.add(uuidMatcher.group());
        }
        var numericMatcher = LONG_NUMBER_PATTERN.matcher(sourcePrompt);
        while (numericMatcher.find()) {
            literals.add(numericMatcher.group());
        }
        return literals;
    }

    public record GeneratedProposal(String sessionId,
                                    String turnId,
                                    String userId,
                                    String reason,
                                    double confidence,
                                    String intentSignature,
                                    String traceRef,
                                    String userPrompt,
                                    String modelProviderId,
                                    String modelId,
                                    String proposedWorkflowJson,
                                    String matchedToolNamesJson) {}

    public record IntentMatch(String processDefId,
                              String name,
                              String proposalId,
                              double score) {}
}
