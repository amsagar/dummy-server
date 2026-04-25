package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentToolRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ToolImportService {
    private static final Set<String> SUPPORTED_OPENAPI_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Pattern CURL_URL_FLAG_PATTERN = Pattern.compile("(?:^|\\s)(?:--url|-url|-u)\\s+('([^']*)'|\"([^\"]*)\"|(\\S+))");
    private static final Pattern CURL_DIRECT_URL_PATTERN = Pattern.compile("(^|\\s)('https?://[^'\\s]+'|\"https?://[^\"\\s]+\"|https?://\\S+)");
    private final ObjectMapper objectMapper;
    private final AgentToolRepository toolRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ToolImportService(ObjectMapper objectMapper, AgentToolRepository toolRepository) {
        this.objectMapper = objectMapper;
        this.toolRepository = toolRepository;
    }

    public List<AgentTool> importOpenApi(String domainId, String spec, boolean enabled) {
        return importOpenApi(domainId, spec, null, enabled);
    }

    public List<AgentTool> importOpenApi(String domainId, String spec, String specUrl, boolean enabled) {
        List<AgentTool> imported = new ArrayList<>();
        try {
            String effectiveSpec = resolveSpec(spec, specUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(effectiveSpec, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> paths = (Map<String, Object>) root.get("paths");
            if (paths == null || paths.isEmpty()) return imported;
            OpenApiBaseParts baseParts = resolveOpenApiBase(root);

            paths.forEach((path, methodsObj) -> {
                if (!(methodsObj instanceof Map<?, ?> methods)) return;
                methods.forEach((methodRaw, operationObj) -> {
                    String method = String.valueOf(methodRaw).toUpperCase(Locale.ROOT);
                    if (!SUPPORTED_OPENAPI_METHODS.contains(method)) return;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> operation = operationObj instanceof Map<?, ?> op ? (Map<String, Object>) op : Map.of();
                    String opId = String.valueOf(operation.getOrDefault("operationId", ""));
                    String summary = String.valueOf(operation.getOrDefault("summary", ""));
                    String toolName = (opId == null || opId.isBlank())
                            ? (method.toLowerCase(Locale.ROOT) + "_" + path.replaceAll("[^a-zA-Z0-9]+", "_"))
                            : opId;

                    AgentTool tool = AgentTool.builder()
                            .domainId(domainId)
                            .name(toolName)
                            .description((summary == null || summary.isBlank()) ? ("Imported from OpenAPI " + method + " " + path) : summary)
                            .sourceType("openapi_import")
                            .method(method)
                            .host(baseParts.host())
                            .endpoint(joinOpenApiPaths(baseParts.basePath(), path))
                            .requestSchema(String.valueOf(operation.getOrDefault("requestBody", "{}")))
                            .responseSchema(String.valueOf(operation.getOrDefault("responses", "{}")))
                            .sampleRequest("{}")
                            .sampleResponse("{}")
                            .enabled(enabled)
                            .build();
                    imported.add(toolRepository.save(tool));
                });
            });
            log.info("[ToolImportService] Imported {} tools from OpenAPI into domain={}", imported.size(), domainId);
            return imported;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse OpenAPI spec: " + e.getMessage(), e);
        }
    }

    private String resolveSpec(String spec, String specUrl) throws Exception {
        if (spec != null && !spec.isBlank()) return spec;
        if (specUrl == null || specUrl.isBlank()) {
            throw new IllegalArgumentException("Either spec JSON or specUrl is required");
        }
        URI uri = URI.create(specUrl);
        if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("specUrl must be http/https");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("Failed to fetch specUrl: HTTP " + response.statusCode());
        }
        return response.body();
    }

    public AgentTool importCurl(String domainId,
                                String curlCommand,
                                String toolName,
                                String description,
                                String responseSample,
                                boolean enabled) {
        String method = extractCurlMethod(curlCommand);
        String extractedUrl = extractCurlUrl(curlCommand);
        String body = extractCurlBody(curlCommand);
        Map<String, String> headers = extractCurlHeaders(curlCommand);
        String name = (toolName == null || toolName.isBlank())
                ? method.toLowerCase(Locale.ROOT) + "_imported_tool"
                : toolName.trim();
        String normalizedResponseSample = responseSample == null || responseSample.isBlank() ? "{}" : responseSample.trim();

        UrlParts curlParts = splitUrl(extractedUrl);

        AgentTool tool = AgentTool.builder()
                .domainId(domainId)
                .name(name)
                .description((description == null || description.isBlank()) ? "Imported from cURL command" : description.trim())
                .sourceType("curl_import")
                .method(method)
                .host(curlParts.host())
                .endpoint(curlParts.endpoint())
                .requestSchema(toJson(Map.of("headers", headers)))
                .responseSchema(inferResponseSchema(normalizedResponseSample))
                .sampleRequest(body == null ? curlCommand : body)
                .sampleResponse(normalizedResponseSample)
                .enabled(enabled)
                .build();
        return toolRepository.save(tool);
    }

    private String inferResponseSchema(String responseSample) {
        if (responseSample == null || responseSample.isBlank() || "{}".equals(responseSample.trim())) {
            return "{}";
        }
        try {
            Object parsed = objectMapper.readValue(responseSample, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return toJson(Map.of("type", "object", "keys", map.keySet()));
            }
            if (parsed instanceof List<?> list) {
                return toJson(Map.of("type", "array", "size", list.size()));
            }
        } catch (Exception ignored) {
        }
        return toJson(Map.of("type", "text"));
    }

    private String extractCurlMethod(String curl) {
        String upper = curl.toUpperCase(Locale.ROOT);
        if (upper.contains(" -X POST")) return "POST";
        if (upper.contains(" -X PUT")) return "PUT";
        if (upper.contains(" -X PATCH")) return "PATCH";
        if (upper.contains(" -X DELETE")) return "DELETE";
        return "GET";
    }

    private String extractCurlUrl(String curl) {
        Matcher flagMatcher = CURL_URL_FLAG_PATTERN.matcher(curl);
        if (flagMatcher.find()) {
            String token = firstNonBlank(flagMatcher.group(2), flagMatcher.group(3), flagMatcher.group(4));
            if (token != null) return token.trim();
        }
        Matcher directMatcher = CURL_DIRECT_URL_PATTERN.matcher(curl);
        if (directMatcher.find()) {
            String token = directMatcher.group(2);
            if (token != null) {
                return token.replace("'", "").replace("\"", "").trim();
            }
        }
        return "/";
    }

    private String extractCurlBody(String curl) {
        Pattern dataPattern = Pattern.compile("(?:--data|-d)\\s+('([^']*)'|\"([^\"]*)\"|(\\S+))");
        Matcher m = dataPattern.matcher(curl);
        if (m.find()) {
            String v = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            return v;
        }
        return null;
    }

    private Map<String, String> extractCurlHeaders(String curl) {
        Map<String, String> headers = new HashMap<>();
        Pattern headerPattern = Pattern.compile("(?:-H|--header)\\s+('([^']*)'|\"([^\"]*)\"|(\\S+))");
        Matcher m = headerPattern.matcher(curl);
        while (m.find()) {
            String token = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            int idx = token.indexOf(':');
            if (idx > 0) {
                headers.put(token.substring(0, idx).trim(), token.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        if (path.startsWith("http://") || path.startsWith("https://")) return sanitizeUrl(path);
        return path.startsWith("/") ? path : "/" + path;
    }

    private OpenApiBaseParts resolveOpenApiBase(Map<String, Object> root) {
        Object serversObj = root.get("servers");
        if (serversObj instanceof List<?> serversList && !serversList.isEmpty()) {
            Object first = serversList.get(0);
            if (first instanceof Map<?, ?> serverMap) {
                Object urlObj = serverMap.get("url");
                if (urlObj != null && !String.valueOf(urlObj).isBlank()) {
                    UrlParts serverParts = splitUrl(String.valueOf(urlObj).trim());
                    return new OpenApiBaseParts(serverParts.host(), serverParts.endpoint());
                }
            }
        }

        String host = asText(root.get("host"));
        if (host == null || host.isBlank()) {
            return new OpenApiBaseParts(null, "");
        }
        String scheme = "https";
        Object schemesObj = root.get("schemes");
        if (schemesObj instanceof List<?> schemes && !schemes.isEmpty()) {
            String candidate = String.valueOf(schemes.get(0)).trim().toLowerCase(Locale.ROOT);
            if ("http".equals(candidate) || "https".equals(candidate)) {
                scheme = candidate;
            }
        }
        String basePath = normalizeBasePath(asText(root.get("basePath")));
        return new OpenApiBaseParts(scheme + "://" + host.trim(), basePath);
    }

    private String joinOpenApiPaths(String basePath, String operationPath) {
        String base = normalizeBasePath(basePath);
        String op = normalizeBasePath(operationPath);
        if (op.isBlank()) op = "/";
        String joined;
        if ("/".equals(base)) {
            joined = op;
        } else if ("/".equals(op)) {
            joined = base;
        } else {
            joined = base + op;
        }
        return normalizePath(joined);
    }

    private String normalizeBasePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) return "/";
        String cleaned = path.trim();
        if (!cleaned.startsWith("/")) cleaned = "/" + cleaned;
        while (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private UrlParts splitUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return new UrlParts(null, "/");
        String cleaned = rawUrl.trim().replace("'", "").replace("\"", "");
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            try {
                URI parsed = URI.create(cleaned);
                int port = parsed.getPort();
                String host = parsed.getScheme() + "://" + parsed.getHost() + (port > 0 ? ":" + port : "");
                String endpoint = parsed.getRawPath();
                if (endpoint == null || endpoint.isBlank()) endpoint = "/";
                if (parsed.getRawQuery() != null && !parsed.getRawQuery().isBlank()) {
                    endpoint += "?" + parsed.getRawQuery();
                }
                return new UrlParts(host, endpoint);
            } catch (Exception ignored) {
                return new UrlParts(null, sanitizeUrl(cleaned));
            }
        }
        return new UrlParts(null, sanitizeUrl(cleaned));
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return "/";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return "/" + url.replaceAll("^/+", "");
    }

    private record OpenApiBaseParts(String host, String basePath) {}
    private record UrlParts(String host, String endpoint) {}
}
