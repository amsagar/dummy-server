package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatTruncateRequest {
    @NotBlank(message = "messageId is required")
    private String messageId;
}
