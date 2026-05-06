package com.pods.agent.config;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.repository.ModelRepository;
import com.pods.agent.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves DB-credentialed EmbeddingModel instances. Mirrors ModelProviderRouter.
 */
@Component
@Slf4j
public class EmbeddingProviderRouter {

    private final ModelRepository modelRepository;
    private final EncryptionService encryptionService;
    private final ModelWhitelistConfig whitelist;

    public EmbeddingProviderRouter(ModelRepository modelRepository,
                                   EncryptionService encryptionService,
                                   ModelWhitelistConfig whitelist) {
        this.modelRepository = modelRepository;
        this.encryptionService = encryptionService;
        this.whitelist = whitelist;
    }

    public Optional<ModelConfig> findDefault() {
        return modelRepository.findEmbeddingDefault();
    }

    public EmbeddingModel resolve(@Nullable ModelRef ref) {
        if (ref == null || ref.providerID() == null) {
            throw new IllegalStateException("No embedding model selected");
        }
        String provider = ref.providerID().toLowerCase();
        String modelID = ref.modelID();

        if ("anthropic".equals(provider)) {
            throw new IllegalStateException("Anthropic does not provide an embedding API");
        }
        if ("azure_claude".equals(provider)) {
            throw new IllegalStateException("Azure Claude does not provide an embedding API");
        }
        if (!whitelist.isAllowed(provider, modelID)) {
            throw new IllegalStateException(
                    "Embedding model '" + provider + "/" + modelID + "' is not in the allowed list");
        }
        return switch (provider) {
            case "openai" -> resolveOpenAI(modelID);
            case "azure", "azure_openai" -> resolveAzure(provider, modelID);
            case "ollama" -> resolveOllama(modelID);
            case "google", "google-vertex" -> resolveGoogle(modelID);
            default -> resolveOpenAiCompatible(provider, modelID);
        };
    }

    private EmbeddingModel resolveOpenAI(String modelID) {
        var creds = creds("openai", modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrl = creds.baseUrl();
            OpenAiApi.Builder b = OpenAiApi.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
            return new OpenAiEmbeddingModel(b.build(), MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().model(modelID).build());
        } catch (Exception e) {
            throw wrap("openai", modelID, e);
        }
    }

    private EmbeddingModel resolveAzure(String providerID, String modelID) {
        var creds = creds(providerID, modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String endpoint = creds.baseUrl();
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("Azure endpoint required for embedding model " + modelID);
            }
            var client = new OpenAIClientBuilder()
                    .endpoint(endpoint)
                    .credential(new AzureKeyCredential(apiKey))
                    .buildClient();
            return new AzureOpenAiEmbeddingModel(client);
        } catch (Exception e) {
            throw wrap(providerID, modelID, e);
        }
    }

    private EmbeddingModel resolveOllama(String modelID) {
        var creds = modelRepository.getCredentials("ollama", modelID).orElse(null);
        try {
            String baseUrl = creds != null ? creds.baseUrl() : null;
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
            OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
            return OllamaEmbeddingModel.builder()
                    .ollamaApi(api)
                    .defaultOptions(OllamaEmbeddingOptions.builder().model(modelID).build())
                    .build();
        } catch (Exception e) {
            throw wrap("ollama", modelID, e);
        }
    }

    private EmbeddingModel resolveGoogle(String modelID) {
        var creds = creds("google", modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/";
            OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
            return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().model(modelID).build());
        } catch (Exception e) {
            throw wrap("google", modelID, e);
        }
    }

    private EmbeddingModel resolveOpenAiCompatible(String providerID, String modelID) {
        var creds = creds(providerID, modelID);
        try {
            String apiKey = encryptionService.decrypt(creds.encryptedKey());
            String baseUrl = creds.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("Base URL required for OpenAI-compatible provider " + providerID);
            }
            OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
            return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().model(modelID).build());
        } catch (Exception e) {
            throw wrap(providerID, modelID, e);
        }
    }

    private ModelRepository.ModelCredentials creds(String providerID, String modelID) {
        return modelRepository.getCredentials(providerID, modelID)
                .filter(ModelRepository.ModelCredentials::hasKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No API key for embedding model " + providerID + "/" + modelID));
    }

    private IllegalStateException wrap(String provider, String modelID, Exception e) {
        if (e instanceof IllegalStateException ise) return ise;
        return new IllegalStateException(
                "Failed to initialize embedding client for " + provider + "/" + modelID + ": " + e.getMessage(), e);
    }
}
