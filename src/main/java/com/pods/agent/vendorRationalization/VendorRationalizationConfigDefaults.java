package com.pods.agent.vendorRationalization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default values planted on first boot and used as fallback when the
 * singleton config row is missing. Matches the legacy CATEGORY_LEVER map,
 * the three savings buckets, and the 80% Pareto threshold so behaviour
 * is identical until business edits via the Settings admin page. No
 * insight sentence templates — every "AI" panel renders sentences live
 * from the configured chat model.
 */
public final class VendorRationalizationConfigDefaults {

    private VendorRationalizationConfigDefaults() {}

    public static VendorRationalizationConfig defaultConfig() {
        return new VendorRationalizationConfig(
                defaultLeverAssignments(),
                defaultSavingsBuckets(),
                defaultInsightThresholds(),
                defaultKpiTargets(),
                0.80,
                null  // chatModelRef — admin must pick a model in Settings
        );
    }

    private static Map<String, String> defaultLeverAssignments() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Financial & Accounting Services", "Renegotiate & Partner");
        m.put("Marketing & Advertising", "Renegotiate & Partner");
        m.put("Rent Space", "Aggregate & Rationalize");
        m.put("Freight & Transportation", "Aggregate & Rationalize");
        m.put("Fleet, Vehicle & Auto Services", "Aggregate & Rationalize");
        m.put("Technology & Software", "Aggregate & Rationalize");
        m.put("Construction & Real Estate", "Renegotiate & Partner");
        m.put("Insurance & Risk Management", "Renegotiate & Partner");
        m.put("Legal", "Deep-Dive & Rebid");
        m.put("Moving", "Deep-Dive & Rebid");
        m.put("Government", "Deep-Dive & Rebid");
        m.put("HR & Staffing", "Deep-Dive & Rebid");
        m.put("Industrial Equipment", "Deep-Dive & Rebid");
        m.put("Consulting & Professional Services", "Renegotiate & Partner");
        return m;
    }

    private static List<VendorRationalizationConfig.SavingsBucket> defaultSavingsBuckets() {
        return List.of(
                // Fragmented: ≥10 vendors. Stage flips to "Identified" at ≥20.
                new VendorRationalizationConfig.SavingsBucket(
                        10, null,
                        0.08, 0.15,
                        0.75,
                        "Validated", "Q4 2025", "Consolidate",
                        20),
                // Concentrated: ≤3 vendors.
                new VendorRationalizationConfig.SavingsBucket(
                        0, 3,
                        0.06, 0.10,
                        0.85,
                        "In Progress", "Q3 2025", "Renegotiate",
                        null),
                // Medium: 4-9 vendors (catch-all for anything else).
                new VendorRationalizationConfig.SavingsBucket(
                        4, 9,
                        0.05, 0.12,
                        0.70,
                        "Identified", "Q1 2026", "Analyze",
                        null)
        );
    }

    private static VendorRationalizationConfig.InsightThresholds defaultInsightThresholds() {
        return new VendorRationalizationConfig.InsightThresholds(
                3,             // quickWinMaxVendors
                20_000_000.0,  // quickWinMinSpend
                0.10,          // consolidationLowPct
                0.15           // consolidationHighPct
        );
    }

    private static VendorRationalizationConfig.KpiTargets defaultKpiTargets() {
        return new VendorRationalizationConfig.KpiTargets("Target: 75-85");
    }
}
