package com.pods.agent.vendorRationalization;

import java.util.List;
import java.util.Map;

/**
 * Wire shape for the vendor-rationalization tunable config. Stored as one
 * JSON blob in {@code agent.vendor_rationalization_config.payload_json}
 * and edited as a unit from the Settings admin page.
 *
 * <p>Every business knob lives here:
 *
 * <ul>
 *   <li>{@link #leverAssignments} — category → strategic lever name.</li>
 *   <li>{@link #savingsBuckets} — ordered rules driving the savings page
 *       (first match wins). Each bucket carries its own confidence, stage,
 *       timeline, and action strings.</li>
 *   <li>{@link #insightThresholds} — the quick-win trigger (≤N vendors AND
 *       ≥$X spend) and the consolidation savings % range surfaced to the
 *       LLM in its prompt context.</li>
 *   <li>{@link #kpiTargets} — display strings for the KPI cards (e.g.
 *       "Target: 75-85").</li>
 *   <li>{@link #paretoThresholdPct} — the 80/20 boundary, expressed as
 *       0-1 (default 0.8).</li>
 *   <li>{@link #chatModelRef} — which LLM (in "providerId/modelId" form)
 *       drives the AI Assistant chat <em>and</em> every "AI Insights" /
 *       "AI Anomaly Detection" / "AI Recommendations" panel in the UI.</li>
 * </ul>
 *
 * <p>Insight prose is no longer templated — every "AI" surface generates
 * its sentences live from {@link #chatModelRef}. There are no per-lever
 * sentence templates in this config anymore.
 */
public record VendorRationalizationConfig(
        Map<String, String> leverAssignments,
        List<SavingsBucket> savingsBuckets,
        InsightThresholds insightThresholds,
        KpiTargets kpiTargets,
        double paretoThresholdPct,
        String chatModelRef
) {

    /**
     * Ordered rule for the savings opportunities engine. The first bucket
     * whose vendor-count range contains the category's vendor count wins.
     *
     * @param identifiedStageWhenVendorsAtLeast  if non-null and the
     *        category's vendor count is at least this value, the row's
     *        stage flips from {@code stage} to {@code "Identified"}.
     *        Mirrors the legacy "≥20 vendors ⇒ Identified" carve-out.
     */
    public record SavingsBucket(
            int minVendors,
            Integer maxVendors,
            double savingsLowPct,
            double savingsHighPct,
            double confidence,
            String stage,
            String timeline,
            String action,
            Integer identifiedStageWhenVendorsAtLeast
    ) {}

    public record InsightThresholds(
            int quickWinMaxVendors,
            double quickWinMinSpend,
            double consolidationLowPct,
            double consolidationHighPct
    ) {}

    public record KpiTargets(
            String activeVendorsTarget
    ) {}
}
