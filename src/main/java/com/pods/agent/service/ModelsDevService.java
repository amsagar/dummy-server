package com.pods.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches and caches the model catalog from https://models.dev/api.json.
 *
 * mirrors the nx-ai-agent ModelsDev.get() pattern:
 *   - Live fetch with 1-hour in-memory cache
 *   - Graceful fallback to last-known-good data on network error
 *   - Exposes structured provider + model lists
 */
@Service
@Slf4j
public class ModelsDevService {

    private static final String MODELS_DEV_URL = "https://models.dev/api.json";
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L; // 1 hour

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // In-memory cache
    private volatile List<ProviderEntry> cachedProviders = List.of();
    private volatile long cacheExpiry = 0;

    public ModelsDevService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .baseUrl(MODELS_DEV_URL)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10 MB
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Returns all providers (with their models) from models.dev.
     * Result is cached for 1 hour.
     */
    public List<ProviderEntry> getProviders() {
        if (System.currentTimeMillis() < cacheExpiry && !cachedProviders.isEmpty()) {
            return cachedProviders;
        }
        return refresh();
    }

    /**
     * Returns all models across all providers, flat list.
     */
    public List<ModelEntry> getAllModels() {
        return getProviders().stream()
                .flatMap(p -> p.models().stream())
                .toList();
    }

    /**
     * Find a specific model by providerID + modelID.
     */
    public ModelEntry findModel(String providerID, String modelID) {
        return getProviders().stream()
                .filter(p -> p.id().equalsIgnoreCase(providerID))
                .flatMap(p -> p.models().stream())
                .filter(m -> m.id().equalsIgnoreCase(modelID))
                .findFirst()
                .orElse(null);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private synchronized List<ProviderEntry> refresh() {
        // Double-check under lock
        if (System.currentTimeMillis() < cacheExpiry && !cachedProviders.isEmpty()) {
            return cachedProviders;
        }
        try {
            String json = webClient.get()
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            // models.dev response: Map<providerID, RawProvider>
            Map<String, RawProvider> raw = objectMapper.readValue(
                    json, new TypeReference<>() {});

            List<ProviderEntry> parsed = new ArrayList<>();
            for (Map.Entry<String, RawProvider> entry : raw.entrySet()) {
                String pid = entry.getKey();
                RawProvider rp = entry.getValue();
                if (rp == null) continue;

                List<ModelEntry> models = new ArrayList<>();
                if (rp.getModels() != null) {
                    for (Map.Entry<String, RawModel> me : rp.getModels().entrySet()) {
                        String mid = me.getKey();
                        RawModel rm = me.getValue();
                        if (rm == null) continue;
                        models.add(new ModelEntry(
                                mid,
                                rm.getName() != null ? rm.getName() : mid,
                                pid,
                                rp.getName() != null ? rp.getName() : pid,
                                rm.getContext(),
                                rm.getCost() != null ? rm.getCost().getInput() : null,
                                rm.getCost() != null ? rm.getCost().getOutput() : null,
                                Boolean.TRUE.equals(rm.getToolcall()),
                                Boolean.TRUE.equals(rm.getAttachment()),
                                Boolean.TRUE.equals(rm.getTemperature()),
                                Boolean.TRUE.equals(rm.getReasoning()),
                                !Boolean.FALSE.equals(rm.getStreaming()) // default true
                        ));
                    }
                }
                parsed.add(new ProviderEntry(pid,
                        rp.getName() != null ? rp.getName() : pid, models));
            }

            cachedProviders = parsed;
            cacheExpiry = System.currentTimeMillis() + CACHE_TTL_MS;
            log.info("[ModelsDevService] Loaded {} providers, {} models from models.dev",
                    parsed.size(), parsed.stream().mapToLong(p -> p.models().size()).sum());

        } catch (Exception e) {
            log.warn("[ModelsDevService] Failed to fetch models.dev ({}), using cached/empty data",
                    e.getMessage());
        }
        return cachedProviders;
    }

    // ── Domain records ────────────────────────────────────────────────────────

    public record ProviderEntry(
            String id,
            String name,
            List<ModelEntry> models
    ) {}

    public record ModelEntry(
            String id,
            String name,
            String providerID,
            String providerName,
            Long context,
            Double costInput,   // $ per million input tokens
            Double costOutput,  // $ per million output tokens
            boolean supportsTools,
            boolean supportsVision,
            boolean supportsTemperature,
            boolean supportsReasoning,
            boolean supportsStreaming
    ) {}

    // ── Raw Jackson POJOs (models.dev response shape) ─────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawProvider {
        private String id;
        private String name;
        private Map<String, RawModel> models;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawModel {
        private String id;
        private String name;
        private Long context;
        private RawCost cost;
        private Boolean toolcall;
        private Boolean attachment;
        private Boolean temperature;
        private Boolean reasoning;
        private Boolean streaming;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawCost {
        private Double input;
        private Double output;
    }
}
