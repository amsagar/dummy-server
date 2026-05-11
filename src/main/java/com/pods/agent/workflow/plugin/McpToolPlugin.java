package com.pods.agent.workflow.plugin;

import com.pods.agent.service.mcp.McpRuntimeAdapter;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Props;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Workflow plugin that invokes an MCP server tool.
 *
 * <p>Bridges to the existing {@link McpRuntimeAdapter} (untouched). Used by
 * activities of {@code TYPE_TOOL} where {@code pluginName="McpToolPlugin"}.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code serverId} (required) — MCP server identifier.</li>
 *   <li>{@code toolName} (required) — name of the tool exposed by that
 *       server.</li>
 *   <li>{@code payload} (optional) — JSON string passed verbatim to the tool;
 *       if a Map is supplied here we serialize via the engine's resolver.</li>
 * </ul>
 *
 * <p>Output: the raw string returned by {@link McpRuntimeAdapter#callTool}.
 */
@Component
@Slf4j
public class McpToolPlugin implements ApplicationPlugin, DescribablePlugin {

    @Override
    public PluginDescriptor describe() {
        return PluginDescriptor.of(
                "McpToolPlugin",
                "MCP Tool",
                "Calls a tool exposed by a registered MCP server.",
                "plug",
                "Tool",
                List.of(
                        Props.optionsDynamic("serverId", "MCP server", true, "mcp-servers"),
                        Props.string("toolName", "Tool name", true)
                                .withDescription("Tool exposed by the selected MCP server."),
                        Props.json("payload", "Payload", false)
                                .withDescription("JSON string passed verbatim to the tool. Defaults to {}.")
                                .withDefault("{}")
                ));
    }


    private final McpRuntimeAdapter mcp;

    public McpToolPlugin(McpRuntimeAdapter mcp) {
        this.mcp = mcp;
    }

    @Override
    public Object execute(Map<String, Object> props) {
        String serverId = stringRequired(props, "serverId");
        String toolName = stringRequired(props, "toolName");
        Object payloadRaw = props.get("payload");
        String payload = payloadRaw == null ? "{}" : String.valueOf(payloadRaw);
        log.debug("[McpToolPlugin] calling server={} tool={} payload={}",
                serverId, toolName, payload);
        return mcp.callTool(serverId, toolName, payload);
    }

    private static String stringRequired(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) {
            throw new IllegalArgumentException("McpToolPlugin requires '" + key + "' property");
        }
        return String.valueOf(v);
    }
}
