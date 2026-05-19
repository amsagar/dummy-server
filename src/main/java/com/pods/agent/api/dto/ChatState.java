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

    /** Optional embedding model override. When null, system default is used. */
    private ModelRef embeddingModel;
    private String runtimeMode;
    private String modelSelectionMode;
    private String agentProfileId;
    /**
     * Optional response-mode profile id (kind='response_mode'). When set, the
     * orchestrator appends its system_prompt to the base profile as a style addendum.
     */
    private String responseModeId;
    private String rollingSummary;

    public ChatState copy() {
        return ChatState.builder()
                .timezone(this.timezone)
                .model(this.model)
                .embeddingModel(this.embeddingModel)
                .runtimeMode(this.runtimeMode)
                .modelSelectionMode(this.modelSelectionMode)
                .agentProfileId(this.agentProfileId)
                .responseModeId(this.responseModeId)
                .rollingSummary(this.rollingSummary)
                .build();
    }

    /** Backward-compat accessor for code still reading modelId as a string. */
    public String getModelId() {
        return model != null ? model.toString() : null;
    }
}
