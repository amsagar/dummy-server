package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turn-scoped, single-flight cache for tool executions.
 *
 * <p>Multiple rules running in parallel (Phase 1.6 fan-out) frequently need
 * the same shared lookup — e.g. all three checks under {@code Pods-Order-Validation}
 * begin by calling {@code Get_OrderID(ORD_ID=X)}. Without coalescing we'd
 * issue N parallel HTTP calls for the same response; with single-flight
 * semantics the first caller registers an in-flight {@link CompletableFuture}
 * and every later caller waits on it.
 *
 * <p><b>Scope</b>: lifetime = chat turn. A new turn starts with an empty
 * cache. There is no TTL — when the turn ends, {@link #clearTurn(String)}
 * drops the bucket. This means subsequent turns always re-fetch (correct:
 * users typing "re-validate" implicitly want fresh data).
 *
 * <p><b>Failure policy</b>: failures are <em>not</em> cached. If the first
 * caller's invocation fails, the entry is evicted via {@link #evict} so
 * siblings retry independently. Combined with the existing retry-on-timeout
 * in {@code ToolCallDelegate}, this avoids amplifying transient failures
 * across all waiting callers.
 *
 * <p><b>Cancellation</b>: when a turn is cancelled mid-flight,
 * {@link #clearTurn} attempts to {@code cancel(true)} every outstanding
 * future, which propagates as a thread interrupt to the JDK {@code HttpClient}
 * doing the underlying I/O.
 */
@Component
@Slf4j
public class TurnToolCache {

    /** Conservative default: cache GET/POST-shaped reads, skip mutations.
     *  Tool-level override comes via {@code AgentTool.cacheable} (Phase 1.3). */
    private static final ObjectMapper CANONICALIZER = new ObjectMapper();

    private final ConcurrentHashMap<String, ConcurrentHashMap<CacheKey, CompletableFuture<ToolExecutionService.ExecutionResult>>>
            byTurn = new ConcurrentHashMap<>();

    /**
     * Either register a pending future for the first caller (returns
     * {@code primary=true}, the caller is responsible for {@link #complete}
     * or {@link #evict}) or hand back the in-flight future shared by an
     * earlier caller (returns {@code primary=false}, the caller just
     * {@code .get()}s the result).
     *
     * <p>When {@code turnId} is {@code null} or the cache is disabled for
     * the tool, returns {@link AcquireResult#disabled()} and the caller
     * should fall back to direct execution.
     */
    public AcquireResult acquireOrWait(String turnId, AgentTool tool, String canonicalArgsJson) {
        if (turnId == null || turnId.isBlank() || tool == null || !isCacheable(tool)) {
            return AcquireResult.disabled();
        }
        CacheKey key = CacheKey.of(tool.getName(), canonicalArgsJson);

        ConcurrentHashMap<CacheKey, CompletableFuture<ToolExecutionService.ExecutionResult>> bucket =
                byTurn.computeIfAbsent(turnId, k -> new ConcurrentHashMap<>());

        // Single-flight: only the thread that successfully inserts a new pending
        // future becomes the primary. All others receive the existing future.
        CompletableFuture<ToolExecutionService.ExecutionResult> newPending = new CompletableFuture<>();
        CompletableFuture<ToolExecutionService.ExecutionResult> existing = bucket.putIfAbsent(key, newPending);
        if (existing == null) {
            return AcquireResult.primary(key, newPending);
        }
        return AcquireResult.waiter(key, existing);
    }

    /** Primary caller finished successfully. Signals waiters with the result. */
    public void complete(String turnId, CacheKey key, ToolExecutionService.ExecutionResult result) {
        CompletableFuture<ToolExecutionService.ExecutionResult> future = futureOf(turnId, key);
        if (future != null && !future.isDone()) {
            future.complete(result);
        }
    }

    /** Primary caller failed. Removes the entry so siblings can re-attempt the
     *  call rather than inherit the failure (which would amplify transient
     *  upstream errors across every parallel rule). */
    public void evict(String turnId, CacheKey key, Throwable cause) {
        ConcurrentHashMap<CacheKey, CompletableFuture<ToolExecutionService.ExecutionResult>> bucket = byTurn.get(turnId);
        if (bucket == null) return;
        CompletableFuture<ToolExecutionService.ExecutionResult> removed = bucket.remove(key);
        if (removed != null && !removed.isDone()) {
            // Complete with the failure so any current waiter unwinds rather
            // than blocking forever. A NEW caller after eviction gets a fresh
            // future via acquireOrWait.
            removed.completeExceptionally(cause == null ? new RuntimeException("Cache entry evicted") : cause);
        }
    }

    /** Drop a turn's entire cache. Cancels every outstanding future so any
     *  thread still waiting (e.g. on a cancelled turn) unblocks immediately. */
    public void clearTurn(String turnId) {
        if (turnId == null) return;
        ConcurrentHashMap<CacheKey, CompletableFuture<ToolExecutionService.ExecutionResult>> bucket = byTurn.remove(turnId);
        if (bucket == null) return;
        for (CompletableFuture<ToolExecutionService.ExecutionResult> f : bucket.values()) {
            if (!f.isDone()) {
                // cancel(true) propagates as Thread.interrupt() on the executing thread,
                // which the JDK HttpClient observes to abort in-flight requests.
                f.cancel(true);
            }
        }
    }

    private CompletableFuture<ToolExecutionService.ExecutionResult> futureOf(String turnId, CacheKey key) {
        ConcurrentHashMap<CacheKey, CompletableFuture<ToolExecutionService.ExecutionResult>> bucket = byTurn.get(turnId);
        return bucket == null ? null : bucket.get(key);
    }

    /** A tool is cacheable when:
     *  <ul>
     *    <li>operator hasn't explicitly disabled it via {@code AgentTool.cacheable=false}, AND</li>
     *    <li>its HTTP method is read-shaped (GET / POST queries) — mutations are NEVER cached.</li>
     *  </ul> */
    private boolean isCacheable(AgentTool tool) {
        if (tool == null) return false;
        if (Boolean.FALSE.equals(tool.getCacheable())) return false;
        String method = tool.getMethod() == null ? "GET" : tool.getMethod().toUpperCase();
        if ("PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method)) {
            return false;
        }
        return true;
    }

    /** Canonicalize a JSON args payload so semantically-equal payloads share a
     *  cache key. Sorts object keys recursively, normalizes numeric forms,
     *  strips insignificant whitespace.
     *
     *  <p>Returns the canonical string; on parse failure returns the original
     *  text (so a malformed payload still has a stable, if naive, key). */
    public static String canonicalize(String json) {
        if (json == null) return "";
        try {
            JsonNode node = CANONICALIZER.readTree(json);
            return canonicalNode(node).toString();
        } catch (Exception ex) {
            return json.strip();
        }
    }

    private static JsonNode canonicalNode(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            keys.addAll(obj.propertyNames());
            Collections.sort(keys);
            ObjectNode sorted = CANONICALIZER.createObjectNode();
            for (String k : keys) {
                sorted.set(k, canonicalNode(obj.get(k)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode out = CANONICALIZER.createArrayNode();
            for (JsonNode child : arr) out.add(canonicalNode(child));
            return out;
        }
        // Numbers / booleans / strings — pass through; Jackson already
        // normalizes whitespace in scalar serialization.
        return node;
    }

    public record CacheKey(String toolName, String canonicalArgs) {
        public static CacheKey of(String toolName, String canonicalArgs) {
            return new CacheKey(
                    Objects.requireNonNullElse(toolName, ""),
                    Objects.requireNonNullElse(canonicalArgs, ""));
        }
    }

    /** Outcome of {@link #acquireOrWait}. */
    public record AcquireResult(
            boolean enabled,
            boolean primary,
            CacheKey key,
            CompletableFuture<ToolExecutionService.ExecutionResult> future) {

        public static AcquireResult disabled() {
            return new AcquireResult(false, false, null, null);
        }

        public static AcquireResult primary(CacheKey k, CompletableFuture<ToolExecutionService.ExecutionResult> f) {
            return new AcquireResult(true, true, k, f);
        }

        public static AcquireResult waiter(CacheKey k, CompletableFuture<ToolExecutionService.ExecutionResult> f) {
            return new AcquireResult(true, false, k, f);
        }
    }

    /** Diagnostics — current cache size for a turn (for tests + admin). */
    public Map<String, Integer> sizeByTurn() {
        Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
        byTurn.forEach((tid, bucket) -> sizes.put(tid, bucket.size()));
        return sizes;
    }
}
