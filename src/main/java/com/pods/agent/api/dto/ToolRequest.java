package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ToolRequest {
    @NotBlank(message = "name is required")
    private String name;
    private String description;
    private String sourceType = "manual";
    private String executionKind = "http_proxy";
    private String permissionScope;
    private Boolean requiresApproval = false;
    private String modelGate;
    private String providerGate;
    private Boolean experimental = false;
    private Integer inputSchemaVersion = 1;
    private String method;
    private String host;
    private String endpoint;
    private String requestSchema;
    private String responseSchema;
    private String sampleRequest;
    private String sampleResponse;
    private Boolean enabled = true;
}
