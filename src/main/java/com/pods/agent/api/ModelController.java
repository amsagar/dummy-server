package com.pods.agent.api;

import com.pods.agent.api.dto.ModelRegisterRequest;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.service.ModelRegistryService;
import com.pods.agent.service.ModelsDevService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI model catalog management.
 *
 * GET    /api/v1/providers              — list all providers from models.dev
 * GET    /api/v1/models                 — merged catalog (models.dev + DB overrides)
 * GET    /api/v1/models/enabled         — enabled models only
 * GET    /api/v1/models/{providerID}/{modelID}  — single model detail
 * POST   /api/v1/models/{providerID}/{modelID}/enable
 * POST   /api/v1/models/{providerID}/{modelID}/disable
 * POST   /api/v1/models                 — register / upsert a custom model override
 */
@RestController
@Slf4j
@Tag(name = "Models", description = "AI model catalog — sourced from models.dev with DB override layer")
public class ModelController {

    private final ModelRegistryService modelRegistryService;

    public ModelController(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/providers")
    @Operation(summary = "List all AI providers from models.dev catalog")
    public ResponseEntity<List<ModelsDevService.ProviderEntry>> listProviders() {
        return ResponseEntity.ok(modelRegistryService.listProviders());
    }

    // ── Models ────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/models")
    @Operation(summary = "List all models (models.dev catalog merged with DB overrides)")
    public ResponseEntity<List<ModelConfig>> listAll() {
        return ResponseEntity.ok(modelRegistryService.listAll());
    }

    @GetMapping("/api/v1/models/enabled")
    @Operation(summary = "List enabled models only")
    public ResponseEntity<List<ModelConfig>> listEnabled() {
        return ResponseEntity.ok(modelRegistryService.listEnabled());
    }

    @GetMapping("/api/v1/models/{providerID}/{modelID}")
    @Operation(summary = "Get a specific model by providerID and modelID")
    public ResponseEntity<?> getModel(@PathVariable String providerID,
                                      @PathVariable String modelID) {
        return modelRegistryService.findById(providerID, modelID)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Model not found: " + providerID + "/" + modelID)));
    }

    @PostMapping("/api/v1/models/{providerID}/{modelID}/enable")
    @Operation(summary = "Enable a model (creates a DB override if needed)")
    public ResponseEntity<?> enableModel(@PathVariable String providerID,
                                         @PathVariable String modelID) {
        try {
            return ResponseEntity.ok(modelRegistryService.enableModel(providerID, modelID));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/models/{providerID}/{modelID}/disable")
    @Operation(summary = "Disable a model")
    public ResponseEntity<?> disableModel(@PathVariable String providerID,
                                           @PathVariable String modelID) {
        try {
            return ResponseEntity.ok(modelRegistryService.disableModel(providerID, modelID));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/models")
    @Operation(summary = "Register or update a model — API key is encrypted before storage")
    public ResponseEntity<?> registerModel(@RequestBody ModelRegisterRequest req) {
        if (req.getProviderID() == null || req.getProviderID().isBlank()
                || req.getModelID() == null || req.getModelID().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "providerID and modelID are required"));
        }
        try {
            ModelConfig saved = modelRegistryService.register(req);
            log.info("[ModelController] Registered {}/{}", saved.getProviderId(), saved.getModelId());
            return ResponseEntity.ok(saved);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }
}
