package com.pods.agent.vendorRationalization;

import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the vendor-rationalization analytics endpoints.
 * Names mirror the TypeScript types in the UI.
 */
public final class VendorRationalizationDtos {

    private VendorRationalizationDtos() {}

    // ── Raw vendor row from the Excel "Enriched Data" sheet ──────────────────

    public record VendorRow(
            String accountNum,
            String topGroup,
            String name,
            String vendorGroup,
            String address,
            String currency,
            double sumAmount,
            String generalizedCategory,
            String category,
            String additionalServices
    ) {}

    // ── Dashboard summary ─────────────────────────────────────────────────────

    public record DashboardSummary(
            double totalSpend,
            int totalVendors,
            int totalCategories,
            double top80PctSpend,
            int top80PctVendors,
            List<CategorySummary> byCategory,
            List<VendorRow> topVendors,
            ParetoSummary pareto
    ) {}

    public record CategorySummary(
            String category,
            int vendorCount,
            double totalSpend,
            double pctOfTotal
    ) {}

    public record ParetoSummary(
            double totalSpend,
            double threshold80Pct,
            int vendorsFor80Pct,
            int totalVendors,
            double pctOfVendors
    ) {}

    // ── Vendor list / search ──────────────────────────────────────────────────

    public record VendorListResponse(
            long total,
            int limit,
            int offset,
            List<VendorRow> vendors
    ) {}

    // ── Category analytics ────────────────────────────────────────────────────

    public record CategoryAnalyticsResponse(
            List<CategoryDetail> categories,
            double totalSpend,
            int totalVendors
    ) {}

    public record CategoryDetail(
            String category,
            int vendorCount,
            double totalSpend,
            double pctOfTotal,
            double avgVendorSpend,
            String rationalizationLever,
            List<VendorRow> topVendors
    ) {}

    // ── Pareto analysis ───────────────────────────────────────────────────────

    public record ParetoAnalysisResponse(
            List<ParetoRow> rows,
            ParetoSummary summary
    ) {}

    public record ParetoRow(
            int rank,
            String vendorName,
            double spendAmount,
            double cumulativeSpend,
            double cumulativePct,
            String topGroup
    ) {}

    // ── Savings opportunities ─────────────────────────────────────────────────

    public record SavingsOpportunitiesResponse(
            double totalIdentifiedSavings,
            int activeOpportunities,
            int quickWins,
            double avgConfidence,
            List<SavingsOpportunity> opportunities
    ) {}

    public record SavingsOpportunity(
            String id,
            String name,
            String category,
            double currentSpend,
            double estimatedSavingsLow,
            double estimatedSavingsHigh,
            double confidence,
            String lever,
            String stage,
            String timeline,
            String action
    ) {}

    // ── AI chat tool responses ────────────────────────────────────────────────

    public record VendorSearchResult(
            int count,
            List<Map<String, Object>> vendors
    ) {}

    public record CategoryInsight(
            String category,
            int vendorCount,
            double totalSpend,
            String rationalizationLever,
            String insight
    ) {}

    /**
     * Wire shape for the dashboard's Strategic Levers panel. Computed live
     * from the data + the configurable lever assignments, replacing the
     * old hardcoded ~$360M / ~$298M / ~$6M array.
     */
    public record StrategicLeverSummary(
            String lever,
            double scopeSpend,
            int categoryCount,
            int vendorCount,
            List<String> exampleCategories
    ) {}

    /**
     * Wire shape for a single AI-generated dashboard insight card. Produced
     * by {@code VendorRationalizationInsightsService} via the configured
     * chat model; replaces the hardcoded three-card template that lived in
     * the dashboard TSX.
     *
     * @param severity one of "high" / "medium" / "low" (drives the card
     *                 icon and color in the UI)
     * @param iconHint one of "consolidation" / "risk" / "quick-win" /
     *                 "info" — UI maps to a Lucide icon
     */
    public record AiInsight(
            String title,
            String body,
            String severity,
            String iconHint
    ) {}

    public record AiInsightsResponse(
            List<AiInsight> insights,
            long generatedAt,
            String modelRef,
            boolean cached
    ) {}
}
