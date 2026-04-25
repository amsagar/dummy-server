package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SkillFileRequest {
    @NotBlank(message = "filePath is required")
    private String filePath;
    private String mimeType = "text/plain";
    @NotBlank(message = "content is required")
    private String content;
}
