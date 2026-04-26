package com.pods.agent.api;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ToolAuthProfile;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.ToolAuthProfileRepository;
import com.pods.agent.service.HttpToolAuthService;
import com.pods.agent.service.ToolRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tool-auth")
@Tag(name = "Tool Auth", description = "Manage auth profiles and per-tool auth overrides for HTTP tools")
public class ToolAuthController {
    private final AgentDomainRepository domainRepository;
    private final AgentToolRepository toolRepository;
    private final ToolAuthProfileRepository profileRepository;
    private final HttpToolAuthService authService;
    private final ToolRegistryService toolRegistryService;
    private final ObjectMapper objectMapper;

    public ToolAuthController(AgentDomainRepository domainRepository,
                              AgentToolRepository toolRepository,
                              ToolAuthProfileRepository profileRepository,
                              HttpToolAuthService authService,
                              ToolRegistryService toolRegistryService,
                              ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.toolRepository = toolRepository;
        this.profileRepository = profileRepository;
        this.authService = authService;
        this.toolRegistryService = toolRegistryService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/domains/{domainId}/profiles")
    @Operation(summary = "List auth profiles by tool domain")
    public ResponseEntity<?> listProfiles(@PathVariable String domainId) {
        if (domainRepository.findById(domainId).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + domainId);
        }
        return ResponseEntity.ok(profileRepository.findByDomainId(domainId).stream().map(this::publicProfileView).toList());
    }

    @PostMapping("/domains/{domainId}/profiles")
    @Operation(summary = "Create auth profile for a domain")
    public ResponseEntity<?> createProfile(@PathVariable String domainId, @RequestBody Map<String, Object> body) {
        if (domainRepository.findById(domainId).isEmpty()) {
            return ResponseEntityFactory.notFound("Tool domain not found: " + domainId);
        }
        if (!profileRepository.findByDomainId(domainId).isEmpty()) {
            return ResponseEntityFactory.badRequest("Only one auth profile is allowed per domain");
        }
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) return ResponseEntityFactory.badRequest("name is required");
        ToolAuthProfile profile = ToolAuthProfile.builder()
                .domainId(domainId)
                .name(name.trim())
                .description(str(body.get("description")))
                .authType(coalesce(str(body.get("authType")), "none"))
                .authConfig(normalizeJson(body.get("authConfig")))
                .enabled(body.get("enabled") instanceof Boolean enabled ? enabled : true)
                .build();
        profileRepository.save(profile);
        return ResponseEntity.ok(publicProfileView(profile));
    }

    @PatchMapping("/domains/{domainId}/profiles/{profileId}")
    @Operation(summary = "Update auth profile")
    public ResponseEntity<?> updateProfile(@PathVariable String domainId,
                                           @PathVariable String profileId,
                                           @RequestBody Map<String, Object> body) {
        ToolAuthProfile profile = profileRepository.findById(profileId).orElse(null);
        if (profile == null || !domainId.equals(profile.getDomainId())) {
            return ResponseEntityFactory.notFound("Auth profile not found: " + profileId);
        }
        if (body.containsKey("name")) profile.setName(coalesce(str(body.get("name")), profile.getName()));
        if (body.containsKey("description")) profile.setDescription(str(body.get("description")));
        if (body.containsKey("authType")) profile.setAuthType(coalesce(str(body.get("authType")), profile.getAuthType()));
        if (body.containsKey("authConfig")) profile.setAuthConfig(normalizeJson(body.get("authConfig")));
        if (body.containsKey("enabled") && body.get("enabled") instanceof Boolean enabled) profile.setEnabled(enabled);
        profileRepository.update(profile);
        return ResponseEntity.ok(publicProfileView(profile));
    }

    @DeleteMapping("/domains/{domainId}/profiles/{profileId}")
    @Operation(summary = "Delete auth profile")
    public ResponseEntity<?> deleteProfile(@PathVariable String domainId, @PathVariable String profileId) {
        ToolAuthProfile profile = profileRepository.findById(profileId).orElse(null);
        if (profile == null || !domainId.equals(profile.getDomainId())) {
            return ResponseEntityFactory.notFound("Auth profile not found: " + profileId);
        }
        profileRepository.delete(profileId);
        return ResponseEntity.ok(Map.of("deleted", true, "id", profileId));
    }

    @PatchMapping("/domains/{domainId}/tools/{toolId}/binding")
    @Operation(summary = "Attach auth profile or override settings to tool")
    public ResponseEntity<?> bindTool(@PathVariable String domainId,
                                      @PathVariable String toolId,
                                      @RequestBody Map<String, Object> body) {
        AgentTool tool = toolRepository.findById(toolId).orElse(null);
        if (tool == null || !domainId.equals(tool.getDomainId())) {
            return ResponseEntityFactory.notFound("Tool not found: " + toolId);
        }
        String authProfileId = body.containsKey("authProfileId") ? str(body.get("authProfileId")) : tool.getAuthProfileId();
        Boolean authOverrideEnabled = body.containsKey("authOverrideEnabled")
                ? (body.get("authOverrideEnabled") instanceof Boolean b ? b : false)
                : tool.getAuthOverrideEnabled();
        if (authProfileId != null && authProfileId.isBlank()) authProfileId = null;
        if (Boolean.TRUE.equals(authOverrideEnabled)) {
            authProfileId = null;
        }
        if (authProfileId != null && !authProfileId.isBlank()) {
            ToolAuthProfile profile = profileRepository.findById(authProfileId).orElse(null);
            if (profile == null) {
                return ResponseEntityFactory.badRequest("Auth profile not found: " + authProfileId);
            }
            if (!domainId.equals(profile.getDomainId())) {
                return ResponseEntityFactory.badRequest("Auth profile does not belong to this domain");
            }
        }
        toolRepository.updateAuthBinding(
                tool.getId(),
                authProfileId,
                authOverrideEnabled,
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
        toolRegistryService.refresh();
        AgentTool refreshed = toolRepository.findById(toolId).orElse(tool);
        String mode = Boolean.TRUE.equals(refreshed.getAuthOverrideEnabled()) ? "tool_override"
                : (refreshed.getAuthProfileId() != null && !refreshed.getAuthProfileId().isBlank() ? "shared_profile" : "none");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("toolId", toolId);
        result.put("mode", mode);
        result.put("authProfileId", refreshed.getAuthProfileId());
        result.put("authOverrideEnabled", Boolean.TRUE.equals(refreshed.getAuthOverrideEnabled()));
        result.put("authType", refreshed.getAuthType());
        result.put("tokenConnected", refreshed.getEncryptedAccessToken() != null && !refreshed.getEncryptedAccessToken().isBlank());
        result.put("tokenExpiresAt", refreshed.getTokenExpiresAt());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/profiles/{profileId}/connect/{authType}")
    @Operation(summary = "Connect auth profile credentials")
    public ResponseEntity<?> connectProfile(@PathVariable String profileId,
                                            @PathVariable String authType,
                                            @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> result = authService.connectProfile(profileId, authType, body == null ? Map.of() : body);
            toolRegistryService.refresh();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tools/{toolId}/connect/{authType}")
    @Operation(summary = "Connect per-tool auth override credentials")
    public ResponseEntity<?> connectTool(@PathVariable String toolId,
                                         @PathVariable String authType,
                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> result = authService.connectToolOverride(toolId, authType, body == null ? Map.of() : body);
            toolRegistryService.refresh();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/profiles/{profileId}/oauth/authorization-url")
    @Operation(summary = "Build OAuth authorization URL for profile")
    public ResponseEntity<?> profileAuthorizationUrl(@PathVariable String profileId,
                                                     @RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
        try {
            String callback = buildCallbackUrl(request);
            String url = authService.buildAuthorizationUrlForProfile(profileId, callback, body == null ? Map.of() : body);
            return ResponseEntity.ok(Map.of("ok", true, "authorizationUrl", url));
        } catch (Exception e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tools/{toolId}/oauth/authorization-url")
    @Operation(summary = "Build OAuth authorization URL for tool override")
    public ResponseEntity<?> toolAuthorizationUrl(@PathVariable String toolId,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  HttpServletRequest request) {
        try {
            String callback = buildCallbackUrl(request);
            String url = authService.buildAuthorizationUrlForTool(toolId, callback, body == null ? Map.of() : body);
            return ResponseEntity.ok(Map.of("ok", true, "authorizationUrl", url));
        } catch (Exception e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/profiles/{profileId}/reauthenticate")
    @Operation(summary = "Reauthenticate profile")
    public ResponseEntity<?> reauthProfile(@PathVariable String profileId) {
        try {
            return ResponseEntity.ok(authService.reauthenticateProfile(profileId));
        } catch (Exception e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tools/{toolId}/reauthenticate")
    @Operation(summary = "Reauthenticate tool override")
    public ResponseEntity<?> reauthTool(@PathVariable String toolId) {
        try {
            return ResponseEntity.ok(authService.reauthenticateTool(toolId));
        } catch (Exception e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "OAuth callback endpoint for HTTP tool auth")
    public ResponseEntity<String> oauthCallback(@RequestParam(required = false) String code,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) String error,
                                                @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null && !error.isBlank()) {
            return callbackHtml(false, errorDescription == null ? error : errorDescription);
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return callbackHtml(false, "Missing code/state");
        }
        try {
            authService.handleOauthCallback(state, code);
            return callbackHtml(true, "Tool auth OAuth connected successfully. You can close this tab.");
        } catch (Exception e) {
            return callbackHtml(false, e.getMessage());
        }
    }

    private Map<String, Object> publicProfileView(ToolAuthProfile profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", profile.getId());
        out.put("domainId", profile.getDomainId());
        out.put("name", profile.getName());
        out.put("description", profile.getDescription());
        out.put("authType", profile.getAuthType());
        out.put("authConfig", redactAuthConfig(profile.getAuthConfig()));
        out.put("clientId", profile.getClientId());
        out.put("tokenUrl", profile.getTokenUrl());
        out.put("authorizationUrl", profile.getAuthorizationUrl());
        out.put("redirectUri", profile.getRedirectUri());
        out.put("scopes", profile.getScopes());
        out.put("tokenExpiresAt", profile.getTokenExpiresAt());
        out.put("enabled", profile.isEnabled());
        out.put("createdAt", profile.getCreatedAt());
        out.put("updatedAt", profile.getUpdatedAt());
        return out;
    }

    private Object redactAuthConfig(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, Object> config = objectMapper.readValue(raw, Map.class);
            if (config.containsKey("apiKey")) config.put("apiKey", "***");
            if (config.containsKey("token")) config.put("token", "***");
            if (config.containsKey("password")) config.put("password", "***");
            if (config.containsKey("clientSecret")) config.put("clientSecret", "***");
            return config;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String buildCallbackUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean standardPort = ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
        String origin = standardPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        return origin + "/api/v1/tool-auth/oauth/callback";
    }

    private ResponseEntity<String> callbackHtml(boolean ok, String message) {
        String title = ok ? "Tool Auth Connected" : "Tool Auth Failed";
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

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String coalesce(String a, String b) {
        return (a == null || a.isBlank()) ? b : a;
    }

    private String normalizeJson(Object raw) {
        if (raw == null) return "{}";
        try {
            Object parsed = raw instanceof String text ? objectMapper.readValue(text, Object.class) : raw;
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            return "{}";
        }
    }
}
