package com.pods.agent.config;

/**
 * Spring AI Anthropic auto-configuration is disabled (see application.yaml).
 * ChatClient instances are built dynamically in ModelProviderRouter using
 * API keys stored encrypted in agent.supported_models.
 *
 * To add a new provider: add a resolver method in ModelProviderRouter.
 */
public class MultiModelConfig {
    // Intentionally empty — all AI clients are built on-demand by ModelProviderRouter.
}
