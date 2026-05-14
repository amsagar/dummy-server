package com.pods.agent.workflow.proposal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory cache of the engine contracts shipped under
 * {@code default-skills/workflow-architect/doc/}:
 * <ul>
 *   <li>{@code plugins.json} — per-plugin envelope metadata. Tells the
 *       validator (and downstream tooling) whether a plugin wraps its return
 *       in an {@code {output, success, stdout, stderr}} envelope
 *       ({@code envelopePath="output"}) or returns the body directly
 *       ({@code envelopePath="raw"}).</li>
 *   <li>{@code tools.json} — per-agent-tool output schema. Lets the validator
 *       reject SpEL expressions that access non-existent top-level fields on
 *       a known tool's response (e.g. {@code decisionTableEvaluate} fields
 *       live under {@code .outputs} not {@code .output}).</li>
 * </ul>
 *
 * <p>Both files are LLM-readable and validator-readable so the contract the
 * model is taught and the contract the engine enforces cannot drift.
 * Tools/plugins not listed are treated as "shape unknown" and skipped from
 * envelope checks rather than rejected — defensive default so new plugins
 * don't immediately fail validation before their contract is documented.
 */
@Component
@Slf4j
public class WorkflowContractCatalog {

    private static final String PLUGINS_PATH = "default-skills/workflow-architect/doc/plugins.json";
    private static final String TOOLS_PATH = "default-skills/workflow-architect/doc/tools.json";
    private static final String SPEL_RULES_PATH = "default-skills/workflow-architect/doc/spel-rules.json";
    private static final String VALIDATOR_CODES_PATH = "default-skills/workflow-architect/doc/validator-codes.json";

    private final Map<String, PluginContract> pluginsByName;
    private final Map<String, ToolContract> toolsByName;
    private final List<SpelRule> spelRules;
    private final Map<String, String> fixRecipesByCode;

    public WorkflowContractCatalog(ObjectMapper objectMapper) {
        this.pluginsByName = loadPlugins(objectMapper);
        this.toolsByName = loadTools(objectMapper);
        this.spelRules = loadSpelRules(objectMapper);
        this.fixRecipesByCode = loadFixRecipes(objectMapper);
        log.info("[WorkflowContractCatalog] loaded {} plugin(s), {} tool(s), {} SpEL rule(s), {} fix recipe(s)",
                pluginsByName.size(), toolsByName.size(), spelRules.size(), fixRecipesByCode.size());
    }

    public Optional<PluginContract> plugin(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(pluginsByName.get(name.trim()));
    }

    public Optional<ToolContract> tool(String toolName) {
        if (toolName == null || toolName.isBlank()) return Optional.empty();
        return Optional.ofNullable(toolsByName.get(toolName.trim()));
    }

    public List<SpelRule> spelRules() {
        return spelRules;
    }

    public Optional<String> fixRecipe(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return Optional.ofNullable(fixRecipesByCode.get(code.trim()));
    }

    private static Map<String, PluginContract> loadPlugins(ObjectMapper om) {
        Map<String, PluginContract> out = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(PLUGINS_PATH).getInputStream()) {
            JsonNode root = om.readTree(in);
            JsonNode arr = root.get("plugins");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String name = textOrNull(n.get("name"));
                if (name == null) continue;
                String envelopePath = textOrNull(n.get("envelopePath"));
                boolean rawReturn = n.get("rawReturn") != null && n.get("rawReturn").asBoolean(false);
                out.put(name, new PluginContract(name, envelopePath, rawReturn));
            }
        } catch (IOException ex) {
            log.warn("[WorkflowContractCatalog] could not read {} — envelope check disabled: {}",
                    PLUGINS_PATH, ex.getMessage());
        }
        return out;
    }

    private static Map<String, ToolContract> loadTools(ObjectMapper om) {
        Map<String, ToolContract> out = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(TOOLS_PATH).getInputStream()) {
            JsonNode root = om.readTree(in);
            JsonNode arr = root.get("tools");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String name = textOrNull(n.get("name"));
                if (name == null) continue;
                JsonNode shape = n.get("outputShape");
                Set<String> topKeys = new LinkedHashSet<>();
                if (shape != null && shape.isObject()) {
                    shape.properties().forEach(e -> topKeys.add(e.getKey()));
                }
                JsonNode accessor = n.get("outputAccessor");
                List<String> accessorHints = new java.util.ArrayList<>();
                if (accessor != null && accessor.isObject()) {
                    accessor.properties().forEach(e -> accessorHints.add(e.getKey() + " -> " + e.getValue().asText()));
                }
                out.put(name, new ToolContract(name, topKeys, accessorHints));
            }
        } catch (IOException ex) {
            log.warn("[WorkflowContractCatalog] could not read {} — tool-shape check disabled: {}",
                    TOOLS_PATH, ex.getMessage());
        }
        return out;
    }

    private static List<SpelRule> loadSpelRules(ObjectMapper om) {
        List<SpelRule> out = new java.util.ArrayList<>();
        try (InputStream in = new ClassPathResource(SPEL_RULES_PATH).getInputStream()) {
            JsonNode root = om.readTree(in);
            JsonNode arr = root.get("rules");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String code = textOrNull(n.get("code"));
                String pattern = textOrNull(n.get("pattern"));
                String description = textOrNull(n.get("description"));
                String replacement = textOrNull(n.get("replacement"));
                if (code == null || pattern == null) continue;
                try {
                    java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
                    out.add(new SpelRule(code, compiled, description, replacement));
                } catch (java.util.regex.PatternSyntaxException ex) {
                    log.warn("[WorkflowContractCatalog] bad regex in spel-rules.json '{}': {}", code, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("[WorkflowContractCatalog] could not read {} — SpEL token check disabled: {}",
                    SPEL_RULES_PATH, ex.getMessage());
        }
        return out;
    }

    private static Map<String, String> loadFixRecipes(ObjectMapper om) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(VALIDATOR_CODES_PATH).getInputStream()) {
            JsonNode root = om.readTree(in);
            JsonNode arr = root.get("codes");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String code = textOrNull(n.get("code"));
                String recipe = textOrNull(n.get("fixRecipe"));
                if (code != null && recipe != null) out.put(code, recipe);
            }
        } catch (IOException ex) {
            log.warn("[WorkflowContractCatalog] could not read {} — fix recipes disabled: {}",
                    VALIDATOR_CODES_PATH, ex.getMessage());
        }
        return out;
    }

    private static String textOrNull(JsonNode n) {
        return (n == null || n.isNull() || !n.isTextual()) ? null : n.asText();
    }

    /**
     * One forbidden-SpEL pattern read from {@code doc/spel-rules.json}.
     * {@code pattern} is the pre-compiled regex; {@code replacement} carries
     * the human-readable corrective hint surfaced to the LLM.
     */
    public record SpelRule(String code, java.util.regex.Pattern pattern,
                           String description, String replacement) {}

    /**
     * Envelope rule for a plugin.
     *
     * @param envelopePath {@code "output"} when the plugin wraps its return
     *                     in an {@code {success, output, stdout, stderr}}
     *                     envelope (CodeExecPlugin); {@code "raw"} otherwise.
     */
    public record PluginContract(String name, String envelopePath, boolean rawReturn) {
        public boolean wrapsInEnvelope() {
            return "output".equalsIgnoreCase(envelopePath);
        }
    }

    /**
     * Output-shape rule for a registered agent tool. {@code outputTopLevelKeys}
     * is the set of keys an expression may legally read off this tool's
     * response. Empty set means "shape unknown — skip the check".
     */
    public record ToolContract(String name, Set<String> outputTopLevelKeys, List<String> accessorHints) {}
}
