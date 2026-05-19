package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.DecisionTable;
import com.pods.agent.agent.MemoryTools;
import com.pods.agent.service.mcp.McpRuntimeAdapter;
import com.pods.agent.service.workspace.WorkspaceContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
public class ToolExecutionService {
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_RETRIES = 2;
    private static final int FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_MS = 30_000L;
    private static final int READ_DEFAULT_LINES = 2000;
    private static final int READ_MAX_LINES = 5000;
    private static final int READ_LEGACY_BYTE_CAP = 65_536;

    // glob/grep pagination caps. Defaults preserve historical behavior; max
    // values bound memory + payload size when callers ask for big windows.
    private static final int GLOB_DEFAULT_LIMIT = 200;
    private static final int GLOB_MAX_LIMIT = 2000;
    private static final int GREP_DEFAULT_LIMIT = 300;
    private static final int GREP_MAX_LIMIT = 2000;
    private static final int GREP_FILE_WALK_LIMIT = 5000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /**
     * Per-request body timeout for tool HTTP calls. Defaults to 45s, configurable
     * via {@code pods.tool-execution.http-timeout-seconds}. The TCP connect timeout
     * above is separate and intentionally tight.
     */
    @org.springframework.beans.factory.annotation.Value("${pods.tool-execution.http-timeout-seconds:45}")
    private int httpTimeoutSeconds = 45;
    private final ObjectMapper objectMapper;
    private final McpClientService mcpClientService;
    private final McpRuntimeAdapter mcpRuntimeAdapter;
    private final MemoryTools memoryTools;
    private final HttpToolAuthService httpToolAuthService;
    private final DecisionTableService decisionTableService;
    private final CatalogSearchService catalogSearchService;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    /** Optional turn-scoped cache for {@link #executeCached}. Setter injection
     *  so existing test constructors don't need updating. */
    private TurnToolCache turnToolCache;

    public ToolExecutionService(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null, null, null, null);
    }

    @Autowired
    public ToolExecutionService(ObjectMapper objectMapper,
                                McpClientService mcpClientService,
                                McpRuntimeAdapter mcpRuntimeAdapter,
                                MemoryTools memoryTools,
                                HttpToolAuthService httpToolAuthService,
                                DecisionTableService decisionTableService,
                                CatalogSearchService catalogSearchService) {
        this.objectMapper = objectMapper;
        this.mcpClientService = mcpClientService;
        this.mcpRuntimeAdapter = mcpRuntimeAdapter;
        this.memoryTools = memoryTools;
        this.httpToolAuthService = httpToolAuthService;
        this.decisionTableService = decisionTableService;
        this.catalogSearchService = catalogSearchService;
    }

    @Autowired(required = false)
    public void setTurnToolCache(TurnToolCache turnToolCache) {
        this.turnToolCache = turnToolCache;
    }

    /**
     * Order-validation VFS materializer. Setter-injected so the framework
     * core compiles standalone (tests, contexts without OV).
     */
    private com.pods.agent.ordervalidation.service.OvOrderVfsService ovOrderVfsService;

    @Autowired(required = false)
    public void setOvOrderVfsService(com.pods.agent.ordervalidation.service.OvOrderVfsService svc) {
        this.ovOrderVfsService = svc;
    }

    /**
     * OV workflow runner. Backs {@code ovStartValidation}. Setter-injected
     * so the framework core compiles standalone.
     */
    private com.pods.agent.ordervalidation.service.OrderValidationRunService ovRunService;

    @Autowired(required = false)
    public void setOvRunService(com.pods.agent.ordervalidation.service.OrderValidationRunService svc) {
        this.ovRunService = svc;
    }

    /**
     * OV analytics — backs {@code ovListRunsForOrder} /
     * {@code ovGetRunDetail} / {@code ovDashboardStats}.
     */
    private com.pods.agent.ordervalidation.service.OrderValidationAnalyticsService ovAnalyticsService;

    @Autowired(required = false)
    public void setOvAnalyticsService(com.pods.agent.ordervalidation.service.OrderValidationAnalyticsService svc) {
        this.ovAnalyticsService = svc;
    }

    /**
     * OV settings repo — supplies the default {@code workflowId} when the
     * model doesn't pass one to {@code ovStartValidation} /
     * {@code ovDashboardStats}.
     */
    private com.pods.agent.ordervalidation.repository.OrderValidationSettingsRepository ovSettingsRepository;

    @Autowired(required = false)
    public void setOvSettingsRepository(com.pods.agent.ordervalidation.repository.OrderValidationSettingsRepository repo) {
        this.ovSettingsRepository = repo;
    }

    /**
     * The "ov-*" agent profiles need their FS reads scoped to the
     * {@code orders/} subtree so the model can't glob the workspace
     * root and surface unrelated skill manifest files. Holder is set
     * by {@code AgentRuntimeService} around the tool dispatch.
     */
    private static final ThreadLocal<String> OV_SUBROOT = new ThreadLocal<>();

    public static void bindOvSubroot(String subroot) {
        if (subroot == null || subroot.isBlank()) OV_SUBROOT.remove();
        else OV_SUBROOT.set(subroot.endsWith("/") ? subroot : subroot + "/");
    }

    public static void clearOvSubroot() {
        OV_SUBROOT.remove();
    }

    public ToolExecutionService(ObjectMapper objectMapper,
                                McpClientService mcpClientService,
                                McpRuntimeAdapter mcpRuntimeAdapter,
                                MemoryTools memoryTools,
                                HttpToolAuthService httpToolAuthService,
                                DecisionTableService decisionTableService) {
        this(objectMapper, mcpClientService, mcpRuntimeAdapter, memoryTools, httpToolAuthService, decisionTableService, null);
    }

    /**
     * Turn-scoped, single-flight tool execution. Within one chat turn,
     * concurrent callers requesting the same {@code (tool, canonicalArgs)}
     * share a single in-flight {@link ExecutionResult}. The first caller
     * runs the real HTTP/SDK call, every later caller waits on its future.
     *
     * <p>Falls back to the plain {@link #execute(AgentTool, String)} when:
     * <ul>
     *   <li>{@code turnId} is null/blank (e.g. invoked outside a chat turn),</li>
     *   <li>the {@link TurnToolCache} bean isn't wired (test contexts), or</li>
     *   <li>{@code TurnToolCache} decides the tool isn't cacheable (mutations,
     *       operator-disabled).</li>
     * </ul>
     *
     * <p>Failure semantics: on exception or non-success result, the cache
     * entry is evicted so siblings retry independently rather than inherit
     * the failure.
     */
    public ExecutionResult executeCached(AgentTool tool, String userText, String turnId) {
        return executeCachedWithMeta(tool, userText, turnId).result();
    }

    /** Like {@link #executeCached} but returns whether the call was served
     *  from the cache (so a caller can surface a {@code rule_domain.tool.cached}
     *  event to the UI). */
    public CachedExecutionResult executeCachedWithMeta(AgentTool tool, String userText, String turnId) {
        if (turnToolCache == null || turnId == null || turnId.isBlank() || tool == null) {
            return new CachedExecutionResult(execute(tool, userText), false);
        }
        String canonicalArgs = TurnToolCache.canonicalize(userText);
        TurnToolCache.AcquireResult acq = turnToolCache.acquireOrWait(turnId, tool, canonicalArgs);
        if (!acq.enabled()) {
            return new CachedExecutionResult(execute(tool, userText), false);
        }
        if (acq.primary()) {
            try {
                ExecutionResult result = execute(tool, userText);
                if (result != null && result.success()) {
                    turnToolCache.complete(turnId, acq.key(), result);
                    return new CachedExecutionResult(result, false);
                }
                // Don't cache failures. Evict so siblings can retry on their own.
                turnToolCache.evict(turnId, acq.key(),
                        new RuntimeException(result == null ? "null result" : result.error()));
                return new CachedExecutionResult(result, false);
            } catch (RuntimeException ex) {
                turnToolCache.evict(turnId, acq.key(), ex);
                throw ex;
            }
        }
        // Waiter: block on the primary's future. If it completes
        // exceptionally (primary failed → evict signalled), fall back to a
        // direct call rather than propagating the primary's exception.
        try {
            return new CachedExecutionResult(acq.future().get(), true);
        } catch (java.util.concurrent.CancellationException cancelled) {
            return new CachedExecutionResult(
                    new ExecutionResult(false, null, "Cancelled: " + tool.getName()), false);
        } catch (Exception ex) {
            log.debug("[ToolExecutionService] cache waiter for {} fell through to direct execute: {}",
                    tool.getName(), ex.getMessage());
            return new CachedExecutionResult(execute(tool, userText), false);
        }
    }

    /** Wrapper around {@link ExecutionResult} that also reports whether the
     *  caller was served from the {@link TurnToolCache} rather than executing
     *  the underlying call themselves. */
    public record CachedExecutionResult(ExecutionResult result, boolean cacheHit) {}

    public ExecutionResult execute(AgentTool tool, String userText) {
        if (tool == null || !tool.isEnabled()) {
            return new ExecutionResult(false, null, "Tool is disabled or missing");
        }
        String kind = resolveExecutionKind(tool);
        return switch (kind) {
            case "filesystem" -> executeFilesystem(tool, userText);
            case "shell" -> executeShell(tool, userText);
            case "web" -> executeWeb(tool, userText);
            case "workflow" -> executeIntegration(tool, userText);
            case "memory" -> executeMemory(tool, userText);
            case "integration" -> executeIntegration(tool, userText);
            default -> executeHttpProxy(tool, userText);
        };
    }

    private ExecutionResult executeHttpProxy(AgentTool tool, String userText) {
        CircuitState state = circuits.computeIfAbsent(tool.getId(), k -> new CircuitState());
        long now = System.currentTimeMillis();
        if (state.isOpen(now)) {
            return new ExecutionResult(false, null, "Circuit open for tool: " + tool.getName());
        }

        String method = tool.getMethod() == null || tool.getMethod().isBlank() ? "GET" : tool.getMethod().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            return new ExecutionResult(false, null, "Unsupported tool HTTP method: " + method);
        }
        String endpoint = resolveEndpoint(tool.getHost(), tool.getEndpoint());
        if (endpoint == null) {
            return new ExecutionResult(false, null, "Invalid tool endpoint");
        }
        Map<String, Object> args = normalizeDomainArgs(tool.getName(), parseArgs(userText));
        Set<String> consumedKeys = new HashSet<>();
        endpoint = hydratePathParams(endpoint, args, consumedKeys);
        List<String> unresolvedPathParams = unresolvedPathParams(endpoint);
        if (!unresolvedPathParams.isEmpty()) {
            return new ExecutionResult(false, null, "Missing required path params: " + String.join(", ", unresolvedPathParams));
        }
        if ("GET".equals(method) || "DELETE".equals(method)) {
            endpoint = appendQueryParams(endpoint, extractQueryParams(args, consumedKeys));
        } else if (shouldMirrorArgsToQuery(tool)) {
            // Opt-in only: selected tools may require mirroring request args into query
            // params for non-GET methods.
            endpoint = appendQueryParams(endpoint, extractQueryParams(args, new HashSet<>(consumedKeys)));
        }

        String body = buildRequestBody(tool, args, consumedKeys, userText, method);
        if (!"GET".equals(method) && !"DELETE".equals(method)
                && requiresMappedPayload(tool.getName())
                && isLikelyEmptyJsonObject(body)) {
            return new ExecutionResult(false, null,
                    "Tool input payload is empty. Map order fields and pass required attributes (zip/country/service fields) before calling this tool.");
        }
        Map<String, String> headers = parseHeaders(tool.getRequestSchema());
        if (httpToolAuthService != null) {
            try {
                headers.putAll(httpToolAuthService.resolveHeaders(tool));
                endpoint = appendQueryParams(endpoint, httpToolAuthService.resolveQueryParams(tool));
            } catch (Exception e) {
                return new ExecutionResult(false, null, "Tool auth resolution failed: " + e.getMessage());
            }
        }
        headers.putIfAbsent("Content-Type", "application/json");

        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("[ToolExecutionService] http tool={} attempt={} curl={}",
                        tool.getName(),
                        attempt + 1,
                        asCurl(method, endpoint, headers, ("GET".equals(method) || "DELETE".equals(method)) ? null : body));
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(httpTimeoutSeconds));
                headers.forEach(req::header);
                if ("GET".equals(method) || "DELETE".equals(method)) {
                    req.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    req.method(method, HttpRequest.BodyPublishers.ofString(body));
                }
                HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
                log.info("[ToolExecutionService] http tool={} status={} response={}",
                        tool.getName(),
                        response.statusCode(),
                        truncate(response.body(), 1200));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    state.reset();
                    String responseBody = response.body();
                    if (responseBody == null || responseBody.isBlank()) {
                        return new ExecutionResult(false, null,
                                "HTTP " + response.statusCode() + " but response body was empty — service may have returned no data");
                    }
                    return new ExecutionResult(true, responseBody, null);
                }
                last = new RuntimeException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            } catch (HttpTimeoutException te) {
                // Caller (ToolCallDelegate) owns timeout retries — return immediately
                // with a TIMEOUT: prefix so it can pattern-match. Don't burn through
                // our internal retry budget on a slow upstream when the delegate is
                // already willing to wait longer.
                log.info("[ToolExecutionService] http tool={} attempt={} timeout after {}s",
                        tool.getName(),
                        attempt + 1,
                        httpTimeoutSeconds);
                state.recordFailure(now);
                return new ExecutionResult(false, null,
                        "TIMEOUT: " + tool.getName() + " did not respond within " + httpTimeoutSeconds + "s");
            } catch (Exception e) {
                log.info("[ToolExecutionService] http tool={} attempt={} error={}",
                        tool.getName(),
                        attempt + 1,
                        e.getMessage());
                last = e;
            }
        }

        state.recordFailure(now);
        return new ExecutionResult(false, null, "Tool execution failed: " + (last != null ? last.getMessage() : "unknown"));
    }

    private ExecutionResult executeFilesystem(AgentTool tool, String userText) {
        try {
            Map<String, Object> args = parseArgs(userText);
            String op = tool.getName() == null ? "unknown" : tool.getName().toLowerCase();
            Object rawPath = args.get("path");
            log.info("[ToolExecutionService] fs op={} path={} inputKeys={}",
                    op,
                    rawPath == null ? "(none)" : rawPath,
                    args.keySet());
            ExecutionResult result = switch (op) {
                case "read" -> fsRead(args);
                case "glob" -> fsGlob(args);
                case "grep" -> fsGrep(args);
                case "write" -> fsWrite(args, false);
                case "edit" -> fsEdit(args);
                case "apply_patch" -> fsApplyPatch(args);
                default -> new ExecutionResult(false, null, "Unsupported filesystem tool: " + tool.getName());
            };
            log.info("[ToolExecutionService] fs op={} success={} bodyChars={} error={}",
                    op,
                    result.success(),
                    result.body() == null ? 0 : result.body().length(),
                    result.error());
            return result;
        } catch (Exception e) {
            return new ExecutionResult(false, null, "Filesystem tool failed: " + e.getMessage());
        }
    }

    private ExecutionResult executeShell(AgentTool tool, String userText) {
        try {
            Map<String, Object> args = parseArgs(userText);
            String command = stringArg(args, "command", userText);
            if (command == null || command.isBlank()) {
                return new ExecutionResult(false, null, "Shell command is required");
            }
            Path workspaceRoot = workspaceRoot();
            String workdir = stringArg(args, "working_directory", ".");
            Path shellDir = safePath(workdir);
            if (!shellDir.startsWith(workspaceRoot)) {
                shellDir = workspaceRoot;
            }
            Process process = new ProcessBuilder("zsh", "-lc", command)
                    .directory(shellDir.toFile())
                    .start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(false, null, "Shell command timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return new ExecutionResult(false, null, "Shell failed: " + truncate(stderr, 1000));
            }
            return new ExecutionResult(true, truncate(stdout, 4000), null);
        } catch (Exception e) {
            return new ExecutionResult(false, null, "Shell tool failed: " + e.getMessage());
        }
    }

    private ExecutionResult executeWeb(AgentTool tool, String userText) {
        try {
            Map<String, Object> args = parseArgs(userText);
            String url = stringArg(args, "url", null);
            if (url == null || url.isBlank()) {
                url = stringArg(args, "search_term", null);
            }
            if (url == null || url.isBlank()) {
                // Backward compatibility for earlier auto-payload shape.
                url = stringArg(args, "query", null);
            }
            if (url == null || url.isBlank()) {
                return new ExecutionResult(false, null, "url/search_term is required");
            }
            if ("websearch".equalsIgnoreCase(tool.getName()) || "codesearch".equalsIgnoreCase(tool.getName())) {
                return executeWebSearch(url, args);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(httpTimeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ExecutionResult(false, null, "Web request failed with status " + response.statusCode());
            }
            return new ExecutionResult(true, truncate(response.body(), 4000), null);
        } catch (Exception e) {
            return new ExecutionResult(false, null, "Web tool failed: " + e.getMessage());
        }
    }

    private ExecutionResult executeWebSearch(String query, Map<String, Object> args) {
        return executeExaWebSearch(query, args);
    }

    private ExecutionResult executeExaWebSearch(String query, Map<String, Object> args) {
        try {
            Map<String, Object> searchArgs = new LinkedHashMap<>();
            searchArgs.put("query", query);
            Integer numResults = intArg(args, "numResults", null);
            if (numResults != null && numResults > 0) searchArgs.put("numResults", numResults);
            String livecrawl = stringArg(args, "livecrawl", null);
            if (livecrawl != null && !livecrawl.isBlank()) searchArgs.put("livecrawl", livecrawl);
            String type = stringArg(args, "type", null);
            if (type != null && !type.isBlank()) searchArgs.put("type", type);
            Integer contextMaxCharacters = intArg(args, "contextMaxCharacters", null);
            if (contextMaxCharacters != null && contextMaxCharacters > 0) searchArgs.put("contextMaxCharacters", contextMaxCharacters);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", 1);
            payload.put("method", "tools/call");
            payload.put("params", Map.of(
                    "name", "web_search_exa",
                    "arguments", searchArgs
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://mcp.exa.ai/mcp"))
                    .timeout(Duration.ofSeconds(httpTimeoutSeconds))
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (compatible; pods-ai-agent/1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ExecutionResult(false, null, "exa_http_" + response.statusCode());
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return new ExecutionResult(false, null, "exa_empty_response");
            }

            Map<String, Object> parsed;
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                parsed = objectMapper.readValue(trimmed, Map.class);
            } else {
                String sseJson = McpClientService.extractJsonFromSse(trimmed);
                if (sseJson == null || sseJson.isBlank()) {
                    return new ExecutionResult(false, null, "exa_unparseable_sse");
                }
                parsed = objectMapper.readValue(sseJson, Map.class);
            }
            if (parsed.get("error") != null) {
                return new ExecutionResult(false, null, "exa_jsonrpc_error");
            }
            String exaOutput = extractExaOutput(parsed);
            if (exaOutput == null || exaOutput.isBlank()) {
                return new ExecutionResult(false, null, "exa_no_content");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("provider", "exa_mcp");
            result.put("output", exaOutput);
            return new ExecutionResult(true, objectMapper.writeValueAsString(result), null);
        } catch (Exception e) {
            return new ExecutionResult(false, null, "exa_error: " + e.getMessage());
        }
    }

    private String extractExaOutput(Map<String, Object> parsed) {
        Object result = parsed.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) return null;
        Object content = resultMap.get("content");
        if (!(content instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object text = firstMap.get("text");
        return text == null ? null : String.valueOf(text);
    }

    private ExecutionResult executeMemory(AgentTool tool, String userText) {
        Map<String, Object> args = parseArgs(userText);
        String name = tool.getName().toLowerCase();
        try {
            if (memoryTools != null) {
                if ("memoryview".equals(name)) {
                    return new ExecutionResult(true, memoryTools.memoryView(stringArg(args, "path", "MEMORY.md")), null);
                }
                if ("memorycreate".equals(name)) {
                    return new ExecutionResult(true, memoryTools.memoryCreate(
                            stringArg(args, "path", "memory.md"),
                            stringArg(args, "category", "reference"),
                            stringArg(args, "content", stringArg(args, "text", "")),
                            listArg(args, "tags"),
                            stringArg(args, "session_id", null)
                    ), null);
                }
                if ("memorystrreplace".equals(name)) {
                    return new ExecutionResult(true, memoryTools.memoryStrReplace(
                            stringArg(args, "path", null),
                            stringArg(args, "old_text", stringArg(args, "old", "")),
                            stringArg(args, "new_text", stringArg(args, "new", ""))
                    ), null);
                }
                if ("memoryinsert".equals(name)) {
                    return new ExecutionResult(true, memoryTools.memoryInsert(
                            stringArg(args, "path", null),
                            intArg(args, "after_line", null),
                            stringArg(args, "text", "")
                    ), null);
                }
                if ("memorydelete".equals(name)) {
                    return new ExecutionResult(true, memoryTools.memoryDelete(stringArg(args, "path", null)), null);
                }
                if ("memoryrename".equals(name)) {
                    return new ExecutionResult(true, memoryTools.memoryRename(
                            stringArg(args, "old_path", null),
                            stringArg(args, "new_path", null)
                    ), null);
                }
            }
            if ("memory_save".equals(name)) {
                return new ExecutionResult(true, "{\"status\":\"saved\",\"memory\":\"" + sanitize(stringArg(args, "text", stringArg(args, "query", ""))) + "\"}", null);
            }
            if ("memory_search".equals(name)) {
                return new ExecutionResult(true, "{\"status\":\"search\",\"query\":\"" + sanitize(stringArg(args, "query", "")) + "\",\"results\":[]}", null);
            }
            return new ExecutionResult(false, null, "Unsupported memory tool: " + tool.getName());
        } catch (Exception e) {
            return new ExecutionResult(false, null, "Memory tool failed: " + e.getMessage());
        }
    }

    /**
     * Materializes one OV run's data into the session workspace and
     * returns a short summary + a file index. The model then drills in
     * with the standard {@code read} / {@code glob} / {@code grep} tools.
     */
    private ExecutionResult executeOvLoadOrder(String userText) {
        if (ovOrderVfsService == null) {
            return new ExecutionResult(false, null, "ovLoadOrder is unavailable in this context");
        }
        try {
            Map<String, Object> args = parseArgs(userText);
            String orderId = stringArg(args, "orderId", null);
            if (orderId == null || orderId.isBlank()) {
                return new ExecutionResult(false, null, "orderId is required");
            }
            Path workspace = WorkspaceContextHolder.current();
            if (workspace == null) {
                return new ExecutionResult(false, null, "no active session workspace");
            }
            // Derive the sessionId from the workspace path.
            // SessionWorkspaceService roots at $TMPDIR/pods-agent-vfs/$sessionId.
            String sessionId = workspace.getFileName().toString();
            Path orderDir = ovOrderVfsService.materialize(sessionId, orderId);

            // List up to 50 files in the order subtree (relative paths).
            List<String> files = new ArrayList<>();
            try (var walk = Files.walk(orderDir)) {
                walk.filter(Files::isRegularFile)
                        .map(orderDir::relativize)
                        .map(Path::toString)
                        .sorted()
                        .limit(50)
                        .forEach(files::add);
            } catch (IOException e) {
                /* best-effort listing */
            }

            String summary = "";
            Path summaryPath = orderDir.resolve("summary.md");
            if (Files.isRegularFile(summaryPath)) {
                try {
                    summary = Files.readString(summaryPath);
                } catch (IOException ignored) {
                    summary = "(summary.md unavailable)";
                }
            }

            // The path returned to the model is workspace-relative so it
            // composes naturally with read/glob/grep paths.
            String relDir = workspace.relativize(orderDir).toString();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderId", orderId);
            body.put("path", relDir);
            body.put("files", files);
            body.put("summary", summary);
            return new ExecutionResult(true, objectMapper.writeValueAsString(body), null);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new ExecutionResult(false, null, ex.getMessage());
        } catch (Exception ex) {
            return new ExecutionResult(false, null, "ovLoadOrder failed: " + ex.getMessage());
        }
    }

    /**
     * Kicks off a fresh OV workflow run for one orderId. Runs synchronously so
     * the resulting {@code rule_executions} rows are committed before this returns —
     * a follow-up {@code ovLoadOrder(runId)} will find them.
     */
    private ExecutionResult executeOvStartValidation(String userText) {
        if (ovRunService == null) {
            return new ExecutionResult(false, null, "ovStartValidation is unavailable in this context");
        }
        try {
            Map<String, Object> args = parseArgs(userText);
            String orderId = stringArg(args, "orderId", null);
            if (orderId == null || orderId.isBlank()) {
                return new ExecutionResult(false, null, "orderId is required");
            }
            String workflowId = stringArg(args, "workflowId", null);
            if (workflowId == null || workflowId.isBlank()) {
                workflowId = (ovSettingsRepository != null)
                        ? ovSettingsRepository.load().workflowId()
                        : null;
            }
            if (workflowId == null || workflowId.isBlank()) {
                return new ExecutionResult(false, null,
                        "OV workflow not configured — set it in the OV-UI Settings page first");
            }
            var summary = ovRunService.start(workflowId, orderId, "agent", false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", summary.instanceId());
            body.put("workflowId", summary.defId());
            body.put("state", summary.state());
            body.put("startedAt", summary.startedAt());
            body.put("endedAt", summary.endedAt());
            if (summary.errorClass() != null) body.put("errorClass", summary.errorClass());
            if (summary.errorMessage() != null) body.put("errorMessage", summary.errorMessage());
            return new ExecutionResult(true, objectMapper.writeValueAsString(body), null);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new ExecutionResult(false, null, ex.getMessage());
        } catch (Exception ex) {
            return new ExecutionResult(false, null, "ovStartValidation failed: " + ex.getMessage());
        }
    }

    /** Lists historical runs for an orderId, newest first. */
    private ExecutionResult executeOvListRunsForOrder(String userText) {
        if (ovAnalyticsService == null) {
            return new ExecutionResult(false, null, "ovListRunsForOrder is unavailable in this context");
        }
        try {
            Map<String, Object> args = parseArgs(userText);
            String orderId = stringArg(args, "orderId", null);
            if (orderId == null || orderId.isBlank()) {
                return new ExecutionResult(false, null, "orderId is required");
            }
            int limit = intArg(args, "limit", 10);
            List<Map<String, Object>> rows = ovAnalyticsService.listRunsForOrder(orderId, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderId", orderId);
            body.put("count", rows.size());
            body.put("runs", rows);
            return new ExecutionResult(true, objectMapper.writeValueAsString(body), null);
        } catch (Exception ex) {
            return new ExecutionResult(false, null, "ovListRunsForOrder failed: " + ex.getMessage());
        }
    }

    /** Inline RunDetail JSON for one synthetic runId — same shape ovLoadOrder writes to run.json. */
    private ExecutionResult executeOvGetRunDetail(String userText) {
        if (ovAnalyticsService == null) {
            return new ExecutionResult(false, null, "ovGetRunDetail is unavailable in this context");
        }
        try {
            Map<String, Object> args = parseArgs(userText);
            String runId = stringArg(args, "runId", null);
            if (runId == null || runId.isBlank()) {
                return new ExecutionResult(false, null, "runId is required");
            }
            var detail = ovAnalyticsService.runDetail(runId);
            if (detail == null) {
                return new ExecutionResult(false, null, "No run found for runId " + runId);
            }
            return new ExecutionResult(true, objectMapper.writeValueAsString(detail), null);
        } catch (Exception ex) {
            return new ExecutionResult(false, null, "ovGetRunDetail failed: " + ex.getMessage());
        }
    }

    /** Time-windowed dashboard aggregates over a workflow. */
    private ExecutionResult executeOvDashboardStats(String userText) {
        if (ovAnalyticsService == null) {
            return new ExecutionResult(false, null, "ovDashboardStats is unavailable in this context");
        }
        try {
            Map<String, Object> args = parseArgs(userText);
            String workflowId = stringArg(args, "workflowId", null);
            if (workflowId == null || workflowId.isBlank()) {
                workflowId = (ovSettingsRepository != null)
                        ? ovSettingsRepository.load().workflowId()
                        : null;
            }
            if (workflowId == null || workflowId.isBlank()) {
                return new ExecutionResult(false, null,
                        "OV workflow not configured — set it in the OV-UI Settings page first");
            }
            Long fromTs = longArg(args, "fromTs");
            Long toTs = longArg(args, "toTs");
            // Default window: last 24h ending now.
            if (toTs == null) toTs = System.currentTimeMillis();
            if (fromTs == null) fromTs = toTs - 24L * 60 * 60 * 1000;
            var metrics = ovAnalyticsService.dashboard(workflowId, fromTs, toTs);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workflowId", workflowId);
            body.put("fromTs", fromTs);
            body.put("toTs", toTs);
            body.put("metrics", metrics);
            return new ExecutionResult(true, objectMapper.writeValueAsString(body), null);
        } catch (Exception ex) {
            return new ExecutionResult(false, null, "ovDashboardStats failed: " + ex.getMessage());
        }
    }

    /**
     * Parse a numeric arg as long (epoch-millis timestamps overflow int).
     * Returns null when the key is absent or unparseable.
     */
    private Long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private ExecutionResult executeIntegration(AgentTool tool, String userText) {
        String name = tool.getName().toLowerCase();
        if ("ovloadorder".equals(name)) {
            return executeOvLoadOrder(userText);
        }
        if ("ovstartvalidation".equals(name)) {
            return executeOvStartValidation(userText);
        }
        if ("ovlistrunsfororder".equals(name)) {
            return executeOvListRunsForOrder(userText);
        }
        if ("ovgetrundetail".equals(name)) {
            return executeOvGetRunDetail(userText);
        }
        if ("ovdashboardstats".equals(name)) {
            return executeOvDashboardStats(userText);
        }
        if ("dtevaluate".equals(name) || "decisiontableevaluate".equals(name)) {
            if (decisionTableService == null) {
                return new ExecutionResult(false, null, "Decision table service is unavailable");
            }
            try {
                Map<String, Object> args = parseArgs(userText);
                String tableName = stringArg(args, "tableName", null);
                if (tableName == null || tableName.isBlank()) {
                    return new ExecutionResult(false, null, "tableName is required");
                }
                Object inputsObj = args.get("inputs");
                Map<String, Object> inputs;
                if (inputsObj instanceof Map<?, ?> map) {
                    inputs = new LinkedHashMap<>();
                    map.forEach((k, v) -> inputs.put(String.valueOf(k), v));
                } else {
                    inputs = new LinkedHashMap<>(args);
                    inputs.remove("tableName");
                }
                List<String> missing = missingRequiredDecisionInputs(tableName, inputs);
                if (!missing.isEmpty()) {
                    return new ExecutionResult(false, null,
                            "dtEvaluate is missing required inputs: " + String.join(", ", missing));
                }
                var result = decisionTableService.evaluate(tableName, inputs);
                return new ExecutionResult(true, objectMapper.writeValueAsString(result.asMap()), null);
            } catch (Exception e) {
                return new ExecutionResult(false, null, "Decision table evaluation failed: " + e.getMessage());
            }
        }
        if ("dtlist".equals(name)) {
            if (decisionTableService == null) {
                return new ExecutionResult(false, null, "Decision table service is unavailable");
            }
            try {
                Map<String, Object> args = parseArgs(userText);
                int limit = Math.min(Math.max(1, intArg(args, "limit", 50)), 200);
                List<DecisionTable> all = decisionTableService.list();
                List<Map<String, Object>> tables = new ArrayList<>();
                for (int i = 0; i < Math.min(all.size(), limit); i++) {
                    DecisionTable t = all.get(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", t.getName());
                    row.put("description", t.getDescription() == null ? "" : t.getDescription());
                    row.put("hitPolicy", t.getHitPolicy());
                    row.put("updatedAt", t.getUpdatedAt());
                    tables.add(row);
                }
                return new ExecutionResult(true, objectMapper.writeValueAsString(Map.of(
                        "count", tables.size(),
                        "total", all.size(),
                        "tables", tables
                )), null);
            } catch (Exception e) {
                return new ExecutionResult(false, null, "dtList failed: " + e.getMessage());
            }
        }
        if ("dtsearch".equals(name)) {
            if (decisionTableService == null) {
                return new ExecutionResult(false, null, "Decision table service is unavailable");
            }
            try {
                Map<String, Object> args = parseArgs(userText);
                String query = stringArg(args, "query", null);
                if (query == null || query.isBlank()) {
                    return new ExecutionResult(false, null, "query is required");
                }
                int topK = intArg(args, "topK", 8);
                List<Map<String, Object>> results = decisionTableService.search(query, topK);
                return new ExecutionResult(true, objectMapper.writeValueAsString(Map.of(
                        "query", query,
                        "count", results.size(),
                        "results", results
                )), null);
            } catch (Exception e) {
                return new ExecutionResult(false, null, "dtSearch failed: " + e.getMessage());
            }
        }
        if ("dtmetadata".equals(name)) {
            if (decisionTableService == null) {
                return new ExecutionResult(false, null, "Decision table service is unavailable");
            }
            Map<String, Object> args = parseArgs(userText);
            String tableName = stringArg(args, "name", null);
            if (tableName == null || tableName.isBlank()) {
                return new ExecutionResult(false, null, "name is required");
            }
            try {
                boolean includeRules = boolArg(args, "includeRules", false);
                Map<String, Object> meta = decisionTableService.describe(tableName, includeRules);
                return new ExecutionResult(true, objectMapper.writeValueAsString(meta), null);
            } catch (IllegalArgumentException e) {
                return new ExecutionResult(false, null, e.getMessage());
            } catch (Exception e) {
                return new ExecutionResult(false, null, "dtMetadata failed: " + e.getMessage());
            }
        }
        if ("integration".equalsIgnoreCase(resolveExecutionKind(tool))
                && (mcpClientService != null || mcpRuntimeAdapter != null)
                && tool.getRequestSchema() != null
                && tool.getRequestSchema().contains("mcpServerId")) {
            try {
                Map<String, Object> binding = objectMapper.readValue(tool.getRequestSchema(), Map.class);
                String mcpServerId = String.valueOf(binding.getOrDefault("mcpServerId", ""));
                String mcpToolName = String.valueOf(binding.getOrDefault("mcpToolName", tool.getName()));
                String result;
                if (mcpRuntimeAdapter != null) {
                    result = mcpRuntimeAdapter.callTool(mcpServerId, mcpToolName, userText);
                } else {
                    result = mcpClientService.callTool(mcpServerId, mcpToolName, userText);
                }
                String mcpError = extractMcpError(result);
                if (mcpError != null) {
                    return new ExecutionResult(false, null, "MCP integration failed: " + mcpError);
                }
                return new ExecutionResult(true, result, null);
            } catch (Exception e) {
                return new ExecutionResult(false, null, "MCP integration failed: " + e.getMessage());
            }
        }
        if ("question".equals(name)) {
            Map<String, Object> args = parseArgs(userText);
            String prompt = stringArg(args, "question", stringArg(args, "query", "Approval required"));
            return new ExecutionResult(true, "approval_required:" + prompt, null);
        }
        if ("toolsearch".equals(name)) {
            if (catalogSearchService == null) {
                return new ExecutionResult(false, null, "Catalog search service is unavailable");
            }
            try {
                Map<String, Object> args = parseArgs(userText);
                String query = stringArg(args, "query", null);
                if (query == null || query.isBlank()) {
                    return new ExecutionResult(false, null, "query is required");
                }
                int topK = intArg(args, "topK", 8);
                boolean includeMcp = boolArg(args, "includeMcp", true);
                boolean includeFramework = boolArg(args, "includeFramework", true);
                List<Map<String, Object>> results = catalogSearchService.searchTools(query, topK, includeMcp, includeFramework);
                return new ExecutionResult(true, objectMapper.writeValueAsString(Map.of(
                        "query", query,
                        "count", results.size(),
                        "results", results
                )), null);
            } catch (Exception e) {
                return new ExecutionResult(false, null, "toolsearch failed: " + e.getMessage());
            }
        }
        if ("skillsearch".equals(name)) {
            if (catalogSearchService == null) {
                return new ExecutionResult(false, null, "Catalog search service is unavailable");
            }
            try {
                Map<String, Object> args = parseArgs(userText);
                String query = stringArg(args, "query", null);
                if (query == null || query.isBlank()) {
                    return new ExecutionResult(false, null, "query is required");
                }
                int topK = intArg(args, "topK", 8);
                List<Map<String, Object>> results = catalogSearchService.searchSkills(query, topK);
                return new ExecutionResult(true, objectMapper.writeValueAsString(Map.of(
                        "query", query,
                        "count", results.size(),
                        "results", results
                )), null);
            } catch (Exception e) {
                return new ExecutionResult(false, null, "skillsearch failed: " + e.getMessage());
            }
        }
        if (Set.of("plan_exit", "task", "batch", "pipeline", "todowrite").contains(name)) {
            return new ExecutionResult(true, "{\"status\":\"ok\",\"tool\":\"" + name + "\"}", null);
        }
        if (Set.of("agent_send", "agent_receive", "skill", "lsp").contains(name)) {
            return new ExecutionResult(true, "{\"status\":\"ok\",\"tool\":\"" + name + "\"}", null);
        }
        return new ExecutionResult(false, null, "Unsupported integration tool: " + tool.getName());
    }

    private ExecutionResult fsRead(Map<String, Object> args) throws IOException {
        Path path = safePath(stringArg(args, "path", null));
        if (!Files.exists(path)) return new ExecutionResult(false, null, "File not found");

        Integer offsetArg = intArg(args, "offset", null);
        Integer limitArg = intArg(args, "limit", null);

        // Backwards-compat: no pagination args → whole-file read with an explicit
        // truncation footer when the legacy byte cap kicks in. apply_patch flows
        // depend on full-file content for exact-match ORIGINAL blocks.
        if (offsetArg == null && limitArg == null) {
            String content = Files.readString(path);
            if (content.length() <= READ_LEGACY_BYTE_CAP) {
                return new ExecutionResult(true, content, null);
            }
            long totalLines = countLines(path);
            String head = content.substring(0, READ_LEGACY_BYTE_CAP);
            String footer = String.format(
                    "%n%n[truncated at %d chars of %d (%d lines total) — call read again with {\"offset\":<line>,\"limit\":<n>} to page]",
                    READ_LEGACY_BYTE_CAP, content.length(), totalLines);
            return new ExecutionResult(true, head + footer, null);
        }

        int startLine = Math.max(1, offsetArg != null ? offsetArg : 1);
        int maxLines = Math.min(READ_MAX_LINES,
                Math.max(1, limitArg != null ? limitArg : READ_DEFAULT_LINES));

        List<String> selected = new ArrayList<>(Math.min(maxLines, 1024));
        long totalLines = 0;
        try (Stream<String> stream = Files.lines(path)) {
            Iterator<String> it = stream.iterator();
            while (it.hasNext() && totalLines < startLine - 1L) {
                it.next();
                totalLines++;
            }
            while (it.hasNext() && selected.size() < maxLines) {
                selected.add(it.next());
                totalLines++;
            }
            while (it.hasNext()) {
                it.next();
                totalLines++;
            }
        }

        StringBuilder body = new StringBuilder(selected.size() * 80);
        for (int i = 0; i < selected.size(); i++) {
            body.append(startLine + i).append('\t').append(selected.get(i)).append('\n');
        }
        long endLine = startLine + selected.size() - 1L;
        boolean hasMore = endLine < totalLines;
        body.append(String.format("%n[lines %d-%d of %d%s]",
                startLine, endLine, totalLines,
                hasMore ? "; call again with {\"offset\":" + (endLine + 1) + "}" : ""));

        return new ExecutionResult(true, body.toString(), null);
    }

    private static long countLines(Path path) throws IOException {
        try (Stream<String> s = Files.lines(path)) {
            return s.count();
        }
    }

    private ExecutionResult fsGlob(Map<String, Object> args) throws IOException {
        String pattern = stringArg(args, "glob", "**/*");
        Path root = safePath(stringArg(args, "path", "."));
        int offset = Math.max(0, intArg(args, "offset", 0));
        int limit = Math.min(GLOB_MAX_LIMIT, Math.max(1, intArg(args, "limit", GLOB_DEFAULT_LIMIT)));

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> allMatches;
        try (Stream<Path> stream = Files.walk(root)) {
            allMatches = stream
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(matcher::matches)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        int total = allMatches.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<String> page = allMatches.subList(from, to);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matches", page);
        body.put("total", total);
        body.put("offset", from);
        body.put("limit", limit);
        if (to < total) {
            body.put("nextOffset", to);
            body.put("hint", "call again with {\"offset\":" + to + "} to page");
        }
        return new ExecutionResult(true, toJson(body), null);
    }

    private ExecutionResult fsGrep(Map<String, Object> args) throws IOException {
        String pattern = stringArg(args, "pattern", null);
        if (pattern == null || pattern.isBlank()) return new ExecutionResult(false, null, "pattern is required");
        Path root = safePath(stringArg(args, "path", "."));
        int offset = Math.max(0, intArg(args, "offset", 0));
        int limit = Math.min(GREP_MAX_LIMIT, Math.max(1, intArg(args, "limit", GREP_DEFAULT_LIMIT)));

        Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        // Collect hits across the file-walk cap (independent of the user's
        // result-window limit) so paging through results stays consistent.
        List<String> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(GREP_FILE_WALK_LIMIT)
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            for (int i = 0; i < lines.size(); i++) {
                                if (compiled.matcher(lines.get(i)).find()) {
                                    hits.add(root.relativize(p) + ":" + (i + 1) + ":" + lines.get(i));
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
        }

        int total = hits.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<String> page = hits.subList(from, to);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hits", page);
        body.put("total", total);
        body.put("offset", from);
        body.put("limit", limit);
        if (to < total) {
            body.put("nextOffset", to);
            body.put("hint", "call again with {\"offset\":" + to + "} to page");
        }
        return new ExecutionResult(true, toJson(body), null);
    }

    private ExecutionResult fsWrite(Map<String, Object> args, boolean requireExisting) throws IOException {
        Path path = safePath(stringArg(args, "path", null));
        if (requireExisting && !Files.exists(path)) {
            return new ExecutionResult(false, null, "Target file does not exist for edit");
        }
        String content = stringArg(args, "content", null);
        if (content == null) {
            return new ExecutionResult(false, null, "content is required");
        }
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return new ExecutionResult(true, "ok", null);
    }

    /**
     * Surgical string-replacement edit on an existing file.
     * - With (old_text, new_text): replaces a single occurrence of old_text. Refuses if
     *   old_text appears 0 times (nothing to edit) or >1 times (ambiguous).
     * - With (content) only: full-file rewrite, kept for backwards-compat with callers
     *   that always pass the entire new file body.
     */
    private ExecutionResult fsEdit(Map<String, Object> args) throws IOException {
        Path path = safePath(stringArg(args, "path", null));
        if (!Files.exists(path)) {
            return new ExecutionResult(false, null, "Target file does not exist for edit");
        }
        String oldText = firstNonNull(stringArg(args, "old_text", null), stringArg(args, "old_string", null));
        String newText = firstNonNull(stringArg(args, "new_text", null), stringArg(args, "new_string", null));
        if (oldText != null) {
            if (newText == null) newText = "";
            String body = Files.readString(path, StandardCharsets.UTF_8);
            int first = body.indexOf(oldText);
            if (first < 0) {
                return new ExecutionResult(false, null,
                        "edit: old_text not found in file. Did you `read` the file first to copy the exact bytes (including indentation)?");
            }
            int second = body.indexOf(oldText, first + 1);
            if (second >= 0) {
                return new ExecutionResult(false, null,
                        "edit: old_text matches multiple locations. Include enough surrounding context to make the match unique.");
            }
            String patched = body.substring(0, first) + newText + body.substring(first + oldText.length());
            // Refuse byte-identical edits. The model has been observed
            // issuing placeholder edits (old_text == new_text, or
            // semantically identical content) to satisfy a "you must edit
            // before replying" instruction; writing identical bytes back
            // succeeds at the OS level but leaves the SHA-256 hash
            // unchanged, which the builder loop then flags as a no-op.
            // Returning success here would make the audit report
            // "edit: 1 successful calls" alongside "draft unchanged" — a
            // self-contradiction that misleads the model on the next
            // retry. Surface the no-op explicitly instead.
            if (patched.equals(body)) {
                return new ExecutionResult(false, null,
                        "edit: no-op — old_text and new_text produce identical content (or are equal). "
                                + "Replying without editing is the correct action when the file is already correct; "
                                + "do not issue placeholder edits to satisfy a retry instruction.");
            }
            Files.writeString(path, patched, StandardCharsets.UTF_8);
            return new ExecutionResult(true, "ok", null);
        }
        String content = stringArg(args, "content", null);
        if (content == null) {
            return new ExecutionResult(false, null, "edit: provide either (old_text, new_text) or content");
        }
        // Full-file rewrite branch: refuse when the supplied content equals
        // the existing file bytes. Same rationale as the surgical-edit
        // guard above — surface no-op rewrites so the loop sees the truth.
        String existing = Files.readString(path, StandardCharsets.UTF_8);
        if (content.equals(existing)) {
            return new ExecutionResult(false, null,
                    "edit: no-op — content is byte-identical to the file on disk. "
                            + "Reply with a one-line confirmation instead of re-writing the same bytes.");
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return new ExecutionResult(true, "ok", null);
    }

    /**
     * Apply a multi-hunk diff to an existing file. Each hunk has the shape:
     *
     *     <<<<<<< ORIGINAL
     *     ...exact existing text...
     *     =======
     *     ...replacement text...
     *     >>>>>>> UPDATED
     *
     * Hunks are applied in order against the current file contents. ORIGINAL must match
     * the file byte-for-byte (including indentation) and unambiguously — multiple matches
     * fail the entire patch so the model is forced to include enough context.
     */
    private ExecutionResult fsApplyPatch(Map<String, Object> args) throws IOException {
        Path path = safePath(stringArg(args, "path", null));
        if (!Files.exists(path)) {
            return new ExecutionResult(false, null, "Target file does not exist for apply_patch");
        }
        String content = stringArg(args, "content", null);
        if (content == null || content.isBlank()) {
            return new ExecutionResult(false, null, "content is required");
        }
        // Trim a Codex-style envelope if present so the model can reuse that habit.
        content = content.replaceAll("(?m)^\\*\\*\\* Begin Patch\\s*$", "")
                .replaceAll("(?m)^\\*\\*\\* End Patch\\s*$", "")
                .replaceAll("(?m)^\\*\\*\\* (?:Update|Add|Delete) File:.*$", "")
                .trim();

        List<String[]> hunks = parsePatchHunks(content);
        if (hunks.isEmpty()) {
            return new ExecutionResult(false, null,
                    "apply_patch: no hunks found. Use blocks of <<<<<<< ORIGINAL / ======= / >>>>>>> UPDATED.");
        }
        String originalBody = Files.readString(path, StandardCharsets.UTF_8);
        String body = originalBody;
        int applied = 0;
        for (String[] hunk : hunks) {
            String original = hunk[0];
            String updated = hunk[1];
            int first = body.indexOf(original);
            if (first < 0) {
                return new ExecutionResult(false, null,
                        "apply_patch: hunk #" + (applied + 1) + " ORIGINAL block not found in file. "
                                + "ORIGINAL must match the current file exactly, including indentation. "
                                + "Re-`read` the file and try again.");
            }
            int second = body.indexOf(original, first + 1);
            if (second >= 0) {
                return new ExecutionResult(false, null,
                        "apply_patch: hunk #" + (applied + 1) + " ORIGINAL block matches multiple locations. "
                                + "Include more surrounding context so the match is unique.");
            }
            body = body.substring(0, first) + updated + body.substring(first + original.length());
            applied++;
        }
        // Refuse a multi-hunk patch whose net effect is byte-identical to
        // the original file. Same rationale as fsEdit's no-op guard —
        // returning success here would lie to the no-op detector in
        // WorkflowBuilderService and start a retry spiral.
        if (body.equals(originalBody)) {
            return new ExecutionResult(false, null,
                    "apply_patch: no-op — all hunks net out to byte-identical content. "
                            + "If the file is already correct, reply with a one-line confirmation instead of "
                            + "re-issuing an identical patch.");
        }
        Files.writeString(path, body, StandardCharsets.UTF_8);
        return new ExecutionResult(true, "ok (" + applied + " hunk" + (applied == 1 ? "" : "s") + " applied)", null);
    }

    private static final Pattern PATCH_HUNK = Pattern.compile(
            "(?s)<{3,}\\s*ORIGINAL\\s*\\r?\\n(.*?)\\r?\\n={3,}\\s*\\r?\\n(.*?)\\r?\\n>{3,}\\s*UPDATED");

    private List<String[]> parsePatchHunks(String content) {
        List<String[]> hunks = new ArrayList<>();
        Matcher m = PATCH_HUNK.matcher(content);
        while (m.find()) {
            hunks.add(new String[] { m.group(1), m.group(2) });
        }
        return hunks;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private Path safePath(String input) {
        String raw = (input == null || input.isBlank()) ? "." : input;
        Path root = workspaceRoot();
        Path resolved = root.resolve(raw).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace root");
        }
        // When an ov-* profile binds a sub-root, file-system tools may
        // only touch paths inside it. Without this the model could
        // glob `.pods-agent/skills/**` or escape the order's own
        // subtree on a typo.
        String sub = OV_SUBROOT.get();
        if (sub != null && !sub.isEmpty()) {
            Path allowed = root.resolve(sub).normalize();
            if (!resolved.startsWith(allowed) && !resolved.equals(allowed)) {
                throw new IllegalArgumentException(
                        "Path outside allowed scope: must be under '" + sub + "'");
            }
        }
        return resolved;
    }

    private Path workspaceRoot() {
        Path workspace = WorkspaceContextHolder.current();
        return workspace != null ? workspace : Path.of("").toAbsolutePath().normalize();
    }

    private Map<String, Object> parseArgs(String userText) {
        if (userText == null || userText.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(userText, Map.class);
        } catch (Exception ignored) {
            return Map.of("query", userText);
        }
    }

    private String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        return String.valueOf(value);
    }

    private Integer intArg(Map<String, Object> args, String key, Integer fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) return true;
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) return false;
        return fallback;
    }

    private List<String> listArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private String resolveExecutionKind(AgentTool tool) {
        if (tool.getExecutionKind() != null && !tool.getExecutionKind().isBlank()) {
            return tool.getExecutionKind();
        }
        String name = tool.getName() == null ? "" : tool.getName().toLowerCase();
        if (Set.of("read", "glob", "grep", "write", "edit", "apply_patch").contains(name)) return "filesystem";
        if ("bash".equals(name)) return "shell";
        if (Set.of("webfetch", "websearch", "codesearch").contains(name)) return "web";
        if (Set.of("memory_save", "memory_search", "memoryview", "memorycreate", "memorystrreplace", "memoryinsert", "memorydelete", "memoryrename").contains(name)) return "memory";
        if (Set.of("lsp", "agent_send", "agent_receive", "skill").contains(name)) return "integration";
        return "http_proxy";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String resolveEndpoint(String host, String endpoint) {
        String base = (host != null && !host.isBlank()) ? host.trim() : null;
        String path = (endpoint != null && !endpoint.isBlank()) ? endpoint.trim() : null;

        String normalized;
        if (base != null && path != null) {
            String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            String cleanPath = path.startsWith("/") ? path : "/" + path;
            normalized = cleanBase + cleanPath;
        } else if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
            normalized = path; // backward compat: full URL stored in endpoint
        } else if (path != null && path.startsWith("/")) {
            normalized = "http://localhost" + path;
        } else if (path != null) {
            normalized = "http://localhost/" + path;
        } else {
            return null;
        }
        try {
            // OpenAPI-imported tool endpoints can include URI template placeholders
            // like /resource/{id}. Validate a safe form but preserve the original.
            String validationCandidate = normalized.replaceAll("\\{[^}/]+}", "placeholder");
            URI uri = new URI(validationCandidate);
            if (uri.getHost() == null || uri.getScheme() == null) return null;
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return null;
            return normalized;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String appendQueryParams(String endpoint, Map<String, String> queryParams) {
        if (endpoint == null || endpoint.isBlank() || queryParams == null || queryParams.isEmpty()) {
            return endpoint;
        }
        StringBuilder builder = new StringBuilder(endpoint);
        builder.append(endpoint.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!first) builder.append("&");
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append("=");
            builder.append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private String hydratePathParams(String endpoint, Map<String, Object> args, Set<String> consumedKeys) {
        if (endpoint == null || endpoint.isBlank() || args == null || args.isEmpty()) return endpoint;
        String hydrated = endpoint;
        java.util.regex.Matcher matcher = Pattern.compile("\\{([^}/]+)}").matcher(endpoint);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key == null || key.isBlank()) continue;
            Object value = findArgIgnoreCase(args, key);
            if (value == null) continue;
            String encoded = URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
            hydrated = hydrated.replace("{" + key + "}", encoded);
            consumedKeys.add(key.toLowerCase());
        }
        return hydrated;
    }

    private List<String> unresolvedPathParams(String endpoint) {
        List<String> missing = new ArrayList<>();
        if (endpoint == null || endpoint.isBlank()) return missing;
        java.util.regex.Matcher matcher = Pattern.compile("\\{([^}/]+)}").matcher(endpoint);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key != null && !key.isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    private Map<String, String> extractQueryParams(Map<String, Object> args, Set<String> consumedKeys) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args == null || args.isEmpty()) return out;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) continue;
            String normalized = key.toLowerCase();
            if ("query".equals(normalized) || consumedKeys.contains(normalized)) continue;
            Object value = entry.getValue();
            if (value == null) continue;
            if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                out.put(key, String.valueOf(value));
                consumedKeys.add(normalized);
            }
        }
        return out;
    }

    private Object findArgIgnoreCase(Map<String, Object> args, String key) {
        if (args == null || args.isEmpty() || key == null) return null;
        if (args.containsKey(key)) return args.get(key);
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> normalizeDomainArgs(String toolName, Map<String, Object> rawArgs) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (rawArgs != null) args.putAll(rawArgs);
        flattenKnownNestedArgs(args);
        String normalizedName = normalizeToolName(toolName);

        if (isContainerAvailabilityTool(normalizedName)) {
            Object zip = firstPresentArg(args, "zip", "Zip", "postalCode", "PostalCode", "zipcode", "zipCode");
            if (zip != null) {
                putIfMissing(args, "zip", zip);
                putIfMissing(args, "Zip", zip);
                putIfMissing(args, "postalCode", zip);
                putIfMissing(args, "PostalCode", zip);
            }
            Object region = firstPresentArg(args, "regionCode", "RegionCode", "countryCode", "CountryCode");
            if (region != null) {
                putIfMissing(args, "regionCode", region);
                putIfMissing(args, "RegionCode", region);
                putIfMissing(args, "countryCode", region);
                putIfMissing(args, "CountryCode", region);
            }
            Object serviceDate = firstPresentArg(args, "serviceDate", "ServiceDate", "requestedDate", "date");
            if (serviceDate != null) {
                putIfMissing(args, "serviceDate", serviceDate);
                putIfMissing(args, "ServiceDate", serviceDate);
            }
            Object serviceType = firstPresentArg(args, "serviceType", "ServiceType", "legCode", "code");
            if (serviceType != null) {
                putIfMissing(args, "serviceType", serviceType);
                putIfMissing(args, "ServiceType", serviceType);
            }
            Object siteIdentity = firstPresentArg(args, "siteIdentity", "SiteIdentity", "serviceCenter", "sc");
            if (siteIdentity != null) {
                putIfMissing(args, "siteIdentity", siteIdentity);
                putIfMissing(args, "SiteIdentity", siteIdentity);
            }
        }

        if (isServiceabilityTool(normalizedName)) {
            Object originZip = firstPresentArg(args, "originPostalCode", "originZip", "origin_zip");
            Object destinationZip = firstPresentArg(args, "destinationPostalCode", "destinationZip", "destination_zip");
            if (originZip != null) {
                putIfMissing(args, "originPostalCode", originZip);
                putIfMissing(args, "originZip", originZip);
            }
            if (destinationZip != null) {
                putIfMissing(args, "destinationPostalCode", destinationZip);
                putIfMissing(args, "destinationZip", destinationZip);
            }
        }
        return args;
    }

    private Object firstPresentArg(Map<String, Object> args, String... keys) {
        if (args == null || args.isEmpty() || keys == null) return null;
        for (String key : keys) {
            Object value = findArgIgnoreCase(args, key);
            if (!isBlankValue(value)) return value;
        }
        return null;
    }

    private boolean isBlankValue(Object value) {
        if (value == null) return true;
        String text = String.valueOf(value);
        return text == null || text.isBlank();
    }

    private void putIfMissing(Map<String, Object> args, String key, Object value) {
        if (key == null || key.isBlank() || value == null) return;
        Object existing = findArgIgnoreCase(args, key);
        if (isBlankValue(existing)) args.put(key, value);
    }

    private String buildRequestBody(AgentTool tool,
                                    Map<String, Object> args,
                                    Set<String> consumedKeys,
                                    String userText,
                                    String method) {
        if ("GET".equals(method) || "DELETE".equals(method)) return null;

        // Highest priority: explicit "body" argument from tool call input.
        Object explicitBody = findArgIgnoreCase(args, "body");
        if (explicitBody instanceof String s && !s.isBlank()) {
            return s;
        }
        if (explicitBody instanceof Map<?, ?> mapBody) {
            try {
                Map<String, Object> normalized = new LinkedHashMap<>();
                mapBody.forEach((k, v) -> normalized.put(String.valueOf(k), v));
                return objectMapper.writeValueAsString(normalized);
            } catch (Exception ignored) {
            }
        }

        // Next: use direct arguments as JSON payload (excluding query/path helper keys).
        Map<String, Object> payload = new LinkedHashMap<>();
        if (args != null && !args.isEmpty()) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) continue;
                String normalized = key.toLowerCase();
                if ("query".equals(normalized) || "body".equals(normalized) || consumedKeys.contains(normalized)) continue;
                payload.put(key, entry.getValue());
            }
        }
        if (!payload.isEmpty()) {
            String mergedWithTemplate = mergeWithSampleRequestTemplate(tool, payload);
            if (mergedWithTemplate != null) {
                return mergedWithTemplate;
            }
            try {
                return objectMapper.writeValueAsString(payload);
            } catch (Exception ignored) {
            }
        }

        // Fallback to sampleRequest for backwards compatibility with static tools.
        if (tool.getSampleRequest() != null && !tool.getSampleRequest().isBlank()) {
            return tool.getSampleRequest();
        }
        return "{\"query\":\"" + sanitize(userText) + "\"}";
    }

    @SuppressWarnings("unchecked")
    private String mergeWithSampleRequestTemplate(AgentTool tool, Map<String, Object> payload) {
        if (tool == null || payload == null || payload.isEmpty()) return null;
        String sample = tool.getSampleRequest();
        if (sample == null || sample.isBlank()) return null;
        try {
            Map<String, Object> sampleMap = objectMapper.readValue(sample, Map.class);
            if (sampleMap == null || sampleMap.isEmpty()) return null;
            Map<String, Object> merged = new LinkedHashMap<>(sampleMap);
            // Overlay model-provided arguments while keeping template defaults/required keys.
            payload.forEach((k, v) -> {
                merged.put(k, v);
                if ("postalCode".equalsIgnoreCase(k)) {
                    merged.put("Zip", v);
                    merged.put("zip", v);
                    merged.put("PostalCode", v);
                }
                if ("regionCode".equalsIgnoreCase(k)) {
                    merged.put("RegionCode", v);
                    merged.put("countryCode", v);
                    merged.put("CountryCode", v);
                }
                if ("serviceDate".equalsIgnoreCase(k)) {
                    merged.put("ServiceDate", v);
                }
                if ("serviceType".equalsIgnoreCase(k)) {
                    merged.put("ServiceType", v);
                }
                if ("siteIdentity".equalsIgnoreCase(k)) {
                    merged.put("SiteIdentity", v);
                }
            });
            return objectMapper.writeValueAsString(merged);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean requiresMappedPayload(String toolName) {
        String normalized = normalizeToolName(toolName);
        return isServiceabilityTool(normalized) || isContainerAvailabilityTool(normalized);
    }

    private boolean shouldMirrorArgsToQuery(AgentTool tool) {
        if (tool == null || tool.getRequestSchema() == null || tool.getRequestSchema().isBlank()) return false;
        try {
            Map<String, Object> parsed = objectMapper.readValue(tool.getRequestSchema(), Map.class);
            if (parsed == null || parsed.isEmpty()) return false;
            Object direct = findArgIgnoreCase(parsed, "mirrorArgsToQuery");
            if (direct instanceof Boolean b) return b;
            Object legacy = findArgIgnoreCase(parsed, "queryParamMirror");
            if (legacy instanceof Boolean b) return b;
            Object transport = findArgIgnoreCase(parsed, "transport");
            if (transport instanceof Map<?, ?> map) {
                Object nested = findArgIgnoreCase((Map<String, Object>) map, "mirrorArgsToQuery");
                if (nested instanceof Boolean b) return b;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenKnownNestedArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return;
        Object leg = firstPresentArg(args, "leg", "orderLeg", "segment");
        if (leg instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String key = k == null ? null : String.valueOf(k);
                if (key == null || key.isBlank() || v == null) return;
                args.putIfAbsent(key, v);
            });
        }
        Object order = firstPresentArg(args, "order", "orderData", "orderDetails");
        if (order instanceof Map<?, ?> map) {
            Object originZip = findArgIgnoreCase((Map<String, Object>) map, "originZip");
            Object destinationZip = findArgIgnoreCase((Map<String, Object>) map, "destinationZip");
            if (!isBlankValue(originZip)) putIfMissing(args, "originZip", originZip);
            if (!isBlankValue(destinationZip)) putIfMissing(args, "destinationZip", destinationZip);
        }
    }

    private boolean isServiceabilityTool(String normalizedName) {
        return "serviceability".equals(normalizedName);
    }

    private boolean isContainerAvailabilityTool(String normalizedName) {
        return "containeravailability".equals(normalizedName);
    }

    private String normalizeToolName(String value) {
        if (value == null) return "";
        return value.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private List<String> missingRequiredDecisionInputs(String tableName, Map<String, Object> inputs) {
        if (decisionTableService == null || tableName == null || tableName.isBlank()) return List.of();
        List<String> required;
        try {
            required = decisionTableService.requiredInputNames(tableName);
        } catch (Exception ignored) {
            return List.of();
        }
        if (required == null || required.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (key == null || key.isBlank()) continue;
            Object value = findArgIgnoreCase(inputs, key);
            if (isBlankValue(value)) missing.add(key);
        }
        return missing;
    }

    private boolean isLikelyEmptyJsonObject(String body) {
        if (body == null) return true;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return true;
        if ("{}".equals(trimmed)) return true;
        try {
            Map<String, Object> parsed = objectMapper.readValue(trimmed, Map.class);
            return parsed == null || parsed.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, String> parseHeaders(String requestSchema) {
        Map<String, String> headers = new HashMap<>();
        if (requestSchema == null || requestSchema.isBlank()) return headers;
        try {
            Map<?, ?> parsed = objectMapper.readValue(requestSchema, Map.class);
            Object h = parsed.get("headers");
            if (h instanceof Map<?, ?> hm) {
                hm.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            }
        } catch (Exception ignored) {
        }
        return headers;
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replace("\"", "\\\"");
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    private String asCurl(String method, String endpoint, Map<String, String> headers, String body) {
        StringBuilder out = new StringBuilder("curl -X ").append(method).append(" '").append(endpoint).append("'");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                out.append(" -H '")
                        .append(entry.getKey())
                        .append(": ")
                        .append(maskHeaderValue(entry.getKey(), entry.getValue()))
                        .append("'");
            }
        }
        if (body != null && !body.isBlank()) {
            out.append(" --data '").append(sanitizeSingleQuotes(truncate(body, 1000))).append("'");
        }
        return out.toString();
    }

    private String maskHeaderValue(String key, String value) {
        if (value == null) return "";
        String k = key == null ? "" : key.toLowerCase();
        if (k.contains("authorization") || k.contains("token") || k.contains("api-key") || k.contains("apikey")) {
            return "***";
        }
        return value;
    }

    private String sanitizeSingleQuotes(String value) {
        if (value == null) return "";
        return value.replace("'", "'\"'\"'");
    }

    private String extractMcpError(String result) {
        if (result == null || result.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(result, Map.class);
            Object isError = parsed.get("isError");
            if (Boolean.TRUE.equals(isError)) {
                return truncate(result, 500);
            }
            Object error = parsed.get("error");
            if (error != null) {
                return String.valueOf(error);
            }
            Object content = parsed.get("content");
            if (content instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> row)) continue;
                    Object text = row.get("text");
                    if (text != null && String.valueOf(text).toLowerCase().contains("not found")) {
                        return String.valueOf(text);
                    }
                }
            }
            return null;
        } catch (Exception ignored) {
            if (result.toLowerCase().contains("unknown tool") || result.toLowerCase().contains("not found")) {
                return truncate(result, 500);
            }
            return null;
        }
    }

    private static final class CircuitState {
        private int failures = 0;
        private long openedAt = -1L;

        boolean isOpen(long now) {
            if (openedAt <= 0) return false;
            if (now - openedAt > CIRCUIT_OPEN_MS) {
                openedAt = -1L;
                failures = 0;
                return false;
            }
            return true;
        }

        void recordFailure(long now) {
            failures++;
            if (failures >= FAILURE_THRESHOLD) {
                openedAt = now;
            }
        }

        void reset() {
            failures = 0;
            openedAt = -1L;
        }
    }

    public record ExecutionResult(boolean success, String body, String error) {}
}
