package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private String sessionId;
    private String userId;
    private long createdAt;
    private long lastActive;
    private String timezone;
    /** Display title (LLM-generated from first message). */
    private String title;
    /** Epoch-ms when archived; null = active */
    private Long archivedAt;
}
