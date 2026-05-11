package com.pods.agent.workflow.plugin.descriptor;

/**
 * Capability-style interface declared separately from
 * {@code com.pods.agent.workflow.joget.plugin.ApplicationPlugin} (which is
 * vendored from Joget Community Edition with a preserved contract).
 *
 * <p>Plugin classes implement this in addition to {@code ApplicationPlugin}
 * to expose form-generation metadata to the workflow UI. Plugins that don't
 * implement this are still functional but won't appear in the node library
 * with form support.
 */
public interface DescribablePlugin {
    PluginDescriptor describe();
}
