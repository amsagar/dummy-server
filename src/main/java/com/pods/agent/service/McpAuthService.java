package com.pods.agent.service;

import com.pods.agent.config.McpOAuthDefaultsProperties;
import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.repository.McpRegistryRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpAuthService {
    private static final String PKCE_VERIFIER_KEY = "pkceCodeVerifier";
    private final ObjectMapper objectMapper;
    private final EncryptionService encryptionService;
    private final McpOAuthDefaultsProperties oauthDefaults;
    private final McpRegistryRepository mcpRegistryRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public McpAuthService(ObjectMapper objectMapper,
                          EncryptionService encryptionService,
                          McpOAuthDefaultsProperties oauthDefaults,
                          McpRegistryRepository mcpRegistryRepository) {
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
        this.oauthDefaults = oauthDefaults;
        this.mcpRegistryRepository = mcpRegistryRepository;
    }

    public Map<String, String> buildAuthHeaders(McpRegistryEntry server) {
        Map<String, String> headers = new HashMap<>();
        String type = server.getAuthType() == null ? "none" : server.getAuthType().trim().toLowerCase();
        Map<String, Object> config = parseJson(server.getAuthConfig());

        return switch (type) {
            case "api_key_header" -> {
                String headerName = stringValue(config.getOrDefault("headerName", "x-api-key"));
                String apiKey = decryptIfNeeded(stringValue(config.getOrDefault("apiKey", null)));
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalArgumentException("apiKey is required for api_key_header auth");
                }
                headers.put(headerName, apiKey);
                yield headers;
            }
            case "bearer_token" -> {
                String token = resolveBearerToken(server, config);
                if (token == null || token.isBlank()) {
                    throw new IllegalArgumentException("Bearer token could not be resolved");
                }
                headers.put("Authorization", "Bearer " + token);
                yield headers;
            }
            case "basic_auth" -> {
                String username = stringValue(config.get("username"));
                String password = decryptIfNeeded(stringValue(config.get("password")));
                if (username == null || username.isBlank() || password == null) {
                    throw new IllegalArgumentException("username/password are required for basic_auth");
                }
                String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
                yield headers;
            }
            case "oauth_client_credentials" -> {
                String token = resolveClientCredentialsToken(server, config);
                headers.put("Authorization", "Bearer " + token);
                yield headers;
            }
            case "oauth_auth_code" -> {
                String token = resolveBearerToken(server, config);
                if (token == null || token.isBlank()) {
                    throw new IllegalArgumentException("OAuth auth-code access token missing. Complete OAuth connect flow.");
                }
                headers.put("Authorization", "Bearer " + token);
                yield headers;
            }
            default -> headers;
        };
    }

    public String buildAuthorizationUrl(McpRegistryEntry server) {
        applyAuthCodeDefaults(server);
        String authUrl = server.getAuthorizationUrl();
        if (authUrl == null || authUrl.isBlank()) {
            throw new IllegalArgumentException("authorizationUrl is required for oauth_auth_code");
        }
        String clientId = server.getClientId();
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required for oauth_auth_code");
        }
        String redirectUri = server.getRedirectUri();
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("redirectUri is required for oauth_auth_code");
        }
        String scopes = server.getScopes() == null ? "" : server.getScopes().trim();
        String codeVerifier = generatePkceCodeVerifier();
        String codeChallenge = toPkceCodeChallenge(codeVerifier);
        setAuthConfigValue(server, PKCE_VERIFIER_KEY, codeVerifier);
        StringBuilder sb = new StringBuilder(authUrl);
        sb.append(authUrl.contains("?") ? "&" : "?")
                .append("response_type=code")
                .append("&client_id=").append(url(clientId))
                .append("&redirect_uri=").append(url(redirectUri))
                .append("&code_challenge=").append(url(codeChallenge))
                .append("&code_challenge_method=S256");
        if (!scopes.isBlank()) {
            sb.append("&scope=").append(url(scopes));
        }
        return sb.toString();
    }

    public List<String> missingAuthCodeAuthorizationFields(McpRegistryEntry server) {
        applyAuthCodeDefaults(server);
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (server.getAuthorizationUrl() == null || server.getAuthorizationUrl().isBlank()) {
            missing.add("authorizationUrl");
        }
        if (server.getClientId() == null || server.getClientId().isBlank()) {
            missing.add("clientId");
        }
        if (server.getRedirectUri() == null || server.getRedirectUri().isBlank()) {
            missing.add("redirectUri");
        }
        return missing;
    }

    public List<String> missingAuthCodeTokenExchangeFields(McpRegistryEntry server) {
        applyAuthCodeDefaults(server);
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (server.getTokenUrl() == null || server.getTokenUrl().isBlank()) {
            missing.add("tokenUrl");
        }
        if (server.getClientId() == null || server.getClientId().isBlank()) {
            missing.add("clientId");
        }
        if (server.getEncryptedClientSecret() == null || server.getEncryptedClientSecret().isBlank()) {
            missing.add("clientSecret");
        }
        if (server.getRedirectUri() == null || server.getRedirectUri().isBlank()) {
            missing.add("redirectUri");
        }
        return missing;
    }

    public TokenResult exchangeAuthorizationCode(McpRegistryEntry server, String code) {
        applyAuthCodeDefaults(server);
        String tokenUrl = server.getTokenUrl();
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new IllegalArgumentException("tokenUrl is required for oauth_auth_code");
        }
        String clientId = server.getClientId();
        String clientSecret = decryptIfNeeded(server.getEncryptedClientSecret());
        String redirectUri = server.getRedirectUri();
        if (clientId == null || clientId.isBlank() || clientSecret == null || redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("clientId/clientSecret/redirectUri are required for oauth_auth_code");
        }
        String form = "grant_type=authorization_code"
                + "&code=" + url(code)
                + "&redirect_uri=" + url(redirectUri)
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret);
        String codeVerifier = getAuthConfigValue(server, PKCE_VERIFIER_KEY);
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form += "&code_verifier=" + url(codeVerifier);
        }
        TokenResult token = fetchToken(tokenUrl, form);
        removeAuthConfigValue(server, PKCE_VERIFIER_KEY);
        return token;
    }

    public TokenResult fetchClientCredentialsToken(McpRegistryEntry server) {
        Map<String, Object> config = parseJson(server.getAuthConfig());
        String tokenUrl = server.getTokenUrl();
        if (tokenUrl == null || tokenUrl.isBlank()) {
            tokenUrl = stringValue(config.get("tokenUrl"));
        }
        String clientId = server.getClientId();
        if (clientId == null || clientId.isBlank()) {
            clientId = stringValue(config.get("clientId"));
        }
        String clientSecret = decryptIfNeeded(server.getEncryptedClientSecret());
        if ((clientSecret == null || clientSecret.isBlank()) && config.get("clientSecret") != null) {
            clientSecret = decryptIfNeeded(stringValue(config.get("clientSecret")));
        }
        String scopes = server.getScopes();
        if ((scopes == null || scopes.isBlank()) && config.get("scopes") != null) {
            scopes = stringValue(config.get("scopes"));
        }
        if (tokenUrl == null || tokenUrl.isBlank() || clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("tokenUrl/clientId/clientSecret are required for oauth_client_credentials");
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret)
                + (scopes == null || scopes.isBlank() ? "" : "&scope=" + url(scopes));
        return fetchToken(tokenUrl, form);
    }

    public void applyAuthCodeDefaults(McpRegistryEntry server) {
        if (server == null) return;
        if (isBlank(server.getClientId())) {
            server.setClientId(trimToNull(oauthDefaults.getDefaultClientId()));
        }
        if (isBlank(server.getRedirectUri())) {
            server.setRedirectUri(trimToNull(oauthDefaults.getDefaultRedirectUri()));
        }
        if (isBlank(server.getScopes())) {
            server.setScopes(trimToNull(oauthDefaults.getDefaultScopes()));
        }
        if (isBlank(server.getAuthorizationUrl())) {
            server.setAuthorizationUrl(trimToNull(oauthDefaults.getDefaultAuthorizationUrl()));
        }
        if (isBlank(server.getTokenUrl())) {
            server.setTokenUrl(trimToNull(oauthDefaults.getDefaultTokenUrl()));
        }
        if (isBlank(server.getEncryptedClientSecret())) {
            String secret = trimToNull(oauthDefaults.getDefaultClientSecret());
            if (secret != null) {
                server.setEncryptedClientSecret(encryptIfConfigured(secret));
            }
        }
    }

    public DynamicClientRegistration registerDynamicClient(McpRegistryEntry server, String registrationEndpoint) {
        try {
            String redirectUri = trimToNull(server.getRedirectUri());
            if (redirectUri == null) {
                redirectUri = trimToNull(oauthDefaults.getDefaultRedirectUri());
            }
            if (redirectUri == null) {
                throw new IllegalArgumentException("redirectUri is required for dynamic client registration");
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("client_name", "pods-ai-agent-mcp");
            payload.put("redirect_uris", List.of(redirectUri));
            payload.put("grant_types", List.of("authorization_code", "refresh_token"));
            payload.put("response_types", List.of("code"));
            payload.put("token_endpoint_auth_method", "client_secret_post");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(registrationEndpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalArgumentException("DCR failed: HTTP " + res.statusCode());
            }
            Map<?, ?> parsed = objectMapper.readValue(res.body(), Map.class);
            String clientId = stringValue(parsed.get("client_id"));
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("DCR response missing client_id");
            }
            String clientSecret = stringValue(parsed.get("client_secret"));
            String registeredRedirectUri = null;
            Object redirectUris = parsed.get("redirect_uris");
            if (redirectUris instanceof List<?> list && !list.isEmpty()) {
                registeredRedirectUri = String.valueOf(list.get(0));
            }
            return new DynamicClientRegistration(
                    clientId,
                    clientSecret,
                    registeredRedirectUri == null ? redirectUri : registeredRedirectUri,
                    trimToNull(server.getAuthorizationUrl()),
                    trimToNull(server.getTokenUrl())
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Dynamic client registration failed: " + e.getMessage(), e);
        }
    }

    private String resolveBearerToken(McpRegistryEntry server, Map<String, Object> config) {
        long nowWithSkew = System.currentTimeMillis() + 30_000L;
        if (server.getEncryptedAccessToken() != null && !server.getEncryptedAccessToken().isBlank()) {
            if (server.getTokenExpiresAt() == null || server.getTokenExpiresAt() > nowWithSkew) {
                return decryptIfNeeded(server.getEncryptedAccessToken());
            }
            String refreshed = tryRefreshAccessToken(server);
            if (refreshed != null && !refreshed.isBlank()) {
                return refreshed;
            }
        }
        return decryptIfNeeded(stringValue(config.get("token")));
    }

    private String tryRefreshAccessToken(McpRegistryEntry server) {
        String refreshToken = decryptIfNeeded(server.getEncryptedRefreshToken());
        if (refreshToken == null || refreshToken.isBlank()) return null;
        String tokenUrl = trimToNull(server.getTokenUrl());
        if (tokenUrl == null) return null;
        String clientId = trimToNull(server.getClientId());
        String clientSecret = trimToNull(decryptIfNeeded(server.getEncryptedClientSecret()));
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + url(refreshToken);
        if (clientId != null) {
            form += "&client_id=" + url(clientId);
        }
        if (clientSecret != null) {
            form += "&client_secret=" + url(clientSecret);
        }
        try {
            TokenResult token = fetchToken(tokenUrl, form);
            server.setEncryptedAccessToken(encryptIfConfigured(token.accessToken()));
            if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
                server.setEncryptedRefreshToken(encryptIfConfigured(token.refreshToken()));
            }
            server.setTokenExpiresAt(token.expiresAt());
            mcpRegistryRepository.update(server);
            return token.accessToken();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveClientCredentialsToken(McpRegistryEntry server, Map<String, Object> config) {
        if (server.getEncryptedAccessToken() != null && !server.getEncryptedAccessToken().isBlank()
                && server.getTokenExpiresAt() != null
                && server.getTokenExpiresAt() > System.currentTimeMillis() + 30_000L) {
            return decryptIfNeeded(server.getEncryptedAccessToken());
        }

        TokenResult token = fetchClientCredentialsToken(server);
        return token.accessToken();
    }

    private TokenResult fetchToken(String tokenUrl, String form) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalArgumentException("Token endpoint failed: HTTP " + res.statusCode());
            }
            Map<?, ?> parsed = objectMapper.readValue(res.body(), Map.class);
            String access = stringValue(parsed.get("access_token"));
            if (access == null || access.isBlank()) {
                throw new IllegalArgumentException("Token endpoint did not return access_token");
            }
            String refresh = stringValue(parsed.get("refresh_token"));
            Number expiresIn = parsed.get("expires_in") instanceof Number n ? n : null;
            Long expiresAt = expiresIn == null ? null : (System.currentTimeMillis() + (expiresIn.longValue() * 1000L));
            return new TokenResult(access, refresh, expiresAt);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to fetch OAuth token: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String decryptIfNeeded(String value) {
        if (value == null || value.isBlank()) return value;
        if (!encryptionService.isConfigured()) return value;
        try {
            if (value.contains(":")) {
                return encryptionService.decrypt(value);
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private String encryptIfConfigured(String value) {
        if (value == null || value.isBlank()) return value;
        if (!encryptionService.isConfigured()) return value;
        if (value.contains(":")) return value;
        return encryptionService.encrypt(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String generatePkceCodeVerifier() {
        byte[] random = new byte[48];
        secureRandom.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String toPkceCodeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to generate PKCE challenge", e);
        }
    }

    private String getAuthConfigValue(McpRegistryEntry server, String key) {
        Map<String, Object> config = parseJson(server.getAuthConfig());
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void setAuthConfigValue(McpRegistryEntry server, String key, String value) {
        Map<String, Object> config = new HashMap<>(parseJson(server.getAuthConfig()));
        config.put(key, value);
        try {
            server.setAuthConfig(objectMapper.writeValueAsString(config));
        } catch (Exception ignored) {
        }
    }

    private void removeAuthConfigValue(McpRegistryEntry server, String key) {
        Map<String, Object> config = new HashMap<>(parseJson(server.getAuthConfig()));
        if (config.remove(key) == null) return;
        try {
            server.setAuthConfig(objectMapper.writeValueAsString(config));
        } catch (Exception ignored) {
        }
    }

    public record TokenResult(String accessToken, String refreshToken, Long expiresAt) {}
    public record DynamicClientRegistration(String clientId,
                                            String clientSecret,
                                            String redirectUri,
                                            String authorizationUrl,
                                            String tokenUrl) {}
}
