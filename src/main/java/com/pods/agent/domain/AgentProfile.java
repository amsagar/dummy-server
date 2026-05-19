package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentProfile {
    private String id;
    private String name;
    private String mode;
    private String systemPrompt;
    private String modelStrategy;
    private boolean enabled;
    private String metadata;
    /**
     * Discriminator. 'system' rows are base profiles consumed by the orchestrator;
     * 'response_mode' rows are user-managed style addenda surfaced by the OV-UI dropdown.
     */
    private String kind;
    private long createdAt;
    private long updatedAt;
}
