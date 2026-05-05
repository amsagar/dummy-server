package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PostmanImportRequest {
    @NotBlank(message = "domainId is required")
    private String domainId;
    @NotBlank(message = "collectionJson is required")
    private String collectionJson;
    private Boolean enabled = true;
}
