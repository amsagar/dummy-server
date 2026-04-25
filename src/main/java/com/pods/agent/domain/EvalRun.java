package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRun {
    private String id;
    private String name;
    private String status;
    private String datasetRef;
    private String scoreSummary;
    private String traceRef;
    private long startedAt;
    private Long completedAt;
}
