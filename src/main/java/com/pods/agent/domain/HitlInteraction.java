package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlInteraction {
    private String id;
    private String sessionId;
    private String turnId;
    private String type;
    private String prompt;
    private String status;
    private String responseText;
    private long createdAt;
    private Long resolvedAt;
}
