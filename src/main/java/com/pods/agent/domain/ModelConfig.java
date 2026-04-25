package com.pods.agent.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Merged AI model entry returned by ModelRegistryService.
 *
 * Source hierarchy (mirrors nx-ai-agent):
 *   "catalog" — from models.dev/api.json (live catalog, capabilities + pricing)
 *   "db"      — only in agent.supported_models (custom/manual entry, no catalog data)
 *
 * The DB table acts as an override / whitelist layer:
 *   - enabled flag is always taken from DB when a DB row exists
 *   - capability fields default from the catalog when no DB row is present
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig {

    // ── Identity ──────────────────────────────────────────────────────────────

    /** Provider identifier, lowercase: "anthropic", "openai", "azure_openai" */
    @JsonProperty("providerID")
    private String providerId;

    /** Model identifier as used by the provider API, e.g. "claude-opus-4-6" */
    @JsonProperty("modelID")
    private String modelId;

    /** Human-readable provider name, e.g. "Anthropic" */
    private String providerName;

    /** Human-readable model name, e.g. "Claude Opus 4.6" */
    private String displayName;

    // ── Capabilities (from models.dev catalog) ────────────────────────────────

    private Long contextWindow;
    private boolean supportsTools;
    private boolean supportsVision;
    private boolean supportsStreaming;
    private boolean supportsReasoning;

    // ── Pricing ($ per million tokens, from models.dev) ───────────────────────

    private Double costInput;
    private Double costOutput;

    // ── Runtime state (from DB override layer) ────────────────────────────────

    /** Whether this model is active for use; controlled via /api/v1/models/{providerId}/{modelId}/enable|disable */
    private boolean enabled;

    /** "catalog" if from models.dev, "db" if manually registered only in DB */
    private String source;

    /** True if an encrypted API key has been stored for this model. The key itself is never exposed. */
    private boolean hasKey;

    /** Custom provider endpoint URL (Azure, Ollama, self-hosted). Null = use provider default. */
    private String baseUrl;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a ModelRef for this config — use when passing to ModelProviderRouter. */
    public ModelRef toRef() {
        return new ModelRef(providerId, modelId);
    }
}
