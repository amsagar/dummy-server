package com.pods.agent.api;

import com.pods.agent.domain.AgentProfile;
import com.pods.agent.domain.EvalRun;
import com.pods.agent.domain.GuardrailPolicy;
import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.AgentProfileRepository;
import com.pods.agent.repository.EvalRunRepository;
import com.pods.agent.repository.GuardrailPolicyRepository;
import com.pods.agent.repository.McpRegistryRepository;
import com.pods.agent.service.EvalHarnessService;
import com.pods.agent.service.RuntimeHookRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform", description = "Platform admin APIs: policies, agent profiles, MCP and eval")
public class PlatformAdminController {
    private final GuardrailPolicyRepository policyRepository;
    private final AgentProfileRepository profileRepository;
    private final McpRegistryRepository mcpRepository;
    private final EvalRunRepository evalRunRepository;
    private final EvalHarnessService evalHarnessService;
    private final RuntimeHookRegistryService hookRegistryService;

    public PlatformAdminController(GuardrailPolicyRepository policyRepository,
                                   AgentProfileRepository profileRepository,
                                   McpRegistryRepository mcpRepository,
                                   EvalRunRepository evalRunRepository,
                                   EvalHarnessService evalHarnessService,
                                   RuntimeHookRegistryService hookRegistryService) {
        this.policyRepository = policyRepository;
        this.profileRepository = profileRepository;
        this.mcpRepository = mcpRepository;
        this.evalRunRepository = evalRunRepository;
        this.evalHarnessService = evalHarnessService;
        this.hookRegistryService = hookRegistryService;
    }

    @GetMapping("/policies")
    public ResponseEntity<?> listPolicies() { return ResponseEntity.ok(policyRepository.findAll()); }

    @PostMapping("/policies")
    public ResponseEntity<?> createPolicy(@RequestBody GuardrailPolicy policy) {
        return ResponseEntity.ok(policyRepository.save(policy));
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<?> deletePolicy(@PathVariable String id) {
        policyRepository.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @GetMapping("/agent-profiles")
    public ResponseEntity<?> listProfiles() { return ResponseEntity.ok(profileRepository.findAll()); }

    @PostMapping("/agent-profiles")
    public ResponseEntity<?> createProfile(@RequestBody AgentProfile profile) {
        return ResponseEntity.ok(profileRepository.save(profile));
    }

    @PatchMapping("/agent-profiles/{id}")
    public ResponseEntity<?> updateProfile(@PathVariable String id, @RequestBody AgentProfile profile) {
        if (profileRepository.findById(id).isEmpty()) return ResponseEntityFactory.notFound("Agent profile not found: " + id);
        profile.setId(id);
        profileRepository.update(profile);
        return ResponseEntity.ok(profile);
    }

    @DeleteMapping("/agent-profiles/{id}")
    public ResponseEntity<?> deleteProfile(@PathVariable String id) {
        profileRepository.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @GetMapping("/mcp")
    public ResponseEntity<?> listMcp() { return ResponseEntity.ok(mcpRepository.findAll()); }

    @PostMapping("/mcp")
    public ResponseEntity<?> createMcp(@RequestBody McpRegistryEntry entry) { return ResponseEntity.ok(mcpRepository.save(entry)); }

    @PatchMapping("/mcp/{id}")
    public ResponseEntity<?> updateMcp(@PathVariable String id, @RequestBody McpRegistryEntry entry) {
        if (mcpRepository.findById(id).isEmpty()) return ResponseEntityFactory.notFound("MCP entry not found: " + id);
        entry.setId(id);
        mcpRepository.update(entry);
        return ResponseEntity.ok(entry);
    }

    @DeleteMapping("/mcp/{id}")
    public ResponseEntity<?> deleteMcp(@PathVariable String id) {
        mcpRepository.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @PostMapping("/eval/runs")
    @Operation(summary = "Launch evaluation run")
    public ResponseEntity<?> launchEval(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "default-eval"));
        String datasetRef = String.valueOf(body.getOrDefault("datasetRef", "inline"));
        @SuppressWarnings("unchecked")
        var dataset = body.get("dataset") instanceof java.util.List<?> list
                ? (java.util.List<java.util.Map<String, Object>>) list
                : java.util.List.<java.util.Map<String, Object>>of();
        EvalRun run = evalHarnessService.launch(name, datasetRef, dataset);
        return ResponseEntity.ok(run);
    }

    @GetMapping("/eval/runs")
    public ResponseEntity<?> listEvalRuns() { return ResponseEntity.ok(evalRunRepository.findAll()); }

    @GetMapping("/eval/runs/{id}")
    public ResponseEntity<?> getEvalRun(@PathVariable String id) {
        return evalRunRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntityFactory.notFound("Eval run not found: " + id));
    }

    @PostMapping("/eval/replay/{sessionId}")
    @Operation(summary = "Replay runtime traces for a session")
    public ResponseEntity<?> replay(@PathVariable String sessionId) {
        return ResponseEntity.ok(evalHarnessService.replaySessionTrace(sessionId));
    }

    @GetMapping("/hooks")
    public ResponseEntity<?> listHooks() { return ResponseEntity.ok(hookRegistryService.listAll()); }

    @PostMapping("/hooks/{hookPoint}")
    public ResponseEntity<?> registerHook(@PathVariable String hookPoint, @RequestBody Map<String, Object> body) {
        String hookName = String.valueOf(body.getOrDefault("hookName", "custom-hook"));
        String profileId = body.get("profileId") != null ? String.valueOf(body.get("profileId")) : null;
        String configJson = body.get("configJson") != null ? String.valueOf(body.get("configJson")) : "{}";
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        hookRegistryService.register(hookPoint, hookName, profileId, configJson, enabled);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "hookPoint", hookPoint,
                "hookName", hookName,
                "profileId", profileId,
                "enabled", enabled
        ));
    }

    @DeleteMapping("/hooks/{hookPoint}")
    public ResponseEntity<?> clearHook(@PathVariable String hookPoint) {
        hookRegistryService.clear(hookPoint);
        return ResponseEntity.ok(Map.of("ok", true, "hookPoint", hookPoint));
    }
}
