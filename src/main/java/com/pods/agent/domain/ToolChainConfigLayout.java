package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainConfigLayout {
    private String id;
    private String toolChainId;
    private String sessionId;
    private String userId;
    private String positionsJson;
    private String viewportJson;
    private long createdAt;
    private long updatedAt;
}
