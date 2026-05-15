package com.pods.agent.service;

import com.pods.agent.domain.Skill;
import com.pods.agent.domain.SkillFile;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.ruledomain.model.SkillRuleManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SkillRegistryService {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistryService.class);

    private final SkillRepository skillRepository;
    private final SkillFileStorageService storageService;
    private final Map<String, SkillSnapshot> enabledSkillCache = new ConcurrentHashMap<>();

    /** Built-in skills loaded from classpath — always available, never shown in UI. */
    private final List<SkillSnapshot> defaultSkills;

    public SkillRegistryService(SkillRepository skillRepository, SkillFileStorageService storageService) {
        this.skillRepository = skillRepository;
        this.storageService = storageService;
        this.defaultSkills = loadDefaultSkills();
        refresh();
    }

    public synchronized void refresh() {
        enabledSkillCache.clear();
        for (Skill skill : skillRepository.findAll()) {
            if (!skill.isEnabled()) continue;
            List<SkillFile> files = skillRepository.findFilesBySkill(skill.getId());
            Map<String, String> contentByPath = new ConcurrentHashMap<>();
            for (SkillFile file : files) {
                contentByPath.put(file.getFilePath(),
                        new String(storageService.get(file.getBlobPath()), StandardCharsets.UTF_8));
            }
            enabledSkillCache.put(skill.getId(), new SkillSnapshot(skill, contentByPath));
        }
    }

    public List<SkillSnapshot> getEnabledSkills() {
        List<SkillSnapshot> all = new ArrayList<>(defaultSkills);
        all.addAll(enabledSkillCache.values());
        return List.copyOf(all);
    }

    public SkillSnapshot getEnabledSkillByName(String name) {
        return getEnabledSkills().stream()
                .filter(s -> s.skill().getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Loads built-in skills from src/main/resources/default-skills/.
     * Supports two layouts:
     *   • Single file:  default-skills/my-skill.md
     *   • Directory:    default-skills/my-skill/SKILL.md  (+ any other files)
     * All loaded skills are always active and never persisted to the database.
     */
    private static List<SkillSnapshot> loadDefaultSkills() {
        List<SkillSnapshot> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

            // ── 1. Single .md files at the root of default-skills/ ────────────
            Resource[] rootFiles = resolver.getResources("classpath:default-skills/*.md");
            for (Resource resource : rootFiles) {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                Map<String, String> meta = parseYamlFrontmatter(content);
                String filename = resource.getFilename() != null ? resource.getFilename() : "unknown";
                String id = filename.replaceAll("\\.md$", "");
                String name = meta.getOrDefault("name", id.replace("-", " "));
                String description = meta.getOrDefault("description", "");
                Skill skill = Skill.builder()
                        .id("system-" + id)
                        .name(name)
                        .description(description)
                        .enabled(true)
                        .build();
                result.add(new SkillSnapshot(skill, Map.of("SKILL.md", content)));
            }

            // ── 2. Directory-based skills: default-skills/{skill-name}/**  ────
            // Pull in EVERY file the skill bundle ships, regardless of
            // extension or directory depth. A skill author should be able to
            // drop scripts (.py, .sh, .sql), schemas (.xsd, .xml), reference
            // catalogs (.csv, .ndjson), config (.toml, .ini, .properties) or
            // any other companion file alongside SKILL.md and have it become
            // visible to the agent via skill_load. Binary or undecodable
            // files are skipped with a warning rather than failing startup,
            // so an accidentally-committed image cannot brick skill loading.
            List<Resource> allDeep = new ArrayList<>(
                    List.of(resolver.getResources("classpath:default-skills/**/*")));
            // Group by the first path segment after "default-skills/"
            Map<String, Map<String, String>> byDir = new java.util.LinkedHashMap<>();
            for (Resource resource : allDeep) {
                if (!resource.isReadable()) continue;
                String uri;
                try {
                    uri = resource.getURI().toString();
                } catch (IOException ioe) {
                    continue;
                }
                // Extract relative path after "default-skills/"
                int marker = uri.indexOf("default-skills/");
                if (marker < 0) continue;
                String rel = uri.substring(marker + "default-skills/".length()); // e.g. "sql-expert/SKILL.md"
                if (rel.isEmpty() || rel.endsWith("/")) continue; // directory entry
                int slash = rel.indexOf('/');
                if (slash < 0) continue; // root-level file already handled above
                String dir = rel.substring(0, slash);          // "sql-expert"
                String filePath = rel.substring(slash + 1);    // "SKILL.md" or "scripts/migrate.py"
                if (filePath.isBlank()) continue; // pure directory entry inside a jar
                String content;
                try {
                    content = resource.getContentAsString(StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException ex) {
                    log.warn("[SkillRegistry] skipping unreadable skill file {}: {}",
                            uri, ex.getMessage());
                    continue;
                }
                byDir.computeIfAbsent(dir, k -> new java.util.LinkedHashMap<>())
                        .put(filePath, content);
            }
            for (Map.Entry<String, Map<String, String>> entry : byDir.entrySet()) {
                String dir = entry.getKey();
                Map<String, String> files = entry.getValue();
                String skillMd = files.getOrDefault("SKILL.md", "");
                Map<String, String> meta = parseYamlFrontmatter(skillMd);
                String name = meta.getOrDefault("name", dir.replace("-", " "));
                String description = meta.getOrDefault("description", "");
                Skill skill = Skill.builder()
                        .id("system-" + dir)
                        .name(name)
                        .description(description)
                        .enabled(true)
                        .build();
                result.add(new SkillSnapshot(skill, files));
            }

        } catch (IOException e) {
            // Non-fatal — default-skills directory may be absent or empty
        }
        List<SkillSnapshot> loaded = List.copyOf(result);
        if (loaded.isEmpty()) {
            log.info("[SkillRegistry] No default skills found in classpath:default-skills/");
        } else {
            log.info("[SkillRegistry] Loaded {} default skill(s): {}", loaded.size(),
                    loaded.stream().map(s -> s.skill().getName() + " (" + s.files().size() + " files)").toList());
        }
        return loaded;
    }

    /** Returns only the built-in classpath skills (for inspection/debug). */
    public List<SkillSnapshot> getDefaultSkills() {
        return defaultSkills;
    }

    private static Map<String, String> parseYamlFrontmatter(String content) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (!content.startsWith("---")) return result;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return result;
        String block = content.substring(4, end);
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 1) continue;
            // Stop at first nested-block marker — flat parser only handles
            // scalars at the top level; nested keys ("rules:", "tools:") are
            // handled separately by parseRuleManifest().
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim().replaceAll("^[\"']|[\"']$", "");
            // Skip lines whose value isn't a simple scalar (no value, or a
            // list marker, or a continuation). These belong to a nested
            // sub-tree the caller handles via parseRuleManifest.
            if (value.isEmpty() || value.equals("|") || value.equals(">")) continue;
            if (!key.isEmpty()) result.put(key, value);
        }
        return result;
    }

    /**
     * Parse the {@code rules:} and {@code domain_intent_examples:} blocks of a
     * skill's YAML frontmatter. Skills without those blocks return
     * {@link SkillRuleManifest#EMPTY} — the orchestrator then falls back to the
     * legacy single-monolithic-BPMN compile.
     *
     * <p>Uses {@code snakeyaml} (already a Spring Boot dependency) rather than
     * the flat line-based parser in {@link #parseYamlFrontmatter} because the
     * structure is nested (lists of objects with nested fields).
     */
    public static SkillRuleManifest parseRuleManifest(String skillMarkdown) {
        if (skillMarkdown == null || !skillMarkdown.startsWith("---")) return SkillRuleManifest.EMPTY;
        int end = skillMarkdown.indexOf("\n---", 3);
        if (end < 0) return SkillRuleManifest.EMPTY;
        String frontmatter = skillMarkdown.substring(3, end).strip();
        if (frontmatter.isEmpty()) return SkillRuleManifest.EMPTY;

        Yaml yaml = new Yaml();
        Object root;
        try {
            root = yaml.load(frontmatter);
        } catch (Exception ex) {
            log.warn("[SkillRegistry] YAML frontmatter parse failed: {}", ex.getMessage());
            return SkillRuleManifest.EMPTY;
        }
        if (!(root instanceof Map<?, ?> map)) return SkillRuleManifest.EMPTY;

        Object rulesNode = map.get("rules");
        if (!(rulesNode instanceof List<?> rulesList) || rulesList.isEmpty()) {
            return SkillRuleManifest.EMPTY;
        }

        List<SkillRuleManifest.Rule> rules = new ArrayList<>();
        for (Object item : rulesList) {
            if (!(item instanceof Map<?, ?> rm)) continue;
            String name = asString(rm.get("name"));
            if (name == null || name.isBlank()) continue;
            List<String> intentExamples = asStringList(rm.get("intent_examples"));
            String resultKey = asString(rm.get("result_key"));
            List<String> tools = asStringList(rm.get("tools"));
            String section = asString(rm.get("skill_section"));
            rules.add(new SkillRuleManifest.Rule(name, intentExamples, resultKey, tools, section));
        }

        List<String> domainIntentExamples = asStringList(map.get("domain_intent_examples"));
        return new SkillRuleManifest(rules, domainIntentExamples);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object v : list) if (v != null) out.add(v.toString());
            return out;
        }
        return List.of(o.toString());
    }

    public record SkillSnapshot(Skill skill, Map<String, String> files) {}
}
