package com.pods.agent.ruledomain.compiler.agentic;

import com.pods.agent.ruledomain.compiler.trace.ExecutionTrace;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes the per-rule compile context into a fresh directory under the
 * session workspace so the compile LLM can inspect it via filesystem
 * tools rather than receive a single giant prompt. Layout:
 *
 * <pre>
 * &lt;sessionWorkspace&gt;/.pods-agent/compile/&lt;turnId&gt;/&lt;ruleName&gt;/
 *   index.md             — what's here, what to do next
 *   instructions.md      — the canonical compile contract (delegate API, FEEL rules, etc.)
 *   skill.md             — the full skill markdown
 *   manifest.json        — this rule's manifest entry (intent, result_key, allowed tools)
 *   trace/
 *     01-Get_OrderID.input.json
 *     01-Get_OrderID.output.json
 *     02-ContainerAvailability.input.json
 *     02-ContainerAvailability.output.json
 *     ...
 * </pre>
 *
 * <p>The directory persists for the duration of the compile and can be
 * inspected after the fact for debugging. {@link #cleanup} removes it.
 */
@Component
@Slf4j
public class TraceCompileWorkspace {

    private final ObjectMapper pretty;

    public TraceCompileWorkspace() {
        // Spring Boot's auto-configured Jackson 3 ObjectMapper isn't quite
        // what we want here (we want pretty-printed output for the LLM to
        // read). Build a dedicated mapper.
        this.pretty = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    public Path materialize(Path sessionWorkspace,
                            String turnId,
                            SkillRuleManifest.Rule rule,
                            String skillName,
                            String skillMarkdown,
                            ExecutionTrace slice,
                            String instructions) throws IOException {
        Path root = sessionWorkspace
                .resolve(".pods-agent")
                .resolve("compile")
                .resolve(turnId)
                .resolve(safeName(rule.name()));
        Files.createDirectories(root.resolve("trace"));

        // 1. Skill markdown — full, no truncation.
        write(root.resolve("skill.md"), skillMarkdown == null ? "" : skillMarkdown);

        // 2. Compile contract / instructions.
        write(root.resolve("instructions.md"), instructions == null ? "" : instructions);

        // 3. Manifest entry (just this rule).
        write(root.resolve("manifest.json"), buildManifest(rule, skillName));

        // 4. Per-step trace files — full payloads, untruncated.
        int i = 1;
        for (ExecutionTrace.TraceStep step : slice.toolSteps()) {
            String base = String.format(Locale.ROOT, "%02d-%s", i++, safeName(step.name()));
            write(root.resolve("trace").resolve(base + ".input.json"),
                    renderJson(step.input()));
            write(root.resolve("trace").resolve(base + ".output.json"),
                    renderJson(step.output()));
        }

        // 5. Index — overview pointing at the other files.
        write(root.resolve("index.md"), buildIndex(rule, skillName, slice));

        log.debug("[TraceCompileWorkspace] materialized rule={} at {}", rule.name(), root);
        return root;
    }

    public void cleanup(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ex) {
            log.debug("[TraceCompileWorkspace] cleanup failed for {}: {}", root, ex.getMessage());
        }
    }

    private String buildIndex(SkillRuleManifest.Rule rule, String skillName, ExecutionTrace slice) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Compile workspace · ").append(skillName).append(" · ").append(rule.name()).append("\n\n");
        sb.append("You are producing a BPMN 2.0 process for one rule of a skill. Files here:\n\n");
        sb.append("- `instructions.md` — the canonical compile contract (delegate field names, FEEL syntax, error handling). **READ THIS FIRST.**\n");
        sb.append("- `skill.md` — the full skill specification. Authoritative for which fields exist, which ItemCodes are legs, how ItemCode maps to ServiceCode, etc.\n");
        sb.append("- `manifest.json` — this rule's manifest entry (intent, result_key, allowed tools).\n");
        sb.append("- `trace/` — the recorded LLM-loop run for this rule. Each step has `.input.json` and `.output.json` — full payloads, no truncation.\n\n");
        sb.append("Trace steps (").append(slice.toolSteps().size()).append(" tool call")
                .append(slice.toolSteps().size() == 1 ? "" : "s").append("):\n");
        int i = 1;
        for (ExecutionTrace.TraceStep s : slice.toolSteps()) {
            sb.append("  ").append(String.format(Locale.ROOT, "%02d", i++)).append(". `")
                    .append(s.name()).append("` (").append(s.elapsedMs()).append("ms)\n");
        }
        sb.append("\n## Suggested reading order\n\n");
        sb.append("1. `instructions.md` — the rules. Especially the trace-literal discipline section.\n");
        sb.append("2. `skill.md` — the contract for this skill.\n");
        sb.append("3. `trace/01-*.output.json` — the order shape (what fields exist).\n");
        sb.append("4. Subsequent `trace/NN-*.output.json` — what each tool returned.\n");
        sb.append("5. Each `trace/NN-*.input.json` — what was sent to the tool. Map every value in each input back to a FEEL path into a prior output.\n\n");
        sb.append("## When you're ready\n\n");
        sb.append("Call `compile_write_bpmn` with the complete BPMN XML as a single argument. ");
        sb.append("If the validator rejects it, you'll get a precise error back; revise and resubmit. ");
        sb.append("Maximum 5 attempts.\n");
        return sb.toString();
    }

    private String buildManifest(SkillRuleManifest.Rule rule, String skillName) {
        ObjectNode root = pretty.createObjectNode();
        root.put("skill", skillName);
        root.put("rule", rule.name());
        if (rule.intentExamples() != null) {
            var ex = root.putArray("intent_examples");
            for (String s : rule.intentExamples()) ex.add(s);
        }
        root.put("result_key", rule.effectiveResultKey());
        if (rule.tools() != null) {
            var t = root.putArray("tools");
            for (String s : rule.tools()) t.add(s);
        }
        try { return pretty.writeValueAsString(root); }
        catch (Exception ex) { return "{}"; }
    }

    private String renderJson(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        try { return pretty.writeValueAsString(node); }
        catch (Exception ex) { return node.toString(); }
    }

    private static void write(Path target, String content) throws IOException {
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private static String safeName(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
