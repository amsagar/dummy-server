package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainConfigSession {
    private String id;
    private String toolChainId;
    private String title;
    private String status;
    private String latestArtifactJson;
    private String pendingQuestionJson;
    private String createdBy;
    private long createdAt;
    private long updatedAt;
    private Long archivedAt;
}
