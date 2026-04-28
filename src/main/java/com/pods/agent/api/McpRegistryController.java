package com.pods.agent.api;

import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.McpRegistryRepository;
import com.pods.agent.service.EncryptionService;
import com.pods.agent.service.McpAuthService;
import com.pods.agent.service.McpClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mcp-registry")
@Tag(name = "MCP Registry", description = "Register and manage remote MCP servers")
public class McpRegistryController {
    private final McpRegistryRepository mcpRegistryRepository;
    private final McpClientService mcpClientService;
    private final McpAuthService mcpAuthService;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public McpRegistryController(McpRegistryRepository mcpRegistryRepository,
                                 McpClientService mcpClientService,
                                 McpAuthService mcpAuthService,
                                 EncryptionService encryptionService,
                                 ObjectMapper objectMapper) {
        this.mcpRegistryRepository = mcpRegistryRepository;
        this.mcpClientService = mcpClientService;
        this.mcpAuthService = mcpAuthService;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Operation(summary = "List MCP servers")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(mcpRegistryRepository.findAll().stream().map(this::publicView).toList());
    }

    @PostMapping
    @Operation(summary = "Create MCP server")
    public ResponseEntity<?> create(@RequestBody McpRegistryEntry entry) {
        if (entry.getName() == null || entry.getName().isBlank()) {
            return ResponseEntityFactory.badRequest("name is required");
        }
        String base = firstNonBlank(entry.getBaseUrl(), entry.getEndpoint());
        if (base == null || base.isBlank()) {
            return ResponseEntityFactory.badRequest("baseUrl/endpoint is required");
        }
        entry.setBaseUrl(base.trim());
        entry.setEndpoint(base.trim());
        if (entry.getAuthType() == null || entry.getAuthType().isBlank()) {
            entry.setAuthType("none");
        }
        McpClientService.AuthDetection detection = mcpClientService.detectAuth(entry);
        if (detection != null) {
            entry.setAuthType(detection.authType());
            Map<String, Object> authConfig = new LinkedHashMap<>();
            if (detection.authConfig() != null) {
                authConfig.putAll(detection.authConfig());
            }
            if (detection.registrationUrl() != null && !detection.registrationUrl().isBlank()) {
                authConfig.put("registrationEndpoint", detection.registrationUrl());
            }
            if (!authConfig.isEmpty()) entry.setAuthConfig(toJson(authConfig));
            if (entry.getTokenUrl() == null || entry.getTokenUrl().isBlank()) {
                entry.setTokenUrl(detection.tokenUrl());
            }
            if (entry.getAuthorizationUrl() == null || entry.getAuthorizationUrl().isBlank()) {
                entry.setAuthorizationUrl(detection.authorizationUrl());
            }
            entry.setLastStatus(detection.authRequired() ? "auth_required" : "connected");
            entry.setLastError(detection.authRequired() ? "Connect required for " + detection.authType() : null);
        }
        encryptSecrets(entry);
        if (entry.getAuthConfig() != null && !entry.getAuthConfig().isBlank()) {
            entry.setAuthConfig(normalizeAuthConfig(entry.getAuthConfig()));
        } else {
            entry.setAuthConfig("{}");
        }
        McpRegistryEntry saved = mcpRegistryRepository.save(entry);
        Map<String, Object> response = new LinkedHashMap<>(publicView(saved));
        boolean authRequired = detection != null && detection.authRequired();
        boolean hasConnect = !authRequired || hasConnectedCredentials(saved, saved.getAuthType());
        if (!hasConnect) {
            response.put("autoDiscovered", false);
            response.put("nextAction", "connect");
            response.put("detectedAuthType", saved.getAuthType());
        } else {
            try {
                Map<String, Object> discovered = mcpClientService.discoverAndSyncTools(saved.getId());
                McpRegistryEntry updated = mcpRegistryRepository.findById(saved.getId()).orElse(saved);
                response = new LinkedHashMap<>(publicView(updated));
                response.put("autoDiscovered", true);
                response.put("discover", discovered);
            } catch (Exception e) {
                response.put("autoDiscovered", false);
                response.put("discoverError", e.getMessage());
            }
        }
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update MCP server")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody McpRegistryEntry patch) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        merge(existing, patch);
        existing.setEndpoint(firstNonBlank(existing.getBaseUrl(), existing.getEndpoint()));
        encryptSecrets(existing);
        existing.setAuthConfig(normalizeAuthConfig(existing.getAuthConfig()));
        mcpRegistryRepository.update(existing);
        return ResponseEntity.ok(publicView(existing));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete MCP server")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (mcpRegistryRepository.findById(id).isEmpty()) {
            return ResponseEntityFactory.notFound("MCP server not found: " + id);
        }
        mcpClientService.deleteServer(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Test MCP server connectivity")
    public ResponseEntity<?> test(@PathVariable String id) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        Map<String, Object> result;
        try {
            result = mcpClientService.testConnection(existing);
        } catch (Exception e) {
            result = Map.of("ok", false, "error", e.getMessage(), "nextAction", "connect");
        }
        McpClientService.AuthDetection detection = mcpClientService.detectAuth(existing);
        if (detection != null && detection.authType() != null && !detection.authType().isBlank()) {
            existing.setAuthType(detection.authType());
            Map<String, Object> authConfig = parseAuthConfig(existing.getAuthConfig());
            if (detection.authConfig() != null) {
                authConfig.putAll(detection.authConfig());
            }
            if (detection.registrationUrl() != null && !detection.registrationUrl().isBlank()) {
                authConfig.put("registrationEndpoint", detection.registrationUrl());
            }
            existing.setAuthConfig(toJson(authConfig));
            if ((existing.getTokenUrl() == null || existing.getTokenUrl().isBlank()) && detection.tokenUrl() != null) {
                existing.setTokenUrl(detection.tokenUrl());
            }
            if ((existing.getAuthorizationUrl() == null || existing.getAuthorizationUrl().isBlank()) && detection.authorizationUrl() != null) {
                existing.setAuthorizationUrl(detection.authorizationUrl());
            }
        }
        existing.setLastVerifiedAt(System.currentTimeMillis());
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        existing.setLastStatus(ok ? "connected" : (detection != null && detection.authRequired() ? "auth_required" : "error"));
        existing.setLastError(ok ? null : (result.get("error") == null ? "Authentication required" : String.valueOf(result.get("error"))));
        mcpRegistryRepository.update(existing);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/discover")
    @Operation(summary = "Discover MCP tools and sync into runtime tools")
    public ResponseEntity<?> discover(@PathVariable String id) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntityFactory.notFound("MCP server not found: " + id);
        }
        McpClientService.AuthDetection detection = mcpClientService.detectAuth(existing);
        if (detection != null && detection.authRequired()) {
            boolean hasConnect = hasConnectedCredentials(existing, detection.authType());
            if (!hasConnect) {
                existing.setAuthType(detection.authType());
                existing.setLastStatus("auth_required");
                existing.setLastError("Connect required for " + detection.authType());
                mcpRegistryRepository.update(existing);
                return ResponseEntity.badRequest().body(Map.of(
                        "ok", false,
                        "nextAction", "connect",
                        "detectedAuthType", detection.authType(),
                        "message", "Authentication required before discovery"
                ));
            }
        }
        try {
            return ResponseEntity.ok(mcpClientService.discoverAndSyncTools(id));
        } catch (IllegalArgumentException e) {
            existing.setLastStatus("auth_required");
            existing.setLastError(e.getMessage());
            mcpRegistryRepository.update(existing);
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "nextAction", "connect",
                    "detectedAuthType", existing.getAuthType() == null ? "bearer_token" : existing.getAuthType(),
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/connect/api-key")
    @Operation(summary = "Connect MCP using API key header")
    public ResponseEntity<?> connectApiKey(@PathVariable String id, @RequestBody Map<String, Object> body) {
        McpRegistryEntry server = mcpRegistryRepository.findById(id).orElse(null);
        if (server == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        String headerName = body.get("headerName") == null ? "x-api-key" : String.valueOf(body.get("headerName"));
        String apiKey = body.get("apiKey") == null ? null : String.valueOf(body.get("apiKey"));
        if (apiKey == null || apiKey.isBlank()) return ResponseEntityFactory.badRequest("apiKey is required");
        server.setAuthType("api_key_header");
        server.setAuthConfig(toJson(Map.of(
                "headerName", headerName,
                "apiKey", encryptIfConfigured(apiKey)
        )));
        server.setLastStatus("connected");
        server.setLastError(null);
        mcpRegistryRepository.update(server);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("authType", "api_key_header");
        appendAutoDiscover(out, server.getId());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/connect/bearer")
    @Operation(summary = "Connect MCP using bearer token")
    public ResponseEntity<?> connectBearer(@PathVariable String id, @RequestBody Map<String, Object> body) {
        McpRegistryEntry server = mcpRegistryRepository.findById(id).orElse(null);
        if (server == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        String token = body.get("token") == null ? null : String.valueOf(body.get("token"));
        if (token == null || token.isBlank()) return ResponseEntityFactory.badRequest("token is required");
        server.setAuthType("bearer_token");
        server.setEncryptedAccessToken(encryptIfConfigured(token));
        server.setTokenExpiresAt(null);
        server.setLastStatus("connected");
        server.setLastError(null);
        mcpRegistryRepository.update(server);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("authType", "bearer_token");
        appendAutoDiscover(out, server.getId());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/connect/basic")
    @Operation(summary = "Connect MCP using basic auth")
    public ResponseEntity<?> connectBasic(@PathVariable String id, @RequestBody Map<String, Object> body) {
        McpRegistryEntry server = mcpRegistryRepository.findById(id).orElse(null);
        if (server == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        String username = body.get("username") == null ? null : String.valueOf(body.get("username"));
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntityFactory.badRequest("username/password are required");
        }
        server.setAuthType("basic_auth");
        server.setAuthConfig(toJson(Map.of(
                "username", username,
                "password", encryptIfConfigured(password)
        )));
        server.setLastStatus("connected");
        server.setLastError(null);
        mcpRegistryRepository.update(server);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("authType", "basic_auth");
        appendAutoDiscover(out, server.getId());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/connect/oauth/client-credentials")
    @Operation(summary = "Connect MCP using OAuth client credentials")
    public ResponseEntity<?> connectOauthClientCredentials(@PathVariable String id, @RequestBody Map<String, Object> body) {
        McpRegistryEntry server = mcpRegistryRepository.findById(id).orElse(null);
        if (server == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        String clientId = body.get("clientId") == null ? null : String.valueOf(body.get("clientId"));
        String clientSecret = body.get("clientSecret") == null ? null : String.valueOf(body.get("clientSecret"));
        String tokenUrl = body.get("tokenUrl") == null ? null : String.valueOf(body.get("tokenUrl"));
        String scopes = body.get("scopes") == null ? null : String.valueOf(body.get("scopes"));
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank() || tokenUrl == null || tokenUrl.isBlank()) {
            return ResponseEntityFactory.badRequest("clientId/clientSecret/tokenUrl are required");
        }
        server.setAuthType("oauth_client_credentials");
        server.setClientId(clientId);
        server.setEncryptedClientSecret(encryptIfConfigured(clientSecret));
        server.setTokenUrl(tokenUrl);
        server.setScopes(scopes);
        McpAuthService.TokenResult token = mcpAuthService.fetchClientCredentialsToken(server);
        server.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
        server.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
        server.setTokenExpiresAt(token.expiresAt());
        server.setLastStatus("connected");
        server.setLastError(null);
        mcpRegistryRepository.update(server);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("authType", "oauth_client_credentials");
        out.put("expiresAt", token.expiresAt());
        appendAutoDiscover(out, server.getId());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}/tools")
    @Operation(summary = "List cached discovered tools for server")
    public ResponseEntity<?> tools(@PathVariable String id) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        try {
            Object parsed = mcpClientService.getCachedDiscoveredTools(id);
            return ResponseEntity.ok(Map.of(
                    "serverId", id,
                    "count", existing.getDiscoveredToolsCount() == null ? 0 : existing.getDiscoveredToolsCount(),
                    "tools", parsed
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("serverId", id, "count", 0, "tools", java.util.List.of()));
        }
    }

    @PostMapping("/{id}/oauth/authorization-url")
    @Operation(summary = "Build OAuth authorization URL for auth-code flow")
    public ResponseEntity<?> authorizationUrl(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              HttpServletRequest request) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        applyOauthAuthCodeOverrides(existing, body);
        existing.setRedirectUri(buildBackendOauthCallbackUrl(request));
        if (!ensureDynamicClient(existing)) {
            existing.setLastStatus("auth_required");
            existing.setLastError("Dynamic client registration is required but not supported by this provider");
            mcpRegistryRepository.update(existing);
            return ResponseEntity.badRequest().body(authActionPayload(existing, List.of("registrationEndpoint"), "This OAuth provider does not support dynamic client registration."));
        }
        List<String> missing = mcpAuthService.missingAuthCodeAuthorizationFields(existing);
        if (!missing.isEmpty()) {
            existing.setLastStatus("auth_required");
            existing.setLastError("OAuth connect details required: " + String.join(", ", missing));
            mcpRegistryRepository.update(existing);
            return ResponseEntity.badRequest().body(authActionPayload(existing, missing, "Provide OAuth details and retry connect"));
        }
        String authUrl = withState(mcpAuthService.buildAuthorizationUrl(existing), "mcp:" + existing.getId());
        mcpRegistryRepository.update(existing);
        return ResponseEntity.ok(Map.of("ok", true, "authorizationUrl", authUrl));
    }

    @PostMapping("/{id}/oauth/callback")
    @Operation(summary = "Exchange OAuth authorization code for tokens")
    public ResponseEntity<?> oauthCallback(@PathVariable String id, @RequestBody Map<String, Object> body) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        applyOauthAuthCodeOverrides(existing, body);
        if (!ensureDynamicClient(existing)) {
            return ResponseEntity.badRequest().body(authActionPayload(existing, List.of("registrationEndpoint"), "Dynamic client registration is required but not supported by this provider."));
        }
        List<String> missing = mcpAuthService.missingAuthCodeTokenExchangeFields(existing);
        if (!missing.isEmpty()) {
            existing.setLastStatus("auth_required");
            existing.setLastError("OAuth token exchange details required: " + String.join(", ", missing));
            mcpRegistryRepository.update(existing);
            return ResponseEntity.badRequest().body(authActionPayload(existing, missing, "Provide OAuth token exchange details and retry"));
        }
        String code = body.get("code") == null ? null : String.valueOf(body.get("code"));
        if (code == null || code.isBlank()) return ResponseEntityFactory.badRequest("code is required");
        McpAuthService.TokenResult token;
        try {
            token = mcpAuthService.exchangeAuthorizationCode(existing, code);
        } catch (IllegalArgumentException e) {
            existing.setLastStatus("auth_required");
            existing.setLastError(e.getMessage());
            mcpRegistryRepository.update(existing);
            return ResponseEntity.badRequest().body(authActionPayload(existing, List.of(), e.getMessage()));
        }
        existing.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
        existing.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
        existing.setTokenExpiresAt(token.expiresAt());
        existing.setLastStatus("connected");
        existing.setLastError(null);
        mcpRegistryRepository.update(existing);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("expiresAt", token.expiresAt());
        appendAutoDiscover(out, existing.getId());
        return ResponseEntity.ok(out);
    }

    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "OAuth callback endpoint for MCP auth-code flow")
    public ResponseEntity<String> oauthCallbackFromProvider(@RequestParam(required = false) String code,
                                                            @RequestParam(required = false) String state,
                                                            @RequestParam(required = false) String error,
                                                            @RequestParam(name = "error_description", required = false) String errorDescription) {
        String serverId = parseServerIdFromState(state);
        if (serverId == null || serverId.isBlank()) {
            return oauthCallbackHtml(false, "Invalid OAuth state. Please retry connect from the MCP Registry page.");
        }
        McpRegistryEntry existing = mcpRegistryRepository.findById(serverId).orElse(null);
        if (existing == null) {
            return oauthCallbackHtml(false, "MCP server not found for OAuth state.");
        }
        if (error != null && !error.isBlank()) {
            existing.setLastStatus("auth_required");
            existing.setLastError(errorDescription != null && !errorDescription.isBlank() ? errorDescription : error);
            mcpRegistryRepository.update(existing);
            return oauthCallbackHtml(false, "OAuth authorization failed: " + (errorDescription != null ? errorDescription : error));
        }
        if (code == null || code.isBlank()) {
            return oauthCallbackHtml(false, "Authorization code missing from callback.");
        }
        try {
            McpAuthService.TokenResult token = mcpAuthService.exchangeAuthorizationCode(existing, code);
            existing.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            existing.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            existing.setTokenExpiresAt(token.expiresAt());
            existing.setLastStatus("connected");
            existing.setLastError(null);
            mcpRegistryRepository.update(existing);
            appendAutoDiscover(new LinkedHashMap<>(), existing.getId());
            return oauthCallbackHtml(true, "MCP OAuth connected successfully. You can close this tab.");
        } catch (Exception e) {
            existing.setLastStatus("auth_required");
            existing.setLastError(e.getMessage());
            mcpRegistryRepository.update(existing);
            return oauthCallbackHtml(false, "OAuth token exchange failed: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/reauthenticate")
    @Operation(summary = "Require fresh authentication for MCP server")
    public ResponseEntity<?> reauthenticate(@PathVariable String id, HttpServletRequest request) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        existing.setEncryptedAccessToken(null);
        existing.setEncryptedRefreshToken(null);
        existing.setTokenExpiresAt(null);
        String authType = existing.getAuthType() == null ? "bearer_token" : existing.getAuthType();
        if ("oauth_auth_code".equalsIgnoreCase(authType)) {
            existing.setRedirectUri(buildBackendOauthCallbackUrl(request));
            if (!ensureDynamicClient(existing)) {
                existing.setLastStatus("auth_required");
                existing.setLastError("Dynamic client registration is required but not supported by this provider");
                mcpRegistryRepository.update(existing);
                return ResponseEntity.badRequest().body(authActionPayload(existing, List.of("registrationEndpoint"), "DCR is required for zero-manual OAuth flow."));
            }
            List<String> missing = mcpAuthService.missingAuthCodeAuthorizationFields(existing);
            if (!missing.isEmpty()) {
                existing.setLastStatus("auth_required");
                existing.setLastError("OAuth connect details required: " + String.join(", ", missing));
                mcpRegistryRepository.update(existing);
                return ResponseEntity.badRequest().body(authActionPayload(existing, missing, "OAuth defaults missing. Configure backend defaults and retry."));
            }
            String authorizationUrl = withState(mcpAuthService.buildAuthorizationUrl(existing), "mcp:" + existing.getId());
            existing.setLastStatus("reauth_started");
            existing.setLastError(null);
            mcpRegistryRepository.update(existing);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "status", "reauth_started",
                    "nextAction", "open_browser",
                    "detectedAuthType", "oauth_auth_code",
                    "authorizationUrl", authorizationUrl
            ));
        }
        if ("oauth_client_credentials".equalsIgnoreCase(authType)) {
            try {
                McpAuthService.TokenResult token = mcpAuthService.fetchClientCredentialsToken(existing);
                existing.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
                existing.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
                existing.setTokenExpiresAt(token.expiresAt());
                existing.setLastStatus("connected");
                existing.setLastError(null);
                mcpRegistryRepository.update(existing);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("status", "connected");
                out.put("detectedAuthType", "oauth_client_credentials");
                out.put("expiresAt", token.expiresAt());
                appendAutoDiscover(out, existing.getId());
                return ResponseEntity.ok(out);
            } catch (Exception e) {
                existing.setLastStatus("auth_required");
                existing.setLastError(e.getMessage());
                mcpRegistryRepository.update(existing);
                return ResponseEntity.badRequest().body(authActionPayload(existing, List.of(), e.getMessage()));
            }
        }
        existing.setLastStatus("auth_required");
        existing.setLastError("Reauthentication requires updated credentials");
        mcpRegistryRepository.update(existing);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "status", "auth_required",
                "nextAction", "connect",
                "detectedAuthType", authType
        ));
    }

    @PostMapping("/{id}/reconnect")
    @Operation(summary = "Reconnect MCP server and refresh auth status")
    public ResponseEntity<?> reconnect(@PathVariable String id) {
        McpRegistryEntry existing = mcpRegistryRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntityFactory.notFound("MCP server not found: " + id);
        Map<String, Object> test = mcpClientService.testConnection(existing);
        McpClientService.AuthDetection detection = mcpClientService.detectAuth(existing);
        if (detection != null && detection.authType() != null && !detection.authType().isBlank()) {
            existing.setAuthType(detection.authType());
            if ((existing.getTokenUrl() == null || existing.getTokenUrl().isBlank()) && detection.tokenUrl() != null) {
                existing.setTokenUrl(detection.tokenUrl());
            }
            if ((existing.getAuthorizationUrl() == null || existing.getAuthorizationUrl().isBlank()) && detection.authorizationUrl() != null) {
                existing.setAuthorizationUrl(detection.authorizationUrl());
            }
        }
        boolean ok = Boolean.TRUE.equals(test.get("ok"));
        boolean authRequired = detection != null && detection.authRequired() && !hasConnectedCredentials(existing, detection.authType());
        existing.setLastVerifiedAt(System.currentTimeMillis());
        existing.setLastStatus(ok ? "connected" : (authRequired ? "auth_required" : "error"));
        existing.setLastError(ok ? null : String.valueOf(test.getOrDefault("error", authRequired ? "Authentication required" : "Reconnect failed")));
        mcpRegistryRepository.update(existing);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok);
        response.put("status", existing.getLastStatus());
        response.put("detectedAuthType", existing.getAuthType());
        response.put("connectRequired", authRequired);
        if (authRequired) response.put("nextAction", "connect");
        response.put("details", test);
        if (ok && !authRequired) {
            appendAutoDiscover(response, existing.getId());
        }
        return ResponseEntity.ok(response);
    }

    private void appendAutoDiscover(Map<String, Object> response, String serverId) {
        try {
            Map<String, Object> discovered = mcpClientService.discoverAndSyncTools(serverId);
            response.put("autoDiscovered", true);
            response.put("discover", discovered);
        } catch (Exception e) {
            response.put("autoDiscovered", false);
            response.put("discoverError", e.getMessage());
        }
    }

    @PatchMapping("/{id}/tools/{toolName}")
    @Operation(summary = "Set MCP discovered tool enabled state")
    public ResponseEntity<?> setToolEnabled(@PathVariable String id,
                                            @PathVariable String toolName,
                                            @RequestBody Map<String, Object> body) {
        if (!(body.get("enabled") instanceof Boolean enabled)) {
            return ResponseEntityFactory.badRequest("enabled boolean is required");
        }
        McpClientService.ToolToggleResult result = mcpClientService.setDiscoveredToolEnabled(id, toolName, enabled);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "serverId", result.serverId(),
                "toolName", toolName,
                "enabled", result.enabled(),
                "changed", result.changed(),
                "total", result.total()
        ));
    }

    @PostMapping("/{id}/tools/enable-all")
    @Operation(summary = "Enable all discovered MCP tools")
    public ResponseEntity<?> enableAllTools(@PathVariable String id) {
        McpClientService.ToolToggleResult result = mcpClientService.setAllDiscoveredToolsEnabled(id, true);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "serverId", result.serverId(),
                "enabled", true,
                "changed", result.changed(),
                "total", result.total()
        ));
    }

    @PostMapping("/{id}/tools/disable-all")
    @Operation(summary = "Disable all discovered MCP tools")
    public ResponseEntity<?> disableAllTools(@PathVariable String id) {
        McpClientService.ToolToggleResult result = mcpClientService.setAllDiscoveredToolsEnabled(id, false);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "serverId", result.serverId(),
                "enabled", false,
                "changed", result.changed(),
                "total", result.total()
        ));
    }

    private void merge(McpRegistryEntry target, McpRegistryEntry patch) {
        if (patch.getName() != null) target.setName(patch.getName());
        if (patch.getTransportType() != null) target.setTransportType(patch.getTransportType());
        if (patch.getBaseUrl() != null) target.setBaseUrl(patch.getBaseUrl());
        if (patch.getEndpoint() != null) target.setEndpoint(patch.getEndpoint());
        if (patch.getSsePath() != null) target.setSsePath(patch.getSsePath());
        if (patch.getStreamablePath() != null) target.setStreamablePath(patch.getStreamablePath());
        if (patch.getHealthPath() != null) target.setHealthPath(patch.getHealthPath());
        if (patch.getVerifyTls() != null) target.setVerifyTls(patch.getVerifyTls());
        if (patch.getConnectTimeoutMs() != null) target.setConnectTimeoutMs(patch.getConnectTimeoutMs());
        if (patch.getReadTimeoutMs() != null) target.setReadTimeoutMs(patch.getReadTimeoutMs());
        if (patch.getAuthType() != null) target.setAuthType(patch.getAuthType());
        if (patch.getAuthConfig() != null) target.setAuthConfig(patch.getAuthConfig());
        if (patch.getClientId() != null) target.setClientId(patch.getClientId());
        if (patch.getEncryptedClientSecret() != null) target.setEncryptedClientSecret(patch.getEncryptedClientSecret());
        if (patch.getTokenUrl() != null) target.setTokenUrl(patch.getTokenUrl());
        if (patch.getAuthorizationUrl() != null) target.setAuthorizationUrl(patch.getAuthorizationUrl());
        if (patch.getRedirectUri() != null) target.setRedirectUri(patch.getRedirectUri());
        if (patch.getScopes() != null) target.setScopes(patch.getScopes());
        if (patch.getHeadersJson() != null) target.setHeadersJson(patch.getHeadersJson());
        if (patch.getQueryJson() != null) target.setQueryJson(patch.getQueryJson());
        if (patch.getDiscoveredToolsJson() != null) target.setDiscoveredToolsJson(patch.getDiscoveredToolsJson());
        if (patch.getDiscoveredToolsCount() != null) target.setDiscoveredToolsCount(patch.getDiscoveredToolsCount());
        if (patch.isEnabled() != target.isEnabled()) target.setEnabled(patch.isEnabled());
    }

    private void encryptSecrets(McpRegistryEntry entry) {
        entry.setEncryptedClientSecret(encryptIfConfigured(entry.getEncryptedClientSecret()));
        if (entry.getAuthConfig() != null && !entry.getAuthConfig().isBlank()) {
            try {
                Map<String, Object> config = objectMapper.readValue(entry.getAuthConfig(), Map.class);
                Object apiKey = config.get("apiKey");
                if (apiKey instanceof String key && !key.isBlank() && !key.contains(":") && encryptionService.isConfigured()) {
                    config.put("apiKey", encryptionService.encrypt(key));
                }
                Object token = config.get("token");
                if (token instanceof String tok && !tok.isBlank() && !tok.contains(":") && encryptionService.isConfigured()) {
                    config.put("token", encryptionService.encrypt(tok));
                }
                Object password = config.get("password");
                if (password instanceof String pwd && !pwd.isBlank() && !pwd.contains(":") && encryptionService.isConfigured()) {
                    config.put("password", encryptionService.encrypt(pwd));
                }
                Object clientSecret = config.get("clientSecret");
                if (clientSecret instanceof String cs && !cs.isBlank() && !cs.contains(":") && encryptionService.isConfigured()) {
                    config.put("clientSecret", encryptionService.encrypt(cs));
                }
                entry.setAuthConfig(objectMapper.writeValueAsString(config));
            } catch (Exception ignored) {
            }
        }
    }

    private String encryptIfConfigured(String value) {
        if (value == null || value.isBlank()) return value;
        if (!encryptionService.isConfigured()) return value;
        if (value.contains(":")) return value;
        return encryptionService.encrypt(value);
    }

    private String normalizeAuthConfig(String authConfig) {
        if (authConfig == null || authConfig.isBlank()) return "{}";
        try {
            Object parsed = objectMapper.readValue(authConfig, Object.class);
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> publicView(McpRegistryEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", entry.getId());
        view.put("name", entry.getName());
        view.put("transportType", entry.getTransportType());
        view.put("baseUrl", entry.getBaseUrl());
        view.put("endpoint", entry.getEndpoint());
        view.put("ssePath", entry.getSsePath());
        view.put("streamablePath", entry.getStreamablePath());
        view.put("healthPath", entry.getHealthPath());
        view.put("verifyTls", entry.getVerifyTls());
        view.put("connectTimeoutMs", entry.getConnectTimeoutMs());
        view.put("readTimeoutMs", entry.getReadTimeoutMs());
        view.put("authType", entry.getAuthType());
        view.put("authConfig", redactAuthConfig(entry.getAuthConfig()));
        view.put("clientId", entry.getClientId());
        view.put("tokenUrl", entry.getTokenUrl());
        view.put("authorizationUrl", entry.getAuthorizationUrl());
        view.put("redirectUri", entry.getRedirectUri());
        view.put("scopes", entry.getScopes());
        boolean hasAccessToken = entry.getEncryptedAccessToken() != null && !entry.getEncryptedAccessToken().isBlank();
        boolean authRequired = entry.getAuthType() != null && !entry.getAuthType().isBlank() && !"none".equalsIgnoreCase(entry.getAuthType());
        boolean connectRequired = authRequired && !hasConnectedCredentials(entry, entry.getAuthType());
        view.put("hasAccessToken", hasAccessToken);
        view.put("tokenExpiresAt", entry.getTokenExpiresAt());
        view.put("authRequired", authRequired);
        view.put("detectedAuthType", entry.getAuthType());
        view.put("connectRequired", connectRequired);
        view.put("confidence", "detected");
        view.put("canReconnect", true);
        view.put("canReauthenticate", authRequired || (entry.getAuthType() != null && !"none".equalsIgnoreCase(entry.getAuthType())));
        view.put("enabled", entry.isEnabled());
        view.put("lastVerifiedAt", entry.getLastVerifiedAt());
        view.put("lastStatus", entry.getLastStatus());
        view.put("lastError", entry.getLastError());
        view.put("discoveredToolsCount", entry.getDiscoveredToolsCount() == null ? 0 : entry.getDiscoveredToolsCount());
        view.put("createdAt", entry.getCreatedAt());
        view.put("updatedAt", entry.getUpdatedAt());
        return view;
    }

    private Object redactAuthConfig(String authConfig) {
        if (authConfig == null || authConfig.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(authConfig, Map.class);
            if (parsed.containsKey("apiKey")) parsed.put("apiKey", "***");
            if (parsed.containsKey("token")) parsed.put("token", "***");
            if (parsed.containsKey("password")) parsed.put("password", "***");
            if (parsed.containsKey("clientSecret")) parsed.put("clientSecret", "***");
            if (parsed.containsKey("pkceCodeVerifier")) parsed.put("pkceCodeVerifier", "***");
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean hasConnectedCredentials(McpRegistryEntry entry, String authType) {
        if (authType == null || authType.isBlank() || "none".equalsIgnoreCase(authType)) return true;
        return switch (authType.toLowerCase()) {
            case "api_key_header", "basic_auth" -> entry.getAuthConfig() != null && !entry.getAuthConfig().isBlank() && !"{}".equals(entry.getAuthConfig().trim());
            case "bearer_token", "oauth_auth_code", "oauth_client_credentials" -> entry.getEncryptedAccessToken() != null && !entry.getEncryptedAccessToken().isBlank();
            default -> false;
        };
    }

    private void applyOauthAuthCodeOverrides(McpRegistryEntry existing, Map<String, Object> body) {
        if (body == null || body.isEmpty()) return;
        String clientId = asNonBlank(body.get("clientId"));
        String clientSecret = asNonBlank(body.get("clientSecret"));
        String redirectUri = asNonBlank(body.get("redirectUri"));
        String authorizationUrl = asNonBlank(body.get("authorizationUrl"));
        String tokenUrl = asNonBlank(body.get("tokenUrl"));
        String scopes = asNonBlank(body.get("scopes"));
        if (clientId != null) existing.setClientId(clientId);
        if (clientSecret != null) existing.setEncryptedClientSecret(encryptIfConfigured(clientSecret));
        if (redirectUri != null) existing.setRedirectUri(redirectUri);
        if (authorizationUrl != null) existing.setAuthorizationUrl(authorizationUrl);
        if (tokenUrl != null) existing.setTokenUrl(tokenUrl);
        if (scopes != null) existing.setScopes(scopes);
        existing.setAuthType("oauth_auth_code");
    }

    private Map<String, Object> authActionPayload(McpRegistryEntry existing, List<String> missing, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("nextAction", "connect");
        out.put("detectedAuthType", existing.getAuthType() == null || existing.getAuthType().isBlank() ? "oauth_auth_code" : existing.getAuthType());
        out.put("missingFields", missing);
        out.put("message", message);
        return out;
    }

    private String asNonBlank(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String parseServerIdFromState(String state) {
        if (state == null || state.isBlank()) return null;
        if (state.startsWith("mcp:")) return state.substring(4);
        return null;
    }

    private String buildBackendOauthCallbackUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean standardPort = ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
        String origin = standardPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        return origin + "/api/v1/mcp-registry/oauth/callback";
    }

    private ResponseEntity<String> oauthCallbackHtml(boolean ok, String message) {
        String title = ok ? "MCP OAuth Connected" : "MCP OAuth Failed";
        String color = ok ? "#166534" : "#991b1b";
        String escaped = sanitizeHtml(message);
        String html = "<!doctype html><html><head><meta charset='utf-8'/><title>" + title + "</title></head>"
                + "<body style='font-family:Inter,Arial,sans-serif;background:#f8fafc;padding:24px;'>"
                + "<div style='max-width:680px;margin:32px auto;background:white;border:1px solid #e2e8f0;border-radius:12px;padding:20px;'>"
                + "<h2 style='margin:0 0 12px;color:" + color + ";'>" + title + "</h2>"
                + "<p style='margin:0 0 16px;color:#334155;'>" + escaped + "</p>"
                + "<button onclick='window.close()' style='background:#0f62fe;color:white;border:none;border-radius:8px;padding:8px 14px;cursor:pointer;'>Close</button>"
                + "</div></body></html>";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private String sanitizeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String withState(String url, String state) {
        if (url == null || url.isBlank()) return url;
        if (url.contains("state=")) return url;
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "state=" + java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, Object> parseAuthConfig(String authConfig) {
        if (authConfig == null || authConfig.isBlank()) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(authConfig, Map.class));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private boolean ensureDynamicClient(McpRegistryEntry existing) {
        if (!"oauth_auth_code".equalsIgnoreCase(existing.getAuthType())) return true;
        if (existing.getClientId() != null && !existing.getClientId().isBlank()) return true;
        try {
            Map<String, Object> authConfig = parseAuthConfig(existing.getAuthConfig());
            String registrationEndpoint = asNonBlank(authConfig.get("registrationEndpoint"));
            if (registrationEndpoint == null) return false;
            McpAuthService.DynamicClientRegistration reg = mcpAuthService.registerDynamicClient(existing, registrationEndpoint);
            existing.setClientId(reg.clientId());
            if (reg.clientSecret() != null && !reg.clientSecret().isBlank()) {
                existing.setEncryptedClientSecret(encryptIfConfigured(reg.clientSecret()));
            }
            if ((existing.getRedirectUri() == null || existing.getRedirectUri().isBlank()) && reg.redirectUri() != null) {
                existing.setRedirectUri(reg.redirectUri());
            }
            if ((existing.getAuthorizationUrl() == null || existing.getAuthorizationUrl().isBlank()) && reg.authorizationUrl() != null) {
                existing.setAuthorizationUrl(reg.authorizationUrl());
            }
            if ((existing.getTokenUrl() == null || existing.getTokenUrl().isBlank()) && reg.tokenUrl() != null) {
                existing.setTokenUrl(reg.tokenUrl());
            }
            Map<String, Object> merged = parseAuthConfig(existing.getAuthConfig());
            merged.put("registrationEndpoint", registrationEndpoint);
            existing.setAuthConfig(toJson(merged));
            mcpRegistryRepository.update(existing);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
