package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CurlImportRequest {
    @NotBlank(message = "domainId is required")
    private String domainId;
    @NotBlank(message = "curlCommand is required")
    private String curlCommand;
    private String toolName;
    private String description;
    private String responseSample;
    private Boolean enabled = true;
}
