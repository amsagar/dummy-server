package com.pods.agent.workflow.api;

import com.pods.agent.service.SkillRegistryService;
import com.pods.agent.service.ToolRegistryService;
import com.pods.agent.workflow.engine.PluginRegistry;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Option;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Indexes all {@link DescribablePlugin} beans and serves the n8n-style node
 * library and form metadata to the frontend. Also handles "test single step"
 * preview executions and the dynamic options loaders ({@code agent-tools},
 * {@code skills}, {@code mcp-servers}) referenced by plugin descriptors.
 */
@Service
@Slf4j
public class PluginCatalogService {

    private final List<DescribablePlugin> describablePlugins;
    private final PluginRegistry pluginRegistry;
    private final ToolRegistryService toolRegistry;
    private final SkillRegistryService skillRegistry;

    private final Map<String, PluginDescriptor> byName = new LinkedHashMap<>();

    public PluginCatalogService(List<DescribablePlugin> describablePlugins,
                                PluginRegistry pluginRegistry,
                                ToolRegistryService toolRegistry,
                                SkillRegistryService skillRegistry) {
        this.describablePlugins = describablePlugins == null ? List.of() : describablePlugins;
        this.pluginRegistry = pluginRegistry;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
    }

    @PostConstruct
    void index() {
        for (DescribablePlugin p : describablePlugins) {
            PluginDescriptor d = p.describe();
            if (d == null || d.name() == null || d.name().isBlank()) {
                log.warn("[PluginCatalog] {} returned null/blank descriptor; skipping",
                        p.getClass().getSimpleName());
                continue;
            }
            if (byName.put(d.name(), d) != null) {
                log.warn("[PluginCatalog] duplicate plugin name: {}", d.name());
            }
        }
        log.info("[PluginCatalog] indexed {} plugin descriptor(s): {}",
                byName.size(), byName.keySet());
    }

    public List<PluginDescriptor> list() {
        return List.copyOf(byName.values());
    }

    public Optional<PluginDescriptor> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Executes a plugin once with the supplied properties and returns the
     * output (or the error). Used by the "Test step" button in the inspector.
     *
     * <p>Note: this does NOT evaluate {@code #{...}} expressions — the engine
     * does that at runtime. Pass already-resolved literal values, or include
     * the expression's expected value directly.
     */
    public PreviewResult preview(String pluginName, Map<String, Object> properties) {
        long t0 = System.nanoTime();
        ApplicationPlugin plugin = pluginRegistry.applicationPlugin(pluginName).orElse(null);
        if (plugin == null) {
            return PreviewResult.failure("unknown plugin: " + pluginName, durationMs(t0));
        }
        try {
            Object output = plugin.execute(properties == null ? Map.of() : properties);
            return PreviewResult.success(output, durationMs(t0));
        } catch (Exception e) {
            return PreviewResult.failure(
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    durationMs(t0));
        }
    }

    /**
     * Resolves the dynamic-options loader id referenced by a plugin descriptor
     * (e.g. {@code optionsLoader: "agent-tools"} → list every enabled tool).
     */
    public List<Option> loadOptions(String loaderId) {
        if (loaderId == null) return List.of();
        return switch (loaderId) {
            case "agent-tools" -> toolRegistry.getEnabledTools().stream()
                    .map(t -> Option.of(t.getName(),
                            t.getName() + (t.getDescription() == null ? "" : " — " + truncate(t.getDescription()))))
                    .toList();
            case "skills" -> skillRegistry.getEnabledSkills().stream()
                    .map(s -> {
                        String name = s.skill().getName();
                        String desc = s.skill().getDescription();
                        return Option.of(name, name + (desc == null || desc.isBlank() ? "" : " — " + truncate(desc)));
                    })
                    .toList();
            case "mcp-servers" -> {
                // MCP server discovery is wired through McpRuntimeAdapter; we
                // intentionally don't import it here to keep the catalog
                // service free of MCP coupling. Frontend can fall back to
                // free-text input for serverId until we expose this.
                yield List.of();
            }
            default -> {
                log.warn("[PluginCatalog] unknown options loader: {}", loaderId);
                yield List.of();
            }
        };
    }

    private static long durationMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 77) + "…" : oneLine;
    }

    public record PreviewResult(boolean success, Object output, String error, long durationMs) {
        public static PreviewResult success(Object output, long durationMs) {
            return new PreviewResult(true, output, null, durationMs);
        }
        public static PreviewResult failure(String error, long durationMs) {
            return new PreviewResult(false, null, error, durationMs);
        }
    }
}
