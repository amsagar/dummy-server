package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ToolAuthProfile;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.ToolAuthProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class HttpToolAuthService {
    private final ObjectMapper objectMapper;
    private final EncryptionService encryptionService;
    private final AgentToolRepository toolRepository;
    private final ToolAuthProfileRepository profileRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    public HttpToolAuthService(ObjectMapper objectMapper,
                               EncryptionService encryptionService,
                               AgentToolRepository toolRepository,
                               ToolAuthProfileRepository profileRepository) {
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
        this.toolRepository = toolRepository;
        this.profileRepository = profileRepository;
    }

    public Optional<ToolAuthProfile> findProfile(String id) {
        return profileRepository.findById(id);
    }

    public Optional<AgentTool> findTool(String id) {
        return toolRepository.findById(id);
    }

    public Map<String, String> resolveHeaders(AgentTool tool) {
        if (tool == null) return Map.of();
        AuthResolution resolution = resolveAuthState(tool);
        if (resolution.state() == null) {
            log.debug("[HttpToolAuthService] auth resolve tool={} source=none reason={}",
                    tool.getName(), resolution.reason());
            return Map.of();
        }
        log.debug("[HttpToolAuthService] auth resolve tool={} source={} authType={}",
                tool.getName(), resolution.source(), resolution.state().authType);
        AuthState state = resolution.state();
        return buildHeaders(state);
    }

    public Map<String, String> resolveQueryParams(AgentTool tool) {
        if (tool == null) return Map.of();
        AuthResolution resolution = resolveAuthState(tool);
        if (resolution.state() == null) {
            return Map.of();
        }
        AuthState state = resolution.state();
        return buildQueryParams(state);
    }

    public Map<String, Object> connectProfile(String profileId, String authType, Map<String, Object> body) {
        ToolAuthProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Auth profile not found: " + profileId));
        applyConnectToProfile(profile, authType, body);
        profileRepository.update(profile);
        log.info("[HttpToolAuthService] profile connect id={} authType={} storedAccessToken={} storedRefreshToken={} tokenExpiresAt={}",
                profileId,
                profile.getAuthType(),
                profile.getEncryptedAccessToken() != null && !profile.getEncryptedAccessToken().isBlank(),
                profile.getEncryptedRefreshToken() != null && !profile.getEncryptedRefreshToken().isBlank(),
                profile.getTokenExpiresAt());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("scope", "profile");
        result.put("id", profileId);
        result.put("authType", profile.getAuthType());
        result.put("tokenExpiresAt", profile.getTokenExpiresAt());
        return result;
    }

    public Map<String, Object> connectToolOverride(String toolId, String authType, Map<String, Object> body) {
        AgentTool tool = toolRepository.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolId));
        applyConnectToTool(tool, authType, body);
        saveTool(tool);
        log.info("[HttpToolAuthService] tool connect id={} authType={} storedAccessToken={} storedRefreshToken={} tokenExpiresAt={}",
                toolId,
                tool.getAuthType(),
                tool.getEncryptedAccessToken() != null && !tool.getEncryptedAccessToken().isBlank(),
                tool.getEncryptedRefreshToken() != null && !tool.getEncryptedRefreshToken().isBlank(),
                tool.getTokenExpiresAt());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("scope", "tool");
        result.put("id", toolId);
        result.put("authType", tool.getAuthType());
        result.put("tokenExpiresAt", tool.getTokenExpiresAt());
        return result;
    }

    public String buildAuthorizationUrlForProfile(String profileId, String callbackUrl, Map<String, Object> body) {
        ToolAuthProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Auth profile not found: " + profileId));
        applyAuthCodeOverrides(profile, body);
        profile.setAuthType("oauth_auth_code");
        profile.setRedirectUri(callbackUrl);
        String url = buildAuthorizationUrl(profile.getAuthorizationUrl(), profile.getClientId(), profile.getRedirectUri(), profile.getScopes(), "profile:" + profile.getId());
        profileRepository.update(profile);
        return url;
    }

    public String buildAuthorizationUrlForTool(String toolId, String callbackUrl, Map<String, Object> body) {
        AgentTool tool = toolRepository.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolId));
        applyAuthCodeOverrides(tool, body);
        tool.setAuthOverrideEnabled(true);
        tool.setAuthType("oauth_auth_code");
        tool.setRedirectUri(callbackUrl);
        String url = buildAuthorizationUrl(tool.getAuthorizationUrl(), tool.getClientId(), tool.getRedirectUri(), tool.getScopes(), "tool:" + tool.getId());
        saveTool(tool);
        return url;
    }

    public Map<String, Object> handleOauthCallback(String state, String code) {
        requireNonBlank(state, "state is required");
        requireNonBlank(code, "code is required");
        if (!state.contains(":")) throw new IllegalArgumentException("Invalid state");
        String[] parts = state.split(":", 2);
        if ("profile".equals(parts[0])) {
            ToolAuthProfile profile = profileRepository.findById(parts[1]).orElseThrow(() -> new IllegalArgumentException("Auth profile not found"));
            TokenResult token = exchangeAuthorizationCode(profile.getTokenUrl(), profile.getClientId(), profile.getEncryptedClientSecret(), profile.getRedirectUri(), code);
            profile.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            profile.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            profile.setTokenExpiresAt(token.expiresAt());
            profileRepository.update(profile);
            return Map.of("ok", true, "scope", "profile", "id", parts[1]);
        }
        if ("tool".equals(parts[0])) {
            AgentTool tool = toolRepository.findById(parts[1]).orElseThrow(() -> new IllegalArgumentException("Tool not found"));
            TokenResult token = exchangeAuthorizationCode(tool.getTokenUrl(), tool.getClientId(), tool.getEncryptedClientSecret(), tool.getRedirectUri(), code);
            tool.setAuthOverrideEnabled(true);
            tool.setAuthType("oauth_auth_code");
            tool.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            tool.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            tool.setTokenExpiresAt(token.expiresAt());
            saveTool(tool);
            return Map.of("ok", true, "scope", "tool", "id", parts[1]);
        }
        throw new IllegalArgumentException("Unsupported state scope");
    }

    public Map<String, Object> reauthenticateProfile(String profileId) {
        ToolAuthProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Auth profile not found"));
        profile.setEncryptedAccessToken(null);
        profile.setEncryptedRefreshToken(null);
        profile.setTokenExpiresAt(null);
        if ("oauth_client_credentials".equalsIgnoreCase(profile.getAuthType())) {
            TokenResult token = fetchClientCredentialsToken(profile.getTokenUrl(), profile.getClientId(), decryptIfNeeded(profile.getEncryptedClientSecret()), profile.getScopes());
            profile.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            profile.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            profile.setTokenExpiresAt(token.expiresAt());
        }
        profileRepository.update(profile);
        return Map.of("ok", true, "scope", "profile", "id", profileId);
    }

    public Map<String, Object> reauthenticateTool(String toolId) {
        AgentTool tool = toolRepository.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found"));
        tool.setEncryptedAccessToken(null);
        tool.setEncryptedRefreshToken(null);
        tool.setTokenExpiresAt(null);
        if ("oauth_client_credentials".equalsIgnoreCase(tool.getAuthType())) {
            TokenResult token = fetchClientCredentialsToken(tool.getTokenUrl(), tool.getClientId(), decryptIfNeeded(tool.getEncryptedClientSecret()), tool.getScopes());
            tool.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            tool.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            tool.setTokenExpiresAt(token.expiresAt());
        }
        saveTool(tool);
        return Map.of("ok", true, "scope", "tool", "id", toolId);
    }

    private Map<String, String> buildHeaders(AuthState state) {
        String type = lower(state.authType);
        if (type == null || "none".equals(type)) return Map.of();
        Map<String, Object> config = parseJson(state.authConfig);
        Map<String, String> headers = new LinkedHashMap<>();
        switch (type) {
            case "api_key_header" -> {
                String name = coalesce(str(config.get("headerName")), "x-api-key");
                String apiKey = decryptIfNeeded(str(config.get("apiKey")));
                requireNonBlank(apiKey, "apiKey missing");
                headers.put(name, apiKey);
            }
            case "api_key_query" -> {
                // This auth mode is applied as query params, not headers.
            }
            case "bearer_token", "oauth_auth_code" -> {
                String token = resolveBearerToken(state, config);
                String headerName = coalesce(str(config.get("headerName")), "Authorization");
                String prefix = coalesce(str(config.get("tokenPrefix")), "Bearer");
                headers.put(headerName, "Authorization".equalsIgnoreCase(headerName) ? prefix + " " + token : token);
            }
            case "basic_auth" -> {
                String username = str(config.get("username"));
                String password = decryptIfNeeded(str(config.get("password")));
                requireNonBlank(username, "username missing");
                requireNonBlank(password, "password missing");
                String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
            case "oauth_client_credentials" -> {
                String token = resolveClientCredentialsToken(state, config);
                headers.put("Authorization", "Bearer " + token);
            }
            case "custom_token_api" -> {
                String token = resolveCustomToken(state, config);
                String headerName = coalesce(str(config.get("tokenHeaderName")), "Authorization");
                String prefix = coalesce(str(config.get("tokenPrefix")), "Bearer");
                headers.put(headerName, "Authorization".equalsIgnoreCase(headerName) ? prefix + " " + token : token);
            }
            default -> {
            }
        }
        return headers;
    }

    private Map<String, String> buildQueryParams(AuthState state) {
        String type = lower(state.authType);
        if (!"api_key_query".equals(type)) return Map.of();
        Map<String, Object> config = parseJson(state.authConfig);
        String paramName = coalesce(str(config.get("paramName")), "api_key");
        String apiKey = decryptIfNeeded(str(config.get("apiKey")));
        requireNonBlank(apiKey, "apiKey missing");
        Map<String, String> query = new LinkedHashMap<>();
        query.put(paramName, apiKey);
        return query;
    }

    private String resolveBearerToken(AuthState state, Map<String, Object> config) {
        long now = System.currentTimeMillis() + 30_000L;
        if (state.encryptedAccessToken != null && !state.encryptedAccessToken.isBlank()) {
            if (state.tokenExpiresAt == null || state.tokenExpiresAt > now) {
                return decryptIfNeeded(state.encryptedAccessToken);
            }
            String refreshed = tryRefreshToken(state);
            if (refreshed != null && !refreshed.isBlank()) return refreshed;
        }
        String token = decryptIfNeeded(str(config.get("token")));
        requireNonBlank(token, "Bearer token missing");
        return token;
    }

    private String resolveClientCredentialsToken(AuthState state, Map<String, Object> config) {
        if (state.encryptedAccessToken != null && !state.encryptedAccessToken.isBlank()
                && state.tokenExpiresAt != null && state.tokenExpiresAt > System.currentTimeMillis() + 30_000L) {
            return decryptIfNeeded(state.encryptedAccessToken);
        }
        String tokenUrl = coalesce(state.tokenUrl, str(config.get("tokenUrl")));
        String clientId = coalesce(state.clientId, str(config.get("clientId")));
        String clientSecret = coalesce(decryptIfNeeded(state.encryptedClientSecret), decryptIfNeeded(str(config.get("clientSecret"))));
        String scopes = coalesce(state.scopes, str(config.get("scopes")));
        TokenResult token = fetchClientCredentialsToken(tokenUrl, clientId, clientSecret, scopes);
        persistToken(state, token);
        return token.accessToken();
    }

    private String resolveCustomToken(AuthState state, Map<String, Object> config) {
        if (state.encryptedAccessToken != null && !state.encryptedAccessToken.isBlank()
                && state.tokenExpiresAt != null && state.tokenExpiresAt > System.currentTimeMillis() + 30_000L) {
            return decryptIfNeeded(state.encryptedAccessToken);
        }
        String requestUrl = coalesce(str(config.get("requestUrl")), state.tokenUrl);
        String requestMethod = coalesce(str(config.get("requestMethod")), "POST");
        Map<String, String> requestHeaders = toStringMap(config.get("requestHeaders"));
        Map<String, String> templateVars = toStringMap(config.get("templateVars"));
        String bodyTemplate = str(config.get("requestBodyTemplate"));
        String body = renderTemplate(bodyTemplate, templateVars);
        requestHeaders.replaceAll((k, v) -> renderTemplate(v, templateVars));
        requestHeaders.putIfAbsent("Content-Type", "application/json");
        requireNonBlank(requestUrl, "custom_token_api requires requestUrl");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(20));
            requestHeaders.forEach(builder::header);
            if ("GET".equalsIgnoreCase(requestMethod)) {
                builder.GET();
            } else {
                builder.method(requestMethod.toUpperCase(), HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("custom token endpoint failed: HTTP " + response.statusCode());
            }
            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            String tokenPath = coalesce(str(config.get("responseTokenPath")), "access_token");
            String token = objectAtPath(parsed, tokenPath);
            requireNonBlank(token, "token missing at responseTokenPath");
            Long expiresAt = null;
            String expiresPath = str(config.get("responseExpiresInPath"));
            if (expiresPath != null && !expiresPath.isBlank()) {
                String expires = objectAtPath(parsed, expiresPath);
                if (expires != null) {
                    try {
                        expiresAt = System.currentTimeMillis() + (Long.parseLong(expires) * 1000L);
                    } catch (Exception ignored) {
                    }
                }
            }
            persistToken(state, new TokenResult(token, null, expiresAt));
            return token;
        } catch (Exception e) {
            throw new IllegalArgumentException("custom_token_api failed: " + e.getMessage(), e);
        }
    }

    private String tryRefreshToken(AuthState state) {
        String refresh = decryptIfNeeded(state.encryptedRefreshToken);
        if (refresh == null || refresh.isBlank() || state.tokenUrl == null || state.tokenUrl.isBlank()) return null;
        String form = "grant_type=refresh_token&refresh_token=" + url(refresh);
        if (state.clientId != null && !state.clientId.isBlank()) {
            form += "&client_id=" + url(state.clientId);
        }
        String clientSecret = decryptIfNeeded(state.encryptedClientSecret);
        if (clientSecret != null && !clientSecret.isBlank()) {
            form += "&client_secret=" + url(clientSecret);
        }
        try {
            TokenResult token = fetchToken(state.tokenUrl, form);
            persistToken(state, token);
            return token.accessToken();
        } catch (Exception e) {
            log.debug("[HttpToolAuthService] refresh failed: {}", e.getMessage());
            return null;
        }
    }

    private void persistToken(AuthState state, TokenResult token) {
        if ("profile".equals(state.scope)) {
            ToolAuthProfile profile = profileRepository.findById(state.id).orElse(null);
            if (profile == null) return;
            profile.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            profile.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            profile.setTokenExpiresAt(token.expiresAt());
            profileRepository.update(profile);
            return;
        }
        AgentTool tool = toolRepository.findById(state.id).orElse(null);
        if (tool == null) return;
        tool.setAuthOverrideEnabled(true);
        tool.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
        tool.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
        tool.setTokenExpiresAt(token.expiresAt());
        saveTool(tool);
    }

    private void applyConnectToProfile(ToolAuthProfile profile, String authType, Map<String, Object> body) {
        String type = lower(authType);
        profile.setAuthType(type);
        switch (type) {
            case "none" -> {
                profile.setAuthConfig("{}");
                profile.setEncryptedAccessToken(null);
                profile.setEncryptedRefreshToken(null);
                profile.setTokenExpiresAt(null);
            }
            case "api_key_header" -> {
                String headerName = coalesce(str(body.get("headerName")), "x-api-key");
                String apiKey = str(body.get("apiKey"));
                requireNonBlank(apiKey, "apiKey is required");
                profile.setAuthConfig(toJson(Map.of("headerName", headerName, "apiKey", encryptIfConfigured(apiKey))));
                profile.setEncryptedAccessToken(null);
                profile.setTokenExpiresAt(null);
            }
            case "api_key_query" -> {
                String paramName = coalesce(str(body.get("paramName")), "api_key");
                String apiKey = str(body.get("apiKey"));
                requireNonBlank(apiKey, "apiKey is required");
                profile.setAuthConfig(toJson(Map.of("paramName", paramName, "apiKey", encryptIfConfigured(apiKey))));
                profile.setEncryptedAccessToken(null);
                profile.setTokenExpiresAt(null);
            }
            case "bearer_token" -> {
                String token = str(body.get("token"));
                requireNonBlank(token, "token is required");
                profile.setAuthConfig("{}");
                profile.setEncryptedAccessToken(encryptIfConfigured(token));
                profile.setEncryptedRefreshToken(null);
                profile.setTokenExpiresAt(null);
            }
            case "basic_auth" -> {
                String username = str(body.get("username"));
                String password = str(body.get("password"));
                requireNonBlank(username, "username is required");
                requireNonBlank(password, "password is required");
                profile.setAuthConfig(toJson(Map.of("username", username, "password", encryptIfConfigured(password))));
                profile.setEncryptedAccessToken(null);
                profile.setTokenExpiresAt(null);
            }
            case "oauth_client_credentials" -> {
                profile.setClientId(str(body.get("clientId")));
                profile.setEncryptedClientSecret(encryptIfConfigured(str(body.get("clientSecret"))));
                profile.setTokenUrl(str(body.get("tokenUrl")));
                profile.setScopes(str(body.get("scopes")));
                TokenResult token = fetchClientCredentialsToken(profile.getTokenUrl(), profile.getClientId(), decryptIfNeeded(profile.getEncryptedClientSecret()), profile.getScopes());
                profile.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
                profile.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
                profile.setTokenExpiresAt(token.expiresAt());
                profile.setAuthConfig("{}");
            }
            case "oauth_auth_code" -> {
                applyAuthCodeOverrides(profile, body);
                profile.setAuthConfig("{}");
            }
            case "custom_token_api" -> {
                Map<String, Object> config = buildCustomTokenConfig(body);
                profile.setAuthConfig(toJson(config));
                profile.setTokenUrl(str(config.get("requestUrl")));
                profile.setEncryptedAccessToken(null);
                profile.setEncryptedRefreshToken(null);
                profile.setTokenExpiresAt(null);
                // Pre-fetch token
                AuthState state = AuthState.forProfile(profile);
                resolveCustomToken(state, config);
            }
            default -> throw new IllegalArgumentException("Unsupported authType: " + authType);
        }
    }

    private void applyConnectToTool(AgentTool tool, String authType, Map<String, Object> body) {
        String type = lower(authType);
        tool.setAuthOverrideEnabled(true);
        tool.setAuthType(type);
        switch (type) {
            case "none" -> {
                tool.setAuthConfig("{}");
                tool.setEncryptedAccessToken(null);
                tool.setEncryptedRefreshToken(null);
                tool.setTokenExpiresAt(null);
            }
            case "api_key_header" -> {
                String headerName = coalesce(str(body.get("headerName")), "x-api-key");
                String apiKey = str(body.get("apiKey"));
                requireNonBlank(apiKey, "apiKey is required");
                tool.setAuthConfig(toJson(Map.of("headerName", headerName, "apiKey", encryptIfConfigured(apiKey))));
                tool.setEncryptedAccessToken(null);
                tool.setTokenExpiresAt(null);
            }
            case "api_key_query" -> {
                String paramName = coalesce(str(body.get("paramName")), "api_key");
                String apiKey = str(body.get("apiKey"));
                requireNonBlank(apiKey, "apiKey is required");
                tool.setAuthConfig(toJson(Map.of("paramName", paramName, "apiKey", encryptIfConfigured(apiKey))));
                tool.setEncryptedAccessToken(null);
                tool.setTokenExpiresAt(null);
            }
            case "bearer_token" -> {
                String token = str(body.get("token"));
                requireNonBlank(token, "token is required");
                tool.setAuthConfig("{}");
                tool.setEncryptedAccessToken(encryptIfConfigured(token));
                tool.setEncryptedRefreshToken(null);
                tool.setTokenExpiresAt(null);
            }
            case "basic_auth" -> {
                String username = str(body.get("username"));
                String password = str(body.get("password"));
                requireNonBlank(username, "username is required");
                requireNonBlank(password, "password is required");
                tool.setAuthConfig(toJson(Map.of("username", username, "password", encryptIfConfigured(password))));
                tool.setEncryptedAccessToken(null);
                tool.setTokenExpiresAt(null);
            }
            case "oauth_client_credentials" -> {
                tool.setClientId(str(body.get("clientId")));
                tool.setEncryptedClientSecret(encryptIfConfigured(str(body.get("clientSecret"))));
                tool.setTokenUrl(str(body.get("tokenUrl")));
                tool.setScopes(str(body.get("scopes")));
                TokenResult token = fetchClientCredentialsToken(tool.getTokenUrl(), tool.getClientId(), decryptIfNeeded(tool.getEncryptedClientSecret()), tool.getScopes());
                tool.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
                tool.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
                tool.setTokenExpiresAt(token.expiresAt());
                tool.setAuthConfig("{}");
            }
            case "oauth_auth_code" -> {
                applyAuthCodeOverrides(tool, body);
                tool.setAuthConfig("{}");
            }
            case "custom_token_api" -> {
                Map<String, Object> config = buildCustomTokenConfig(body);
                tool.setAuthConfig(toJson(config));
                tool.setTokenUrl(str(config.get("requestUrl")));
                tool.setEncryptedAccessToken(null);
                tool.setEncryptedRefreshToken(null);
                tool.setTokenExpiresAt(null);
                AuthState state = AuthState.forTool(tool);
                resolveCustomToken(state, config);
            }
            default -> throw new IllegalArgumentException("Unsupported authType: " + authType);
        }
    }

    private Map<String, Object> buildCustomTokenConfig(Map<String, Object> body) {
        String requestUrl = str(body.get("requestUrl"));
        requireNonBlank(requestUrl, "requestUrl is required");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("requestUrl", requestUrl);
        config.put("requestMethod", coalesce(str(body.get("requestMethod")), "POST"));
        config.put("requestHeaders", body.getOrDefault("requestHeaders", Map.of()));
        config.put("requestBodyTemplate", body.getOrDefault("requestBodyTemplate", ""));
        config.put("templateVars", body.getOrDefault("templateVars", Map.of()));
        config.put("responseTokenPath", coalesce(str(body.get("responseTokenPath")), "access_token"));
        config.put("responseExpiresInPath", coalesce(str(body.get("responseExpiresInPath")), "expires_in"));
        config.put("tokenHeaderName", coalesce(str(body.get("tokenHeaderName")), "Authorization"));
        config.put("tokenPrefix", coalesce(str(body.get("tokenPrefix")), "Bearer"));
        return config;
    }

    private void applyAuthCodeOverrides(ToolAuthProfile profile, Map<String, Object> body) {
        if (body == null) return;
        String clientId = trimToNull(str(body.get("clientId")));
        String clientSecret = trimToNull(str(body.get("clientSecret")));
        String tokenUrl = trimToNull(str(body.get("tokenUrl")));
        String authorizationUrl = trimToNull(str(body.get("authorizationUrl")));
        String redirectUri = trimToNull(str(body.get("redirectUri")));
        String scopes = trimToNull(str(body.get("scopes")));
        if (clientId != null) profile.setClientId(clientId);
        if (clientSecret != null) profile.setEncryptedClientSecret(encryptIfConfigured(clientSecret));
        if (tokenUrl != null) profile.setTokenUrl(tokenUrl);
        if (authorizationUrl != null) profile.setAuthorizationUrl(authorizationUrl);
        if (redirectUri != null) profile.setRedirectUri(redirectUri);
        if (scopes != null) profile.setScopes(scopes);
    }

    private void applyAuthCodeOverrides(AgentTool tool, Map<String, Object> body) {
        if (body == null) return;
        String clientId = trimToNull(str(body.get("clientId")));
        String clientSecret = trimToNull(str(body.get("clientSecret")));
        String tokenUrl = trimToNull(str(body.get("tokenUrl")));
        String authorizationUrl = trimToNull(str(body.get("authorizationUrl")));
        String redirectUri = trimToNull(str(body.get("redirectUri")));
        String scopes = trimToNull(str(body.get("scopes")));
        if (clientId != null) tool.setClientId(clientId);
        if (clientSecret != null) tool.setEncryptedClientSecret(encryptIfConfigured(clientSecret));
        if (tokenUrl != null) tool.setTokenUrl(tokenUrl);
        if (authorizationUrl != null) tool.setAuthorizationUrl(authorizationUrl);
        if (redirectUri != null) tool.setRedirectUri(redirectUri);
        if (scopes != null) tool.setScopes(scopes);
    }

    private String buildAuthorizationUrl(String authorizationUrl, String clientId, String redirectUri, String scopes, String state) {
        requireNonBlank(authorizationUrl, "authorizationUrl is required");
        requireNonBlank(clientId, "clientId is required");
        requireNonBlank(redirectUri, "redirectUri is required");
        StringBuilder sb = new StringBuilder(authorizationUrl);
        sb.append(authorizationUrl.contains("?") ? "&" : "?")
                .append("response_type=code")
                .append("&client_id=").append(url(clientId))
                .append("&redirect_uri=").append(url(redirectUri))
                .append("&state=").append(url(state));
        if (scopes != null && !scopes.isBlank()) {
            sb.append("&scope=").append(url(scopes));
        }
        return sb.toString();
    }

    private TokenResult exchangeAuthorizationCode(String tokenUrl,
                                                  String clientId,
                                                  String encryptedClientSecret,
                                                  String redirectUri,
                                                  String code) {
        String clientSecret = decryptIfNeeded(encryptedClientSecret);
        requireNonBlank(tokenUrl, "tokenUrl is required");
        requireNonBlank(clientId, "clientId is required");
        requireNonBlank(clientSecret, "clientSecret is required");
        requireNonBlank(redirectUri, "redirectUri is required");
        String form = "grant_type=authorization_code"
                + "&code=" + url(code)
                + "&redirect_uri=" + url(redirectUri)
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret);
        return fetchToken(tokenUrl, form);
    }

    private TokenResult fetchClientCredentialsToken(String tokenUrl, String clientId, String clientSecret, String scopes) {
        requireNonBlank(tokenUrl, "tokenUrl is required");
        requireNonBlank(clientId, "clientId is required");
        requireNonBlank(clientSecret, "clientSecret is required");
        String form = "grant_type=client_credentials"
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret)
                + (scopes == null || scopes.isBlank() ? "" : "&scope=" + url(scopes));
        return fetchToken(tokenUrl, form);
    }

    private TokenResult fetchToken(String tokenUrl, String form) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("Token endpoint failed: HTTP " + response.statusCode());
            }
            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            String accessToken = str(parsed.get("access_token"));
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("access_token missing in response");
            }
            String refreshToken = str(parsed.get("refresh_token"));
            Number expiresIn = parsed.get("expires_in") instanceof Number n ? n : null;
            Long expiresAt = expiresIn == null ? null : System.currentTimeMillis() + (expiresIn.longValue() * 1000L);
            return new TokenResult(accessToken, refreshToken, expiresAt);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to fetch token: " + e.getMessage(), e);
        }
    }

    private AuthResolution resolveAuthState(AgentTool tool) {
        if (Boolean.TRUE.equals(tool.getAuthOverrideEnabled()) && tool.getAuthType() != null && !tool.getAuthType().isBlank()) {
            return new AuthResolution(AuthState.forTool(tool), "tool_override", "override_enabled");
        }
        if (tool.getDomainId() == null || tool.getDomainId().isBlank()) {
            return new AuthResolution(null, "none", "missing_domain_id");
        }
        var enabledProfiles = profileRepository.findByDomainId(tool.getDomainId()).stream()
                .filter(ToolAuthProfile::isEnabled)
                .toList();
        if (enabledProfiles.size() == 1) {
            return new AuthResolution(AuthState.forProfile(enabledProfiles.get(0)), "domain_profile", "single_enabled_domain_profile");
        }
        if (enabledProfiles.isEmpty()) {
            return new AuthResolution(null, "none", "no_enabled_domain_profile");
        }
        return new AuthResolution(null, "none", "multiple_enabled_domain_profiles");
    }

    private void saveTool(AgentTool tool) {
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
    }

    private Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Object value) {
        if (!(value instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    private String renderTemplate(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        if (vars == null) return out;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private String objectAtPath(Map<String, Object> source, String path) {
        if (source == null || path == null || path.isBlank()) return null;
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return String.valueOf(current);
    }

    private String encryptIfConfigured(String value) {
        if (value == null || value.isBlank()) return value;
        if (!encryptionService.isConfigured()) return value;
        if (value.contains(":")) return value;
        return encryptionService.encrypt(value);
    }

    private String decryptIfNeeded(String value) {
        if (value == null || value.isBlank()) return value;
        if (!encryptionService.isConfigured()) return value;
        try {
            if (value.contains(":")) return encryptionService.decrypt(value);
        } catch (Exception ignored) {
        }
        return value;
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private String coalesce(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }

    private record TokenResult(String accessToken, String refreshToken, Long expiresAt) {}

    private static final class AuthState {
        private final String scope;
        private final String id;
        private final String authType;
        private final String authConfig;
        private final String clientId;
        private final String encryptedClientSecret;
        private final String tokenUrl;
        private final String scopes;
        private final String encryptedAccessToken;
        private final String encryptedRefreshToken;
        private final Long tokenExpiresAt;

        private AuthState(String scope,
                          String id,
                          String authType,
                          String authConfig,
                          String clientId,
                          String encryptedClientSecret,
                          String tokenUrl,
                          String scopes,
                          String encryptedAccessToken,
                          String encryptedRefreshToken,
                          Long tokenExpiresAt) {
            this.scope = scope;
            this.id = id;
            this.authType = authType;
            this.authConfig = authConfig;
            this.clientId = clientId;
            this.encryptedClientSecret = encryptedClientSecret;
            this.tokenUrl = tokenUrl;
            this.scopes = scopes;
            this.encryptedAccessToken = encryptedAccessToken;
            this.encryptedRefreshToken = encryptedRefreshToken;
            this.tokenExpiresAt = tokenExpiresAt;
        }

        static AuthState forProfile(ToolAuthProfile profile) {
            return new AuthState(
                    "profile",
                    profile.getId(),
                    profile.getAuthType(),
                    profile.getAuthConfig(),
                    profile.getClientId(),
                    profile.getEncryptedClientSecret(),
                    profile.getTokenUrl(),
                    profile.getScopes(),
                    profile.getEncryptedAccessToken(),
                    profile.getEncryptedRefreshToken(),
                    profile.getTokenExpiresAt()
            );
        }

        static AuthState forTool(AgentTool tool) {
            return new AuthState(
                    "tool",
                    tool.getId(),
                    tool.getAuthType(),
                    tool.getAuthConfig(),
                    tool.getClientId(),
                    tool.getEncryptedClientSecret(),
                    tool.getTokenUrl(),
                    tool.getScopes(),
                    tool.getEncryptedAccessToken(),
                    tool.getEncryptedRefreshToken(),
                    tool.getTokenExpiresAt()
            );
        }
    }

    private record AuthResolution(AuthState state, String source, String reason) {}
}
