package com.pods.agent.api.dto;

import com.pods.agent.domain.ModelRef;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    @Size(max = 10000, message = "Message must be under 10000 characters")
    private String message;

    private String sessionId;
    private String timezone;
    private String runtimeMode;
    private String modelSelectionMode;
    private String agentProfileId;
    private String toolChainId;
    private Integer toolChainVersion;

    /**
     * Model to use for this turn.
     * Example: { "providerID": "anthropic", "modelID": "claude-opus-4-6" }
     *
     * Takes precedence over the legacy modelId field.
     */
    private ModelRef model;

    /** Optional per-request embedding model override (for tool retrieval). */
    private ModelRef embeddingModel;

    /**
     * Legacy flat modelId, e.g. "anthropic/claude-opus-4-6".
     * Ignored when model is provided.
     *
     * @deprecated use model: { providerID, modelID } instead
     */
    @Deprecated
    private String modelId;
    private List<Attachment> attachments;

    /** Returns the effective ModelRef: prefers model, falls back to parsing modelId. */
    public ModelRef resolvedModel() {
        if (model != null) return model;
        return ModelRef.parse(modelId);
    }

    public boolean hasMessageOrAttachments() {
        return (message != null && !message.isBlank())
                || (attachments != null && !attachments.isEmpty());
    }

    @Data
    public static class Attachment {
        private String fileName;
        private String mimeType;
        private long sizeBytes;
        private String contentBase64;
    }
}
