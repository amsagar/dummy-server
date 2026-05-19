package com.pods.agent.api;

import com.pods.agent.domain.AgentProfile;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.AgentProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD over user-managed response modes (admin dashboard authors them; the OV-UI
 * dropdown consumes them). Backed by {@code agent_profiles} rows where
 * {@code kind='response_mode'}; system profiles (e.g. {@code ov-base}) are never
 * exposed through this endpoint.
 */
@RestController
@RequestMapping("/api/v1/response-modes")
@Tag(name = "Response Modes", description = "Reusable AI response style addenda for the order-validation chat")
public class ResponseModeController {

    private static final String KIND = "response_mode";
    private static final String DEFAULT_MODE = "planner_worker";
    private static final String DEFAULT_MODEL_STRATEGY = "manual";

    private final AgentProfileRepository repository;

    public ResponseModeController(AgentProfileRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List enabled response modes")
    public ResponseEntity<?> list() {
        List<Map<String, Object>> body = repository.findByKind(KIND).stream()
                .filter(AgentProfile::isEnabled)
                .map(ResponseModeController::toDto)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a response mode by id")
    public ResponseEntity<?> get(@PathVariable String id) {
        return repository.findById(id)
                .filter(p -> KIND.equalsIgnoreCase(p.getKind()))
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(toDto(p)))
                .orElseGet(() -> ResponseEntityFactory.notFound("Response mode '" + id + "' not found"));
    }

    @PostMapping
    @Operation(summary = "Create a response mode")
    public ResponseEntity<?> create(@RequestBody UpsertRequest request) {
        String name = trimOrNull(request == null ? null : request.name);
        String systemPrompt = request == null ? null : request.systemPrompt;
        if (name == null) return ResponseEntityFactory.badRequest("name is required");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return ResponseEntityFactory.badRequest("systemPrompt is required");
        }
        String id = trimOrNull(request.id);
        if (id == null) id = slugify(name) + "-" + UUID.randomUUID().toString().substring(0, 8);

        AgentProfile profile = AgentProfile.builder()
                .id(id)
                .name(name)
                .mode(DEFAULT_MODE)
                .systemPrompt(systemPrompt)
                .modelStrategy(DEFAULT_MODEL_STRATEGY)
                .enabled(true)
                .kind(KIND)
                .build();
        try {
            repository.save(profile);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            return ResponseEntityFactory.badRequest("A response mode with that id or name already exists");
        }
        return ResponseEntity.ok(toDto(profile));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a response mode")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UpsertRequest request) {
        var existingOpt = repository.findById(id).filter(p -> KIND.equalsIgnoreCase(p.getKind()));
        if (existingOpt.isEmpty()) {
            return ResponseEntityFactory.notFound("Response mode '" + id + "' not found");
        }
        String name = trimOrNull(request == null ? null : request.name);
        String systemPrompt = request == null ? null : request.systemPrompt;
        if (name == null) return ResponseEntityFactory.badRequest("name is required");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return ResponseEntityFactory.badRequest("systemPrompt is required");
        }
        AgentProfile existing = existingOpt.get();
        existing.setName(name);
        existing.setSystemPrompt(systemPrompt);
        existing.setEnabled(true);
        existing.setKind(KIND);
        if (existing.getMode() == null || existing.getMode().isBlank()) existing.setMode(DEFAULT_MODE);
        if (existing.getModelStrategy() == null || existing.getModelStrategy().isBlank()) {
            existing.setModelStrategy(DEFAULT_MODEL_STRATEGY);
        }
        repository.update(existing);
        return ResponseEntity.ok(toDto(existing));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a response mode (enabled=false)")
    public ResponseEntity<?> delete(@PathVariable String id) {
        var existingOpt = repository.findById(id).filter(p -> KIND.equalsIgnoreCase(p.getKind()));
        if (existingOpt.isEmpty()) {
            return ResponseEntityFactory.notFound("Response mode '" + id + "' not found");
        }
        AgentProfile existing = existingOpt.get();
        existing.setEnabled(false);
        repository.update(existing);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    private static Map<String, Object> toDto(AgentProfile profile) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", profile.getId());
        dto.put("name", profile.getName());
        dto.put("systemPrompt", profile.getSystemPrompt());
        dto.put("enabled", profile.isEnabled());
        dto.put("kind", profile.getKind());
        dto.put("createdAt", profile.getCreatedAt());
        dto.put("updatedAt", profile.getUpdatedAt());
        return dto;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String slugify(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String slug = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "mode" : slug;
    }

    public static class UpsertRequest {
        public String id;
        public String name;
        public String systemPrompt;
    }
}
