package com.pods.agent.workflow.plugin.descriptor;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Top-level plugin metadata returned by {@code GET /api/v1/workflow/plugins}.
 * The frontend uses {@link #category} to group items in the node library and
 * {@link #icon} (a Lucide icon name) to render the node tile.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginDescriptor(
        String name,
        String label,
        String description,
        String icon,
        String category,
        List<PluginPropertyDescriptor> properties
) {
    public static PluginDescriptor of(String name,
                                      String label,
                                      String description,
                                      String icon,
                                      String category,
                                      List<PluginPropertyDescriptor> properties) {
        return new PluginDescriptor(name, label, description, icon, category, properties);
    }
}
