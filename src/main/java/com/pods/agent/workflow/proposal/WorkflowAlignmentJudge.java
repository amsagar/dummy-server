package com.pods.agent.workflow.proposal;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.WorkflowProposalProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.SkillRegistryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Single-shot LLM judge that runs after structural validation passes inside
 * the {@link WorkflowBuilderService} loop. Confirms the drafted workflow
 * aligns with the skills the original chat agent loaded (priority 1) and the
 * execution log (priority 2).
 *
 * <p>Returns {@link Verdict#aligned} = false with a critique string when
 * the workflow contradicts a skill rule, references variables / outputs
 * differently than the skill prescribes, or omits required steps the skill
 * mandates. The builder loop feeds the critique back to the builder agent so
 * it can edit the draft file in place.
 */
@Service
@Slf4j
public class WorkflowAlignmentJudge {

    private final ModelProviderRouter modelProviderRouter;
    private final SkillRegistryService skillRegistryService;
    private final WorkflowProposalProperties properties;
    private final ObjectMapper objectMapper;

    public WorkflowAlignmentJudge(ModelProviderRouter modelProviderRouter,
                                  SkillRegistryService skillRegistryService,
                                  WorkflowProposalProperties properties,
                                  ObjectMapper objectMapper) {
        this.modelProviderRouter = modelProviderRouter;
        this.skillRegistryService = skillRegistryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Judge alignment. Returns {@link Verdict#aligned} = true on any failure
     * to invoke the LLM (model unresolvable, network error, parse failure)
     * so a misbehaving judge cannot indefinitely block a valid workflow.
     * Structural validation runs separately and is always enforced.
     *
     * @param classifierReason Phase-1 classifier reasoning text (intent +
     *     control-flow hint). May contain words like "loop", "for each",
     *     "batch" — surfaced verbatim so the judge can corroborate /
     *     contradict what it sees in the workflow JSON.
     */
    public Verdict judge(String workflowJson,
                         List<String> skillNames,
                         String executionLogJson,
                         String classifierReason,
                         ModelRef fallbackModel) {
        if (workflowJson == null || workflowJson.isBlank()) {
            return new Verdict(false, "workflow_json_empty", "high");
        }
        ModelRef modelRef = resolveModel(fallbackModel);
        if (modelRef == null) {
            log.warn("[WorkflowAlignmentJudge] no model available; treating as aligned");
            return new Verdict(true, "alignment_skipped_no_model", "low");
        }
        try {
            String skillsBlob = renderSkillContent(skillNames);
            String prompt = """
                    You are the Workflow Alignment Judge. You will be given a
                    candidate workflow JSON document, the content of every skill
                    the original chat agent loaded during the source turn, and the
                    typed execution log for that turn.

                    Decide whether the workflow correctly implements what the
                    skills prescribe. Skills are the source of truth: if the
                    workflow contradicts a skill rule (wrong variable name,
                    missing mandatory step, wrong tool argument shape, ignoring
                    a guard condition the skill requires, using AiChatPlugin
                    where the skill mandates ai_reasoning, etc.) you MUST mark
                    it misaligned and explain precisely.

                    Priority of evidence:
                      1. Skill content (highest). Treat skill rules as MUST.
                      2. Phase-1 classifier reasoning (next). The classifier
                         already saw the same execution log and labeled the
                         intent — honor any "loop", "for each", "batch",
                         "parallel" hint it gives.
                      3. Execution log (informational). Use it to confirm tool
                         names + input shapes — but NEVER let log observations
                         force a literal call-by-call replay of the workflow.

                    SKELETON-FIRST rule (highest priority — overrides
                    everything else when applicable): If any loaded skill
                    includes a file under {@code templates/*.json}, treat
                    THAT JSON as the canonical structural shape for this
                    workflow. The builder is expected to start from it and
                    edit field values only. Your critique must:
                      - Reference the skeleton's exact activity IDs and
                        transitions when describing what's missing or wrong.
                      - Refuse to "patch around" structural divergence —
                        if the draft restructures activities the skeleton
                        had (deleting, renaming, replacing foreach with
                        enumeration or parallel_task, dropping accumulators,
                        or rewiring transitions), the critique is
                        severity=high and the corrective action is
                        "restart from `templates/<name>.json` verbatim and
                        edit only field values", NOT a list of activity-
                        level patches. Piecemeal patches across multiple
                        retry attempts have a documented failure mode: the
                        model drifts further from the skeleton each round.
                        Anchor the critique on the skeleton, not on the
                        current draft.
                      - Identify the skeleton file by name in your critique
                        when one exists, so the builder knows which file to
                        copy from on the next attempt.

                    HARD anti-enumeration rule (overrides any other instinct):
                    Skill rules dictate control flow. If the execution log
                    contains 3+ calls to the same tool with varying values for
                    the same input key (e.g. {"id":1}, {"id":2}, {"id":3}, ...),
                    the workflow MUST encode that as a SINGLE foreach / while /
                    batch activity whose body is one parameterized tool
                    activity — per the workflow-architect skill rule
                    "AgentToolPlugin activities = distinct call-sites (one per
                    loop body, even if the turn called it many times)".
                    A workflow that enumerates the calls as N separate tool
                    activities (e.g. `call_X_1`, `call_X_2`, ..., `call_X_20`
                    with hardcoded inputs) is misaligned with severity=high.
                    Your critique in that case MUST direct the builder to:
                      - delete the N enumerated tool activities,
                      - add ONE foreach activity (collection from the prior
                        list-fetch tool's output, itemVar=currentItem),
                      - put a single tool activity in the body that reads its
                        varying input from #{#currentItem.<key>} (or #{#currentItem}
                        if the items themselves are scalars),
                      - add the loop transitions (body / no-match exit / back-edge),
                      - reference templates/foreach-accumulate.json for the shape.
                    NEVER request the opposite (do NOT ever ask the builder to
                    add more enumerated activities to "match" the log lines).

                    If no skills were loaded by the chat agent, base your
                    judgement on the classifier reasoning + execution log,
                    still applying the anti-enumeration rule above.

                    Output contract — return ONLY this JSON, no prose, no fences:

                    {
                      "aligned": true|false,
                      "critique": "When aligned=false, one to three sentences
                                   describing exactly what to change in the
                                   workflow JSON. When aligned=true, may be
                                   empty.",
                      "severity": "low" | "medium" | "high"
                    }

                    Be specific in critiques: name the activity id, the property
                    path, and the corrective action. The builder agent will use
                    your critique verbatim to edit the file.

                    --- Skills loaded by chat agent (PRIORITY 1) ---
                    %s

                    --- Phase-1 classifier reasoning (PRIORITY 2) ---
                    %s

                    --- Workflow JSON to judge ---
                    %s

                    --- Execution log (PRIORITY 3) ---
                    %s
                    """.formatted(
                            skillsBlob,
                            classifierReason == null || classifierReason.isBlank()
                                    ? "(no classifier reasoning available)"
                                    : classifierReason,
                            workflowJson,
                            executionLogJson == null ? "(none)" : executionLogJson);

            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(modelRef, true);
            String raw = spec.client()
                    .prompt()
                    .system("You output strict JSON. No prose, no markdown.")
                    .user(prompt)
                    .call()
                    .content();
            return parseVerdict(raw);
        } catch (Exception e) {
            log.warn("[WorkflowAlignmentJudge] judge failed: {} (treating as aligned)", e.getMessage());
            return new Verdict(true, "alignment_skipped_error:" + e.getMessage(), "low");
        }
    }

    private ModelRef resolveModel(ModelRef fallback) {
        WorkflowProposalProperties.ModelOverride override = properties.getAlignmentModel();
        if (override != null && override.isPresent()) {
            return new ModelRef(override.getProviderId(), override.getModelId());
        }
        return fallback;
    }

    private String renderSkillContent(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty() || skillRegistryService == null) {
            return "(no skills loaded by chat agent)";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : skillNames) {
            if (name == null || name.isBlank()) continue;
            SkillRegistryService.SkillSnapshot snapshot = skillRegistryService.getEnabledSkillByName(name);
            if (snapshot == null || snapshot.files() == null || snapshot.files().isEmpty()) {
                sb.append("<skill name=\"").append(name).append("\" missing=\"true\"/>\n");
                continue;
            }
            sb.append("<skill name=\"").append(name).append("\">\n");
            // Anchor SKILL.md first; sort the rest alphabetically for determinism.
            List<Map.Entry<String, String>> entries = new ArrayList<>(snapshot.files().entrySet());
            entries.sort((a, b) -> {
                boolean aIsSkill = "SKILL.md".equalsIgnoreCase(a.getKey());
                boolean bIsSkill = "SKILL.md".equalsIgnoreCase(b.getKey());
                if (aIsSkill && !bIsSkill) return -1;
                if (!aIsSkill && bIsSkill) return 1;
                return a.getKey().compareToIgnoreCase(b.getKey());
            });
            for (Map.Entry<String, String> e : entries) {
                sb.append("\n## File: ").append(e.getKey()).append("\n\n").append(e.getValue()).append("\n");
            }
            sb.append("</skill>\n\n");
        }
        return sb.length() == 0 ? "(no skills loaded by chat agent)" : sb.toString();
    }

    private Verdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Verdict(true, "alignment_skipped_empty_response", "low");
        }
        try {
            String text = raw.trim();
            int first = text.indexOf('{');
            int last = text.lastIndexOf('}');
            if (first < 0 || last <= first) {
                return new Verdict(true, "alignment_skipped_no_json", "low");
            }
            String json = text.substring(first, last + 1);
            JsonNode node = objectMapper.readTree(json);
            boolean aligned = node.path("aligned").asBoolean(true);
            String critique = node.path("critique").asText("");
            String severity = node.path("severity").asText("medium").toLowerCase(Locale.ROOT);
            return new Verdict(aligned, critique == null ? "" : critique.trim(), severity);
        } catch (Exception e) {
            return new Verdict(true, "alignment_skipped_parse_error:" + e.getMessage(), "low");
        }
    }

    /**
     * @param aligned  true when the judge says the workflow correctly
     *                 implements its skills + execution log.
     * @param critique non-empty actionable feedback when aligned=false.
     * @param severity one of {@code low|medium|high}; advisory only today.
     */
    public record Verdict(boolean aligned, String critique, String severity) {}
}
