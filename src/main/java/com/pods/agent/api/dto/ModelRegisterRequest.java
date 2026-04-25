package com.pods.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Request body for POST /api/v1/models — register or update a model.
 *
 * The apiKey is encrypted with AES-256-GCM before storage.
 * It is never returned in any API response.
 */
@Data
public class ModelRegisterRequest {

    @JsonProperty("providerID")
    private String providerID;

    @JsonProperty("modelID")
    private String modelID;

    /** Display name override (optional — catalog name used if absent). */
    private String displayName;

    /** Plain-text API key — will be encrypted before storage. Null = keep existing key. */
    private String apiKey;

    /** Custom provider endpoint URL (Azure base URL, Ollama URL, etc.). Null = use default. */
    private String baseUrl;

    /** Whether this model is active. Defaults to true. */
    private boolean enabled = true;
}
