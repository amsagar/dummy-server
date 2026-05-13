package com.pods.agent.vendorRationalization;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Business logic for vendor rationalization analytics.
 * All data is derived from the Excel file via {@link VendorSpendDataService}.
 */
@Service
@Slf4j
public class VendorRationalizationService {

    private static final String DEFAULT_LEVER = "Deep-Dive & Rebid";
    private static final long CONFIG_CACHE_TTL_MS = 5_000L;

    private final VendorSpendDataService dataService;
    private final VendorRationalizationConfigRepository configRepo;
    private final ObjectMapper objectMapper;

    private final AtomicReference<CachedConfig> configCache = new AtomicReference<>(null);

    public VendorRationalizationService(VendorSpendDataService dataService,
                                        VendorRationalizationConfigRepository configRepo,
                                        ObjectMapper objectMapper) {
        this.dataService = dataService;
        this.configRepo = configRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the active config, cached in-process for {@link #CONFIG_CACHE_TTL_MS}
     * so dashboard requests don't hit the DB on every call. Falls back to
     * {@link VendorRationalizationConfigDefaults#defaultConfig()} when the
     * row is missing or parsing fails — the dashboard keeps rendering with
     * defaults rather than blowing up.
     */
    private VendorRationalizationConfig config() {
        long now = System.currentTimeMillis();
        CachedConfig cached = configCache.get();
        if (cached != null && (now - cached.loadedAt) < CONFIG_CACHE_TTL_MS) {
            return cached.config;
        }
        VendorRationalizationConfig fresh = loadConfigFromRepo();
        configCache.set(new CachedConfig(fresh, now));
        return fresh;
    }

    /** Force the next {@link #config()} call to re-read from the DB. */
    public void invalidateConfigCache() {
        configCache.set(null);
    }

    /**
     * Public accessor for the active config — used by the chat path and
     * the AI-insights service. Same caching as {@link #config()}.
     */
    public VendorRationalizationConfig getConfig() {
        return config();
    }

    private VendorRationalizationConfig loadConfigFromRepo() {
        try {
            var row = configRepo.find();
            if (row.isPresent()) {
                return objectMapper.readValue(row.get().payloadJson(), VendorRationalizationConfig.class);
            }
        } catch (Exception e) {
            log.warn("[VRService] failed to parse stored config, using defaults: {}", e.getMessage());
        }
        return VendorRationalizationConfigDefaults.defaultConfig();
    }

    private record CachedConfig(VendorRationalizationConfig config, long loadedAt) {}

    // ── Dashboard ─────────────────────────────────────────────────────────────

    public VendorRationalizationDtos.DashboardSummary getDashboard() {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();
        if (all.isEmpty()) return emptyDashboard();

        double totalSpend = all.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
        int totalVendors = all.size();

        // Category breakdown
        Map<String, List<VendorRationalizationDtos.VendorRow>> byCategory = all.stream()
                .filter(v -> v.generalizedCategory() != null && !v.generalizedCategory().isBlank())
                .collect(Collectors.groupingBy(VendorRationalizationDtos.VendorRow::generalizedCategory));

        List<VendorRationalizationDtos.CategorySummary> categories = byCategory.entrySet().stream()
                .map(e -> {
                    double catSpend = e.getValue().stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
                    return new VendorRationalizationDtos.CategorySummary(
                            e.getKey(), e.getValue().size(), catSpend,
                            totalSpend > 0 ? (catSpend / totalSpend) * 100 : 0);
                })
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.CategorySummary::totalSpend).reversed())
                .toList();

        // Top 20 vendors by spend
        List<VendorRationalizationDtos.VendorRow> topVendors = all.stream()
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                .limit(20)
                .toList();

        // Pareto threshold from config (default 0.80).
        double paretoPct = config().paretoThresholdPct();
        List<VendorRationalizationDtos.VendorRow> sorted = all.stream()
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                .toList();
        double threshold = totalSpend * paretoPct;
        double cumulative = 0;
        int vendorsFor80 = 0;
        for (VendorRationalizationDtos.VendorRow v : sorted) {
            cumulative += v.sumAmount();
            vendorsFor80++;
            if (cumulative >= threshold) break;
        }

        VendorRationalizationDtos.ParetoSummary pareto = new VendorRationalizationDtos.ParetoSummary(
                totalSpend, threshold, vendorsFor80, totalVendors,
                totalVendors > 0 ? (vendorsFor80 * 100.0) / totalVendors : 0);

        return new VendorRationalizationDtos.DashboardSummary(
                totalSpend, totalVendors, byCategory.size(),
                threshold, vendorsFor80, categories, topVendors, pareto);
    }

    // ── Vendor list ───────────────────────────────────────────────────────────

    public VendorRationalizationDtos.VendorListResponse listVendors(
            String search, String category, String topGroup, int limit, int offset) {

        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();

        List<VendorRationalizationDtos.VendorRow> filtered = all.stream()
                .filter(v -> {
                    if (search != null && !search.isBlank()) {
                        String q = search.toLowerCase();
                        return (v.name() != null && v.name().toLowerCase().contains(q))
                                || (v.generalizedCategory() != null && v.generalizedCategory().toLowerCase().contains(q))
                                || (v.category() != null && v.category().toLowerCase().contains(q))
                                || (v.accountNum() != null && v.accountNum().toLowerCase().contains(q));
                    }
                    return true;
                })
                .filter(v -> {
                    if (category != null && !category.isBlank() && !"all".equalsIgnoreCase(category)) {
                        return category.equalsIgnoreCase(v.generalizedCategory());
                    }
                    return true;
                })
                .filter(v -> {
                    if (topGroup != null && !topGroup.isBlank() && !"all".equalsIgnoreCase(topGroup)) {
                        return topGroup.equalsIgnoreCase(v.topGroup());
                    }
                    return true;
                })
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                .toList();

        long total = filtered.size();
        List<VendorRationalizationDtos.VendorRow> page = filtered.stream()
                .skip(offset)
                .limit(limit)
                .toList();

        return new VendorRationalizationDtos.VendorListResponse((int) total, limit, offset, page);
    }

    // ── Category analytics ────────────────────────────────────────────────────

    public VendorRationalizationDtos.CategoryAnalyticsResponse getCategoryAnalytics() {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();
        if (all.isEmpty()) return new VendorRationalizationDtos.CategoryAnalyticsResponse(List.of(), 0, 0);

        double totalSpend = all.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();

        Map<String, List<VendorRationalizationDtos.VendorRow>> byCategory = all.stream()
                .filter(v -> v.generalizedCategory() != null && !v.generalizedCategory().isBlank())
                .collect(Collectors.groupingBy(VendorRationalizationDtos.VendorRow::generalizedCategory));

        List<VendorRationalizationDtos.CategoryDetail> details = byCategory.entrySet().stream()
                .map(e -> {
                    String cat = e.getKey();
                    List<VendorRationalizationDtos.VendorRow> vendors = e.getValue();
                    double catSpend = vendors.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
                    double avg = vendors.isEmpty() ? 0 : catSpend / vendors.size();
                    String lever = config().leverAssignments().getOrDefault(cat, DEFAULT_LEVER);

                    List<VendorRationalizationDtos.VendorRow> top5 = vendors.stream()
                            .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                            .limit(5)
                            .toList();

                    return new VendorRationalizationDtos.CategoryDetail(
                            cat, vendors.size(), catSpend,
                            totalSpend > 0 ? (catSpend / totalSpend) * 100 : 0,
                            avg, lever, top5);
                })
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.CategoryDetail::totalSpend).reversed())
                .toList();

        return new VendorRationalizationDtos.CategoryAnalyticsResponse(details, totalSpend, all.size());
    }

    // ── Pareto analysis ───────────────────────────────────────────────────────

    public VendorRationalizationDtos.ParetoAnalysisResponse getParetoAnalysis(int limit) {
        List<VendorRationalizationDtos.ParetoRow> all = dataService.getParetoData();

        // If pareto sheet is empty, compute from enriched data
        if (all.isEmpty()) {
            all = computeParetoFromEnriched();
        }

        double paretoPct = config().paretoThresholdPct();
        List<VendorRationalizationDtos.VendorRow> enriched = dataService.getAllVendors();
        double totalSpend = enriched.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
        double threshold = totalSpend * paretoPct;

        int vendorsFor80 = 0;
        for (VendorRationalizationDtos.ParetoRow r : all) {
            if (r.cumulativePct() >= paretoPct) { vendorsFor80 = r.rank(); break; }
        }
        if (vendorsFor80 == 0) vendorsFor80 = all.size();

        VendorRationalizationDtos.ParetoSummary summary = new VendorRationalizationDtos.ParetoSummary(
                totalSpend, threshold, vendorsFor80, enriched.size(),
                enriched.isEmpty() ? 0 : (vendorsFor80 * 100.0) / enriched.size());

        List<VendorRationalizationDtos.ParetoRow> page = all.stream().limit(limit).toList();
        return new VendorRationalizationDtos.ParetoAnalysisResponse(page, summary);
    }

    // ── Savings opportunities ─────────────────────────────────────────────────

    public VendorRationalizationDtos.SavingsOpportunitiesResponse getSavingsOpportunities() {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();
        if (all.isEmpty()) return new VendorRationalizationDtos.SavingsOpportunitiesResponse(
                0, 0, 0, 0, List.of());

        // Generate opportunities from category data
        Map<String, List<VendorRationalizationDtos.VendorRow>> byCategory = all.stream()
                .filter(v -> v.generalizedCategory() != null && !v.generalizedCategory().isBlank())
                .collect(Collectors.groupingBy(VendorRationalizationDtos.VendorRow::generalizedCategory));

        List<VendorRationalizationDtos.SavingsOpportunity> opportunities = new ArrayList<>();
        int idCounter = 1;
        List<VendorRationalizationConfig.SavingsBucket> buckets = config().savingsBuckets();

        for (Map.Entry<String, List<VendorRationalizationDtos.VendorRow>> entry : byCategory.entrySet()) {
            String cat = entry.getKey();
            List<VendorRationalizationDtos.VendorRow> vendors = entry.getValue();
            int vCount = vendors.size();
            double catSpend = vendors.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
            String lever = config().leverAssignments().getOrDefault(cat, DEFAULT_LEVER);

            // First bucket whose vendor-count range covers this category wins.
            VendorRationalizationConfig.SavingsBucket bucket = pickBucket(buckets, vCount);
            if (bucket == null) continue; // no matching bucket — skip this category

            double savingsLow = catSpend * bucket.savingsLowPct();
            double savingsHigh = catSpend * bucket.savingsHighPct();
            // Legacy "ramp up confidence with vendor count" was only applied to
            // the fragmented bucket. We keep it but scale it off the bucket's
            // base confidence so the curve still looks the same.
            double confidence = bucket.confidence();
            if (bucket.maxVendors() == null && vCount >= bucket.minVendors()) {
                confidence += Math.min(vCount, 50) / 500.0;
            }

            String stage = bucket.stage();
            if (bucket.identifiedStageWhenVendorsAtLeast() != null
                    && vCount >= bucket.identifiedStageWhenVendorsAtLeast()) {
                stage = "Identified";
            }

            opportunities.add(new VendorRationalizationDtos.SavingsOpportunity(
                    "opp-" + idCounter++,
                    cat + " Optimization",
                    cat, catSpend,
                    savingsLow, savingsHigh,
                    Math.min(confidence, 0.95),
                    lever, stage, bucket.timeline(), bucket.action()));
        }

        // Sort by estimated savings high desc
        opportunities.sort(Comparator.comparingDouble(
                VendorRationalizationDtos.SavingsOpportunity::estimatedSavingsHigh).reversed());

        double totalSavings = opportunities.stream()
                .mapToDouble(o -> (o.estimatedSavingsLow() + o.estimatedSavingsHigh()) / 2)
                .sum();
        long quickWins = opportunities.stream()
                .filter(o -> "In Progress".equals(o.stage()) || "Validated".equals(o.stage()))
                .count();
        double avgConf = opportunities.stream()
                .mapToDouble(VendorRationalizationDtos.SavingsOpportunity::confidence)
                .average().orElse(0);

        return new VendorRationalizationDtos.SavingsOpportunitiesResponse(
                totalSavings, opportunities.size(), (int) quickWins, avgConf, opportunities);
    }

    // ── Agent tool helpers ────────────────────────────────────────────────────

    public List<Map<String, Object>> searchVendorsForAgent(String query, int limit) {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();
        String q = query == null ? "" : query.toLowerCase();

        return all.stream()
                .filter(v -> q.isBlank()
                        || (v.name() != null && v.name().toLowerCase().contains(q))
                        || (v.generalizedCategory() != null && v.generalizedCategory().toLowerCase().contains(q))
                        || (v.category() != null && v.category().toLowerCase().contains(q)))
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                .limit(limit)
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", v.name());
                    m.put("category", v.generalizedCategory());
                    m.put("subCategory", v.category());
                    m.put("spend", v.sumAmount());
                    m.put("topGroup", v.topGroup());
                    m.put("vendorGroup", v.vendorGroup());
                    return m;
                })
                .toList();
    }

    public Map<String, Object> getCategoryInsightForAgent(String categoryName) {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();

        List<VendorRationalizationDtos.VendorRow> catVendors = all.stream()
                .filter(v -> categoryName != null
                        && categoryName.equalsIgnoreCase(v.generalizedCategory()))
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                .toList();

        if (catVendors.isEmpty()) {
            return Map.of("error", "Category not found: " + categoryName);
        }

        double totalSpend = catVendors.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
        String lever = config().leverAssignments().getOrDefault(categoryName, DEFAULT_LEVER);

        List<Map<String, Object>> topVendors = catVendors.stream().limit(5).map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", v.name());
            m.put("spend", v.sumAmount());
            m.put("pctOfCategory", totalSpend > 0 ? (v.sumAmount() / totalSpend) * 100 : 0);
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", categoryName);
        result.put("vendorCount", catVendors.size());
        result.put("totalSpend", totalSpend);
        result.put("rationalizationLever", lever);
        result.put("topVendors", topVendors);
        // The chat assistant is itself an LLM — it synthesizes the natural-
        // language insight from these stats in its own reply. We deliberately
        // don't ship a pre-baked "insight" sentence here so there's no
        // template-driven prose anywhere in the system.
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * First savings bucket whose {@code [minVendors, maxVendors]} range
     * contains {@code vendorCount}. Returns {@code null} when no bucket
     * matches — the caller should skip that category rather than invent
     * a fallback (the legacy code didn't have a fallback either).
     */
    private VendorRationalizationConfig.SavingsBucket pickBucket(
            List<VendorRationalizationConfig.SavingsBucket> buckets,
            int vendorCount) {
        if (buckets == null) return null;
        for (var b : buckets) {
            int min = b.minVendors();
            int max = b.maxVendors() == null ? Integer.MAX_VALUE : b.maxVendors();
            if (vendorCount >= min && vendorCount <= max) return b;
        }
        return null;
    }

    /**
     * Strategic Levers panel data — computed live by grouping categories
     * (with their current spend) by the config's lever assignments.
     * Replaces the hardcoded {@code ~$360M / ~$298M / ~$6M} array that
     * used to live in the dashboard TSX.
     */
    public List<VendorRationalizationDtos.StrategicLeverSummary> getStrategicLeversSummary() {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();
        if (all.isEmpty()) return List.of();
        Map<String, String> assignments = config().leverAssignments();

        // Group categories by their lever (defaulting unknown categories
        // to "Deep-Dive & Rebid", matching the rest of the service).
        Map<String, List<Map.Entry<String, Double>>> byLever = new LinkedHashMap<>();
        Map<String, Integer> vendorCounts = new HashMap<>();

        all.stream()
                .filter(v -> v.generalizedCategory() != null && !v.generalizedCategory().isBlank())
                .collect(Collectors.groupingBy(VendorRationalizationDtos.VendorRow::generalizedCategory))
                .forEach((cat, vendors) -> {
                    String lever = assignments.getOrDefault(cat, DEFAULT_LEVER);
                    double catSpend = vendors.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();
                    byLever.computeIfAbsent(lever, k -> new ArrayList<>()).add(Map.entry(cat, catSpend));
                    vendorCounts.merge(lever, vendors.size(), Integer::sum);
                });

        return byLever.entrySet().stream()
                .map(e -> {
                    String lever = e.getKey();
                    List<Map.Entry<String, Double>> cats = e.getValue();
                    double scopeSpend = cats.stream().mapToDouble(Map.Entry::getValue).sum();
                    List<String> examples = cats.stream()
                            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                            .limit(4)
                            .map(Map.Entry::getKey)
                            .toList();
                    return new VendorRationalizationDtos.StrategicLeverSummary(
                            lever, scopeSpend, cats.size(),
                            vendorCounts.getOrDefault(lever, 0), examples);
                })
                .sorted(Comparator.comparingDouble(
                        VendorRationalizationDtos.StrategicLeverSummary::scopeSpend).reversed())
                .toList();
    }

    private List<VendorRationalizationDtos.ParetoRow> computeParetoFromEnriched() {
        List<VendorRationalizationDtos.VendorRow> all = dataService.getAllVendors();
        double totalSpend = all.stream().mapToDouble(VendorRationalizationDtos.VendorRow::sumAmount).sum();

        List<VendorRationalizationDtos.VendorRow> sorted = all.stream()
                .sorted(Comparator.comparingDouble(VendorRationalizationDtos.VendorRow::sumAmount).reversed())
                .toList();

        List<VendorRationalizationDtos.ParetoRow> rows = new ArrayList<>();
        double cumulative = 0;
        int rank = 1;
        for (VendorRationalizationDtos.VendorRow v : sorted) {
            cumulative += v.sumAmount();
            rows.add(new VendorRationalizationDtos.ParetoRow(
                    rank++, v.name(), v.sumAmount(), cumulative,
                    totalSpend > 0 ? cumulative / totalSpend : 0, v.topGroup()));
        }
        return rows;
    }

    private VendorRationalizationDtos.DashboardSummary emptyDashboard() {
        return new VendorRationalizationDtos.DashboardSummary(
                0, 0, 0, 0, 0, List.of(), List.of(),
                new VendorRationalizationDtos.ParetoSummary(0, 0, 0, 0, 0));
    }
}
