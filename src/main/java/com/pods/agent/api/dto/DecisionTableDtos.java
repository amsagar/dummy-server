package com.pods.agent.api.dto;

import lombok.Data;

import java.util.Map;

public final class DecisionTableDtos {
    private DecisionTableDtos() {
    }

    @Data
    public static class DecisionTableSummary {
        private String name;
        private String description;
        private String hitPolicy;
        private long updatedAt;
    }

    @Data
    public static class DecisionTableDetail {
        private String id;
        private String name;
        private String description;
        private String hitPolicy;
        private Object dmnJson;
        private Object metadata;
        private long createdAt;
        private long updatedAt;
    }

    @Data
    public static class DecisionTableUpsertRequest {
        private String name;
        private String description;
        private String hitPolicy;
        private Object dmnJson;
        private Object metadata;
    }

    @Data
    public static class EvaluateDecisionTableRequest {
        private Map<String, Object> inputs;
    }
}
