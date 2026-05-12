package com.pods.agent.orderValidation;

import com.pods.agent.workflow.api.ProcessDefService;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import com.pods.agent.workflow.persistence.ActivityInstRepository;
import com.pods.agent.workflow.persistence.ActivityInstRow;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import com.pods.agent.workflow.persistence.ProcessInstRow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Aggregates completed (and in-progress) workflow runs into the analytics
 * shapes the order-validation UI consumes.
 *
 * <p>The source of truth is {@code agent.process_inst} — every completed
 * order-validation run stores its Step 7 payload as {@code result_json}.
 * The service queries
 * {@link ProcessInstRepository#listFiltered(String, String, String, String, Long, Long, int, int)},
 * parses each row's JSON, and folds them into dashboard / per-check / per-row
 * views. No mutating calls are made.
 */
@Service
@Slf4j
public class OrderValidationAnalyticsService {

    private static final int MAX_ROWS_FOR_AGGREGATES = 2000;

    private final ProcessInstRepository processInstRepo;
    private final ActivityInstRepository activityInstRepo;
    private final ProcessDefService processDefService;
    private final ObjectMapper objectMapper;

    public OrderValidationAnalyticsService(ProcessInstRepository processInstRepo,
                                           ActivityInstRepository activityInstRepo,
                                           ProcessDefService processDefService,
                                           ObjectMapper objectMapper) {
        this.processInstRepo = processInstRepo;
        this.activityInstRepo = activityInstRepo;
        this.processDefService = processDefService;
        this.objectMapper = objectMapper;
    }

    public List<OrderValidationDtos.WorkflowSummary> listWorkflows() {
        return processDefService.findAll().stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing(s -> s.name() == null ? "" : s.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private OrderValidationDtos.WorkflowSummary toSummary(ProcessDefDto def) {
        return new OrderValidationDtos.WorkflowSummary(def.id(), def.name(), def.version(), def.description());
    }

    public OrderValidationDtos.DashboardMetrics dashboard(String workflowId, Long fromTs, Long toTs) {
        List<RunView> runs = fetchRuns(workflowId, fromTs, toTs, MAX_ROWS_FOR_AGGREGATES);

        long total = runs.size();
        long passed = runs.stream().filter(r -> "clear".equals(r.overallStatus)).count();
        long failed = runs.stream().filter(r -> "failed".equals(r.overallStatus) || "error".equals(r.overallStatus)).count();
        double passRate = total == 0 ? 0.0 : (passed * 100.0) / total;

        Long avgMs = avgDurationMs(runs);

        long legPass = 0, legFail = 0, svcPass = 0, svcFail = 0, contPass = 0, contFail = 0, contSkip = 0;
        for (RunView r : runs) {
            if (r.payload == null) continue;
            if (Boolean.TRUE.equals(r.legSequenceValid)) legPass++;
            else if (Boolean.FALSE.equals(r.legSequenceValid)) legFail++;

            for (ServiceabilityEntry s : r.serviceability) {
                if ("skipped".equalsIgnoreCase(s.status)) continue;
                if (Boolean.TRUE.equals(s.isServiceable)) svcPass++;
                else svcFail++;
            }
            for (ContainerEntry c : r.containerAvailability) {
                if (!c.checked) contSkip++;
                else if (c.availableDates != null && !c.availableDates.isEmpty()) contPass++;
                else contFail++;
            }
        }

        List<OrderValidationDtos.VolumeBucket> buckets = bucketByDay(runs, fromTs, toTs);
        List<OrderValidationDtos.RecentResult> recent = runs.stream()
                .sorted(Comparator.comparingLong((RunView r) -> r.startedAt).reversed())
                .limit(10)
                .map(this::toRecentResult)
                .toList();

        return new OrderValidationDtos.DashboardMetrics(
                total,
                round1(passRate),
                failed,
                avgMs,
                new OrderValidationDtos.PassFailByCheck(legPass, legFail, svcPass, svcFail, contPass, contFail, contSkip),
                buckets,
                recent
        );
    }

    public OrderValidationDtos.OrderQueueResponse orderQueue(String workflowId,
                                                             Long fromTs,
                                                             Long toTs,
                                                             String status,
                                                             String search,
                                                             int limit,
                                                             int offset) {
        // We fetch up to MAX_ROWS, then filter/paginate in memory. For larger
        // installations push the filters into SQL via process_inst columns,
        // but result_json filters always require app-side parsing.
        List<RunView> all = fetchRuns(workflowId, fromTs, toTs, MAX_ROWS_FOR_AGGREGATES);

        long total = all.size();
        long passed = all.stream().filter(r -> "clear".equals(r.overallStatus)).count();
        long review = all.stream().filter(r -> "review".equals(r.overallStatus)).count();
        long failed = all.stream().filter(r -> "failed".equals(r.overallStatus) || "error".equals(r.overallStatus)).count();

        List<RunView> filtered = all.stream()
                .filter(r -> matchesStatus(r, status))
                .filter(r -> matchesSearch(r, search))
                .sorted(Comparator.comparingLong((RunView r) -> r.startedAt).reversed())
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();

        List<OrderValidationDtos.OrderQueueRow> rows = filtered.stream()
                .map(this::toQueueRow)
                .toList();

        return new OrderValidationDtos.OrderQueueResponse(total, passed, review, failed, rows);
    }

    public OrderValidationDtos.LegSequenceSummary legSequence(String workflowId,
                                                              Long fromTs,
                                                              Long toTs,
                                                              String search,
                                                              int limit,
                                                              int offset) {
        List<RunView> runs = fetchRuns(workflowId, fromTs, toTs, MAX_ROWS_FOR_AGGREGATES);
        List<RunView> withLeg = runs.stream().filter(r -> r.legSequenceValid != null).toList();

        long total = withLeg.size();
        long pass = withLeg.stream().filter(r -> Boolean.TRUE.equals(r.legSequenceValid)).count();
        long fail = total - pass;
        double passRate = total == 0 ? 0.0 : (pass * 100.0) / total;

        Map<String, Long> byJourney = new HashMap<>();
        for (RunView r : withLeg) {
            if (Boolean.FALSE.equals(r.legSequenceValid)) {
                String jt = r.journeyType == null ? "Unknown" : r.journeyType;
                byJourney.merge(jt, 1L, Long::sum);
            }
        }
        List<OrderValidationDtos.FailuresByJourney> failuresByJourney = byJourney.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new OrderValidationDtos.FailuresByJourney(e.getKey(), e.getValue()))
                .toList();

        String mostCommon = failuresByJourney.isEmpty() ? null
                : "Missing leg · " + failuresByJourney.get(0).journeyType();

        String needle = search == null ? null : search.toLowerCase(Locale.ROOT).trim();
        List<RunView> matched = withLeg.stream()
                .filter(r -> matchesOrderId(r.orderId, needle))
                .sorted(Comparator.comparingLong((RunView r) -> r.startedAt).reversed())
                .toList();

        int safeLimit = clampLimit(limit);
        int safeOffset = clampOffset(offset, matched.size());

        List<OrderValidationDtos.LegSequenceResult> recent = matched.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .map(r -> new OrderValidationDtos.LegSequenceResult(
                        r.instId,
                        r.orderId,
                        r.journeyType,
                        r.actualSequence,
                        r.matchedRule,
                        Boolean.TRUE.equals(r.legSequenceValid),
                        r.legSequenceMessage
                ))
                .toList();

        return new OrderValidationDtos.LegSequenceSummary(
                total, round1(passRate), fail, mostCommon, failuresByJourney, recent, matched.size());
    }

    private static boolean matchesOrderId(String orderId, String needle) {
        if (needle == null || needle.isEmpty()) return true;
        return orderId != null && orderId.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 25;
        return Math.min(limit, 200);
    }

    private static int clampOffset(int offset, int total) {
        if (offset <= 0) return 0;
        if (offset >= total) return Math.max(0, total - 1);
        return offset;
    }

    public OrderValidationDtos.ServiceabilitySummary serviceability(String workflowId,
                                                                    Long fromTs,
                                                                    Long toTs,
                                                                    String search,
                                                                    int limit,
                                                                    int offset) {
        List<RunView> runs = fetchRuns(workflowId, fromTs, toTs, MAX_ROWS_FOR_AGGREGATES);

        long total = 0, serviceable = 0, exceptions = 0, skipped = 0;
        Map<String, Long> byException = new HashMap<>();
        for (RunView r : runs) {
            for (ServiceabilityEntry s : r.serviceability) {
                total++;
                if ("skipped".equalsIgnoreCase(s.status)) {
                    skipped++;
                } else if (Boolean.TRUE.equals(s.isServiceable)) {
                    serviceable++;
                } else {
                    exceptions++;
                    if (s.exceptionType != null && !s.exceptionType.isBlank()) {
                        byException.merge(s.exceptionType, 1L, Long::sum);
                    }
                }
            }
        }

        // Paginate by ORDER so leg groups stay whole. Order matches when its
        // orderId contains the search needle (case-insensitive).
        String needle = search == null ? null : search.toLowerCase(Locale.ROOT).trim();
        List<RunView> matchedOrders = runs.stream()
                .filter(r -> !r.serviceability.isEmpty())
                .filter(r -> matchesOrderId(r.orderId, needle))
                .sorted(Comparator.comparingLong((RunView r) -> r.startedAt).reversed())
                .toList();

        int safeLimit = clampLimit(limit);
        int safeOffset = clampOffset(offset, matchedOrders.size());

        List<OrderValidationDtos.ServiceabilityResult> recentRows = new ArrayList<>();
        for (RunView r : matchedOrders.stream().skip(safeOffset).limit(safeLimit).toList()) {
            for (ServiceabilityEntry s : r.serviceability) {
                recentRows.add(new OrderValidationDtos.ServiceabilityResult(
                        r.instId, r.orderId, s.lineId, s.itemCode, s.originZip, s.destinationZip,
                        s.isServiceable, s.exceptionType, s.status));
            }
        }

        double serviceableRate = total == 0 ? 0.0 : (serviceable * 100.0) / total;
        List<OrderValidationDtos.ExceptionBreakdown> breakdown = byException.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new OrderValidationDtos.ExceptionBreakdown(e.getKey(), e.getValue()))
                .toList();

        return new OrderValidationDtos.ServiceabilitySummary(
                total, round1(serviceableRate), exceptions, skipped, breakdown, recentRows, matchedOrders.size());
    }

    public OrderValidationDtos.ContainerAvailabilitySummary containerAvailability(String workflowId,
                                                                                  Long fromTs,
                                                                                  Long toTs,
                                                                                  String search,
                                                                                  int limit,
                                                                                  int offset) {
        List<RunView> runs = fetchRuns(workflowId, fromTs, toTs, MAX_ROWS_FOR_AGGREGATES);

        long total = 0, withDates = 0, skipped = 0, noAvailability = 0;
        Map<String, Long> byReason = new HashMap<>();

        for (RunView r : runs) {
            for (ContainerEntry c : r.containerAvailability) {
                total++;
                if (!c.checked) {
                    skipped++;
                    String reason = c.skipReason == null ? "Unknown" : c.skipReason;
                    byReason.merge(reason, 1L, Long::sum);
                } else if (c.availableDates != null && !c.availableDates.isEmpty()) {
                    withDates++;
                } else {
                    noAvailability++;
                }
            }
        }

        String needle = search == null ? null : search.toLowerCase(Locale.ROOT).trim();
        List<RunView> matchedOrders = runs.stream()
                .filter(r -> !r.containerAvailability.isEmpty())
                .filter(r -> matchesOrderId(r.orderId, needle))
                .sorted(Comparator.comparingLong((RunView r) -> r.startedAt).reversed())
                .toList();

        int safeLimit = clampLimit(limit);
        int safeOffset = clampOffset(offset, matchedOrders.size());

        List<OrderValidationDtos.ContainerAvailabilityResult> recentRows = new ArrayList<>();
        for (RunView r : matchedOrders.stream().skip(safeOffset).limit(safeLimit).toList()) {
            for (ContainerEntry c : r.containerAvailability) {
                recentRows.add(new OrderValidationDtos.ContainerAvailabilityResult(
                        r.instId, r.orderId, c.lineId, c.itemCode, c.checked,
                        c.availableDates == null ? List.of() : c.availableDates,
                        c.skipReason));
            }
        }

        double rate = total == 0 ? 0.0 : (withDates * 100.0) / total;
        List<OrderValidationDtos.SkipReason> skipReasons = byReason.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new OrderValidationDtos.SkipReason(e.getKey(), e.getValue()))
                .toList();

        return new OrderValidationDtos.ContainerAvailabilitySummary(
                total, round1(rate), skipped, noAvailability, skipReasons, recentRows, matchedOrders.size());
    }

    public List<OrderValidationDtos.ActivityInvocation> activityIo(String instId, String defId) {
        List<ActivityInstRow> rows = activityInstRepo.findByInstIdAndDefIdOrdered(instId, defId);

        // Resolve the activity definition so we can re-evaluate its `input`
        // SpEL expression against each iteration's snapshot. This gives the
        // caller the *actual* tool input (e.g. `{originZip, destinationZip,
        // ...}` for callServiceability) rather than the full variable scope.
        Optional<ProcessDefDto.ActivityDto> activityDef = processInstRepo.findById(instId)
                .flatMap(p -> processDefService.findById(p.defId()))
                .flatMap(def -> def.activities().stream()
                        .filter(a -> defId.equals(a.id()))
                        .findFirst());
        String inputExpr = activityDef
                .map(ProcessDefDto.ActivityDto::properties)
                .map(p -> p.get("input"))
                .filter(v -> v instanceof String)
                .map(Object::toString)
                .orElse(null);

        List<OrderValidationDtos.ActivityInvocation> out = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ActivityInstRow r = rows.get(i);
            Object rawInput = parseJsonOrNull(r.inputSnapshot());
            Object rawOutput = parseJsonOrNull(r.outputSnapshot());
            out.add(new OrderValidationDtos.ActivityInvocation(
                    r.id(),
                    i,
                    r.state(),
                    r.startedAt(),
                    r.endedAt(),
                    r.attempt(),
                    resolveActivityInput(inputExpr, rawInput),
                    unwrapEnvelopeOutput(rawOutput),
                    r.errorMessage()));
        }
        return out;
    }

    /**
     * If the activity has a SpEL `input` property, re-evaluate it against
     * the snapshot map so the caller sees only what the tool actually
     * consumed. Falls back to the raw snapshot when there's no expression
     * or evaluation fails.
     *
     * <p>Workflow expressions are stored in the {@code #{ ... }} template
     * form; the engine's ActivityDispatcher strips the wrapper before
     * handing the inner expression to {@code SecureSpelEvaluator} (see
     * ActivityDispatcher#unwrap). We mirror that here — without it the
     * SpEL parser treats {@code #{} as a syntax error and silently falls
     * back to the raw snapshot.
     */
    @SuppressWarnings("unchecked")
    private Object resolveActivityInput(String inputExpr, Object rawInput) {
        if (inputExpr == null || inputExpr.isBlank()) return rawInput;
        String trimmed = inputExpr.trim();
        String expr = trimmed.startsWith("#{") && trimmed.endsWith("}")
                ? trimmed.substring(2, trimmed.length() - 1)
                : trimmed;

        Map<String, Object> bindings;
        if (rawInput instanceof JsonNode node && node.isObject()) {
            bindings = objectMapper.convertValue(node, Map.class);
        } else if (rawInput instanceof Map<?, ?> m) {
            bindings = (Map<String, Object>) m;
        } else {
            return rawInput;
        }
        try {
            var result = com.pods.agent.workflow.joget.expression.SecureSpelEvaluator
                    .evaluate(expr, bindings);
            if (result.ok()) return result.value();
            log.debug("SpEL re-evaluation failed for activity input: {}", result.error());
        } catch (Exception ex) {
            log.debug("Failed to re-evaluate activity input expression: {}", ex.getMessage());
        }
        return rawInput;
    }

    /**
     * CodeExecPlugin wraps the user code's return value as
     * {@code {success, output, stdout, stderr}}. Surface only the {@code
     * output} field by default — the rest is noise for activity I/O views.
     * AgentToolPlugin results are returned as-is.
     */
    private Object unwrapEnvelopeOutput(Object raw) {
        if (raw instanceof JsonNode node && node.isObject()
                && node.has("success") && node.has("output")) {
            return node.get("output");
        }
        return raw;
    }

    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.debug("Failed to parse activity snapshot JSON: {}", ex.getMessage());
            return json;
        }
    }

    public Optional<OrderValidationDtos.RunDetail> runDetail(String instId) {
        Optional<ProcessInstRow> rowOpt = processInstRepo.findById(instId);
        if (rowOpt.isEmpty()) return Optional.empty();
        ProcessInstRow row = rowOpt.get();
        RunView v = buildView(row);
        enrichWithOrderMetadata(List.of(v));
        enrichWithLegPrepMetadata(List.of(v));
        enrichWithContainerReasons(v);

        // Pull the activity timeline.
        List<ActivityInstRow> acts = activityInstRepo.findByInstId(instId);
        List<OrderValidationDtos.ActivityTimeline> timeline = acts.stream()
                .map(a -> new OrderValidationDtos.ActivityTimeline(
                        a.activityDefId(), a.type(), a.state(), a.startedAt(), a.endedAt(), a.attempt(), a.errorMessage()))
                .toList();

        OrderValidationDtos.LegSequenceResult legResult = new OrderValidationDtos.LegSequenceResult(
                v.instId,
                Optional.ofNullable(v.orderId).orElse("—"),
                v.journeyType,
                v.actualSequence,
                v.matchedRule,
                Boolean.TRUE.equals(v.legSequenceValid),
                v.legSequenceMessage
        );

        List<OrderValidationDtos.ServiceabilityResult> svc = v.serviceability.stream()
                .map(s -> new OrderValidationDtos.ServiceabilityResult(
                        v.instId, v.orderId, s.lineId, s.itemCode, s.originZip, s.destinationZip,
                        s.isServiceable, s.exceptionType, s.status))
                .toList();

        List<OrderValidationDtos.ContainerAvailabilityResult> cont = v.containerAvailability.stream()
                .map(c -> new OrderValidationDtos.ContainerAvailabilityResult(
                        v.instId, v.orderId, c.lineId, c.itemCode, c.checked,
                        c.availableDates == null ? List.of() : c.availableDates,
                        c.skipReason))
                .toList();

        Long durationMs = (v.endedAt != null && v.startedAt > 0) ? Math.max(0, v.endedAt - v.startedAt) : null;

        return Optional.of(new OrderValidationDtos.RunDetail(
                v.instId,
                Optional.ofNullable(v.orderId).orElse("—"),
                v.journeyType,
                v.state,
                v.overallStatus,
                v.startedAt,
                v.endedAt,
                durationMs,
                row.errorClass(),
                row.errorMessage(),
                legResult,
                svc,
                cont,
                timeline
        ));
    }

    // ------------------------------------------------------------------
    //  Internal mechanics
    // ------------------------------------------------------------------

    private List<RunView> fetchRuns(String workflowId, Long fromTs, Long toTs, int limit) {
        List<ProcessInstRow> rows = processInstRepo.listFiltered(
                null, null, workflowId, null, fromTs, toTs, limit, 0);
        List<RunView> out = new ArrayList<>(rows.size());
        for (ProcessInstRow row : rows) {
            out.add(buildView(row));
        }
        enrichWithOrderMetadata(out);
        enrichWithLegPrepMetadata(out);
        for (RunView v : out) enrichWithContainerReasons(v);
        return out;
    }

    /**
     * Batch-loads the {@code fetchOrder} activity's {@code output_snapshot}
     * for every run in the page and folds {@code OrderType} (journey type)
     * and a few other order-level fields back into each {@link RunView}.
     *
     * <p>This is the fallback for runs whose {@code result_json} doesn't
     * include {@code journeyType} directly — the workflow template can be
     * updated to include it later, at which point this lookup becomes a
     * no-op (the field is already populated and we skip the override).
     */
    private void enrichWithOrderMetadata(List<RunView> runs) {
        if (runs.isEmpty()) return;
        List<String> instIds = runs.stream().map(r -> r.instId).filter(java.util.Objects::nonNull).toList();
        if (instIds.isEmpty()) return;

        Map<String, String> snapshots = activityInstRepo.findOutputSnapshotsByInstIdsAndDefId(instIds, "fetchOrder");
        if (snapshots.isEmpty()) return;

        for (RunView r : runs) {
            String snap = snapshots.get(r.instId);
            if (snap == null || snap.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(snap);
                JsonNode order = unwrapEnvelope(node);
                if (order == null || !order.isObject()) continue;

                if (r.journeyType == null) {
                    String orderType = textOrNull(order.get("OrderType"));
                    if (orderType != null) r.journeyType = orderType;
                }
                if (r.orderId == null) {
                    JsonNode oi = order.get("OrderIdentity");
                    if (oi != null && !oi.isNull()) r.orderId = oi.isTextual() ? oi.asText() : oi.toString();
                }
            } catch (Exception ex) {
                log.debug("Failed to parse fetchOrder output_snapshot for inst {}: {}", r.instId, ex.getMessage());
            }
        }
    }

    /**
     * Fallback enrichment for runs created before the workflow template
     * started emitting {@code actualSequence} at the root of the result and
     * before {@code accumulateServiceability} added {@code originZip}
     * /{@code destinationZip} on each entry. Reads
     * {@code prepareLegLines.output_snapshot} once per run and folds the
     * data into the in-memory {@link RunView} so the UI sees populated
     * fields without re-running.
     */
    private void enrichWithLegPrepMetadata(List<RunView> runs) {
        if (runs.isEmpty()) return;
        List<String> instIds = runs.stream().map(r -> r.instId).filter(java.util.Objects::nonNull).toList();
        if (instIds.isEmpty()) return;

        Map<String, String> snapshots = activityInstRepo.findOutputSnapshotsByInstIdsAndDefId(instIds, "prepareLegLines");
        if (snapshots.isEmpty()) return;

        for (RunView r : runs) {
            String snap = snapshots.get(r.instId);
            if (snap == null || snap.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(snap);
                JsonNode body = unwrapEnvelope(node);
                if (body == null || !body.isObject()) continue;

                if (r.actualSequence == null || r.actualSequence.isEmpty()) {
                    List<String> seq = readStringArray(body.get("actualSequence"));
                    if (seq != null && !seq.isEmpty()) r.actualSequence = seq;
                }
                if (r.journeyType == null) {
                    String jt = textOrNull(body.get("journeyType"));
                    if (jt != null) r.journeyType = jt;
                }

                JsonNode legLines = body.get("legLines");
                if (legLines == null || !legLines.isArray() || r.serviceability.isEmpty()) continue;

                Map<String, String[]> zipsByLineId = new HashMap<>();
                for (JsonNode leg : legLines) {
                    String lineId = textOrNull(leg.get("LineIdentity"));
                    if (lineId == null) continue;
                    String origin = null, dest = null;
                    JsonNode addrs = leg.get("Addresses");
                    if (addrs != null && addrs.isArray()) {
                        for (JsonNode a : addrs) {
                            String type = textOrNull(a.get("AddressType"));
                            String zip = textOrNull(a.get("PostalCode"));
                            if (zip == null) continue;
                            if ("Origination".equals(type)) origin = zip;
                            else if ("Destination".equals(type)) dest = zip;
                        }
                    }
                    zipsByLineId.put(lineId, new String[]{origin, dest});
                }
                for (ServiceabilityEntry s : r.serviceability) {
                    if (s.lineId == null) continue;
                    String[] zips = zipsByLineId.get(s.lineId);
                    if (zips == null) continue;
                    if (s.originZip == null) s.originZip = zips[0];
                    if (s.destinationZip == null) s.destinationZip = zips[1];
                }
            } catch (Exception ex) {
                log.debug("Failed to parse prepareLegLines output_snapshot for inst {}: {}", r.instId, ex.getMessage());
            }
        }
    }

    /**
     * Fallback for runs created before {@code accumulateContainer} started
     * persisting {@code skipReason}. Walks {@code callContainerAvailability}
     * activity snapshots in iteration order — those align 1:1 with the
     * container entries on the run — and pulls the first non-blank
     * {@code ReasonCode} from each response as the {@code skipReason}.
     */
    private void enrichWithContainerReasons(RunView v) {
        if (v.containerAvailability.isEmpty()) return;
        boolean anyMissing = false;
        for (ContainerEntry c : v.containerAvailability) {
            if (c.skipReason == null && c.availableDates != null && c.availableDates.isEmpty()) {
                anyMissing = true;
                break;
            }
        }
        if (!anyMissing) return;
        List<ActivityInstRow> rows = activityInstRepo.findByInstIdAndDefIdOrdered(v.instId, "callContainerAvailability");
        for (int i = 0; i < v.containerAvailability.size() && i < rows.size(); i++) {
            ContainerEntry c = v.containerAvailability.get(i);
            if (c.skipReason != null) continue;
            if (c.availableDates != null && !c.availableDates.isEmpty()) continue;
            String snap = rows.get(i).outputSnapshot();
            if (snap == null || snap.isBlank()) continue;
            try {
                JsonNode node = unwrapEnvelope(objectMapper.readTree(snap));
                if (node == null || !node.isObject()) continue;
                JsonNode dates = node.get("GeneralAvailabilityDates");
                if (dates == null || !dates.isArray()) continue;
                for (JsonNode d : dates) {
                    String reason = textOrNull(d.get("ReasonCode"));
                    if (reason != null && !reason.isBlank()) {
                        c.skipReason = reason;
                        break;
                    }
                }
            } catch (Exception ex) {
                log.debug("Failed to parse callContainerAvailability snapshot for inst {}: {}", v.instId, ex.getMessage());
            }
        }
    }

    /**
     * Tool plugins wrap returns as {@code {success, output, stdout, stderr}}.
     * The actual order JSON lives at {@code .output} when present; if the
     * snapshot is the bare order object, return it as-is.
     */
    private static JsonNode unwrapEnvelope(JsonNode node) {
        if (node == null) return null;
        if (node.isObject() && node.has("output")) {
            JsonNode inner = node.get("output");
            return inner == null || inner.isNull() ? node : inner;
        }
        return node;
    }

    private RunView buildView(ProcessInstRow row) {
        RunView v = new RunView();
        v.instId = row.id();
        v.startedAt = row.startedAt() == null ? 0L : row.startedAt();
        v.endedAt = row.endedAt();
        v.state = row.state();
        v.errorMessage = row.errorMessage();

        if (row.resultJson() != null && !row.resultJson().isBlank()) {
            try {
                v.payload = objectMapper.readTree(row.resultJson());
                parsePayload(v);
            } catch (Exception ex) {
                log.debug("Failed to parse result_json for inst {}: {}", row.id(), ex.getMessage());
            }
        }

        v.overallStatus = computeOverallStatus(v);
        return v;
    }

    private void parsePayload(RunView v) {
        JsonNode root = v.payload;
        if (root == null || !root.isObject()) return;

        v.orderId = textOrNull(root.get("orderId"));
        v.journeyType = textOrNull(root.get("journeyType"));
        v.actualSequence = readStringArray(root.get("actualSequence"));

        JsonNode leg = root.get("legSequence");
        if (leg != null && leg.isObject()) {
            // Decision-table response shape: { matched, outputs, matchedRows } OR
            // skill-spec shape: { valid, journeyType, sequence, message }.
            if (leg.has("matched")) v.legSequenceValid = leg.get("matched").asBoolean();
            else if (leg.has("valid")) v.legSequenceValid = leg.get("valid").asBoolean();

            JsonNode outputs = leg.get("outputs");
            if (v.journeyType == null) {
                JsonNode jt = leg.get("journeyType");
                if (jt != null && jt.isTextual()) v.journeyType = jt.asText();
                else if (outputs != null && outputs.has("journeyType")) v.journeyType = textOrNull(outputs.get("journeyType"));
            }

            JsonNode msg = leg.get("message");
            if (msg != null && msg.isTextual()) v.legSequenceMessage = msg.asText();
            else if (outputs != null && outputs.has("message")) v.legSequenceMessage = textOrNull(outputs.get("message"));

            if (v.actualSequence == null || v.actualSequence.isEmpty()) {
                JsonNode seq = leg.get("sequence");
                if (seq == null && outputs != null) seq = outputs.get("sequence");
                v.actualSequence = readStringArray(seq);
            }

            JsonNode matchedRows = leg.get("matchedRows");
            if (matchedRows != null && matchedRows.isArray() && matchedRows.size() > 0) {
                JsonNode first = matchedRows.get(0);
                v.matchedRule = textOrNull(first.get("ruleId"));
                if (v.matchedRule == null) v.matchedRule = textOrNull(first.get("id"));
            }
        }

        JsonNode svc = root.get("serviceability");
        if (svc != null && svc.isArray()) {
            for (JsonNode entry : svc) {
                ServiceabilityEntry s = new ServiceabilityEntry();
                s.lineId = textOrNull(entry.get("lineId"));
                s.itemCode = textOrNull(entry.get("itemCode"));
                s.originZip = textOrNull(entry.get("originZip"));
                s.destinationZip = textOrNull(entry.get("destinationZip"));
                s.isServiceable = entry.has("isServiceable") && !entry.get("isServiceable").isNull()
                        ? entry.get("isServiceable").asBoolean() : null;
                s.exceptionType = textOrNull(entry.get("exceptionType"));
                s.status = textOrNull(entry.get("status"));
                v.serviceability.add(s);
            }
        }

        JsonNode cont = root.get("containerAvailability");
        if (cont != null && cont.isArray()) {
            for (JsonNode entry : cont) {
                ContainerEntry c = new ContainerEntry();
                c.lineId = textOrNull(entry.get("lineId"));
                c.itemCode = textOrNull(entry.get("itemCode"));
                c.checked = entry.has("checked") && entry.get("checked").asBoolean(false);
                c.availableDates = readStringArray(entry.get("availableDates"));
                c.skipReason = textOrNull(entry.get("skipReason"));
                v.containerAvailability.add(c);
            }
        }
    }

    private String computeOverallStatus(RunView v) {
        if (v.state == null) return "unknown";
        if (v.state.startsWith("open.running")) return "running";
        if (!v.state.startsWith("closed.completed")) return "error";
        // closed.completed — judge by check outcomes
        boolean anyFail = Boolean.FALSE.equals(v.legSequenceValid);
        boolean anyExc = false;
        for (ServiceabilityEntry s : v.serviceability) {
            if ("skipped".equalsIgnoreCase(s.status)) continue;
            if (Boolean.FALSE.equals(s.isServiceable)) anyExc = true;
        }
        boolean anyContFail = false;
        for (ContainerEntry c : v.containerAvailability) {
            if (c.checked && (c.availableDates == null || c.availableDates.isEmpty())) anyContFail = true;
        }
        if (anyFail) return "failed";
        if (anyExc || anyContFail) return "review";
        return "clear";
    }

    private List<OrderValidationDtos.VolumeBucket> bucketByDay(List<RunView> runs, Long fromTs, Long toTs) {
        if (runs.isEmpty()) return List.of();
        ZoneId zone = ZoneId.systemDefault();
        long start = fromTs != null
                ? startOfDay(fromTs, zone)
                : runs.stream().mapToLong(r -> r.startedAt).min().orElse(System.currentTimeMillis());
        long end = toTs != null
                ? startOfDay(toTs, zone)
                : runs.stream().mapToLong(r -> r.startedAt).max().orElse(System.currentTimeMillis());

        Map<Long, long[]> byDay = new LinkedHashMap<>();
        for (long day = start; day <= end; day = nextDay(day, zone)) {
            byDay.put(day, new long[]{0L, 0L});
        }

        for (RunView r : runs) {
            long day = startOfDay(r.startedAt, zone);
            long[] cell = byDay.computeIfAbsent(day, k -> new long[]{0L, 0L});
            cell[0]++;
            if ("failed".equals(r.overallStatus) || "error".equals(r.overallStatus)) cell[1]++;
        }

        List<OrderValidationDtos.VolumeBucket> out = new ArrayList<>(byDay.size());
        for (Map.Entry<Long, long[]> e : byDay.entrySet()) {
            out.add(new OrderValidationDtos.VolumeBucket(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    private static long startOfDay(long ts, ZoneId zone) {
        return Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static long nextDay(long dayStartTs, ZoneId zone) {
        return LocalDate.from(Instant.ofEpochMilli(dayStartTs).atZone(zone))
                .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private Long avgDurationMs(List<RunView> runs) {
        long sum = 0;
        int n = 0;
        for (RunView r : runs) {
            if (r.endedAt != null && r.startedAt > 0) {
                sum += Math.max(0, r.endedAt - r.startedAt);
                n++;
            }
        }
        if (n == 0) return null;
        return sum / n;
    }

    private OrderValidationDtos.RecentResult toRecentResult(RunView r) {
        return new OrderValidationDtos.RecentResult(
                r.instId,
                Optional.ofNullable(r.orderId).orElse("—"),
                r.journeyType,
                r.legSequenceValid == null ? "na" : (r.legSequenceValid ? "pass" : "fail"),
                aggregateServiceabilityStatus(r),
                aggregateContainerStatus(r),
                r.overallStatus,
                r.startedAt
        );
    }

    private OrderValidationDtos.OrderQueueRow toQueueRow(RunView r) {
        return new OrderValidationDtos.OrderQueueRow(
                r.instId,
                Optional.ofNullable(r.orderId).orElse("—"),
                r.journeyType,
                r.serviceability.isEmpty() ? null : r.serviceability.size(),
                r.legSequenceValid == null ? "na" : (r.legSequenceValid ? "pass" : "fail"),
                aggregateServiceabilityStatus(r),
                aggregateContainerStatus(r),
                r.overallStatus,
                r.startedAt,
                r.endedAt,
                r.state == null ? "unknown" : r.state,
                r.errorMessage
        );
    }

    private String aggregateServiceabilityStatus(RunView r) {
        if (r.serviceability.isEmpty()) return "na";
        boolean anyFail = false;
        boolean anyChecked = false;
        for (ServiceabilityEntry s : r.serviceability) {
            if ("skipped".equalsIgnoreCase(s.status)) continue;
            anyChecked = true;
            if (Boolean.FALSE.equals(s.isServiceable)) anyFail = true;
        }
        if (!anyChecked) return "skipped";
        return anyFail ? "exception" : "pass";
    }

    private String aggregateContainerStatus(RunView r) {
        if (r.containerAvailability.isEmpty()) return "na";
        boolean anyChecked = false;
        boolean anyFail = false;
        boolean anyPass = false;
        for (ContainerEntry c : r.containerAvailability) {
            if (!c.checked) continue;
            anyChecked = true;
            if (c.availableDates != null && !c.availableDates.isEmpty()) anyPass = true;
            else anyFail = true;
        }
        if (!anyChecked) return "skipped";
        if (anyFail && !anyPass) return "fail";
        if (anyFail) return "exception";
        return "pass";
    }

    private boolean matchesStatus(RunView r, String status) {
        if (status == null || status.isBlank()) return true;
        switch (status.toLowerCase(Locale.ROOT)) {
            case "passed":
                return "clear".equals(r.overallStatus);
            case "review":
                return "review".equals(r.overallStatus);
            case "failed":
                return "failed".equals(r.overallStatus) || "error".equals(r.overallStatus);
            case "running":
                return "running".equals(r.overallStatus);
            default:
                return true;
        }
    }

    private boolean matchesSearch(RunView r, String search) {
        if (search == null || search.isBlank()) return true;
        String needle = search.toLowerCase(Locale.ROOT);
        return (r.orderId != null && r.orderId.toLowerCase(Locale.ROOT).contains(needle))
                || (r.journeyType != null && r.journeyType.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        return n.toString();
    }

    private static List<String> readStringArray(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode item : n) {
            if (item == null || item.isNull()) continue;
            out.add(item.isTextual() ? item.asText() : item.toString());
        }
        return out;
    }

    private static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    // ------------------------------------------------------------------
    //  Mutable internal view types — never escape the service boundary.
    // ------------------------------------------------------------------

    private static final class RunView {
        String instId;
        String orderId;
        String journeyType;
        Boolean legSequenceValid;
        String legSequenceMessage;
        String matchedRule;
        List<String> actualSequence = List.of();
        final List<ServiceabilityEntry> serviceability = new ArrayList<>();
        final List<ContainerEntry> containerAvailability = new ArrayList<>();
        long startedAt;
        Long endedAt;
        String state;
        String errorMessage;
        String overallStatus = "unknown";
        JsonNode payload;
    }

    private static final class ServiceabilityEntry {
        String lineId;
        String itemCode;
        String originZip;
        String destinationZip;
        Boolean isServiceable;
        String exceptionType;
        String status;
    }

    private static final class ContainerEntry {
        String lineId;
        String itemCode;
        boolean checked;
        List<String> availableDates = List.of();
        String skipReason;
    }
}
