package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostUsage {
    private String id;
    private String sessionId;
    private String turnId;
    private String providerId;
    private String modelId;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private double estimatedCostUsd;
    private long createdAt;
}
