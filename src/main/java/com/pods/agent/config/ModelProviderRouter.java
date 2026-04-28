package com.pods.agent.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.repository.ModelRepository;
import com.pods.agent.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Routes a ModelRef (providerID + modelID) to the correct ChatClient + ChatOptions.
 *
 * Spring AI auto-configuration is disabled. All clients are built on-demand
 * using the API key stored encrypted in agent.supported_models.
 */
@Component
@Slf4j
public class ModelProviderRouter {

    private final ModelRepository modelRepository;
    private final EncryptionService encryptionService;
    private final ModelWhitelistConfig whitelist;
    private final RuntimeTuningProperties runtimeTuningProperties;

    public ModelProviderRouter(ModelRepository modelRepository,
                               EncryptionService encryptionService,
                               ModelWhitelistConfig whitelist,
                               RuntimeTuningProperties runtimeTuningProperties) {
        this.modelRepository = modelRepository;
        this.encryptionService = encryptionService;
        this.whitelist = whitelist;
        this.runtimeTuningProperties = runtimeTuningProperties;
    }

    private int maxTokens() {
        return runtimeTuningProperties.getMaxOutputTokens();
    }

    /**
     * o-series and GPT-5+ models reject max_tokens and require max_completion_tokens.
     */
    private static boolean requiresCompletionTokens(String modelID) {
        if (modelID == null) return false;
        String id = modelID.toLowerCase();
        return id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
                || id.startsWith("gpt-5") || id.startsWith("gpt-o");
    }

    private OpenAiChatOptions openAiOptions(String modelID) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model(modelID);
        if (requiresCompletionTokens(modelID)) {
            b.maxCompletionTokens(maxTokens());
        } else {
            b.maxTokens(maxTokens());
        }
        return b.build();
    }

    public Spec resolve(@Nullable ModelRef model) {
        if (model == null || model.providerID() == null) {
            throw new IllegalStateException(
                    "No model selected — choose a model via the chat interface");
        }

        String provider = model.providerID().toLowerCase();
        String modelID = model.modelID();

        // Enforce whitelist
        if (!whitelist.isAllowed(provider, modelID)) {
            throw new IllegalStateException(
                    "Model '" + provider + "/" + modelID + "' is not in the allowed list (whitelist). " +
                    "Contact your administrator to enable it.");
        }

        return switch (provider) {
            case "anthropic" -> resolveAnthropic(modelID);
            case "azure", "azure_openai" -> resolveAzure(provider, modelID);
            case "openai" -> resolveOpenAI(modelID);
            case "google", "google-vertex" -> resolveGoogle(modelID);
            case "ollama" -> resolveOllama(modelID);
            default -> resolveOpenAiCompatible(provider, modelID);
        };
    }

    // ── Provider resolvers ────────────────────────────────────────────────────

    private Spec resolveAnthropic(String modelID) {
        var creds = getCredsOrThrow("anthropic", modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrl = creds.baseUrl();

            AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder().apiKey(apiKey);
            AnthropicOkHttpClientAsync.Builder asyncClientBuilder = AnthropicOkHttpClientAsync.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) {
                clientBuilder.baseUrl(baseUrl);
                asyncClientBuilder.baseUrl(baseUrl);
            }
            AnthropicClient anthropicClient = clientBuilder.build();
            AnthropicClientAsync anthropicClientAsync = asyncClientBuilder.build();

            AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                    .model(modelID)
                    .maxTokens(maxTokens());
            if (runtimeTuningProperties.isEnableAnthropicThinking()) {
                optionsBuilder.thinking(ThinkingConfigParam.ofAdaptive(
                        ThinkingConfigAdaptive.builder().build()));
            }
            AnthropicChatModel model = AnthropicChatModel.builder()
                    .anthropicClient(anthropicClient)
                    .anthropicClientAsync(anthropicClientAsync)
                    .options(optionsBuilder.build())
                    .build();

            log.debug("[ModelProviderRouter] → anthropic/{} (thinking={})", modelID,
                    runtimeTuningProperties.isEnableAnthropicThinking());
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError("anthropic", modelID, e);
        }
    }

    private Spec resolveAzure(String providerID, String modelID) {
        var creds = getCredsOrThrow(providerID, modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String endpoint = creds.baseUrl();
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("Azure endpoint (Base URL) is missing for " + modelID);
            }

            AzureOpenAiChatModel model = AzureOpenAiChatModel.builder()
                    .openAIClientBuilder(new OpenAIClientBuilder()
                            .endpoint(endpoint)
                            .credential(new AzureKeyCredential(apiKey)))
                    .defaultOptions(AzureOpenAiChatOptions.builder()
                            .deploymentName(modelID)
                            .maxCompletionTokens(maxTokens())
                            .build())
                    .build();

            log.debug("[ModelProviderRouter] → {}/{}", providerID, modelID);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError(providerID, modelID, e);
        }
    }

    private Spec resolveOpenAI(String modelID) {
        var creds = getCredsOrThrow("openai", modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrl = creds.baseUrl();

            OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) apiBuilder.baseUrl(baseUrl);

            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(apiBuilder.build())
                    .defaultOptions(openAiOptions(modelID))
                    .build();

            log.debug("[ModelProviderRouter] → openai/{}", modelID);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError("openai", modelID, e);
        }
    }

    private Spec resolveGoogle(String modelID) {
        var creds = getCredsOrThrow("google", modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            // Gemini OpenAI-compatible endpoint
            String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/";

            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();

            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(openAiOptions(modelID))
                    .build();

            log.debug("[ModelProviderRouter] → google/{} (via OpenAI-compatible bridge)", modelID);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError("google", modelID, e);
        }
    }

    private Spec resolveOllama(String modelID) {
        var creds = getCredsOrThrow("ollama", modelID);
        try {
            String baseUrl = creds.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";

            // OllamaApi should be built using its builder in 1.0.5
            OllamaApi ollamaApi = OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .build();

            OllamaChatModel model = OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                            .model(modelID)
                            .numPredict(maxTokens())
                            .build())
                    .build();

            log.debug("[ModelProviderRouter] → ollama/{} (base={})", modelID, baseUrl);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError("ollama", modelID, e);
        }
    }

    private Spec resolveOpenAiCompatible(String providerID, String modelID) {
        var creds = getCredsOrThrow(providerID, modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrl = creds.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("Base URL is required for OpenAI-compatible provider: " + providerID);
            }

            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();

            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(openAiOptions(modelID))
                    .build();

            log.debug("[ModelProviderRouter] → {}/{} (openai-compatible, base={})", providerID, modelID, baseUrl);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError(providerID, modelID, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ModelRepository.ModelCredentials getCredsOrThrow(String providerID, String modelID) {
        return modelRepository.getCredentials(providerID, modelID)
                .filter(ModelRepository.ModelCredentials::hasKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No API key found for " + providerID + "/" + modelID +
                        " — register the model with an API key via the Models page"));
    }

    private IllegalStateException wrapError(String provider, String modelID, Exception e) {
        if (e instanceof IllegalStateException) return (IllegalStateException) e;
        return new IllegalStateException(
                "Failed to initialize client for " + provider + "/" + modelID + ": " + e.getMessage(), e);
    }

    /** Resolved ChatClient + provider-specific ChatOptions for a request. */
    public record Spec(ChatClient client, @Nullable ChatOptions options) {}
}
