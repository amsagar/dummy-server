package com.pods.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Compound model reference: providerID + modelID.
 * Matches the nx-ai-agent pattern: { providerID: "anthropic", modelID: "claude-opus-4-6" }
 *
 * Used in ChatRequest, ChatState, and ModelProviderRouter to unambiguously
 * identify which provider and model to use for a given request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelRef(String providerID, String modelID) {

    /**
     * Parse a slash-separated string like "anthropic/claude-opus-4-6".
     * Returns null if the string is blank or malformed.
     */
    public static ModelRef parse(String combined) {
        if (combined == null || combined.isBlank()) return null;
        int slash = combined.indexOf('/');
        if (slash < 1 || slash == combined.length() - 1) return null;
        return new ModelRef(combined.substring(0, slash), combined.substring(slash + 1));
    }

    /** Returns "providerID/modelID" — useful for logging and DB keys. */
    @Override
    public String toString() {
        return providerID + "/" + modelID;
    }
}
