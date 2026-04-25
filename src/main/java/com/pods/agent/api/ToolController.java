package com.pods.agent.api;

import com.pods.agent.api.dto.CurlImportRequest;
import com.pods.agent.api.dto.OpenApiImportRequest;
import com.pods.agent.api.dto.ToolDomainRequest;
import com.pods.agent.api.dto.ToolRequest;
import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.service.FrameworkToolPackService;
import com.pods.agent.service.ToolImportService;
import com.pods.agent.service.ToolRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public ToolController(AgentDomainRepository domainRepository,
                          AgentToolRepository toolRepository,
                          ToolImportService toolImportService,
                          ToolRegistryService toolRegistryService,
                          FrameworkToolPackService frameworkToolPackService) {
        this.domainRepository = domainRepository;
        this.toolRepository = toolRepository;
        this.toolImportService = toolImportService;
        this.toolRegistryService = toolRegistryService;
        this.frameworkToolPackService = frameworkToolPackService;
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
        return ResponseEntity.ok(toolRepository.findByDomainId(domainId));
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
                .enabled(Boolean.TRUE.equals(request.getEnabled()))
                .build();
        AgentTool saved = toolRepository.save(tool);
        toolRegistryService.refresh();
        return ResponseEntity.ok(saved);
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
        tool.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        toolRepository.update(tool);
        toolRegistryService.refresh();
        return ResponseEntity.ok(tool);
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
}
