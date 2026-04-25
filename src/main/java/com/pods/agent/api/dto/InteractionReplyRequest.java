package com.pods.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class InteractionReplyRequest {
    @NotBlank(message = "requestId is required")
    private String requestId;
    private String message;
    private List<String> selectedOptionIds;
}
