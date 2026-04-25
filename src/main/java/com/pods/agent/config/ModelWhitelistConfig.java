package com.pods.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Operator-controlled model whitelist.
 *
 * Configure in application.yaml:
 *
 *   pods:
 *     models:
 *       allowed-providers: openai, azure_openai, anthropic, google-vertex, ollama
 *
 */
@Component
@ConfigurationProperties(prefix = "pods.models")
@Data
public class ModelWhitelistConfig {

    /** Comma-separated list of allowed provider IDs. "*" means all allowed. */
    private String allowedProviders = "*";

    private List<String> getAllowedList() {
        if (allowedProviders == null || allowedProviders.isBlank() || allowedProviders.equals("*")) {
            return new ArrayList<>();
        }
        return Arrays.stream(allowedProviders.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .toList();
    }

    /**
     * Returns true when no whitelist is configured (open — all models from catalog are shown).
     */
    public boolean isOpen() {
        return allowedProviders == null || allowedProviders.isBlank() || allowedProviders.equals("*");
    }

    /**
     * Returns true if the given providerID is allowed by this whitelist.
     */
    public boolean isProviderAllowed(String providerID) {
        if (isOpen()) return true;
        if (providerID == null) return false;
        return getAllowedList().contains(providerID.toLowerCase());
    }

    /**
     * For backward compat / granular check (currently just checks provider).
     */
    public boolean isAllowed(String providerID, String modelID) {
        return isProviderAllowed(providerID);
    }
}
