package com.pods.agent.workflow.plugin;

import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Props;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Workflow plugin that loads a skill by name and returns its content.
 *
 * <p>Bridges to the existing {@link SkillRegistryService} (untouched). Used by
 * activities of {@code TYPE_TOOL} where {@code pluginName="SkillToolPlugin"}.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code name} (required) — exact skill name from the registry.</li>
 * </ul>
 *
 * <p>Output: a map with {@code name}, {@code description}, and {@code files}
 * keys, plus {@code skillContent} as the rendered SKILL.md text. The activity
 * graph can map any of these into process variables for downstream steps.
 */
@Component
@Slf4j
public class SkillToolPlugin implements ApplicationPlugin, DescribablePlugin {

    @Override
    public PluginDescriptor describe() {
        return PluginDescriptor.of(
                "SkillToolPlugin",
                "Skill",
                "Loads a skill bundle by name and exposes its content for downstream activities.",
                "book-open",
                "Tool",
                List.of(
                        Props.optionsDynamic("name", "Skill name", true, "skills")
                                .withDescription("Exact skill name from the registry (case-insensitive).")
                ));
    }


    private final SkillRegistryService skillRegistry;

    public SkillToolPlugin(SkillRegistryService skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public Object execute(Map<String, Object> props) {
        Object nameObj = props.get("name");
        if (nameObj == null) {
            throw new IllegalArgumentException("SkillToolPlugin requires 'name' property");
        }
        String name = String.valueOf(nameObj).trim();
        SkillRegistryService.SkillSnapshot snapshot = skillRegistry.getEnabledSkillByName(name);
        if (snapshot == null || snapshot.skill() == null) {
            throw new IllegalStateException("skill not found: " + name);
        }
        Map<String, String> files = snapshot.files() == null ? Map.of() : snapshot.files();
        String skillMd = files.entrySet().stream()
                .filter(e -> "SKILL.md".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", snapshot.skill().getName());
        result.put("description", snapshot.skill().getDescription());
        result.put("skillContent", skillMd);
        result.put("files", files.keySet());
        return result;
    }
}
