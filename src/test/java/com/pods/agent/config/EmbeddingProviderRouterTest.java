package com.pods.agent.config;

import com.pods.agent.domain.ModelRef;
import com.pods.agent.repository.ModelRepository;
import com.pods.agent.service.EncryptionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingProviderRouterTest {

    @Test
    void rejects_anthropic_provider() {
        ModelRepository repo = mock(ModelRepository.class);
        EncryptionService enc = mock(EncryptionService.class);
        ModelWhitelistConfig wl = mock(ModelWhitelistConfig.class);
        when(wl.isAllowed(anyString(), anyString())).thenReturn(true);
        EmbeddingProviderRouter router = new EmbeddingProviderRouter(repo, enc, wl);
        assertThrows(IllegalStateException.class,
                () -> router.resolve(new ModelRef("anthropic", "claude-x")));
    }

    @Test
    void rejects_null_ref() {
        ModelRepository repo = mock(ModelRepository.class);
        EncryptionService enc = mock(EncryptionService.class);
        ModelWhitelistConfig wl = mock(ModelWhitelistConfig.class);
        EmbeddingProviderRouter router = new EmbeddingProviderRouter(repo, enc, wl);
        assertThrows(IllegalStateException.class, () -> router.resolve(null));
    }

    @Test
    void rejects_when_not_whitelisted() {
        ModelRepository repo = mock(ModelRepository.class);
        EncryptionService enc = mock(EncryptionService.class);
        ModelWhitelistConfig wl = mock(ModelWhitelistConfig.class);
        when(wl.isAllowed(anyString(), anyString())).thenReturn(false);
        EmbeddingProviderRouter router = new EmbeddingProviderRouter(repo, enc, wl);
        assertThrows(IllegalStateException.class,
                () -> router.resolve(new ModelRef("openai", "text-embedding-3-small")));
    }
}
