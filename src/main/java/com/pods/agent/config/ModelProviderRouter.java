package com.pods.agent.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.HttpClientOptions;
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
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

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

    private static String normalizeAzureEndpoint(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/openai/v1")) return base.substring(0, base.length() - "/openai/v1".length());
        if (base.endsWith("/openai")) return base.substring(0, base.length() - "/openai".length());
        return base;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void trySetAzurePreviewServiceVersion(OpenAIClientBuilder builder) {
        try {
            Class<?> versionClass = Class.forName("com.azure.ai.openai.models.OpenAIServiceVersion");
            Object version = Enum.valueOf((Class<Enum>) versionClass.asSubclass(Enum.class), "V2024_10_21");
            OpenAIClientBuilder.class.getMethod("serviceVersion", versionClass).invoke(builder, version);
        } catch (Exception ignored) {
            // Best-effort for SDKs that expose serviceVersion. Keep defaults otherwise.
        }
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
        return resolve(model, false);
    }

    /**
     * Resolve a chat client. If {@code disableThinking} is true, Anthropic models will be configured
     * WITHOUT extended thinking even when the global {@code enableAnthropicThinking} flag is on.
     * Use this for structured-output calls (JSON generation) where extended thinking can consume the
     * entire token budget and prevent the actual answer from being emitted.
     */
    public Spec resolve(@Nullable ModelRef model, boolean disableThinking) {
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
            case "anthropic" -> resolveAnthropic(modelID, disableThinking);
            case "azure_claude" -> resolveAzureClaude(modelID);
            case "azure", "azure_openai" -> resolveAzure(provider, modelID);
            case "openai" -> resolveOpenAI(modelID);
            case "google", "google-vertex" -> resolveGoogle(modelID);
            case "ollama" -> resolveOllama(modelID);
            default -> resolveOpenAiCompatible(provider, modelID);
        };
    }

    // ── Provider resolvers ────────────────────────────────────────────────────

    private Spec resolveAnthropic(String modelID) {
        return resolveAnthropic(modelID, false);
    }

    private Spec resolveAnthropic(String modelID, boolean disableThinking) {
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

            boolean thinkingEnabled = runtimeTuningProperties.isEnableAnthropicThinking() && !disableThinking;
            AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                    .model(modelID)
                    .maxTokens(maxTokens());
            if (thinkingEnabled) {
                optionsBuilder.thinking(ThinkingConfigParam.ofAdaptive(
                        ThinkingConfigAdaptive.builder().build()));
            }
            AnthropicChatModel model = AnthropicChatModel.builder()
                    .anthropicClient(anthropicClient)
                    .anthropicClientAsync(anthropicClientAsync)
                    .options(optionsBuilder.build())
                    .build();

            log.debug("[ModelProviderRouter] → anthropic/{} (thinking={})", modelID, thinkingEnabled);
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

            String normalizedEndpoint = normalizeAzureEndpoint(endpoint);
            // Reasoning models (gpt-5, o-series) routinely take >60s per turn
            // when ingesting tool results and planning the next step. The
            // Azure SDK's default response timeout is 60s, so we override it
            // explicitly via HttpClientOptions; the SDK propagates these to
            // the auto-configured Netty HTTP client.
            HttpClientOptions httpClientOptions = new HttpClientOptions()
                    .setResponseTimeout(Duration.ofMillis(runtimeTuningProperties.getAzureResponseTimeoutMs()));
            OpenAIClientBuilder clientBuilder = new OpenAIClientBuilder()
                    .endpoint(normalizedEndpoint)
                    .credential(new AzureKeyCredential(apiKey))
                    .clientOptions(httpClientOptions);
            // Claude and other partner models may require newer Azure API versions.
            trySetAzurePreviewServiceVersion(clientBuilder);

            AzureOpenAiChatModel model = AzureOpenAiChatModel.builder()
                    .openAIClientBuilder(clientBuilder)
                    .defaultOptions(AzureOpenAiChatOptions.builder()
                            .deploymentName(modelID)
                            .maxCompletionTokens(maxTokens())
                            .build())
                    .build();

            log.debug("[ModelProviderRouter] → {}/{} (azure-deployment-api, endpoint={})",
                    providerID, modelID, normalizedEndpoint);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError(providerID, modelID, e);
        }
    }

    private static String extractFoundryResource(String baseUrlOrResource) {
        if (baseUrlOrResource == null || baseUrlOrResource.isBlank()) return "";
        String value = baseUrlOrResource.trim();
        try {
            if (value.contains("://")) {
                URI uri = URI.create(value);
                String host = uri.getHost();
                if (host != null && !host.isBlank()) {
                    int dot = host.indexOf('.');
                    return dot > 0 ? host.substring(0, dot) : host;
                }
            }
        } catch (Exception ignored) {
        }
        int dot = value.indexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static Object buildFoundryBackend(String resource, String apiKey) {
        try {
            Class<?> c = Class.forName("com.anthropic.foundry.backends.FoundryBackend");
            Object builder = c.getMethod("builder").invoke(null);
            builder.getClass().getMethod("resource", String.class).invoke(builder, resource);
            builder.getClass().getMethod("apiKey", String.class).invoke(builder, apiKey);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize FoundryBackend: " + e.getMessage(), e);
        }
    }

    private static void applyBackend(Object clientBuilder, Object backend) {
        try {
            for (var m : clientBuilder.getClass().getMethods()) {
                if (!"backend".equals(m.getName()) || m.getParameterCount() != 1) continue;
                m.invoke(clientBuilder, backend);
                return;
            }
            throw new IllegalStateException("No backend(...) method found on client builder " + clientBuilder.getClass().getName());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply Foundry backend: " + e.getMessage(), e);
        }
    }

    private Spec resolveAzureClaude(String modelID) {
        var creds = getCredsOrThrowFromProviders(modelID, "azure_claude", "azure", "azure_openai");
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrlOrResource = creds.baseUrl();
            if (baseUrlOrResource == null || baseUrlOrResource.isBlank()) {
                throw new IllegalStateException(
                        "Azure Claude baseUrl/resource is required (for Foundry Anthropic endpoint).");
            }
            String resource = extractFoundryResource(baseUrlOrResource);
            if (resource.isBlank()) {
                throw new IllegalStateException("Could not derive Foundry resource from baseUrl/resource: " + baseUrlOrResource);
            }

            Object backend = buildFoundryBackend(resource, apiKey);
            Object syncBuilder = AnthropicOkHttpClient.builder();
            applyBackend(syncBuilder, backend);
            Object syncFoundry = syncBuilder.getClass().getMethod("build").invoke(syncBuilder);

            Object asyncBuilder = AnthropicOkHttpClientAsync.builder();
            applyBackend(asyncBuilder, backend);
            Object asyncFoundry = asyncBuilder.getClass().getMethod("build").invoke(asyncBuilder);

            AnthropicChatModel model = AnthropicChatModel.builder()
                    .anthropicClient((AnthropicClient) syncFoundry)
                    .anthropicClientAsync((AnthropicClientAsync) asyncFoundry)
                    .options(AnthropicChatOptions.builder()
                            .model(modelID)
                            .maxTokens(maxTokens())
                            .build())
                    .build();

            log.debug("[ModelProviderRouter] → azure_claude/{} (anthropic-foundry, resource={})", modelID, resource);
            return new Spec(ChatClient.create(model), null);
        } catch (Exception e) {
            throw wrapError("azure_claude", modelID, e);
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

    private ModelRepository.ModelCredentials getCredsOrThrowFromProviders(String modelID, String... providerIDs) {
        for (String providerID : providerIDs) {
            var creds = modelRepository.getCredentials(providerID, modelID)
                    .filter(ModelRepository.ModelCredentials::hasKey);
            if (creds.isPresent()) return creds.get();
        }
        throw new IllegalStateException(
                "No API key found for model '" + modelID + "' under providers " + String.join(", ", providerIDs) +
                        " — register the model with an API key via the Models page");
    }

    private IllegalStateException wrapError(String provider, String modelID, Exception e) {
        if (e instanceof IllegalStateException) return (IllegalStateException) e;
        return new IllegalStateException(
                "Failed to initialize client for " + provider + "/" + modelID + ": " + e.getMessage(), e);
    }

    /** Resolved ChatClient + provider-specific ChatOptions for a request. */
    public record Spec(ChatClient client, @Nullable ChatOptions options) {}
}
