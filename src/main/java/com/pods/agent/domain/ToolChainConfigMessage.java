package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainConfigMessage {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private String metadataJson;
    private long createdAt;
    // For role="system": the SSE event type (tool.call, tool.done, tool.result,
    // tool.match, question, approval_required, approval_status). Null for
    // user/assistant rows.
    private String eventType;
    // JSON payload for system events (tool input/output, question metadata, etc.).
    private String eventPayload;
    // HITL request id (links a question/approval row to its later resolution).
    private String requestId;
    // HITL lifecycle: "pending", "answered", "approved", "rejected". Null for
    // non-HITL system events and regular messages.
    private String hitlStatus;
    // The user's response text for resolved HITL events.
    private String hitlResponse;
}
