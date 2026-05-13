package com.pods.agent.vendorRationalization;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Tool implementations exposed to the AI chat assistant for the
 * Vendor Rationalization portal. Each method returns a JSON string
 * so the SSE {@code tool.result} event carries plain JSON the
 * front-end can render with {@code JsonTree}.
 */
@Slf4j
@Component
public class VendorRationalizationAgentTools {

    private final VendorRationalizationService service;
    private final ObjectMapper objectMapper;

    public VendorRationalizationAgentTools(VendorRationalizationService service,
                                           ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * Search vendors by name, category, or keyword.
     * Returns top matching vendors with spend data.
     */
    public String vrSearchVendors(String query, Integer limit) {
        int lim = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        try {
            List<Map<String, Object>> vendors = service.searchVendorsForAgent(query, lim);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("count", vendors.size());
            result.put("vendors", vendors);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return jsonError("search_failed", e.getMessage());
        }
    }

    /**
     * Get detailed analytics for a specific spend category.
     * Returns vendor count, total spend, top vendors, and rationalization insight.
     */
    public String vrGetCategoryInsight(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return jsonError("missing_category", "categoryName is required");
        }
        try {
            Map<String, Object> insight = service.getCategoryInsightForAgent(categoryName);
            return objectMapper.writeValueAsString(insight);
        } catch (Exception e) {
            return jsonError("insight_failed", e.getMessage());
        }
    }

    /**
     * Get the executive dashboard summary — total spend, vendor count,
     * category breakdown, and 80/20 Pareto stats.
     */
    public String vrGetDashboardStats() {
        try {
            VendorRationalizationDtos.DashboardSummary summary = service.getDashboard();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalSpend", summary.totalSpend());
            result.put("totalVendors", summary.totalVendors());
            result.put("totalCategories", summary.totalCategories());
            result.put("top80PctVendors", summary.top80PctVendors());
            result.put("top80PctSpend", summary.top80PctSpend());

            List<Map<String, Object>> cats = summary.byCategory().stream().limit(10).map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", c.category());
                m.put("vendorCount", c.vendorCount());
                m.put("totalSpend", c.totalSpend());
                m.put("pctOfTotal", String.format("%.1f%%", c.pctOfTotal()));
                return m;
            }).toList();
            result.put("topCategories", cats);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return jsonError("dashboard_failed", e.getMessage());
        }
    }

    /**
     * Get top savings opportunities across all categories.
     * Returns estimated savings ranges and recommended actions.
     */
    public String vrGetSavingsOpportunities(Integer topN) {
        int n = (topN == null || topN <= 0) ? 10 : Math.min(topN, 50);
        try {
            VendorRationalizationDtos.SavingsOpportunitiesResponse resp = service.getSavingsOpportunities();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalIdentifiedSavings", resp.totalIdentifiedSavings());
            result.put("activeOpportunities", resp.activeOpportunities());
            result.put("quickWins", resp.quickWins());
            result.put("avgConfidence", String.format("%.0f%%", resp.avgConfidence() * 100));

            List<Map<String, Object>> opps = resp.opportunities().stream().limit(n).map(o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", o.name());
                m.put("category", o.category());
                m.put("currentSpend", o.currentSpend());
                m.put("estimatedSavings", String.format("$%.1fM - $%.1fM",
                        o.estimatedSavingsLow() / 1_000_000, o.estimatedSavingsHigh() / 1_000_000));
                m.put("confidence", String.format("%.0f%%", o.confidence() * 100));
                m.put("lever", o.lever());
                m.put("stage", o.stage());
                m.put("action", o.action());
                return m;
            }).toList();
            result.put("opportunities", opps);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return jsonError("savings_failed", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String jsonError(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", true, "code", code, "message", message));
        } catch (Exception e) {
            return "{\"error\":true,\"code\":\"serialization_error\"}";
        }
    }
}
