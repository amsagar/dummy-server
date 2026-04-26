package com.pods.agent.service;

import com.pods.agent.api.dto.ChatState;
import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.domain.ModelRef;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingAutoRouterService {

    private final EmbeddingProviderRouter embeddingProviderRouter;

    public EmbeddingAutoRouterService(EmbeddingProviderRouter embeddingProviderRouter) {
        this.embeddingProviderRouter = embeddingProviderRouter;
    }

    public ModelRef pickEmbeddingModel(ChatState state) {
        if (state != null && state.getEmbeddingModel() != null) {
            return state.getEmbeddingModel();
        }
        return embeddingProviderRouter.findDefault()
                .map(m -> new ModelRef(m.getProviderId(), m.getModelId()))
                .orElse(null);
    }
}
