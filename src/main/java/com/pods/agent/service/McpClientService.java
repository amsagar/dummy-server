package com.pods.agent.service;

import com.pods.agent.domain.AgentDomain;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.McpRegistryEntry;
import com.pods.agent.repository.AgentDomainRepository;
import com.pods.agent.repository.AgentToolRepository;
import com.pods.agent.repository.McpRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class McpClientService {
    private final ObjectMapper objectMapper;
    private final McpAuthService mcpAuthService;
    private final McpRegistryRepository mcpRegistryRepository;
    private final AgentDomainRepository domainRepository;
    private final AgentToolRepository toolRepository;
    private final ToolRegistryService toolRegistryService;
    private final Map<String, String> githubLoginCache = new ConcurrentHashMap<>();

    public McpClientService(ObjectMapper objectMapper,
                            McpAuthService mcpAuthService,
                            McpRegistryRepository mcpRegistryRepository,
                            AgentDomainRepository domainRepository,
                            AgentToolRepository toolRepository,
                            ToolRegistryService toolRegistryService) {
        this.objectMapper = objectMapper;
        this.mcpAuthService = mcpAuthService;
        this.mcpRegistryRepository = mcpRegistryRepository;
        this.domainRepository = domainRepository;
        this.toolRepository = toolRepository;
        this.toolRegistryService = toolRegistryService;
    }

    public Map<String, Object> testConnection(McpRegistryEntry server) {
        String healthUrl = resolveHealthUrl(server);
        HttpClient client = buildHttpClient(server);
        Map<String, String> authHeaders = mcpAuthService.buildAuthHeaders(server);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofMillis(server.getReadTimeoutMs() == null ? 30_000 : server.getReadTimeoutMs()))
                    .GET();
            authHeaders.forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return Map.of(
                    "ok", ok,
                    "statusCode", response.statusCode(),
                    "healthUrl", healthUrl,
                    "bodyPreview", truncate(response.body(), 500)
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "statusCode", 0,
                    "healthUrl", healthUrl,
                    "error", e.getMessage()
            );
        }
    }

    public AuthDetection detectAuth(McpRegistryEntry server) {
        HttpClient client = buildHttpClient(server);
        try {
            HttpResponse<String> response = null;
            for (String probeUrl : resolveRpcCandidates(server)) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(probeUrl))
                        .timeout(Duration.ofMillis(server.getReadTimeoutMs() == null ? 30_000 : server.getReadTimeoutMs()))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":\"auth-probe\",\"method\":\"tools/list\",\"params\":{}}"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 404) {
                    break;
                }
            }
            if (response == null) {
                return new AuthDetection("bearer_token", Map.of("detectedFrom", "probe_failed"), null, null, null, true, true, false, "low");
            }
            String authHeader = response.headers().firstValue("www-authenticate").orElse("").toLowerCase(Locale.ROOT);
            String bodyRaw = response.body() == null ? "" : response.body();
            String body = bodyRaw.toLowerCase(Locale.ROOT);
            boolean jsonRpcError = hasJsonRpcErrorPayload(bodyRaw);

            if (response.statusCode() >= 200 && response.statusCode() < 300 && !jsonRpcError) {
                return new AuthDetection("none", Map.of("detectedFrom", "success_response"), null, null, null, false, false, false, "high");
            }

            OAuthMetadata metadata = fetchOAuthMetadata(client, server);
            if (metadata != null && metadata.tokenUrl() != null && !metadata.tokenUrl().isBlank()) {
                String type = (metadata.authorizationUrl() != null && !metadata.authorizationUrl().isBlank())
                        ? "oauth_auth_code"
                        : "oauth_client_credentials";
                return new AuthDetection(
                        type,
                        Map.of("detectedFrom", "oauth_well_known"),
                        metadata.tokenUrl(),
                        metadata.authorizationUrl(),
                        metadata.registrationUrl(),
                        true,
                        true,
                        metadata.registrationUrl() != null && !metadata.registrationUrl().isBlank(),
                        "high"
                );
            }

            if (authHeader.contains("basic")) {
                return new AuthDetection("basic_auth", Map.of("detectedFrom", "www-authenticate"), null, null, null, true, true, false, "high");
            }
            if (authHeader.contains("bearer")) {
                return new AuthDetection("bearer_token", Map.of("detectedFrom", "www-authenticate"), null, null, null, true, true, false, "high");
            }
            if (body.contains("api key") || body.contains("x-api-key") || body.contains("apikey")) {
                return new AuthDetection("api_key_header", Map.of("headerName", "x-api-key", "detectedFrom", "error_body"), null, null, null, true, true, false, "medium");
            }
            if (response.statusCode() == 401 || jsonRpcError || containsAuthErrorHint(body)) {
                return new AuthDetection("bearer_token", Map.of("detectedFrom", "401_fallback"), null, null, null, true, true, false, "low");
            }
            return new AuthDetection("none", Map.of("detectedFrom", "fallback"), null, null, null, false, false, false, "low");
        } catch (Exception e) {
            OAuthMetadata metadata = null;
            try {
                metadata = fetchOAuthMetadata(client, server);
            } catch (Exception ignored) {
            }
            if (metadata != null && metadata.tokenUrl() != null && !metadata.tokenUrl().isBlank()) {
                String type = (metadata.authorizationUrl() != null && !metadata.authorizationUrl().isBlank())
                        ? "oauth_auth_code"
                        : "oauth_client_credentials";
                return new AuthDetection(
                        type,
                        Map.of("detectedFrom", "oauth_well_known_after_error"),
                        metadata.tokenUrl(),
                        metadata.authorizationUrl(),
                        metadata.registrationUrl(),
                        true,
                        true,
                        metadata.registrationUrl() != null && !metadata.registrationUrl().isBlank(),
                        "medium"
                );
            }
            return new AuthDetection("bearer_token", Map.of("detectedFrom", "network_error", "error", e.getMessage()), null, null, null, true, true, false, "low");
        }
    }

    public List<Map<String, Object>> listTools(McpRegistryEntry server) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        );
        Map<String, Object> response = jsonRpc(server, payload);
        Object result = response.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) return List.of();
        Object tools = resultMap.get("tools");
        if (!(tools instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object t : list) {
            if (t instanceof Map<?, ?> tm) {
                out.add(new LinkedHashMap<>((Map<String, Object>) tm));
            }
        }
        return out;
    }

    public List<Map<String, Object>> getCachedDiscoveredTools(String serverId) {
        McpRegistryEntry server = mcpRegistryRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));
        return normalizeCachedTools(server);
    }

    public ToolToggleResult setDiscoveredToolEnabled(String serverId, String toolName, boolean enabled) {
        McpRegistryEntry server = mcpRegistryRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));
        List<Map<String, Object>> tools = normalizeCachedTools(server);
        int changed = 0;
        for (Map<String, Object> tool : tools) {
            String name = asText(tool.get("name"));
            if (name != null && name.equals(toolName)) {
                tool.put("enabled", enabled);
                changed++;
            }
        }
        if (changed == 0) {
            throw new IllegalArgumentException("MCP tool not found in cache: " + toolName);
        }
        server.setDiscoveredToolsJson(writeJson(tools));
        server.setDiscoveredToolsCount(tools.size());
        server.setUpdatedAt(System.currentTimeMillis());
        mcpRegistryRepository.update(server);
        syncDomainToolEnabled(server, toolName, enabled);
        toolRegistryService.refresh();
        return new ToolToggleResult(serverId, changed, tools.size(), enabled);
    }

    public ToolToggleResult setAllDiscoveredToolsEnabled(String serverId, boolean enabled) {
        McpRegistryEntry server = mcpRegistryRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));
        List<Map<String, Object>> tools = normalizeCachedTools(server);
        for (Map<String, Object> tool : tools) {
            tool.put("enabled", enabled);
        }
        server.setDiscoveredToolsJson(writeJson(tools));
        server.setDiscoveredToolsCount(tools.size());
        server.setUpdatedAt(System.currentTimeMillis());
        mcpRegistryRepository.update(server);
        syncAllDomainToolEnabled(server, enabled);
        toolRegistryService.refresh();
        return new ToolToggleResult(serverId, tools.size(), tools.size(), enabled);
    }

    public String callTool(String serverId, String toolName, String userText) {
        McpRegistryEntry server = mcpRegistryRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));
        if (!server.isEnabled()) {
            throw new IllegalArgumentException("MCP server is disabled: " + server.getName());
        }
        Map<String, Boolean> enabledByName = cachedEnabledByToolName(server);
        if (!enabledByName.containsKey(toolName)) {
            throw new IllegalArgumentException("MCP tool is not available on server: " + toolName);
        }
        if (enabledByName.containsKey(toolName) && !Boolean.TRUE.equals(enabledByName.get(toolName))) {
            throw new IllegalArgumentException("MCP tool is disabled: " + toolName);
        }
        Map<String, Object> args;
        try {
            args = objectMapper.readValue(userText, Map.class);
        } catch (Exception ignored) {
            args = Map.of("query", userText);
        }
        String directGithub = tryDirectGithubOwnedRepoSearch(server, toolName, args);
        if (directGithub != null) {
            return directGithub;
        }
        args = enrichArgumentsForKnownTools(server, toolName, args);
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", args
                )
        );
        Map<String, Object> response = jsonRpc(server, payload);
        if (response.get("error") instanceof Map<?, ?> err) {
            return "{\"error\":" + quote(String.valueOf(err)) + "}";
        }
        try {
            return objectMapper.writeValueAsString(response.get("result"));
        } catch (Exception e) {
            return String.valueOf(response.get("result"));
        }
    }

    private String tryDirectGithubOwnedRepoSearch(McpRegistryEntry server, String toolName, Map<String, Object> args) {
        if (!isGithubServer(server)) return null;
        String normalizedTool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (!normalizedTool.contains("search_repositories")) return null;
        String query = asText(args == null ? null : args.get("query"));
        if (!looksLikeMyProjectsQuery(query)) return null;
        String login = resolveGithubLogin(server);
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Unable to resolve authenticated GitHub user. Please reconnect GitHub and retry.");
        }
        try {
            Map<String, String> headers = mcpAuthService.buildAuthHeaders(server);
            String auth = headers.get("Authorization");
            if (auth == null || auth.isBlank()) {
                throw new IllegalArgumentException("GitHub token is missing.");
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/repos?per_page=100&type=owner&sort=updated"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", auth)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> res = buildHttpClient(server).send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalArgumentException("GitHub user repo lookup failed: HTTP " + res.statusCode());
            }
            Object parsed = objectMapper.readValue(res.body(), Object.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scope", "user:" + login);
            out.put("repositories", parsed);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalArgumentException("GitHub user repo lookup failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> discoverAndSyncTools(String serverId) {
        McpRegistryEntry server = mcpRegistryRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));
        List<Map<String, Object>> discoveredRaw = listTools(server);
        Map<String, Boolean> previousEnabled = cachedEnabledByToolName(server);
        List<Map<String, Object>> discovered = new ArrayList<>();
        for (Map<String, Object> raw : discoveredRaw) {
            Map<String, Object> normalized = new LinkedHashMap<>(raw);
            String name = asText(normalized.get("name"));
            boolean enabled = name == null || !previousEnabled.containsKey(name) || Boolean.TRUE.equals(previousEnabled.get(name));
            normalized.put("enabled", enabled);
            discovered.add(normalized);
        }
        String domainId = ensureDomain(server);
        List<AgentTool> existing = toolRepository.findByDomainId(domainId);
        int created = 0;
        int updated = 0;
        int disabledStale = 0;
        Set<String> activeLocalNames = new LinkedHashSet<>();
        for (Map<String, Object> toolDef : discovered) {
            String remoteToolName = String.valueOf(toolDef.getOrDefault("name", "tool_" + UUID.randomUUID()));
            String localName = "mcp_" + sanitizeName(remoteToolName);
            activeLocalNames.add(localName);
            boolean enabled = toolDef.get("enabled") == null || Boolean.TRUE.equals(toolDef.get("enabled"));
            Optional<AgentTool> existingTool = existing.stream().filter(t -> localName.equals(t.getName())).findFirst();

            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("mcpServerId", serverId);
            binding.put("mcpToolName", remoteToolName);
            binding.put("inputSchema", toolDef.getOrDefault("inputSchema", Map.of()));
            binding.put("headers", Map.of("x-mcp-server", server.getName()));

            if (existingTool.isPresent()) {
                AgentTool t = existingTool.get();
                t.setDescription(String.valueOf(toolDef.getOrDefault("description", "MCP tool: " + remoteToolName)));
                t.setExecutionKind("integration");
                t.setPermissionScope("integration");
                t.setMethod("POST");
                t.setHost(server.getBaseUrl() != null && !server.getBaseUrl().isBlank() ? server.getBaseUrl() : server.getEndpoint());
                t.setEndpoint(server.getStreamablePath() != null ? server.getStreamablePath() : "/mcp");
                t.setRequestSchema(writeJson(binding));
                t.setResponseSchema("{}");
                t.setSampleRequest("{\"query\":\"\"}");
                t.setSampleResponse("{}");
                t.setEnabled(enabled);
                toolRepository.update(t);
                updated++;
            } else {
                AgentTool t = AgentTool.builder()
                        .domainId(domainId)
                        .name(localName)
                        .description(String.valueOf(toolDef.getOrDefault("description", "MCP tool: " + remoteToolName)))
                        .sourceType("openapi_import")
                        .executionKind("integration")
                        .permissionScope("integration")
                        .requiresApproval(false)
                        .method("POST")
                        .host(server.getBaseUrl() != null && !server.getBaseUrl().isBlank() ? server.getBaseUrl() : server.getEndpoint())
                        .endpoint(server.getStreamablePath() != null ? server.getStreamablePath() : "/mcp")
                        .requestSchema(writeJson(binding))
                        .responseSchema("{}")
                        .sampleRequest("{\"query\":\"\"}")
                        .sampleResponse("{}")
                        .enabled(enabled)
                        .build();
                toolRepository.save(t);
                created++;
            }
        }
        for (AgentTool tool : existing) {
            if (tool.getName() == null || !tool.getName().startsWith("mcp_")) continue;
            if (activeLocalNames.contains(tool.getName())) continue;
            if (!tool.isEnabled()) continue;
            tool.setEnabled(false);
            toolRepository.update(tool);
            disabledStale++;
        }
        server.setDiscoveredToolsJson(writeJson(discovered));
        server.setDiscoveredToolsCount(discovered.size());
        server.setLastVerifiedAt(System.currentTimeMillis());
        server.setLastStatus("connected");
        server.setLastError(null);
        mcpRegistryRepository.update(server);
        toolRegistryService.refresh();
        return Map.of(
                "serverId", serverId,
                "discoveredCount", discovered.size(),
                "created", created,
                "updated", updated,
                "disabledStale", disabledStale
        );
    }

    private Map<String, Object> jsonRpc(McpRegistryEntry server, Map<String, Object> payload) {
        HttpClient client = buildHttpClient(server);
        Map<String, String> authHeaders = mcpAuthService.buildAuthHeaders(server);
        try {
            String body = objectMapper.writeValueAsString(payload);
            IllegalArgumentException lastError = null;
            for (String endpoint : resolveRpcCandidates(server)) {
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofMillis(server.getReadTimeoutMs() == null ? 30_000 : server.getReadTimeoutMs()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                authHeaders.forEach(req::header);
                HttpResponse<String> response = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    lastError = new IllegalArgumentException("MCP request failed HTTP 404 at " + endpoint);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("MCP request failed HTTP " + response.statusCode() + ": " + truncate(response.body(), 1000));
                }
                Map<String, Object> parsed = parseMcpResponseBody(response.body());
                if (parsed.get("error") != null) {
                    throw new IllegalArgumentException("MCP JSON-RPC error: " + describeJsonRpcError(parsed.get("error")));
                }
                return parsed;
            }
            if (lastError != null) {
                throw lastError;
            }
            throw new IllegalArgumentException("MCP request failed on all RPC endpoints");
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP call failed: " + e.getMessage(), e);
        }
    }

    private HttpClient buildHttpClient(McpRegistryEntry server) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(server.getConnectTimeoutMs() == null ? 10_000 : server.getConnectTimeoutMs()))
                .build();
    }

    private String resolveRpcUrl(McpRegistryEntry server) {
        String base = server.getBaseUrl() != null && !server.getBaseUrl().isBlank() ? server.getBaseUrl() : server.getEndpoint();
        String path = server.getStreamablePath();
        if (path == null || path.isBlank()) path = "/mcp";
        if (base.endsWith("/") && path.startsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private List<String> resolveRpcCandidates(McpRegistryEntry server) {
        String base = server.getBaseUrl() != null && !server.getBaseUrl().isBlank() ? server.getBaseUrl() : server.getEndpoint();
        if (base == null || base.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addEndpointCandidate(candidates, base, server.getStreamablePath());
        addEndpointCandidate(candidates, base, server.getSsePath());
        addEndpointCandidate(candidates, base, "/mcp");
        addEndpointCandidate(candidates, base, "/sse");
        addEndpointCandidate(candidates, base, "/streamable");
        addEndpointCandidate(candidates, base, "/api/mcp");
        addEndpointCandidate(candidates, base, "/");
        return new ArrayList<>(candidates);
    }

    private void addEndpointCandidate(LinkedHashSet<String> out, String base, String path) {
        if (path == null || path.isBlank()) return;
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if ("/".equals(normalizedPath)) {
            out.add(normalizedBase + "/");
            return;
        }
        out.add(normalizedBase + normalizedPath);
    }

    private String resolveHealthUrl(McpRegistryEntry server) {
        String base = server.getBaseUrl() != null && !server.getBaseUrl().isBlank() ? server.getBaseUrl() : server.getEndpoint();
        String path = server.getHealthPath();
        if (path == null || path.isBlank()) path = "/health";
        if (base.endsWith("/") && path.startsWith("/")) return base.substring(0, base.length() - 1) + path;
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private OAuthMetadata fetchOAuthMetadata(HttpClient client, McpRegistryEntry server) {
        String base = server.getBaseUrl() != null && !server.getBaseUrl().isBlank() ? server.getBaseUrl() : server.getEndpoint();
        if (base == null || base.isBlank()) return null;
        String[] candidates = new String[]{
                "/.well-known/oauth-authorization-server",
                "/.well-known/openid-configuration"
        };
        for (String c : candidates) {
            String url = base.endsWith("/") ? base.substring(0, base.length() - 1) + c : base + c;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() < 200 || res.statusCode() >= 300) continue;
                Map<?, ?> parsed = objectMapper.readValue(res.body(), Map.class);
                String token = parsed.get("token_endpoint") == null ? null : String.valueOf(parsed.get("token_endpoint"));
                String auth = parsed.get("authorization_endpoint") == null ? null : String.valueOf(parsed.get("authorization_endpoint"));
                String registration = parsed.get("registration_endpoint") == null ? null : String.valueOf(parsed.get("registration_endpoint"));
                if (token != null && !token.isBlank()) {
                    return new OAuthMetadata(token, auth, registration);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String ensureDomain(McpRegistryEntry server) {
        String name = "MCP " + server.getName();
        return domainRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(AgentDomain::getId)
                .orElseGet(() -> domainRepository.save(AgentDomain.builder()
                        .name(name)
                        .description("Discovered tools from MCP server " + server.getName())
                        .enabled(true)
                        .build()).getId());
    }

    private String sanitizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
    }

    private String mcpDomainName(McpRegistryEntry server) {
        return "MCP " + server.getName();
    }

    private Optional<String> findDomainId(McpRegistryEntry server) {
        String name = mcpDomainName(server);
        return domainRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(AgentDomain::getId);
    }

    private void syncDomainToolEnabled(McpRegistryEntry server, String remoteToolName, boolean enabled) {
        Optional<String> domainId = findDomainId(server);
        if (domainId.isEmpty()) return;
        String localName = "mcp_" + sanitizeName(remoteToolName);
        List<AgentTool> tools = toolRepository.findByDomainId(domainId.get());
        for (AgentTool tool : tools) {
            if (localName.equals(tool.getName())) {
                tool.setEnabled(enabled);
                toolRepository.update(tool);
                return;
            }
        }
    }

    private void syncAllDomainToolEnabled(McpRegistryEntry server, boolean enabled) {
        Optional<String> domainId = findDomainId(server);
        if (domainId.isEmpty()) return;
        List<AgentTool> tools = toolRepository.findByDomainId(domainId.get());
        for (AgentTool tool : tools) {
            if (tool.getName() != null && tool.getName().startsWith("mcp_")) {
                tool.setEnabled(enabled);
                toolRepository.update(tool);
            }
        }
    }

    private List<Map<String, Object>> normalizeCachedTools(McpRegistryEntry server) {
        List<Map<String, Object>> tools = parseCachedTools(server.getDiscoveredToolsJson());
        for (Map<String, Object> tool : tools) {
            if (!(tool.get("enabled") instanceof Boolean)) {
                tool.put("enabled", true);
            }
        }
        return tools;
    }

    private List<Map<String, Object>> parseCachedTools(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (!(parsed instanceof List<?> list)) return new ArrayList<>();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
            return out;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private Map<String, Boolean> cachedEnabledByToolName(McpRegistryEntry server) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map<String, Object> tool : normalizeCachedTools(server)) {
            String name = asText(tool.get("name"));
            if (name == null || name.isBlank()) continue;
            boolean enabled = !(tool.get("enabled") instanceof Boolean b) || b;
            out.put(name, enabled);
        }
        return out;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    private String quote(String text) {
        return "\"" + String.valueOf(text).replace("\"", "'") + "\"";
    }

    static boolean hasJsonRpcErrorPayload(String body) {
        if (body == null || body.isBlank()) return false;
        return body.contains("\"jsonrpc\"") && body.contains("\"error\"");
    }

    static boolean containsAuthErrorHint(String bodyLower) {
        if (bodyLower == null || bodyLower.isBlank()) return false;
        return bodyLower.contains("authentication required")
                || bodyLower.contains("unauthorized")
                || bodyLower.contains("invalid_token")
                || bodyLower.contains("access denied")
                || bodyLower.contains("forbidden");
    }

    private Map<String, Object> parseMcpResponseBody(String body) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Empty MCP response body");
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readValue(trimmed, Map.class);
        }
        String sseJson = extractJsonFromSse(trimmed);
        if (sseJson != null) {
            return objectMapper.readValue(sseJson, Map.class);
        }
        throw new IllegalArgumentException("Unsupported MCP response format");
    }

    static String extractJsonFromSse(String sseBody) {
        if (sseBody == null || sseBody.isBlank()) return null;
        String[] lines = sseBody.split("\\R");
        String candidate = null;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.startsWith("data:")) continue;
            String payload = trimmed.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) continue;
            if (payload.startsWith("{") && payload.endsWith("}")) {
                candidate = payload;
            }
        }
        return candidate;
    }

    private String describeJsonRpcError(Object error) {
        if (error instanceof Map<?, ?> map) {
            Object msg = map.get("message");
            Object code = map.get("code");
            if (msg != null && code != null) return code + ": " + msg;
            if (msg != null) return String.valueOf(msg);
        }
        return String.valueOf(error);
    }

    private Map<String, Object> enrichArgumentsForKnownTools(McpRegistryEntry server, String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return args;
        if (!isGithubServer(server)) return args;
        String normalizedTool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (!normalizedTool.contains("search_repositories")) return args;
        Object queryVal = args.get("query");
        if (queryVal == null) return args;
        String query = String.valueOf(queryVal).trim();
        if (query.isBlank()) return args;
        String normalizedQuery = normalizeGithubRepositorySearchQuery(server, query);
        if (normalizedQuery.equals(query)) return args;
        Map<String, Object> out = new LinkedHashMap<>(args);
        out.put("query", normalizedQuery);
        log.debug("[McpClientService] Normalized GitHub repo search query: original='{}', normalized='{}'",
                truncate(query, 200),
                truncate(normalizedQuery, 200));
        return out;
    }

    private String normalizeGithubRepositorySearchQuery(McpRegistryEntry server, String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) return trimmed;
        String login = resolveGithubLogin(server);
        String withUser = replaceCurrentUserPlaceholders(trimmed, login);
        if (withUser.equals(trimmed) && hasRepositoryScopeQualifier(withUser)) {
            return withUser;
        }
        if (!hasRepositoryScopeQualifier(withUser) && login != null && !login.isBlank()) {
            return withUser + " user:" + login;
        }
        return withUser;
    }

    private String replaceCurrentUserPlaceholders(String query, String login) {
        if (query == null || query.isBlank()) return query;
        if (login == null || login.isBlank()) return query;
        String normalized = query;
        normalized = normalized.replaceAll("(?i)user\\s*:\\s*(current_user|authenticated_user|auth_user|me|self)", "user:" + login);
        normalized = normalized.replaceAll("(?i)owner\\s*:\\s*(current_user|authenticated_user|auth_user|me|self)", "owner:" + login);
        normalized = normalized.replaceAll("(?i)org\\s*:\\s*(current_user|authenticated_user|auth_user|me|self)", "org:" + login);
        return normalized;
    }

    private boolean isGithubServer(McpRegistryEntry server) {
        String base = server.getBaseUrl() != null ? server.getBaseUrl() : server.getEndpoint();
        if (base == null) return false;
        String lower = base.toLowerCase(Locale.ROOT);
        return lower.contains("github");
    }

    private boolean hasRepositoryScopeQualifier(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("user:")
                || lower.contains("org:")
                || lower.contains("owner:")
                || lower.contains("repo:");
    }

    private boolean looksLikeMyProjectsQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String lower = query.toLowerCase(Locale.ROOT);
        return (lower.contains("my") || lower.contains("all"))
                && (lower.contains("repo") || lower.contains("repos") || lower.contains("project") || lower.contains("projects"));
    }

    private String resolveGithubLogin(McpRegistryEntry server) {
        if (server.getId() != null && githubLoginCache.containsKey(server.getId())) {
            return githubLoginCache.get(server.getId());
        }
        try {
            Map<String, String> authHeaders = mcpAuthService.buildAuthHeaders(server);
            String auth = authHeaders.get("Authorization");
            if (auth == null || auth.isBlank()) return null;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", auth)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> res = buildHttpClient(server).send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) return null;
            Map<?, ?> parsed = objectMapper.readValue(res.body(), Map.class);
            Object loginObj = parsed.get("login");
            if (loginObj == null) return null;
            String login = String.valueOf(loginObj);
            if (server.getId() != null) {
                githubLoginCache.put(server.getId(), login);
            }
            return login;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record AuthDetection(String authType,
                                Map<String, Object> authConfig,
                                String tokenUrl,
                                String authorizationUrl,
                                String registrationUrl,
                                boolean authRequired,
                                boolean connectRequired,
                                boolean dcrSupported,
                                String confidence) {}
    public record ToolToggleResult(String serverId, int changed, int total, boolean enabled) {}
    private record OAuthMetadata(String tokenUrl, String authorizationUrl, String registrationUrl) {}
}
