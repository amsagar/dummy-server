package com.pods.agent.ruledomain.compiler.trace;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Option B: derive a rule manifest for a skill from its prose markdown and
 * a recorded execution trace. Runs once per skill (cached on the skill row),
 * re-derived only when the markdown changes.
 *
 * <p>This is the "business authors write prose, system figures out the
 * rules" path. The LLM reads both the skill description (high-level intent)
 * and the trace (what tools actually got called in what order), and produces
 * a structured manifest with rule names, tool ownership, intent examples,
 * and result keys.
 *
 * <p>The output is a {@link SkillRuleManifest} — same shape an author could
 * have written by hand, just produced automatically. Downstream code
 * (RuleSlicer, TraceBasedBpmnCompiler) doesn't care which path produced it.
 */
@Component
@Slf4j
public class SkillManifestDeriver {

    private final ModelProviderRouter modelProviderRouter;
    private final RuleDomainProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillManifestDeriver(ModelProviderRouter modelProviderRouter,
                                RuleDomainProperties props) {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
    }

    /**
     * Ask the compiler model to partition the skill into rules grounded in
     * the trace. Returns {@link SkillRuleManifest#EMPTY} when derivation
     * fails — caller treats that as "leave the skill un-manifested for now
     * and try again on the next successful turn".
     */
    public SkillRuleManifest derive(String skillName, String skillMarkdown, ExecutionTrace trace) {
        if (trace == null || trace.steps().isEmpty()) {
            log.debug("[SkillManifestDeriver] empty trace for skill={} — cannot derive", skillName);
            return SkillRuleManifest.EMPTY;
        }
        if (skillMarkdown == null) skillMarkdown = "";

        ModelRef compilerRef = new ModelRef(
                props.getCompilerModel().getProviderId(),
                props.getCompilerModel().getModelId());
        ModelProviderRouter.Spec spec = modelProviderRouter.resolve(compilerRef, true);
        ChatClient client = spec.client();

        String system = systemPrompt();
        String user = userPrompt(skillName, skillMarkdown, trace);

        String response;
        try {
            var req = client.prompt().system(system).user(user);
            if (spec.options() != null) req = req.options(spec.options());
            response = req.call().content();
        } catch (Exception ex) {
            log.warn("[SkillManifestDeriver] LLM call failed for skill={}: {}", skillName, ex.getMessage());
            return SkillRuleManifest.EMPTY;
        }

        return parseManifest(response, trace);
    }

    private static String systemPrompt() {
        return """
                You partition a skill into independently-runnable sub-rules. The
                business author writes the skill as prose; you read that prose AND
                the recorded execution trace of one successful run, and identify the
                natural sub-tasks that make sense as separate compiled BPMN rules.

                Each rule should be:
                  - independently meaningful — the user could ask for just this rule.
                  - tool-coherent — owns a clear subset of the tools called in the trace.
                  - addressable — has at least one sample user phrasing that targets
                    only this rule and nothing else.

                Shared tools (used by every rule, e.g. an "ID lookup" tool) appear in
                every rule's tools list — the runtime's turn-tool-cache dedupes the
                actual call.

                Output JSON only — no commentary, no markdown fences. Schema:

                {
                  "rules": [
                    {
                      "name": "kebab-case-identifier",
                      "intent_examples": ["sample user phrasing", "..."],
                      "result_key": "camelCaseOutputKey",
                      "tools": ["ToolName1", "ToolName2"]
                    }
                  ],
                  "domain_intent_examples": [
                    "umbrella phrasing that fans out to all rules"
                  ]
                }

                Rules:
                  - Produce 1–5 rules. Single-purpose skills get one rule.
                  - Never invent tools — only use tool names that appear in the trace.
                  - intent_examples: 2–4 per rule, varied phrasings.
                  - domain_intent_examples: 2–3 phrasings that mean "do all rules".
                  - Use placeholders like {order}, {id} for variable parts of phrasings.
                """;
    }

    private String userPrompt(String skillName, String skillMarkdown, ExecutionTrace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill: ").append(skillName).append("\n\n");
        sb.append("## Skill prose\n");
        sb.append(truncate(skillMarkdown, 6000)).append("\n\n");

        sb.append("## Recorded execution trace (one successful run)\n");
        sb.append("User said: ").append(truncate(trace.userPrompt(), 400)).append("\n\n");
        sb.append("Tool calls in order:\n");
        int n = 1;
        for (ExecutionTrace.TraceStep s : trace.toolSteps()) {
            sb.append(n++).append(". ").append(s.name())
                    .append(" — input keys: ");
            if (s.input() != null && s.input().isObject()) {
                List<String> keys = new ArrayList<>();
                for (String k : s.input().propertyNames()) keys.add(k);
                sb.append(keys);
            } else {
                sb.append("(scalar)");
            }
            sb.append("\n");
        }

        sb.append("\nProduce the manifest JSON now. JSON only, no commentary.\n");
        return sb.toString();
    }

    /**
     * Parse the LLM's JSON output into a {@link SkillRuleManifest}. Validates
     * that every referenced tool name actually appeared in the trace — drops
     * hallucinated tools rather than letting them propagate into compiled
     * BPMNs.
     */
    private SkillRuleManifest parseManifest(String llmOutput, ExecutionTrace trace) {
        if (llmOutput == null || llmOutput.isBlank()) return SkillRuleManifest.EMPTY;
        String cleaned = stripFences(llmOutput).trim();

        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception ex) {
            log.warn("[SkillManifestDeriver] manifest JSON parse failed: {}", ex.getMessage());
            return SkillRuleManifest.EMPTY;
        }

        Set<String> traceTools = new LinkedHashSet<>();
        for (ExecutionTrace.TraceStep s : trace.toolSteps()) {
            if (s.name() != null) traceTools.add(s.name());
        }

        JsonNode rulesNode = root.path("rules");
        if (!rulesNode.isArray() || rulesNode.isEmpty()) return SkillRuleManifest.EMPTY;

        List<SkillRuleManifest.Rule> rules = new ArrayList<>();
        for (JsonNode r : rulesNode) {
            String name = r.path("name").asString("");
            if (name.isBlank()) continue;
            List<String> intentExamples = readStringArray(r.path("intent_examples"));
            String resultKey = r.path("result_key").asString(null);
            List<String> tools = filterToTraceTools(readStringArray(r.path("tools")), traceTools);
            if (tools.isEmpty()) {
                log.debug("[SkillManifestDeriver] dropping rule {} — no recognized tools", name);
                continue;
            }
            rules.add(new SkillRuleManifest.Rule(name, intentExamples, resultKey, tools, null));
        }
        if (rules.isEmpty()) return SkillRuleManifest.EMPTY;

        List<String> domainExamples = readStringArray(root.path("domain_intent_examples"));
        return new SkillRuleManifest(rules, domainExamples);
    }

    private static List<String> readStringArray(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (JsonNode v : n) {
            String s = v.asString("");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static List<String> filterToTraceTools(List<String> candidates, Set<String> traceTools) {
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (traceTools.contains(c)) out.add(c);
        }
        return out;
    }

    private static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }

    /** Serialize a manifest back to the JSON shape this deriver produces. Used
     *  for storage on the skill row + comparison/invalidation. */
    public String toJson(SkillRuleManifest manifest) {
        if (manifest == null || manifest.isEmpty()) return null;
        try {
            tools.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            tools.jackson.databind.node.ArrayNode rules = root.putArray("rules");
            for (SkillRuleManifest.Rule r : manifest.rules()) {
                tools.jackson.databind.node.ObjectNode rn = rules.addObject();
                rn.put("name", r.name());
                rn.put("result_key", r.effectiveResultKey());
                tools.jackson.databind.node.ArrayNode examples = rn.putArray("intent_examples");
                if (r.intentExamples() != null) {
                    for (String ex : r.intentExamples()) examples.add(ex);
                }
                tools.jackson.databind.node.ArrayNode toolsArr = rn.putArray("tools");
                if (r.tools() != null) {
                    for (String t : r.tools()) toolsArr.add(t);
                }
            }
            tools.jackson.databind.node.ArrayNode dExamples = root.putArray("domain_intent_examples");
            if (manifest.domainIntentExamples() != null) {
                for (String ex : manifest.domainIntentExamples()) dExamples.add(ex);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Inverse of {@link #toJson} — used by the registry to load a previously
     *  derived manifest off the skill row. */
    public SkillRuleManifest fromJson(String json) {
        if (json == null || json.isBlank()) return SkillRuleManifest.EMPTY;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode rulesNode = root.path("rules");
            if (!rulesNode.isArray() || rulesNode.isEmpty()) return SkillRuleManifest.EMPTY;

            List<SkillRuleManifest.Rule> rules = new ArrayList<>();
            for (JsonNode r : rulesNode) {
                String name = r.path("name").asString("");
                if (name.isBlank()) continue;
                rules.add(new SkillRuleManifest.Rule(
                        name,
                        readStringArray(r.path("intent_examples")),
                        r.path("result_key").asString(null),
                        readStringArray(r.path("tools")),
                        null));
            }
            return new SkillRuleManifest(rules, readStringArray(root.path("domain_intent_examples")));
        } catch (Exception ex) {
            return SkillRuleManifest.EMPTY;
        }
    }
}
