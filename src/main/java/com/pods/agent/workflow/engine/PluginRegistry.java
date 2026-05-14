package com.pods.agent.workflow.engine;

import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.joget.plugin.DecisionPlugin;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Looks up workflow plugins by name. Spring discovers all
 * {@link ApplicationPlugin} and {@link DecisionPlugin} beans at startup and
 * we index them by their bean name so process definitions can reference them
 * via {@link com.pods.agent.workflow.engine.domain.ActivityDef#pluginName()}.
 *
 * <p>This replaces Joget's OSGi-based PluginManager with a Spring-native
 * lookup. Adapters in {@code com.pods.agent.workflow.plugin.*} register
 * automatically via {@code @Component}.
 */
@Component
@Slf4j
public class PluginRegistry {

    private final Map<String, ApplicationPlugin> appPluginsByName = new HashMap<>();
    private final Map<String, DecisionPlugin> decisionPluginsByName = new HashMap<>();
    private final List<ApplicationPlugin> appPluginBeans;
    private final List<DecisionPlugin> decisionPluginBeans;

    public PluginRegistry(List<ApplicationPlugin> appPluginBeans,
                          List<DecisionPlugin> decisionPluginBeans) {
        this.appPluginBeans = appPluginBeans == null ? List.of() : appPluginBeans;
        this.decisionPluginBeans = decisionPluginBeans == null ? List.of() : decisionPluginBeans;
    }

    @PostConstruct
    void index() {
        for (ApplicationPlugin p : appPluginBeans) {
            String name = p.getClass().getSimpleName();
            if (appPluginsByName.put(name, p) != null) {
                log.warn("[PluginRegistry] Duplicate ApplicationPlugin name: {}", name);
            }
        }
        for (DecisionPlugin p : decisionPluginBeans) {
            String name = p.getClass().getSimpleName();
            if (decisionPluginsByName.put(name, p) != null) {
                log.warn("[PluginRegistry] Duplicate DecisionPlugin name: {}", name);
            }
        }
        log.info("[PluginRegistry] indexed {} ApplicationPlugin(s), {} DecisionPlugin(s)",
                appPluginsByName.size(), decisionPluginsByName.size());
    }

    public Optional<ApplicationPlugin> applicationPlugin(String name) {
        return Optional.ofNullable(appPluginsByName.get(name));
    }

    public Optional<DecisionPlugin> decisionPlugin(String name) {
        return Optional.ofNullable(decisionPluginsByName.get(name));
    }

    /**
     * Names of every registered {@link ApplicationPlugin}. Exposed for the
     * workflow-architect contract reconciler so it can verify the
     * machine-readable contract under
     * {@code default-skills/workflow-architect/doc/plugins.json} matches the
     * actually-registered plugin set at boot.
     */
    public java.util.Set<String> applicationPluginNames() {
        return java.util.Set.copyOf(appPluginsByName.keySet());
    }
}
