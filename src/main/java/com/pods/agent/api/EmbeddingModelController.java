package com.pods.agent.api;

import com.pods.agent.api.dto.ModelRegisterRequest;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.repository.ModelRepository;
import com.pods.agent.service.EncryptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@Tag(name = "Embedding Models")
@RequestMapping("/api/v1/embedding-models")
public class EmbeddingModelController {

    private final ModelRepository modelRepository;
    private final EncryptionService encryption;

    public EmbeddingModelController(ModelRepository modelRepository, EncryptionService encryption) {
        this.modelRepository = modelRepository;
        this.encryption = encryption;
    }

    @GetMapping
    public ResponseEntity<List<ModelConfig>> list() {
        return ResponseEntity.ok(modelRepository.findByKind("embedding"));
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody EmbeddingRegisterRequest req) {
        if (req.getProviderID() == null || req.getProviderID().isBlank()
                || req.getModelID() == null || req.getModelID().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "providerID and modelID are required"));
        }
        if ("anthropic".equalsIgnoreCase(req.getProviderID())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Anthropic does not provide an embedding API"));
        }
        String encryptedKey = null;
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            if (!encryption.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of("error", "Encryption key not configured"));
            }
            encryptedKey = encryption.encrypt(req.getApiKey());
        }
        ModelConfig cfg = ModelConfig.builder()
                .providerId(req.getProviderID())
                .modelId(req.getModelID())
                .displayName(req.getDisplayName())
                .enabled(req.isEnabled())
                .baseUrl(req.getBaseUrl())
                .modelKind("embedding")
                .defaultModel(false)
                .embeddingDimensions(req.getDimensions())
                .build();
        modelRepository.upsert(cfg, encryptedKey);
        log.info("[EmbeddingModelController] Registered embedding {}/{} hasKey={}",
                req.getProviderID(), req.getModelID(), encryptedKey != null);
        return ResponseEntity.ok(cfg);
    }

    @PostMapping("/{providerID}/{modelID}/default")
    public ResponseEntity<?> markDefault(@PathVariable String providerID, @PathVariable String modelID) {
        modelRepository.setDefault(providerID, modelID, "embedding");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/{providerID}/{modelID}/enable")
    public ResponseEntity<?> enable(@PathVariable String providerID, @PathVariable String modelID) {
        modelRepository.setEnabled(providerID, modelID, true);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/{providerID}/{modelID}/disable")
    public ResponseEntity<?> disable(@PathVariable String providerID, @PathVariable String modelID) {
        modelRepository.setEnabled(providerID, modelID, false);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @lombok.Data
    public static class EmbeddingRegisterRequest extends ModelRegisterRequest {
        private Integer dimensions;
    }
}
