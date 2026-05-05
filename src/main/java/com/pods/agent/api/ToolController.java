package com.pods.agent.api;

import com.pods.agent.api.dto.CurlImportRequest;
import com.pods.agent.api.dto.OpenApiImportRequest;
import com.pods.agent.api.dto.PostmanImportRequest;
import com.pods.agent.api.dto.ToolDomainRequest;
import com.pods.agent.api.dto.ToolRequest;
import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.service.FrameworkToolPackService;
import com.pods.agent.service.ToolImportService;
import com.pods.agent.service.ToolExecutionService;
import com.pods.agent.service.ToolRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tool-domains")
@Tag(name = "Tools", description = "Tool domains and tools management")
public class ToolController {
    private final AgentDomainRepository domainRepository;
    private final AgentToolRepository toolRepository;
    private final ToolImportService toolImportService;
    private final ToolRegistryService toolRegistryService;
    private final FrameworkToolPackService frameworkToolPackService;
    private final ToolExecutionService toolExecutionService;
    private final ObjectMapper objectMapper;

    public ToolController(AgentDomainRepository domainRepository,
                          AgentToolRepository toolRepository,
                          ToolImportService toolImportService,
                          ToolRegistryService toolRegistryService,
                          FrameworkToolPackService frameworkToolPackService,
                          ToolExecutionService toolExecutionService,
                          ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.toolRepository = toolRepository;
        this.toolImportService = toolImportService;
        this.toolRegistryService = toolRegistryService;
        this.frameworkToolPackService = frameworkToolPackService;
        this.toolExecutionService = toolExecutionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Operation(summary = "List all tool domains")
    public ResponseEntity<?> listDomains() {
        return ResponseEntity.ok(domainRepository.findAll().stream()
                .filter(domain -> !isHiddenDomain(domain))
                .toList());
    }

    @PostMapping
    @Operation(summary = "Create tool domain")
    public ResponseEntity<?> createDomain(@Valid @RequestBody ToolDomainRequest request) {
        AgentDomain saved = domainRepository.save(AgentDomain.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .enabled(Boolean.TRUE.equals(request.getEnabled()))
                .build());
        toolRegistryService.refresh();
        return ResponseEntity.ok(saved);
    }

    @PatchMapping("/{domainId}")
    @Operation(summary = "Update tool domain")
    public ResponseEntity<?> updateDomain(@PathVariable String domainId, @Valid @RequestBody ToolDomainRequest request) {
        AgentDomain domain = domainRepository.findById(domainId)
                .orElse(null);
        if (domain == null) return ResponseEntityFactory.notFound("Tool domain not found: " + domainId);
        domain.setName(request.getName().trim());
        domain.setDescription(request.getDescription());
        domain.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        domainRepository.update(domain);
        toolRegistryService.refresh();
        return ResponseEntity.ok(domain);
    }

    @DeleteMapping("/{domainId}")
    @Operation(summary = "Delete tool domain")
    public ResponseEntity<?> deleteDomain(@PathVariable String domainId) {
        if (domainRepository.findById(domainId).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + domainId);
        }
        domainRepository.delete(domainId);
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of("deleted", true, "domainId", domainId));
    }

    @GetMapping("/{domainId}/tools")
    @Operation(summary = "List tools by domain")
    public ResponseEntity<?> listTools(@PathVariable String domainId) {
        if (domainRepository.findById(domainId).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + domainId);
        }
        return ResponseEntity.ok(toolRepository.findByDomainId(domainId).stream().map(this::publicToolView).toList());
    }

    @PostMapping("/{domainId}/tools")
    @Operation(summary = "Create tool")
    public ResponseEntity<?> createTool(@PathVariable String domainId, @Valid @RequestBody ToolRequest request) {
        if (domainRepository.findById(domainId).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + domainId);
        }
        var manualValidation = validateManualHttpOnly(request);
        if (manualValidation != null) return manualValidation;
        AgentTool tool = AgentTool.builder()
                .domainId(domainId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .sourceType(request.getSourceType())
                .executionKind(resolveExecutionKind(request))
                .permissionScope(request.getPermissionScope())
                .requiresApproval(Boolean.TRUE.equals(request.getRequiresApproval()))
                .modelGate(request.getModelGate())
                .providerGate(request.getProviderGate())
                .experimental(Boolean.TRUE.equals(request.getExperimental()))
                .inputSchemaVersion(request.getInputSchemaVersion() == null ? 1 : request.getInputSchemaVersion())
                .method(request.getMethod())
                .host(request.getHost())
                .endpoint(request.getEndpoint())
                .requestSchema(request.getRequestSchema())
                .responseSchema(request.getResponseSchema())
                .sampleRequest(request.getSampleRequest())
                .sampleResponse(request.getSampleResponse())
                .authProfileId(request.getAuthProfileId())
                .authOverrideEnabled(Boolean.TRUE.equals(request.getAuthOverrideEnabled()))
                .authType(request.getAuthType())
                .authConfig(request.getAuthConfig())
                .clientId(request.getClientId())
                .tokenUrl(request.getTokenUrl())
                .authorizationUrl(request.getAuthorizationUrl())
                .redirectUri(request.getRedirectUri())
                .scopes(request.getScopes())
                .tokenExpiresAt(request.getTokenExpiresAt())
                .enabled(Boolean.TRUE.equals(request.getEnabled()))
                .build();
        AgentTool saved = toolRepository.save(tool);
        toolRepository.updateAuthBinding(
                saved.getId(),
                saved.getAuthProfileId(),
                saved.getAuthOverrideEnabled(),
                saved.getAuthType(),
                saved.getAuthConfig(),
                saved.getClientId(),
                saved.getEncryptedClientSecret(),
                saved.getTokenUrl(),
                saved.getAuthorizationUrl(),
                saved.getRedirectUri(),
                saved.getScopes(),
                saved.getEncryptedAccessToken(),
                saved.getEncryptedRefreshToken(),
                saved.getTokenExpiresAt()
        );
        saved = toolRepository.findById(saved.getId()).orElse(saved);
        toolRegistryService.refresh();
        return ResponseEntity.ok(publicToolView(saved));
    }

    @PatchMapping("/{domainId}/tools/{toolId}")
    @Operation(summary = "Update tool")
    public ResponseEntity<?> updateTool(@PathVariable String domainId,
                                        @PathVariable String toolId,
                                        @Valid @RequestBody ToolRequest request) {
        AgentTool tool = toolRepository.findById(toolId).orElse(null);
        if (tool == null || !domainId.equals(tool.getDomainId())) {
            return ResponseEntityFactory.notFound("Tool not found: " + toolId);
        }
        var manualValidation = validateManualHttpOnly(request);
        if (manualValidation != null) return manualValidation;
        tool.setName(request.getName().trim());
        tool.setDescription(request.getDescription());
        tool.setSourceType(request.getSourceType());
        tool.setExecutionKind(resolveExecutionKind(request));
        tool.setPermissionScope(request.getPermissionScope());
        tool.setRequiresApproval(Boolean.TRUE.equals(request.getRequiresApproval()));
        tool.setModelGate(request.getModelGate());
        tool.setProviderGate(request.getProviderGate());
        tool.setExperimental(Boolean.TRUE.equals(request.getExperimental()));
        tool.setInputSchemaVersion(request.getInputSchemaVersion() == null ? 1 : request.getInputSchemaVersion());
        tool.setMethod(request.getMethod());
        tool.setHost(request.getHost());
        tool.setEndpoint(request.getEndpoint());
        tool.setRequestSchema(request.getRequestSchema());
        tool.setResponseSchema(request.getResponseSchema());
        tool.setSampleRequest(request.getSampleRequest());
        tool.setSampleResponse(request.getSampleResponse());
        tool.setAuthProfileId(request.getAuthProfileId());
        tool.setAuthOverrideEnabled(Boolean.TRUE.equals(request.getAuthOverrideEnabled()));
        tool.setAuthType(request.getAuthType());
        tool.setAuthConfig(request.getAuthConfig());
        tool.setClientId(request.getClientId());
        tool.setTokenUrl(request.getTokenUrl());
        tool.setAuthorizationUrl(request.getAuthorizationUrl());
        tool.setRedirectUri(request.getRedirectUri());
        tool.setScopes(request.getScopes());
        tool.setTokenExpiresAt(request.getTokenExpiresAt());
        tool.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        toolRepository.update(tool);
        toolRepository.updateAuthBinding(
                tool.getId(),
                tool.getAuthProfileId(),
                tool.getAuthOverrideEnabled(),
                tool.getAuthType(),
                tool.getAuthConfig(),
                tool.getClientId(),
                tool.getEncryptedClientSecret(),
                tool.getTokenUrl(),
                tool.getAuthorizationUrl(),
                tool.getRedirectUri(),
                tool.getScopes(),
                tool.getEncryptedAccessToken(),
                tool.getEncryptedRefreshToken(),
                tool.getTokenExpiresAt()
        );
        tool = toolRepository.findById(tool.getId()).orElse(tool);
        toolRegistryService.refresh();
        return ResponseEntity.ok(publicToolView(tool));
    }

    @PostMapping("/{domainId}/tools/{toolId}/enable")
    @Operation(summary = "Enable tool")
    public ResponseEntity<?> enableTool(@PathVariable String domainId, @PathVariable String toolId) {
        AgentTool tool = toolRepository.findById(toolId).orElse(null);
        if (tool == null || !domainId.equals(tool.getDomainId())) {
            return ResponseEntityFactory.notFound("Tool not found: " + toolId);
        }
        toolRepository.setEnabled(toolId, true);
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of("enabled", true, "toolId", toolId));
    }

    @PostMapping("/{domainId}/tools/{toolId}/disable")
    @Operation(summary = "Disable tool")
    public ResponseEntity<?> disableTool(@PathVariable String domainId, @PathVariable String toolId) {
        AgentTool tool = toolRepository.findById(toolId).orElse(null);
        if (tool == null || !domainId.equals(tool.getDomainId())) {
            return ResponseEntityFactory.notFound("Tool not found: " + toolId);
        }
        toolRepository.setEnabled(toolId, false);
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of("enabled", false, "toolId", toolId));
    }

    @DeleteMapping("/{domainId}/tools/{toolId}")
    @Operation(summary = "Delete tool")
    public ResponseEntity<?> deleteTool(@PathVariable String domainId, @PathVariable String toolId) {
        AgentTool tool = toolRepository.findById(toolId).orElse(null);
        if (tool == null || !domainId.equals(tool.getDomainId())) {
            return ResponseEntityFactory.notFound("Tool not found: " + toolId);
        }
        toolRepository.delete(toolId);
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of("deleted", true, "toolId", toolId));
    }

    @PostMapping("/{domainId}/tools/{toolId}/test")
    @Operation(summary = "Execute tool with test payload")
    public ResponseEntity<?> testTool(@PathVariable String domainId,
                                      @PathVariable String toolId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        AgentTool tool = toolRepository.findById(toolId).orElse(null);
        if (tool == null || !domainId.equals(tool.getDomainId())) {
            return ResponseEntityFactory.notFound("Tool not found: " + toolId);
        }
        Object payloadObj = body == null ? null : body.get("payload");
        String payload;
        if (payloadObj == null) {
            payload = (tool.getSampleRequest() == null || tool.getSampleRequest().isBlank()) ? "{}" : tool.getSampleRequest();
        } else if (payloadObj instanceof String s) {
            payload = s;
        } else {
            try {
                payload = objectMapper.writeValueAsString(payloadObj);
            } catch (Exception e) {
                payload = "{}";
            }
        }
        var result = toolExecutionService.execute(tool, payload);
        return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "toolId", toolId,
                "toolName", tool.getName(),
                "payload", payload,
                "body", result.body() == null ? "" : result.body(),
                "error", result.error() == null ? "" : result.error()
        ));
    }

    @PostMapping("/import/openapi")
    @Operation(summary = "Import tools from OpenAPI spec")
    public ResponseEntity<?> importOpenApi(@Valid @RequestBody OpenApiImportRequest request) {
        if (request.getDomainId() == null || request.getDomainId().isBlank()) {
            return ResponseEntityFactory.badRequest("domainId is required");
        }
        if ((request.getSpec() == null || request.getSpec().isBlank())
                && (request.getSpecUrl() == null || request.getSpecUrl().isBlank())) {
            return ResponseEntityFactory.badRequest("Provide either spec JSON or specUrl");
        }
        if (domainRepository.findById(request.getDomainId()).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + request.getDomainId());
        }
        var imported = toolImportService.importOpenApi(
                request.getDomainId(),
                request.getSpec(),
                request.getSpecUrl(),
                Boolean.TRUE.equals(request.getEnabled()));
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of("importedCount", imported.size(), "tools", imported));
    }

    @PostMapping("/import/curl")
    @Operation(summary = "Import a tool from cURL request")
    public ResponseEntity<?> importCurl(@Valid @RequestBody CurlImportRequest request) {
        if (domainRepository.findById(request.getDomainId()).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + request.getDomainId());
        }
        var imported = toolImportService.importCurl(
                request.getDomainId(),
                request.getCurlCommand(),
                request.getToolName(),
                request.getDescription(),
                request.getResponseSample(),
                Boolean.TRUE.equals(request.getEnabled()));
        toolRegistryService.refresh();
        return ResponseEntity.ok(imported);
    }

    @PostMapping("/import/postman")
    @Operation(summary = "Import tools from Postman collection JSON")
    public ResponseEntity<?> importPostman(@Valid @RequestBody PostmanImportRequest request) {
        if (domainRepository.findById(request.getDomainId()).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + request.getDomainId());
        }
        var imported = toolImportService.importPostmanCollection(
                request.getDomainId(),
                request.getCollectionJson(),
                Boolean.TRUE.equals(request.getEnabled()));
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of("importedCount", imported.size(), "tools", imported));
    }

    @PostMapping("/install/framework-defaults")
    @Operation(summary = "Install AI-agent framework default tool pack")
    public ResponseEntity<?> installFrameworkDefaults() {
        var result = frameworkToolPackService.installDefaults();
        toolRegistryService.refresh();
        return ResponseEntity.ok(Map.of(
                "created", result.created(),
                "updated", result.updated(),
                "total", result.total()
        ));
    }

    private ResponseEntity<?> validateManualHttpOnly(ToolRequest request) {
        if (!"manual".equalsIgnoreCase(request.getSourceType())) {
            return null;
        }
        if (request.getExecutionKind() != null && !"http_proxy".equalsIgnoreCase(request.getExecutionKind())) {
            return ResponseEntityFactory.badRequest("Manual tools support only http_proxy execution");
        }
        return null;
    }

    private String resolveExecutionKind(ToolRequest request) {
        if ("manual".equalsIgnoreCase(request.getSourceType())) {
            return "http_proxy";
        }
        return request.getExecutionKind();
    }

    private boolean isHiddenDomain(AgentDomain domain) {
        if (domain == null || domain.getName() == null) return false;
        String normalized = domain.getName().trim().toLowerCase();
        return normalized.startsWith("mcp ") || normalized.startsWith("framework ");
    }

    private Map<String, Object> publicToolView(AgentTool tool) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", tool.getId());
        out.put("domainId", tool.getDomainId());
        out.put("name", tool.getName());
        out.put("description", tool.getDescription());
        out.put("sourceType", tool.getSourceType());
        out.put("executionKind", tool.getExecutionKind());
        out.put("permissionScope", tool.getPermissionScope());
        out.put("requiresApproval", tool.isRequiresApproval());
        out.put("modelGate", tool.getModelGate());
        out.put("providerGate", tool.getProviderGate());
        out.put("experimental", tool.isExperimental());
        out.put("inputSchemaVersion", tool.getInputSchemaVersion());
        out.put("method", tool.getMethod());
        out.put("host", tool.getHost());
        out.put("endpoint", tool.getEndpoint());
        out.put("requestSchema", tool.getRequestSchema());
        out.put("responseSchema", tool.getResponseSchema());
        out.put("sampleRequest", tool.getSampleRequest());
        out.put("sampleResponse", tool.getSampleResponse());
        out.put("authProfileId", tool.getAuthProfileId());
        out.put("authOverrideEnabled", Boolean.TRUE.equals(tool.getAuthOverrideEnabled()));
        out.put("authType", tool.getAuthType());
        out.put("authConfig", redactAuthConfig(tool.getAuthConfig()));
        out.put("clientId", tool.getClientId());
        out.put("tokenUrl", tool.getTokenUrl());
        out.put("authorizationUrl", tool.getAuthorizationUrl());
        out.put("redirectUri", tool.getRedirectUri());
        out.put("scopes", tool.getScopes());
        out.put("hasAccessToken", tool.getEncryptedAccessToken() != null && !tool.getEncryptedAccessToken().isBlank());
        out.put("tokenExpiresAt", tool.getTokenExpiresAt());
        out.put("enabled", tool.isEnabled());
        out.put("createdAt", tool.getCreatedAt());
        out.put("updatedAt", tool.getUpdatedAt());
        return out;
    }

    private Object redactAuthConfig(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            if (parsed.containsKey("apiKey")) parsed.put("apiKey", "***");
            if (parsed.containsKey("token")) parsed.put("token", "***");
            if (parsed.containsKey("password")) parsed.put("password", "***");
            if (parsed.containsKey("clientSecret")) parsed.put("clientSecret", "***");
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
