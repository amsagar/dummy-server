package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookMapping {
    private String id;
    private String hookPoint;
    private String hookName;
    private String profileId;
    private boolean enabled;
    private String configJson;
    private long createdAt;
    private long updatedAt;
}
