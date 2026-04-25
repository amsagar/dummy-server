package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ToolDomainRequest {
    @NotBlank(message = "name is required")
    private String name;
    private String description;
    private Boolean enabled = true;
}
