package com.pods.agent.vendorRationalization;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Vendor Rationalization portal.
 * All endpoints are read-only and exposed under permitAll() in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/vendor-rationalization")
public class VendorRationalizationController {

    private final VendorRationalizationService service;
    private final VendorSpendDataService dataService;
    private final VendorRationalizationConfigRepository configRepo;
    private final VendorRationalizationInsightsService insightsService;
    private final ObjectMapper objectMapper;

    public VendorRationalizationController(VendorRationalizationService service,
                                           VendorSpendDataService dataService,
                                           VendorRationalizationConfigRepository configRepo,
                                           VendorRationalizationInsightsService insightsService,
                                           ObjectMapper objectMapper) {
        this.service = service;
        this.dataService = dataService;
        this.configRepo = configRepo;
        this.insightsService = insightsService;
        this.objectMapper = objectMapper;
    }

    /** Executive dashboard summary — KPIs, category breakdown, top vendors. */
    @GetMapping("/dashboard")
    public ResponseEntity<VendorRationalizationDtos.DashboardSummary> dashboard() {
        return ResponseEntity.ok(service.getDashboard());
    }

    /** Paginated, searchable vendor list. */
    @GetMapping("/vendors")
    public ResponseEntity<VendorRationalizationDtos.VendorListResponse> vendors(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "topGroup", required = false) String topGroup,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.listVendors(search, category, topGroup, limit, offset));
    }

    /** Category-level analytics with lever classification. */
    @GetMapping("/categories")
    public ResponseEntity<VendorRationalizationDtos.CategoryAnalyticsResponse> categories() {
        return ResponseEntity.ok(service.getCategoryAnalytics());
    }

    /** Pareto (80/20) analysis — ranked vendor list with cumulative spend %. */
    @GetMapping("/pareto")
    public ResponseEntity<VendorRationalizationDtos.ParetoAnalysisResponse> pareto(
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ResponseEntity.ok(service.getParetoAnalysis(limit));
    }

    /** AI-generated savings opportunities by category. */
    @GetMapping("/savings")
    public ResponseEntity<VendorRationalizationDtos.SavingsOpportunitiesResponse> savings() {
        return ResponseEntity.ok(service.getSavingsOpportunities());
    }

    /** List all distinct categories. */
    @GetMapping("/categories/list")
    public ResponseEntity<List<String>> categoryList() {
        List<String> cats = dataService.getAllVendors().stream()
                .map(VendorRationalizationDtos.VendorRow::generalizedCategory)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .toList();
        return ResponseEntity.ok(cats);
    }

    /** Reload the Excel file without restarting the server. */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        dataService.reload();
        int count = dataService.getAllVendors().size();
        return ResponseEntity.ok(Map.of("status", "reloaded", "vendorCount", count));
    }

    /**
     * Strategic Levers panel data — scope $ + example categories computed
     * live from the spend data using the configurable lever assignments.
     * Replaces the hardcoded array that used to live in the dashboard TSX.
     */
    @GetMapping("/strategic-levers")
    public ResponseEntity<List<VendorRationalizationDtos.StrategicLeverSummary>> strategicLevers() {
        return ResponseEntity.ok(service.getStrategicLeversSummary());
    }

    /**
     * Returns the active tunable config (lever map, savings buckets, KPI
     * targets, insight thresholds + templates, Pareto threshold). Falls
     * back to compiled-in defaults when the singleton row isn't there
     * yet, so the dashboard never has to handle a 404.
     */
    @GetMapping("/config")
    public ResponseEntity<VendorRationalizationConfig> getConfig() {
        try {
            var row = configRepo.find();
            if (row.isPresent()) {
                return ResponseEntity.ok(
                        objectMapper.readValue(row.get().payloadJson(), VendorRationalizationConfig.class));
            }
        } catch (Exception e) {
            // fall through to defaults
        }
        return ResponseEntity.ok(VendorRationalizationConfigDefaults.defaultConfig());
    }

    /**
     * Replace the entire config payload. Validates that lever assignments
     * are non-empty, savings buckets are non-empty, percentages stay in
     * 0-1, and the Pareto threshold is in (0, 1). Invalidates the
     * service's in-process cache so the next read picks up the new value
     * without waiting for the TTL.
     */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody VendorRationalizationConfig body) {
        String validation = validate(body);
        if (validation != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validation));
        }
        try {
            String payload = objectMapper.writeValueAsString(body);
            configRepo.upsert(payload);
            service.invalidateConfigCache();
            // The model selection lives inside the config — bust the
            // insights cache so the next /insights call uses whatever
            // model the admin just picked.
            insightsService.invalidate();
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to persist config: " + e.getMessage()));
        }
    }

    /**
     * AI-generated insight cards. {@code surface} picks which UI panel
     * the cards are for (dashboard / category / vendor / savings / contracts);
     * {@code scope} is an optional sub-key (the selected category name when
     * {@code surface=category}). {@code refresh=true} forces a fresh LLM
     * call; default returns the 10-minute in-process cache when warm.
     */
    @GetMapping("/insights")
    public ResponseEntity<VendorRationalizationDtos.AiInsightsResponse> insights(
            @RequestParam(value = "surface", defaultValue = "dashboard") String surface,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(insightsService.getInsights(surface, scope, refresh));
    }

    private static String validate(VendorRationalizationConfig c) {
        if (c == null) return "Body is required";
        if (c.leverAssignments() == null) return "leverAssignments is required";
        if (c.savingsBuckets() == null || c.savingsBuckets().isEmpty()) return "savingsBuckets must contain at least one bucket";
        if (c.paretoThresholdPct() <= 0 || c.paretoThresholdPct() >= 1) return "paretoThresholdPct must be between 0 and 1";
        if (c.insightThresholds() == null) return "insightThresholds is required";
        if (c.kpiTargets() == null) return "kpiTargets is required";
        for (var b : c.savingsBuckets()) {
            if (b.savingsLowPct() < 0 || b.savingsLowPct() > 1) return "savingsBuckets: savingsLowPct must be 0-1";
            if (b.savingsHighPct() < 0 || b.savingsHighPct() > 1) return "savingsBuckets: savingsHighPct must be 0-1";
            if (b.confidence() < 0 || b.confidence() > 1) return "savingsBuckets: confidence must be 0-1";
            if (b.minVendors() < 0) return "savingsBuckets: minVendors must be non-negative";
        }
        return null;
    }
}
