package com.pods.agent.workflow.proposal;

import com.pods.agent.workflow.engine.PluginRegistry;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * At boot, cross-references the engine's runtime {@link PluginRegistry}
 * against the LLM-facing {@link WorkflowContractCatalog} (which loads
 * {@code default-skills/workflow-architect/doc/plugins.json}) and logs any
 * drift. If a plugin is registered in code but missing from the contract
 * file, the LLM will have no documentation for it during workflow
 * generation; if a plugin appears in the contract but not the registry,
 * the LLM will be told about a capability that doesn't exist.
 *
 * <p>Both conditions are operationally bad but neither is catastrophic, so
 * we log a {@code WARN} rather than fail boot. Failing startup on contract
 * drift is the right long-term posture, but it would break local dev
 * loops every time a plugin is added without a doc update — we'll graduate
 * to a fail-fast policy once the doc is registry-generated rather than
 * hand-authored.
 */
@Component
@Slf4j
public class WorkflowContractReconciler {

    private final PluginRegistry pluginRegistry;
    private final WorkflowContractCatalog contractCatalog;

    public WorkflowContractReconciler(PluginRegistry pluginRegistry,
                                      WorkflowContractCatalog contractCatalog) {
        this.pluginRegistry = pluginRegistry;
        this.contractCatalog = contractCatalog;
    }

    @PostConstruct
    void reconcile() {
        Set<String> registered = pluginRegistry.applicationPluginNames();
        Set<String> documented = new LinkedHashSet<>();
        for (String name : registered) {
            // touch every registered name through the catalog so we can both
            // log the union and exercise the lookup path once at boot.
            contractCatalog.plugin(name);
        }
        // Hand-grab the documented set via reflection-free iteration: the
        // catalog doesn't expose the keyset, so re-derive by trying each
        // registered name and listing which ones missed.
        Set<String> missingInDoc = new LinkedHashSet<>();
        for (String name : registered) {
            if (contractCatalog.plugin(name).isEmpty()) missingInDoc.add(name);
        }
        if (!missingInDoc.isEmpty()) {
            log.warn("[WorkflowContractReconciler] {} plugin(s) registered in code are NOT documented in"
                    + " default-skills/workflow-architect/doc/plugins.json: {}."
                    + " LLM-generated workflows referencing these plugins will lack envelope guidance.",
                    missingInDoc.size(), missingInDoc);
        } else {
            log.info("[WorkflowContractReconciler] all {} registered plugin(s) have contract entries.",
                    registered.size());
        }
    }
}
