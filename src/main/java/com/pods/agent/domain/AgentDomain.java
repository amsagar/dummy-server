package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDomain {
    private String id;
    private String name;
    private String description;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;
}
