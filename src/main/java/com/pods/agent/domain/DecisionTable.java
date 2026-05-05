package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionTable {
    private String id;
    private String name;
    private String description;
    private String dmnJson;
    private String hitPolicy;
    private String metadataJson;
    private long createdAt;
    private long updatedAt;
}
