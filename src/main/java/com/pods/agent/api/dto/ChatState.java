package com.pods.agent.api.dto;

import com.pods.agent.domain.ModelRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatState {

    private String timezone;

    /** Model for this session turn. */
    private ModelRef model;
    private String runtimeMode;
    private String modelSelectionMode;
    private String agentProfileId;
    private String rollingSummary;

    public ChatState copy() {
        return ChatState.builder()
                .timezone(this.timezone)
                .model(this.model)
                .runtimeMode(this.runtimeMode)
                .modelSelectionMode(this.modelSelectionMode)
                .agentProfileId(this.agentProfileId)
                .rollingSummary(this.rollingSummary)
                .build();
    }

    /** Backward-compat accessor for code still reading modelId as a string. */
    public String getModelId() {
        return model != null ? model.toString() : null;
    }
}
