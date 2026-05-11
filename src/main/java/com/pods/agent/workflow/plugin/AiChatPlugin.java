package com.pods.agent.workflow.plugin;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Option;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Props;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Workflow plugin that calls an LLM via the project's existing
 * {@link ModelProviderRouter} — exactly the same code path the chat features
 * use. We do not touch the chat features themselves; this plugin lets a
 * workflow activity invoke the same routed {@link ChatClient}.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code provider} (required) — provider id (e.g. {@code anthropic},
 *       {@code openai}, {@code ollama}, {@code azure_openai}).</li>
 *   <li>{@code model} (required) — model id (e.g. {@code claude-opus-4-7}).</li>
 *   <li>{@code prompt} (required) — user prompt.</li>
 *   <li>{@code system} (optional) — system prompt.</li>
 * </ul>
 *
 * <p>Output: the assistant's reply as a {@link String}.
 */
@Component
@Slf4j
public class AiChatPlugin implements ApplicationPlugin, DescribablePlugin {

    @Override
    public PluginDescriptor describe() {
        return PluginDescriptor.of(
                "AiChatPlugin",
                "AI Chat",
                "Calls an LLM via ModelProviderRouter and returns the assistant text.",
                "sparkles",
                "AI",
                List.of(
                        Props.options("provider", "Provider", true, "anthropic", List.of(
                                Option.of("anthropic", "Anthropic"),
                                Option.of("openai", "OpenAI"),
                                Option.of("azure_openai", "Azure OpenAI"),
                                Option.of("ollama", "Ollama"))),
                        Props.string("model", "Model", true)
                                .withPlaceholder("claude-sonnet-4-6"),
                        Props.string("system", "System prompt", false),
                        Props.string("prompt", "User prompt", true)
                                .withDescription("Supports SecureSpel expressions, e.g. #{'Q: ' + #userQuestion}")
                ));
    }


    private final ModelProviderRouter router;

    public AiChatPlugin(ModelProviderRouter router) {
        this.router = router;
    }

    @Override
    public Object execute(Map<String, Object> props) {
        String provider = require(props, "provider");
        String model = require(props, "model");
        String prompt = require(props, "prompt");
        String system = stringOrNull(props.get("system"));

        ModelProviderRouter.Spec spec = router.resolve(new ModelRef(provider, model));
        if (spec == null || spec.client() == null) {
            throw new IllegalStateException("ModelProviderRouter returned no ChatClient for "
                    + provider + "/" + model);
        }
        ChatClient.ChatClientRequestSpec req = spec.client().prompt();
        if (system != null && !system.isBlank()) {
            req = req.system(system);
        }
        req = req.user(prompt);
        if (spec.options() != null) {
            req = req.options(spec.options());
        }
        String reply = req.call().content();
        log.debug("[AiChatPlugin] provider={} model={} reply.length={}",
                provider, model, reply == null ? 0 : reply.length());
        return reply;
    }

    private static String require(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) {
            throw new IllegalArgumentException("AiChatPlugin requires '" + key + "' property");
        }
        return String.valueOf(v);
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
