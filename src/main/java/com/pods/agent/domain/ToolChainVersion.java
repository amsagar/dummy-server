package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChainVersion {
    private String id;
    private String toolChainId;
    private int version;
    private String graphJson;
    private String inputSchema;
    private String outputSchema;
    private String responseMode;
    private String synthesisPrompt;
    private String intentsJson;
    private String intentSignature;
    private String structureSignature;
    private String ragConfigJson;
    private String variablesJson;
    private boolean published;
    private String createdBy;
    private long createdAt;
}
