package com.pods.agent.ordervalidation.service;

import com.pods.agent.ordervalidation.model.ActivityInvocation;
import com.pods.agent.ordervalidation.model.CheckResults;
import com.pods.agent.ordervalidation.model.RunDetail;
import com.pods.agent.service.workspace.SessionWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Materializes one OV run's data into the per-session workspace so the
 * chat assistant can navigate it with the standard {@code read} /
 * {@code glob} / {@code grep} file-system tools.
 *
 * <p>Layout under {@code ${workspace}/orders/${orderIdSlug}/}:
 * <pre>
 *   summary.md
 *   run.json
 *   order_payload.json
 *   leg_sequence.json
 *   serviceability.jsonl
 *   container.jsonl
 *   activity_timeline.jsonl
 *   calls/leg-sequence/&lt;n&gt;-&lt;activity&gt;.{in,out}.json
 *   calls/serviceability/leg-&lt;NN&gt;.{in,out}.json
 *   calls/container/line-&lt;NN&gt;.{in,out}.json
 *   .meta/touched_at      &larr; epoch millis; refreshed on every materialize()
 * </pre>
 *
 * Files use {@code runDetail / orderPayload / runActivities} from
 * {@link OrderValidationAnalyticsService} verbatim; no new SQL.
 *
 * <p>Each call to {@link #materialize(String, String)} re-writes every file
 * and updates {@code .meta/touched_at}. The {@link #evictStale(Duration)}
 * sweep deletes per-order subtrees whose touch is older than the TTL.
 */
@Service
@Slf4j
public class OvOrderVfsService {

    /** Concept ids the framework knows how to resolve into per-call activity events. */
    private static final List<String> CONCEPT_RULES = List.of(
            "evaluateLegSequence", "callServiceability", "callContainerAvailability");

    /** Per-order TTL: re-materialize automatically clears it. */
    static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private final SessionWorkspaceService workspaceService;
    private final OrderValidationAnalyticsService analytics;
    private final NamedParameterJdbcTemplate jdbc;
    /** Pretty-printed mapper for *.json files (human-readable). */
    private final ObjectMapper pretty;
    /** Compact mapper for *.jsonl rows (one object per line). */
    private final ObjectMapper compact;

    public OvOrderVfsService(SessionWorkspaceService workspaceService,
                             OrderValidationAnalyticsService analytics,
                             NamedParameterJdbcTemplate jdbc) {
        this.workspaceService = workspaceService;
        this.analytics = analytics;
        this.jdbc = jdbc;
        this.pretty = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        this.compact = JsonMapper.builder().build();
    }

    /**
     * Materialize a run into the session workspace. The {@code orderOrInstId}
     * may be either a raw orderId (e.g. {@code 600030447}) or the synthetic
     * run id ({@code ov-run-…__ov-turn-…}). Returns the per-order directory.
     *
     * <p>Idempotent: a second call re-writes the files and bumps the TTL.
     */
    public Path materialize(String sessionId, String orderOrInstId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        if (orderOrInstId == null || orderOrInstId.isBlank()) {
            throw new IllegalArgumentException("orderId required");
        }

        String runId = resolveRunId(orderOrInstId);
        if (runId == null) {
            throw new IllegalStateException(
                    "No OV run found for order/instId " + orderOrInstId);
        }

        RunDetail detail = analytics.runDetail(runId);
        if (detail == null) {
            throw new IllegalStateException(
                    "Run " + runId + " resolved but no detail available");
        }

        String slug = slugify(detail.orderId() != null ? detail.orderId() : orderOrInstId);
        Path workspace = workspaceService.getOrCreate(sessionId);
        Path orderDir = workspace.resolve("orders").resolve(slug).normalize();
        if (!orderDir.startsWith(workspace)) {
            throw new IllegalStateException("Order dir escapes workspace");
        }

        try {
            Files.createDirectories(orderDir);
            writeSummary(detail, orderDir);
            writeJson(orderDir.resolve("run.json"), detail);
            writeJson(orderDir.resolve("order_payload.json"),
                    safeOrderPayload(runId));
            writeJson(orderDir.resolve("leg_sequence.json"), detail.legSequence());
            writeJsonLines(orderDir.resolve("serviceability.jsonl"), detail.serviceability());
            writeJsonLines(orderDir.resolve("container.jsonl"), detail.containerAvailability());
            writeJsonLines(orderDir.resolve("activity_timeline.jsonl"), detail.timeline());

            writeCalls(orderDir.resolve("calls"), runId);
            writeTouchedAt(orderDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize OV run " + runId, e);
        }

        log.info("[OvVfs] Materialized session={} order={} runId={} dir={}",
                sessionId, slug, runId, orderDir);
        return orderDir;
    }

    /** Names of subdirs under {@code orders/} in this session. */
    public List<String> listOrders(String sessionId) {
        Path workspace = workspaceService.get(sessionId);
        if (workspace == null) return List.of();
        Path orders = workspace.resolve("orders");
        if (!Files.isDirectory(orders)) return List.of();
        try (var stream = Files.list(orders)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** True if the subtree exists and was touched within the TTL. */
    public boolean isFresh(String sessionId, String orderIdSlug) {
        Path workspace = workspaceService.get(sessionId);
        if (workspace == null) return false;
        Path stamp = workspace.resolve("orders").resolve(slugify(orderIdSlug))
                .resolve(".meta").resolve("touched_at");
        if (!Files.isRegularFile(stamp)) return false;
        try {
            long touched = Long.parseLong(Files.readString(stamp).trim());
            return Duration.ofMillis(System.currentTimeMillis() - touched)
                    .compareTo(DEFAULT_TTL) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Recursively delete one order's subtree from the workspace. */
    public void evictOrder(String sessionId, String orderIdSlug) {
        Path workspace = workspaceService.get(sessionId);
        if (workspace == null) return;
        Path dir = workspace.resolve("orders").resolve(slugify(orderIdSlug));
        deleteRecursively(dir);
    }

    /**
     * Sweep every session's {@code orders/*} dirs and delete those whose
     * {@code .meta/touched_at} is older than the given TTL. Runs on a
     * scheduled cadence; safe to call manually.
     */
    public int evictStale(Duration ttl) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "pods-agent-vfs");
        if (!Files.isDirectory(root)) return 0;
        long cutoff = System.currentTimeMillis() - ttl.toMillis();
        int removed = 0;
        try (var sessions = Files.list(root)) {
            for (Path sessionDir : (Iterable<Path>) sessions::iterator) {
                Path orders = sessionDir.resolve("orders");
                if (!Files.isDirectory(orders)) continue;
                try (var orderDirs = Files.list(orders)) {
                    for (Path orderDir : (Iterable<Path>) orderDirs::iterator) {
                        if (!Files.isDirectory(orderDir)) continue;
                        Path stamp = orderDir.resolve(".meta").resolve("touched_at");
                        long touched = readTouched(stamp);
                        if (touched > 0 && touched < cutoff) {
                            deleteRecursively(orderDir);
                            removed++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[OvVfs] evictStale walk failed: {}", e.getMessage());
        }
        if (removed > 0) {
            log.info("[OvVfs] evictStale removed {} stale order subtrees (ttl={})",
                    removed, ttl);
        }
        return removed;
    }

    /** Scheduled sweep: every 15 minutes; TTL is {@link #DEFAULT_TTL}. */
    @Scheduled(fixedDelay = 15L * 60 * 1000, initialDelay = 60L * 1000)
    public void scheduledEvict() {
        try {
            evictStale(DEFAULT_TTL);
        } catch (Exception e) {
            log.warn("[OvVfs] scheduled evict failed: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    /** Map an orderId or a synthetic instId to a canonical runId. */
    private String resolveRunId(String input) {
        // Synthetic instIds carry the "__" separator from
        // session_id || '__' || turn_id (see OV_ID_EXPR). Treat them as
        // run ids directly.
        if (input.contains("__")) return input;
        // Otherwise look up the most recent run for this orderId. We
        // use the underlying rule_executions + rule_domains directly
        // rather than the OV-runs CTE so the lookup works for
        // any skill (caller's workflow may not be the only OV skill).
        try {
            List<String> rows = jdbc.query(
                    "SELECT CASE WHEN e.session_id IS NOT NULL AND e.session_id <> '' "
                    + "         THEN e.session_id || '__' || COALESCE(e.turn_id, '') "
                    + "         ELSE e.id END AS run_id "
                    + "FROM agent.rule_executions e "
                    + "WHERE e.inputs_json IS NOT NULL "
                    + "  AND NULLIF(e.inputs_json, '')::jsonb ->> 'orderId' = :oid "
                    + "ORDER BY e.created_at DESC LIMIT 1",
                    new MapSqlParameterSource("oid", input),
                    (rs, i) -> rs.getString("run_id"));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[OvVfs] resolveRunId failed for {}: {}", input, e.getMessage());
            return null;
        }
    }

    private Object safeOrderPayload(String runId) {
        try {
            Map<String, Object> body = analytics.orderPayload(runId);
            return body != null ? body : Map.of("payload", null);
        } catch (Exception e) {
            return Map.of("error", "orderPayload failed: " + e.getMessage());
        }
    }

    /** Build a short, human-readable summary as the model's entry point. */
    private void writeSummary(RunDetail detail, Path orderDir) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("# Order ").append(detail.orderId()).append("\n\n");
        s.append("- **Run id**: `").append(detail.instId()).append("`\n");
        s.append("- **State**: ").append(nz(detail.state())).append("\n");
        s.append("- **Overall**: ").append(nz(detail.overallStatus())).append("\n");
        if (detail.journeyType() != null && !detail.journeyType().isBlank()) {
            s.append("- **Order type**: ").append(detail.journeyType()).append("\n");
        }
        s.append("- **Started**: ")
                .append(Instant.ofEpochMilli(detail.startedAt())).append("\n");
        if (detail.endedAt() != null) {
            s.append("- **Ended**: ")
                    .append(Instant.ofEpochMilli(detail.endedAt())).append("\n");
        }
        if (detail.durationMs() != null) {
            s.append("- **Duration**: ").append(detail.durationMs()).append(" ms\n");
        }
        if (detail.errorMessage() != null && !detail.errorMessage().isBlank()) {
            s.append("\n## Error\n").append(detail.errorMessage()).append("\n");
        }

        s.append("\n## Leg sequence\n");
        if (detail.legSequence() != null) {
            CheckResults.LegSequenceResult ls = detail.legSequence();
            s.append("- valid: ").append(ls.valid()).append("\n");
            s.append("- matchedRule: ").append(nz(ls.matchedRule())).append("\n");
            if (ls.actualSequence() != null && !ls.actualSequence().isEmpty()) {
                s.append("- sequence: ").append(String.join(" → ", ls.actualSequence())).append("\n");
            }
            if (ls.message() != null && !ls.message().isBlank()) {
                s.append("- message: ").append(ls.message()).append("\n");
            }
        } else {
            s.append("(no leg-sequence result)\n");
        }

        s.append("\n## Serviceability (")
                .append(detail.serviceability() == null ? 0 : detail.serviceability().size())
                .append(" rows)\n");
        if (detail.serviceability() != null) {
            int passed = 0, failed = 0;
            for (var r : detail.serviceability()) {
                if (Boolean.TRUE.equals(r.isServiceable())) passed++;
                else failed++;
            }
            s.append("- pass: ").append(passed).append(" · fail: ").append(failed).append("\n");
        }

        s.append("\n## Container availability (")
                .append(detail.containerAvailability() == null ? 0 : detail.containerAvailability().size())
                .append(" IDEL lines)\n");

        s.append("\n## Files in this directory\n");
        s.append("- `run.json` — full RunDetail (per-check verdicts + timeline)\n");
        s.append("- `order_payload.json` — raw Get_OrderID response (Lines, Addresses, …)\n");
        s.append("- `leg_sequence.json` — leg-sequence rule result\n");
        s.append("- `serviceability.jsonl` — one record per leg\n");
        s.append("- `container.jsonl` — one record per IDEL line (with skip reason)\n");
        s.append("- `activity_timeline.jsonl` — every per-activity event in time order\n");
        s.append("- `calls/<rule>/*.{in,out}.json` — raw input/output of each toolCall\n");

        Files.writeString(orderDir.resolve("summary.md"), s.toString());
    }

    /** Write all per-concept toolCall (input, output) pairs under {@code calls/}. */
    private void writeCalls(Path callsRoot, String runId) throws IOException {
        for (String concept : CONCEPT_RULES) {
            List<ActivityInvocation> events;
            try {
                events = analytics.runActivities(runId, concept);
            } catch (Exception e) {
                log.warn("[OvVfs] runActivities failed for {}/{}: {}",
                        runId, concept, e.getMessage());
                continue;
            }
            if (events == null || events.isEmpty()) continue;
            Path dir = callsRoot.resolve(folderForConcept(concept));
            Files.createDirectories(dir);
            for (int i = 0; i < events.size(); i++) {
                ActivityInvocation ev = events.get(i);
                String base = filePrefix(concept, ev, i);
                writeJson(dir.resolve(base + ".in.json"), ev.input());
                writeJson(dir.resolve(base + ".out.json"), ev.output());
            }
        }
    }

    private static String folderForConcept(String concept) {
        return switch (concept) {
            case "evaluateLegSequence" -> "leg-sequence";
            case "callServiceability" -> "serviceability";
            case "callContainerAvailability" -> "container";
            default -> concept;
        };
    }

    private static String filePrefix(String concept, ActivityInvocation ev, int i) {
        return switch (concept) {
            case "callServiceability" -> String.format("leg-%02d", i);
            case "callContainerAvailability" -> String.format("line-%02d", i);
            default -> String.format("%02d", i);
        };
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, pretty.writeValueAsString(value == null ? Map.of() : value));
    }

    private void writeJsonLines(Path path, List<?> items) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        if (items != null) {
            for (Object it : items) {
                // jsonl: one compact JSON object per line.
                sb.append(compact.writeValueAsString(it == null ? Collections.emptyMap() : it));
                sb.append('\n');
            }
        }
        Files.writeString(path, sb.toString());
    }

    private void writeTouchedAt(Path orderDir) throws IOException {
        Path meta = orderDir.resolve(".meta");
        Files.createDirectories(meta);
        Files.writeString(meta.resolve("touched_at"),
                Long.toString(System.currentTimeMillis()));
    }

    private long readTouched(Path stamp) {
        if (!Files.isRegularFile(stamp)) return 0;
        try {
            return Long.parseLong(Files.readString(stamp).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            paths.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : paths) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* ignore */ }
            }
        } catch (IOException e) {
            log.warn("[OvVfs] deleteRecursively failed for {}: {}", dir, e.getMessage());
        }
    }

    private static String slugify(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }
}
