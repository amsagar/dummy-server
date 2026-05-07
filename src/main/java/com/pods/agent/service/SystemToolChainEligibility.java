package com.pods.agent.service;

import lombok.Builder;

import java.util.List;

@Builder
public record SystemToolChainEligibility(
        boolean toolChainNeeded,
        boolean simpleTurn,
        String confidence,
        String reason,
        List<String> referencedSkills
) {
    public boolean isHighConfidence() {
        return "high".equalsIgnoreCase(confidence);
    }

    public String normalizedConfidence() {
        if (confidence == null || confidence.isBlank()) return "low";
        String value = confidence.trim().toLowerCase();
        return switch (value) {
            case "high", "medium", "low" -> value;
            default -> "low";
        };
    }
}
