package com.pods.agent.service;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record SystemToolChainArtifact(
        String name,
        String description,
        List<String> intents,
        List<String> referencedSkills,
        String graphJson,
        String inputSchema,
        String outputSchema,
        String responseMode,
        String synthesisPrompt,
        Map<String, Object> ragConfig
) {}
