package com.pods.agent.api.dto;

import lombok.Data;

@Data
public class OpenApiImportRequest {
    private String domainId;
    private String spec;
    private String specUrl;
    private Boolean enabled = true;
}
