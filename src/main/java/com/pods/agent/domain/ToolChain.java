package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChain {
    private String id;
    private String name;
    private String description;
    private boolean enabled;
    private String status;
    private Integer currentVersion;
    private String origin;
    private String approvalStatus;
    private String approvedBy;
    private Long approvedAt;
    private String intentSignature;
    private String structureSignature;
    private String metadataJson;
    private String createdBy;
    private long createdAt;
    private long updatedAt;
}
