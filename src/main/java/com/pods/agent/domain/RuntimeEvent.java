package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeEvent {
    private String id;
    private String sessionId;
    private String turnId;
    private String eventType;
    private String payload;
    private long createdAt;
}
