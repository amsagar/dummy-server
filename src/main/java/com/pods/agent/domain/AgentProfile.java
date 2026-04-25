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
    private long createdAt;
    private long updatedAt;
}
