package com.pods.agent.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

public final class ToolChainDtos {
    private ToolChainDtos() {}

    @Data
    public static class ToolChainCreateRequest {
        private String name;
        private String description;
        private Boolean enabled;
        private Map<String, Object> metadata;
    }

    @Data
    public static class ToolChainVersionRequest {
        private Integer version;
        private String graphJson;
        private String inputSchema;
        private String outputSchema;
        private String responseMode;
        private String synthesisPrompt;
        private List<String> intents;
        private Map<String, Object> ragConfig;
    }

    @Data
    public static class ToolChainExecuteRequest {
        private Integer version;
        private String triggerSource;
        private Map<String, Object> input;
        private Map<String, Object> options;
        private String idempotencyKey;
    }

    @Data
    public static class ToolChainGenerateDraftRequest {
        private String prompt;
        private String modelRef;
    }

    @Data
    public static class ToolChainApprovalDecisionRequest {
        private String comment;
    }

    @Data
    public static class ToolChainConfigChatRequest {
        private String toolChainId;
        private String toolChainName;
        private String toolChainDescription;
        private Boolean createIfMissing;
        private String sessionId;
        private String message;
        private String answerText;
        private String selectedOptionId;
        private String selectedOptionLabel;
        private String requestId;
        private String modelSelectionMode;
        private Map<String, String> modelRef;
        private List<Map<String, Object>> attachments;
    }

    @Data
    public static class ToolChainConfigSessionUpdateRequest {
        private String title;
        private Boolean archived;
    }

    @Data
    public static class ToolChainConfigSessionLayoutRequest {
        private Map<String, Object> positions;
        private Map<String, Object> viewport;
    }
}
