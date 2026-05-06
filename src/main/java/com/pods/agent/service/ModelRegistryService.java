package com.pods.agent.service;

import com.pods.agent.api.dto.ModelRegisterRequest;
import com.pods.agent.config.ModelWhitelistConfig;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.repository.ModelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the AI model catalog.
 *
 * Mirrors the nx-ai-agent OrgModel.list() merge pattern:
 *
 *   Source 1 — models.dev/api.json (live catalog, canonical capabilities + pricing)
 *   Source 2 — agent.supported_models DB (override layer: enabled flag, custom config)
 *
 * Merge rules:
 *   - Models from models.dev catalog are the base entries (source="catalog")
 *   - If a DB row exists for the same (providerID, modelID), its enabled flag wins
 *   - Models only in DB (not in catalog) are included as source="db"
 *   - Models only in catalog (no DB row) default to enabled=true
 */
@Service
@Slf4j
public class ModelRegistryService {

    private final ModelRepository modelRepository;
    private final ModelsDevService modelsDevService;
    private final ModelWhitelistConfig whitelist;
    private final EncryptionService encryption;

    public ModelRegistryService(ModelRepository modelRepository,
                                ModelsDevService modelsDevService,
                                ModelWhitelistConfig whitelist,
                                EncryptionService encryption) {
        this.modelRepository = modelRepository;
        this.modelsDevService = modelsDevService;
        this.whitelist = whitelist;
        this.encryption = encryption;
    }

    /**
     * List all models — merged from models.dev catalog + DB overrides.
     */
    public List<ModelConfig> listAll() {
        return merge(false);
    }

    /**
     * List only enabled models.
     */
    public List<ModelConfig> listEnabled() {
        return merge(true).stream()
                .filter(ModelConfig::isEnabled)
                .filter(m -> !"embedding".equalsIgnoreCase(m.getModelKind()))
                .toList();
    }

    /**
     * Find a specific model by providerID + modelID.
     * Checks catalog first, falls back to DB-only entries.
     */
    public Optional<ModelConfig> findById(String providerID, String modelID) {
        return listAll().stream()
                .filter(m -> providerID.equalsIgnoreCase(m.getProviderId())
                          && modelID.equalsIgnoreCase(m.getModelId()))
                .findFirst();
    }

    /**
     * Backward-compat: find by flat modelId (tries "providerID/modelID" parse,
     * then falls back to matching modelID alone).
     */
    public Optional<ModelConfig> findById(String modelId) {
        if (modelId == null) return Optional.empty();
        ModelRef ref = ModelRef.parse(modelId);
        if (ref != null) return findById(ref.providerID(), ref.modelID());
        // Legacy: match by modelID only (first match)
        return listAll().stream()
                .filter(m -> modelId.equalsIgnoreCase(m.getModelId()))
                .findFirst();
    }

    public ModelConfig enableModel(String providerID, String modelID) {
        modelRepository.setEnabled(providerID, modelID, true);
        log.info("[ModelRegistryService] Enabled {}/{}", providerID, modelID);
        return findById(providerID, modelID).orElseThrow(
                () -> new IllegalArgumentException("Model not found: " + providerID + "/" + modelID));
    }

    public ModelConfig disableModel(String providerID, String modelID) {
        modelRepository.setEnabled(providerID, modelID, false);
        log.info("[ModelRegistryService] Disabled {}/{}", providerID, modelID);
        return findById(providerID, modelID).orElseThrow(
                () -> new IllegalArgumentException("Model not found: " + providerID + "/" + modelID));
    }

    /**
     * Register / upsert a model from the UI request.
     * The API key (if provided) is encrypted before storage and never returned.
     */
    public ModelConfig register(ModelRegisterRequest req) {
        String encryptedKey = null;
        String baseUrl = req.getBaseUrl();
        String providerID = req.getProviderID();

        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            if (!encryption.isConfigured()) {
                throw new IllegalStateException(
                        "Encryption key not configured on server — set PODS_ENCRYPTION_KEY env variable");
            }
            encryptedKey = encryption.encrypt(req.getApiKey());
        } else {
            // No key provided — inherit credentials from another model in the same provider
            var existing = modelRepository.findCredentialsByProvider(providerID);
            // For azure_claude, allow inheriting credentials from existing Azure providers.
            if (existing.isEmpty() && "azure_claude".equalsIgnoreCase(providerID)) {
                existing = modelRepository.findCredentialsByProvider("azure");
            }
            if (existing.isEmpty() && "azure_claude".equalsIgnoreCase(providerID)) {
                existing = modelRepository.findCredentialsByProvider("azure_openai");
            }
            if (existing.isPresent()) {
                encryptedKey = existing.get().encryptedKey();
                if ((baseUrl == null || baseUrl.isBlank()) && existing.get().baseUrl() != null) {
                    baseUrl = existing.get().baseUrl();
                }
                log.info("[ModelRegistryService] Reusing provider credentials for {}/{}", providerID, req.getModelID());
            }
        }

        ModelConfig model = ModelConfig.builder()
                .providerId(req.getProviderID())
                .modelId(req.getModelID())
                .displayName(req.getDisplayName())
                .enabled(req.isEnabled())
                .baseUrl(baseUrl)
                .build();

        modelRepository.upsert(model, encryptedKey);
        log.info("[ModelRegistryService] Registered {}/{} hasKey={}", req.getProviderID(), req.getModelID(), encryptedKey != null);
        return findById(req.getProviderID(), req.getModelID()).orElseThrow();
    }

    public void deleteModel(String providerID, String modelID) {
        modelRepository.delete(providerID, modelID);
        log.info("[ModelRegistryService] Deleted {}/{}", providerID, modelID);
    }

    /**
     * Returns all providers as seen in the models.dev catalog.
     */
    public List<ModelsDevService.ProviderEntry> listProviders() {
        List<ModelsDevService.ProviderEntry> providers = new ArrayList<>(modelsDevService.getProviders().stream()
                .filter(p -> whitelist.isProviderAllowed(p.id()))
                .toList());
        if (whitelist.isProviderAllowed("azure_claude")) {
            providers.add(buildAzureClaudeProvider(providers));
        }
        return providers;
    }

    // ── Merge logic ───────────────────────────────────────────────────────────
    //
    // Only DB-registered models are listed — the catalog (models.dev) is used
    // solely to enrich DB rows with live capabilities and pricing.
    // This keeps the management view focused: you only see what you registered.

    private List<ModelConfig> merge(boolean enabledOnly) {
        // Build catalog index for enrichment lookups
        Map<String, ModelsDevService.ModelEntry> catalogIndex = new LinkedHashMap<>();
        for (ModelsDevService.ProviderEntry provider : modelsDevService.getProviders()) {
            for (ModelsDevService.ModelEntry me : provider.models()) {
                catalogIndex.put(key(me.providerID(), me.id()), me);
            }
        }

        List<ModelConfig> result = new ArrayList<>();

        // Iterate only DB rows — these are what the user has explicitly registered
        for (ModelConfig db : modelRepository.findAll()) {
            if (db.getProviderId() == null || db.getModelId() == null) continue;
            if (enabledOnly && !db.isEnabled()) continue;

            String k = key(db.getProviderId(), db.getModelId());
            ModelsDevService.ModelEntry me = catalogIndex.get(k);

            if (me != null) {
                // Enrich DB row with live catalog capabilities + pricing
                result.add(ModelConfig.builder()
                        .providerId(db.getProviderId())
                        .modelId(db.getModelId())
                        .providerName(me.providerName())
                        .displayName(db.getDisplayName() != null ? db.getDisplayName() : me.name())
                        .contextWindow(me.context())
                        .supportsTools(me.supportsTools())
                        .supportsVision(me.supportsVision())
                        .supportsStreaming(me.supportsStreaming())
                        .supportsReasoning(me.supportsReasoning())
                        .costInput(me.costInput())
                        .costOutput(me.costOutput())
                        .enabled(db.isEnabled())
                        .hasKey(db.isHasKey())
                        .baseUrl(db.getBaseUrl())
                        .source("catalog")
                        .build());
            } else {
                // DB-only model (not in catalog) — show as-is
                result.add(db);
            }
        }

        return result;
    }

    private static String key(String providerID, String modelID) {
        return providerID.toLowerCase() + ":" + modelID.toLowerCase();
    }

    private ModelsDevService.ProviderEntry buildAzureClaudeProvider(List<ModelsDevService.ProviderEntry> providers) {
        List<ModelsDevService.ModelEntry> azureClaudeModels = new ArrayList<>();
        for (ModelsDevService.ProviderEntry provider : providers) {
            String pid = provider.id() == null ? "" : provider.id().toLowerCase();
            if (!"azure".equals(pid) && !"azure_openai".equals(pid)) continue;
            for (ModelsDevService.ModelEntry m : provider.models()) {
                if (m.id() != null && m.id().toLowerCase().startsWith("claude")) {
                    azureClaudeModels.add(new ModelsDevService.ModelEntry(
                            m.id(), m.name(), "azure_claude", "Azure Claude",
                            m.context(), m.costInput(), m.costOutput(),
                            m.supportsTools(), m.supportsVision(), m.supportsTemperature(),
                            m.supportsReasoning(), m.supportsStreaming()
                    ));
                }
            }
        }

        // Include already-registered Claude model IDs even if catalog omits them.
        for (ModelConfig cfg : modelRepository.findAll()) {
            String pid = cfg.getProviderId() == null ? "" : cfg.getProviderId().toLowerCase();
            String mid = cfg.getModelId();
            if (mid == null || !mid.toLowerCase().startsWith("claude")) continue;
            if (!"azure".equals(pid) && !"azure_openai".equals(pid) && !"azure_claude".equals(pid)) continue;
            boolean exists = azureClaudeModels.stream().anyMatch(m -> m.id().equalsIgnoreCase(mid));
            if (exists) continue;
            azureClaudeModels.add(new ModelsDevService.ModelEntry(
                    mid, mid, "azure_claude", "Azure Claude",
                    null, null, null,
                    true, false, true, true, true
            ));
        }

        return new ModelsDevService.ProviderEntry("azure_claude", "Azure Claude", azureClaudeModels);
    }
}
