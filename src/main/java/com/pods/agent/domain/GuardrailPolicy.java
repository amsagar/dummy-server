package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailPolicy {
    private String id;
    private String name;
    private String scope;
    private String ruleType;
    private String ruleValue;
    private String decision;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;
}
