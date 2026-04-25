package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTool {
    private String id;
    private String domainId;
    private String name;
    private String description;
    private String sourceType;
    private String executionKind;
    private String permissionScope;
    private boolean requiresApproval;
    private String modelGate;
    private String providerGate;
    private boolean experimental;
    private int inputSchemaVersion;
    private String method;
    private String host;
    private String endpoint;
    private String requestSchema;
    private String responseSchema;
    private String sampleRequest;
    private String sampleResponse;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;
}
