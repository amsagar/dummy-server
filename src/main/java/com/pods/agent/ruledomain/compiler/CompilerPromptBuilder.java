package com.pods.agent.ruledomain.compiler;

import com.pods.agent.domain.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the compiler prompt — system instructions + few-shot BPMN
 * examples + skill spec + tool catalog + user request.
 *
 * The system prompt and few-shot examples are loaded once at startup so we
 * don't re-read them per compile call.
 */
@Component
@Slf4j
public class CompilerPromptBuilder {

    private final String systemPrompt;
    private final String fewShotExamples;

    public CompilerPromptBuilder() throws IOException {
        this.systemPrompt = loadResource("prompts/bpmn-compiler-system.md");
        this.fewShotExamples = loadFewShots();
    }

    public String buildSystem() {
        return systemPrompt + "\n\n## Examples\n\n" + fewShotExamples;
    }

    public String buildUser(String skillMarkdown,
                            List<AgentTool> tools,
                            String userRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill specification\n\n");
        sb.append(skillMarkdown == null ? "(no skill markdown)" : skillMarkdown);
        sb.append("\n\n## Tool catalog\n\n");
        if (tools == null || tools.isEmpty()) {
            sb.append("(no tools registered)\n");
        } else {
            for (AgentTool t : tools) {
                sb.append("### ").append(t.getName()).append("\n");
                if (t.getDescription() != null) {
                    sb.append(t.getDescription()).append("\n\n");
                }
                if (t.getRequestSchema() != null && !t.getRequestSchema().isBlank()) {
                    sb.append("**Request schema:**\n```\n")
                            .append(truncate(t.getRequestSchema(), 1200))
                            .append("\n```\n\n");
                }
                if (t.getSampleRequest() != null && !t.getSampleRequest().isBlank()) {
                    sb.append("**Sample request:**\n```json\n")
                            .append(truncate(t.getSampleRequest(), 800))
                            .append("\n```\n\n");
                }
                if (t.getSampleResponse() != null && !t.getSampleResponse().isBlank()) {
                    sb.append("**Sample response:**\n```json\n")
                            .append(truncate(t.getSampleResponse(), 1200))
                            .append("\n```\n\n");
                }
            }
        }
        // Templatize the user request: every long numeric run (likely a domain
        // id like an order id) becomes a placeholder so the LLM can't bake it
        // into the BPMN as a literal. The intent is preserved but the specific
        // values are clearly marked as runtime parameters.
        String templatized = templatizeRequest(userRequest);
        sb.append("\n## User request (templatized — specific ids replaced with `<param>` placeholders)\n\n")
                .append(templatized)
                .append("\n\n## Output\n\nProduce the BPMN XML now. XML only. ")
                .append("Remember: the BPMN must work for every future request that matches this intent, ")
                .append("not just the one shown above. Reference process variables (`orderId`, `userMessage`, etc.), ")
                .append("not the placeholder strings.");
        return sb.toString();
    }

    /** Replace 4+ digit numeric runs with `<orderId>` placeholders so the LLM
     *  can't copy them as literals. Other proper-noun-looking tokens are
     *  preserved since they may be part of the intent itself. */
    static String templatizeRequest(String userRequest) {
        if (userRequest == null) return "";
        return userRequest.replaceAll("\\b\\d{4,}\\b", "<orderId>");
    }

    /** Build a repair-pass user prompt when the first compile attempt was rejected. */
    public String buildRepair(String previousXml, String validationError) {
        return ("""
                Your previous attempt produced invalid BPMN. The Flowable parser rejected it
                with this diagnostic:

                ```
                """) + validationError + "\n```\n\n"
                + "Here is what you produced:\n\n```xml\n" + previousXml + "\n```\n\n"
                + "Produce a corrected BPMN now. XML only — no commentary.\n";
    }

    private static String loadResource(String classpath) throws IOException {
        try (var in = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + classpath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String loadFewShots() throws IOException {
        Resource[] examples = new PathMatchingResourcePatternResolver()
                .getResources("classpath:prompts/bpmn-compiler-examples/*.bpmn20.xml");
        Arrays.sort(examples, Comparator.comparing(Resource::getFilename));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < examples.length; i++) {
            sb.append("### Example ").append(i + 1).append(": ")
                    .append(stripExtension(examples[i].getFilename())).append("\n\n");
            sb.append("```xml\n");
            sb.append(examples[i].getContentAsString(StandardCharsets.UTF_8));
            sb.append("\n```\n\n");
        }
        return sb.toString();
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }
}
