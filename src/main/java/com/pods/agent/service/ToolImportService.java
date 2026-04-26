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
            Map<String, AgentTool> existingByName = new HashMap<>();
            for (AgentTool existing : toolRepository.findByDomainId(domainId)) {
                if (existing.getName() != null && !existing.getName().isBlank()) {
                    existingByName.put(existing.getName().toLowerCase(Locale.ROOT), existing);
                }
            }

            paths.forEach((path, methodsObj) -> {
                if (!(methodsObj instanceof Map<?, ?> methods)) return;
                @SuppressWarnings("unchecked")
                List<Object> pathParameters = methods.get("parameters") instanceof List<?> list
                        ? (List<Object>) list
                        : List.of();
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
                            .requestSchema(toJson(Map.of(
                                    "inputSchema", buildOperationInputSchema(operation, pathParameters),
                                    "requestBody", operation.getOrDefault("requestBody", Map.of())
                            )))
                            .responseSchema(toJson(operation.getOrDefault("responses", Map.of())))
                            .sampleRequest("{}")
                            .sampleResponse("{}")
                            .enabled(enabled)
                            .build();
                    AgentTool existing = existingByName.get(toolName.toLowerCase(Locale.ROOT));
                    if (existing != null) {
                        existing.setDescription(tool.getDescription());
                        existing.setSourceType(tool.getSourceType());
                        existing.setMethod(tool.getMethod());
                        existing.setHost(tool.getHost());
                        existing.setEndpoint(tool.getEndpoint());
                        existing.setRequestSchema(tool.getRequestSchema());
                        existing.setResponseSchema(tool.getResponseSchema());
                        existing.setSampleRequest(tool.getSampleRequest());
                        existing.setSampleResponse(tool.getSampleResponse());
                        existing.setEnabled(tool.isEnabled());
                        toolRepository.update(existing);
                        imported.add(existing);
                    } else {
                        AgentTool saved = toolRepository.save(tool);
                        existingByName.put(saved.getName().toLowerCase(Locale.ROOT), saved);
                        imported.add(saved);
                    }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildOperationInputSchema(Map<String, Object> operation, List<Object> pathParameters) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        List<Object> operationParameters = operation.get("parameters") instanceof List<?> params
                ? new ArrayList<>(params)
                : List.of();
        List<Object> mergedParameters = new ArrayList<>(pathParameters == null ? List.of() : pathParameters);
        mergedParameters.addAll(operationParameters);
        Map<String, Object> mergedByKey = new HashMap<>();
        List<Object> ordered = new ArrayList<>();
        for (Object paramObj : mergedParameters) {
            if (!(paramObj instanceof Map<?, ?> rawParam)) continue;
            Map<String, Object> param = (Map<String, Object>) rawParam;
            String name = asText(param.get("name"));
            if (name == null || name.isBlank()) continue;
            String in = asText(param.get("in"));
            String paramKey = (in == null ? "" : in.toLowerCase(Locale.ROOT)) + ":" + name.toLowerCase(Locale.ROOT);
            if (!mergedByKey.containsKey(paramKey)) {
                ordered.add(paramKey);
            }
            mergedByKey.put(paramKey, paramObj);
        }
        for (Object keyObj : ordered) {
            String paramKey = String.valueOf(keyObj);
            Object paramObj = mergedByKey.get(paramKey);
            if (!(paramObj instanceof Map<?, ?> rawParam)) continue;
            Map<String, Object> param = (Map<String, Object>) rawParam;
            String name = asText(param.get("name"));
            if (name == null || name.isBlank()) continue;

            Object paramSchema = param.get("schema");
            Map<String, Object> normalizedParamSchema;
            if (paramSchema instanceof Map<?, ?> rawParamSchema) {
                normalizedParamSchema = new HashMap<>((Map<String, Object>) rawParamSchema);
            } else {
                normalizedParamSchema = Map.of("type", "string");
            }
            String description = asText(param.get("description"));
            if (description != null && !description.isBlank() && !normalizedParamSchema.containsKey("description")) {
                normalizedParamSchema = new HashMap<>(normalizedParamSchema);
                normalizedParamSchema.put("description", description);
            }
            properties.put(name, normalizedParamSchema);
            if (Boolean.TRUE.equals(param.get("required"))) {
                required.add(name);
            }
        }

        Map<String, Object> requestBodySchema = extractRequestBodySchema(operation.get("requestBody"));
        if (requestBodySchema != null && !requestBodySchema.isEmpty()) {
            Object bodyType = requestBodySchema.get("type");
            Object bodyProps = requestBodySchema.get("properties");
            if ("object".equals(bodyType) && bodyProps instanceof Map<?, ?> bodyMap) {
                for (Map.Entry<?, ?> entry : bodyMap.entrySet()) {
                    if (entry.getKey() != null) {
                        properties.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                Object bodyRequired = requestBodySchema.get("required");
                if (bodyRequired instanceof List<?> reqList) {
                    for (Object req : reqList) {
                        if (req != null) required.add(String.valueOf(req));
                    }
                }
            } else {
                properties.put("body", requestBodySchema);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required.stream().distinct().toList());
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRequestBodySchema(Object requestBodyObj) {
        if (!(requestBodyObj instanceof Map<?, ?> rawBody)) return Map.of();
        Map<String, Object> requestBody = (Map<String, Object>) rawBody;
        Object contentObj = requestBody.get("content");
        if (contentObj instanceof Map<?, ?> rawContent && !rawContent.isEmpty()) {
            Map<String, Object> content = (Map<String, Object>) rawContent;
            for (String key : List.of("application/json", "multipart/form-data", "application/x-www-form-urlencoded")) {
                Object mediaObj = content.get(key);
                Map<String, Object> schema = extractMediaSchema(mediaObj);
                if (schema != null && !schema.isEmpty()) return schema;
            }
            for (Object mediaObj : content.values()) {
                Map<String, Object> schema = extractMediaSchema(mediaObj);
                if (schema != null && !schema.isEmpty()) return schema;
            }
        }
        Object schemaObj = requestBody.get("schema");
        if (schemaObj instanceof Map<?, ?> rawSchema) {
            return new HashMap<>((Map<String, Object>) rawSchema);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMediaSchema(Object mediaObj) {
        if (!(mediaObj instanceof Map<?, ?> rawMedia)) return null;
        Map<String, Object> media = (Map<String, Object>) rawMedia;
        Object schemaObj = media.get("schema");
        if (schemaObj instanceof Map<?, ?> rawSchema) {
            return new HashMap<>((Map<String, Object>) rawSchema);
        }
        return null;
    }

    private record OpenApiBaseParts(String host, String basePath) {}
    private record UrlParts(String host, String endpoint) {}
}
