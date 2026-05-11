/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-plugin-base/src/main/java/org/joget/plugin/base/ApplicationPlugin.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. The contract is preserved: a plugin executes
 * with a property map and may return a value placed into the workflow context.
 *
 * The Joget original uses raw Map; we tighten to Map<String, Object>.
 */
package com.pods.agent.workflow.joget.plugin;

import java.util.Map;

/**
 * Interface for a process tool plugin. Implementations are invoked by the
 * workflow engine when an activity of TYPE_TOOL is reached. The agent's
 * adapters in com.pods.agent.workflow.plugin.* implement this to bridge to
 * existing layers (SkillToolCallback, McpRuntimeAdapter, ChatClient).
 */
public interface ApplicationPlugin {

    /**
     * Execute the plugin against the given properties.
     *
     * Convention: when the engine invokes this, "workflowAssignment" is set on
     * the property map to a {@link com.pods.agent.workflow.joget.model.WorkflowAssignment}
     * describing the current activity.
     *
     * @param props plugin configuration plus engine-supplied context
     * @return the plugin output; the engine stores this under the configured
     *         output variable, or ignores it if none is configured.
     */
    Object execute(Map<String, Object> props);
}
