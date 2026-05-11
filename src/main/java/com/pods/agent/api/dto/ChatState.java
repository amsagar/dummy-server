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
    private String toolChainId;
    private Integer toolChainVersion;
    private String workflowDefId;
    private boolean workflowSelectedByUser;
    /** True when the toolChainId was set via an explicit user confirmation
     *  (the "Use ToolChain X / Use normal AI loop" question). When true, the
     *  runtime fails loudly on chain execution errors instead of silently
     *  falling back to the dynamic LLM loop. */
    private boolean toolChainSelectedByUser;
    private String rollingSummary;

    public ChatState copy() {
        return ChatState.builder()
                .timezone(this.timezone)
                .model(this.model)
                .embeddingModel(this.embeddingModel)
                .runtimeMode(this.runtimeMode)
                .modelSelectionMode(this.modelSelectionMode)
                .agentProfileId(this.agentProfileId)
                .toolChainId(this.toolChainId)
                .toolChainVersion(this.toolChainVersion)
                .workflowDefId(this.workflowDefId)
                .toolChainSelectedByUser(this.toolChainSelectedByUser)
                .workflowSelectedByUser(this.workflowSelectedByUser)
                .rollingSummary(this.rollingSummary)
                .build();
    }

    /** Backward-compat accessor for code still reading modelId as a string. */
    public String getModelId() {
        return model != null ? model.toString() : null;
    }
}
