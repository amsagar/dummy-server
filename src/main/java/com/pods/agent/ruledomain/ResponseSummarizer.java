package com.pods.agent.ruledomain;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.config.RuleDomainProperties;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.ruledomain.model.ExecutionOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * One-shot LLM call that turns a compiled-domain {@link ExecutionOutcome}
 * into a natural-language response for the user. This is the *only* LLM
 * call on the hot path of a cache-hit request.
 */
@Component
@Slf4j
public class ResponseSummarizer {

    private final ModelProviderRouter modelProviderRouter;
    private final RuleDomainProperties props;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public ResponseSummarizer(ModelProviderRouter modelProviderRouter,
                              RuleDomainProperties props,
                              ObjectMapper objectMapper) throws IOException {
        this.modelProviderRouter = modelProviderRouter;
        this.props = props;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadResource("prompts/bpmn-summarizer-system.md");
    }

    public String summarize(ExecutionOutcome outcome, String userMessage) {
        ModelRef ref = new ModelRef(
                props.getSummarizerModel().getProviderId(),
                props.getSummarizerModel().getModelId());
        ModelProviderRouter.Spec spec = modelProviderRouter.resolve(ref, true);
        ChatClient client = spec.client();

        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(outcome.outputs());
        } catch (Exception ex) {
            resultJson = "{\"<serialization-failed>\":\"" + ex.getMessage() + "\"}";
        }

        String user = "User's original message:\n" + userMessage + "\n\n" +
                "Structured workflow output:\n```json\n" + resultJson + "\n```\n\n" +
                "Write the user-facing response.";
        if (outcome.error() != null) {
            user = user + "\n\nNote: the workflow recorded an error: " + outcome.error();
        }

        try {
            var req = client.prompt().system(systemPrompt).user(user);
            if (spec.options() != null) req = req.options(spec.options());
            return req.call().content();
        } catch (Exception ex) {
            log.warn("Summarizer LLM call failed, returning raw JSON: {}", ex.getMessage());
            return "Workflow result:\n" + resultJson;
        }
    }

    private static String loadResource(String classpath) throws IOException {
        try (var in = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + classpath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
