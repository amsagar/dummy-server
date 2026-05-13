package com.pods.agent.vendorRationalization;

import com.pods.agent.config.ModelProviderRouter;
import com.pods.agent.domain.ModelRef;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates AI insight cards for the vendor-rationalization UI. Every "AI"
 * panel in the dashboard goes through this single service — there are no
 * template-driven insights left in the product. Each panel ("surface")
 * gets its own prompt, its own cache slot, and its own count of cards.
 *
 * <p>Surfaces:
 * <ul>
 *   <li>{@code dashboard} — Executive Dashboard right panel (3–5 cards)</li>
 *   <li>{@code category}  — Category Analytics right panel (2–4 cards per category)</li>
 *   <li>{@code vendor}    — Vendor Performance anomaly panel (3–5 cards)</li>
 *   <li>{@code savings}   — Savings Opportunities recommendation panel (3 cards)</li>
 *   <li>{@code contracts} — Contracts &amp; Compliance anomaly panel (4 cards)</li>
 * </ul>
 *
 * Output for every surface is a JSON array of
 * {@code {title, body, severity, iconHint}} objects so the UI can render
 * them uniformly. Cached ~10 minutes per surface; {@link #invalidate()}
 * busts everything (called by the config PUT handler).
 */
@Slf4j
@Service
public class VendorRationalizationInsightsService {

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final int  MAX_CATEGORIES_IN_PROMPT = 12;
    private static final int  MAX_VENDORS_IN_PROMPT = 8;

    private final VendorRationalizationService analyticsService;
    private final ModelProviderRouter modelProviderRouter;
    private final ObjectMapper objectMapper;

    /** One cache slot per {@code surface} (+ optional category sub-key). */
    private final ConcurrentHashMap<String, AtomicReference<Cached>> caches = new ConcurrentHashMap<>();

    public VendorRationalizationInsightsService(
            VendorRationalizationService analyticsService,
            @Lazy ModelProviderRouter modelProviderRouter,
            ObjectMapper objectMapper) {
        this.analyticsService = analyticsService;
        this.modelProviderRouter = modelProviderRouter;
        this.objectMapper = objectMapper;
    }

    /**
     * @param surface  one of dashboard / category / vendor / savings / contracts
     * @param scope    optional sub-key (e.g. the selected category name for
     *                 surface=category); null for surfaces that don't scope
     */
    public VendorRationalizationDtos.AiInsightsResponse getInsights(
            String surface, String scope, boolean forceRefresh) {
        String key = cacheKey(surface, scope);
        AtomicReference<Cached> slot = caches.computeIfAbsent(key, k -> new AtomicReference<>());
        if (!forceRefresh) {
            Cached c = slot.get();
            if (c != null && (System.currentTimeMillis() - c.generatedAt) < CACHE_TTL_MS) {
                return new VendorRationalizationDtos.AiInsightsResponse(
                        c.insights, c.generatedAt, c.modelRef, true);
            }
        }
        return generate(surface, scope, slot);
    }

    private synchronized VendorRationalizationDtos.AiInsightsResponse generate(
            String surface, String scope, AtomicReference<Cached> slot) {
        Cached existing = slot.get();
        if (existing != null && (System.currentTimeMillis() - existing.generatedAt) < CACHE_TTL_MS) {
            return new VendorRationalizationDtos.AiInsightsResponse(
                    existing.insights, existing.generatedAt, existing.modelRef, true);
        }

        VendorRationalizationConfig config = analyticsService.getConfig();
        String modelRef = config.chatModelRef();
        if (modelRef == null || modelRef.isBlank()) {
            log.info("[VRInsights/{}] no chat model configured — skipping LLM call", surface);
            return new VendorRationalizationDtos.AiInsightsResponse(
                    List.of(), System.currentTimeMillis(), null, false);
        }

        ModelRef ref = ModelRef.parse(modelRef);
        try {
            ModelProviderRouter.Spec spec = modelProviderRouter.resolve(ref, true);
            String system = systemPrompt(surface);
            String prompt = userPrompt(surface, scope);

            log.info("[VRInsights/{}{}] requesting insights from {} (prompt {} chars)",
                    surface,
                    scope == null ? "" : ":" + scope,
                    modelRef, prompt.length());
            String raw = spec.client().prompt()
                    .system(system)
                    .user(prompt)
                    .call()
                    .content();

            List<VendorRationalizationDtos.AiInsight> parsed = parseInsights(raw);
            if (parsed.isEmpty()) {
                log.warn("[VRInsights/{}] model returned empty / unparseable insights — keeping last-known cache", surface);
                if (existing != null) {
                    return new VendorRationalizationDtos.AiInsightsResponse(
                            existing.insights, existing.generatedAt, existing.modelRef, true);
                }
            }
            long now = System.currentTimeMillis();
            slot.set(new Cached(parsed, now, modelRef));
            return new VendorRationalizationDtos.AiInsightsResponse(parsed, now, modelRef, false);
        } catch (Exception e) {
            log.warn("[VRInsights/{}] generation failed: {}", surface, e.getMessage());
            if (existing != null) {
                return new VendorRationalizationDtos.AiInsightsResponse(
                        existing.insights, existing.generatedAt, existing.modelRef, true);
            }
            return new VendorRationalizationDtos.AiInsightsResponse(
                    List.of(), System.currentTimeMillis(), modelRef, false);
        }
    }

    public void invalidate() {
        caches.clear();
    }

    private static String cacheKey(String surface, String scope) {
        return surface + (scope == null || scope.isBlank() ? "" : "::" + scope.toLowerCase(Locale.ROOT));
    }

    // ── Prompt construction ──────────────────────────────────────────────

    private static final String JSON_SCHEMA_RULES = """
            Output format: a single JSON array, nothing else. No markdown
            fences, no prose before or after the array. Each element must
            match this schema EXACTLY:

              {
                "title":    "<short headline, 3-6 words>",
                "body":     "<one sentence, 18-35 words, citing concrete numbers from the data>",
                "severity": "high" | "medium" | "low",
                "iconHint": "consolidation" | "risk" | "quick-win" | "info"
              }

            Severity policy:
              - "high"   — blocking risk or material $20M+ opportunity
              - "medium" — meaningful but non-urgent
              - "low"    — informational

            iconHint policy:
              - "consolidation" — fragmentation / too-many-vendors plays
              - "risk"          — concentration, dependency, or compliance risk
              - "quick-win"     — small vendor count with high spend, easy lift
              - "info"          — neutral observation

            Always cite real numbers (vendor counts, $ amounts, percentages) from
            the supplied data. Never invent figures or company names. If a piece
            of data isn't present, omit that insight rather than fabricating.
            """;

    private String systemPrompt(String surface) {
        String role = switch (surface) {
            case "category"  -> "You are a procurement analyst commenting on one category at a time. " +
                                "Focus on vendor sprawl, concentration risk, and the recommended lever for THIS category. " +
                                "Produce 2 to 4 insights.";
            case "vendor"    -> "You are a procurement analyst reviewing the vendor portfolio. " +
                                "Focus on anomalies: vendor sprawl (too many vendors in a category), " +
                                "concentration risk (one-vendor dependency), and outlier spend. " +
                                "Produce 3 to 5 insights.";
            case "savings"   -> "You are a procurement strategist ranking savings opportunities. " +
                                "Use the supplied savings rows verbatim — call out the highest-confidence play, " +
                                "the biggest dependency exposure, and the easiest quick win. " +
                                "Produce exactly 3 insights, in that order (high-confidence, dependency, quick-win).";
            case "contracts" -> "You are a contracts & compliance analyst flagging risk anomalies inferred from " +
                                "the vendor / spend snapshot. Likely angles: billing variance (categories with " +
                                "many vendors usually have inconsistent rates), duplicate-invoice exposure " +
                                "(fragmented categories), term mismatch (large single-vendor categories), and " +
                                "volume inconsistency (low-spend strategic categories). Produce 3 to 5 insights.";
            default          -> "You are a procurement insights analyst. Given a snapshot of a company's vendor " +
                                "spend, produce 3 to 5 actionable executive insights for the dashboard.";
        };
        return role + "\n\n" + JSON_SCHEMA_RULES;
    }

    private String userPrompt(String surface, String scope) {
        return switch (surface) {
            case "category"  -> buildCategoryPrompt(scope);
            case "vendor"    -> buildVendorPrompt();
            case "savings"   -> buildSavingsPrompt();
            case "contracts" -> buildContractsPrompt();
            default          -> buildDashboardPrompt();
        };
    }

    private String buildDashboardPrompt() {
        VendorRationalizationDtos.DashboardSummary d = analyticsService.getDashboard();
        VendorRationalizationConfig config = analyticsService.getConfig();

        StringBuilder sb = new StringBuilder();
        sb.append("## Vendor spend snapshot\n\n");
        sb.append(String.format("Total spend: $%.0fM%n", d.totalSpend() / 1_000_000));
        sb.append(String.format("Total vendors: %d%n", d.totalVendors()));
        sb.append(String.format("Total categories: %d%n", d.totalCategories()));
        sb.append(String.format("80%% of spend captured by: %d vendors (%.1f%% of total)%n",
                d.top80PctVendors(),
                d.totalVendors() > 0 ? (d.top80PctVendors() * 100.0 / d.totalVendors()) : 0));
        sb.append("\n## Top categories (by spend)\n\n");
        sb.append("| Category | Vendors | Spend | % of total | Strategic lever |\n");
        sb.append("|---|---|---|---|---|\n");
        d.byCategory().stream().limit(MAX_CATEGORIES_IN_PROMPT).forEach(c -> {
            String lever = config.leverAssignments().getOrDefault(c.category(), "Deep-Dive & Rebid");
            sb.append(String.format("| %s | %d | $%.1fM | %.1f%% | %s |%n",
                    c.category(), c.vendorCount(),
                    c.totalSpend() / 1_000_000, c.pctOfTotal(), lever));
        });

        sb.append("\n## Top vendors (by spend)\n\n");
        sb.append("| Vendor | Category | Spend |\n");
        sb.append("|---|---|---|\n");
        d.topVendors().stream().limit(MAX_VENDORS_IN_PROMPT).forEach(v -> {
            sb.append(String.format("| %s | %s | $%.2fM |%n",
                    v.name(),
                    v.generalizedCategory() == null ? "—" : v.generalizedCategory(),
                    v.sumAmount() / 1_000_000));
        });

        sb.append("\n## Heuristic thresholds in use\n\n");
        sb.append(String.format("- Quick-win heuristic: a category with ≤ %d vendors AND > $%.0fM spend%n",
                config.insightThresholds().quickWinMaxVendors(),
                config.insightThresholds().quickWinMinSpend() / 1_000_000));
        sb.append(String.format("- Consolidation savings band: %.0f%%-%.0f%% of category spend%n",
                config.insightThresholds().consolidationLowPct() * 100,
                config.insightThresholds().consolidationHighPct() * 100));

        sb.append("\nProduce 3-5 dashboard insights now (JSON array only).");
        return sb.toString();
    }

    private String buildCategoryPrompt(String categoryName) {
        VendorRationalizationConfig config = analyticsService.getConfig();
        VendorRationalizationDtos.CategoryAnalyticsResponse cats = analyticsService.getCategoryAnalytics();
        VendorRationalizationDtos.CategoryDetail target = cats.categories().stream()
                .filter(c -> categoryName != null && categoryName.equalsIgnoreCase(c.category()))
                .findFirst()
                .orElse(cats.categories().isEmpty() ? null : cats.categories().get(0));

        StringBuilder sb = new StringBuilder();
        if (target == null) {
            sb.append("No category data available. Produce an empty JSON array `[]`.");
            return sb.toString();
        }
        sb.append("## Selected category\n\n");
        sb.append(String.format("Name: %s%n", target.category()));
        sb.append(String.format("Vendors: %d%n", target.vendorCount()));
        sb.append(String.format("Total spend: $%.1fM%n", target.totalSpend() / 1_000_000));
        sb.append(String.format("Share of total addressable spend: %.1f%%%n", target.pctOfTotal()));
        sb.append(String.format("Average vendor spend: $%.2fM%n", target.avgVendorSpend() / 1_000_000));
        sb.append(String.format("Recommended strategic lever: %s%n",
                config.leverAssignments().getOrDefault(target.category(), "Deep-Dive & Rebid")));

        sb.append("\n## Top vendors within this category\n\n");
        sb.append("| Vendor | Spend |\n|---|---|\n");
        target.topVendors().stream().limit(MAX_VENDORS_IN_PROMPT).forEach(v ->
                sb.append(String.format("| %s | $%.2fM |%n", v.name(), v.sumAmount() / 1_000_000)));

        sb.append("\n## Consolidation band in use\n\n");
        sb.append(String.format("- Suggested savings if consolidated: %.0f%%-%.0f%% of category spend%n",
                config.insightThresholds().consolidationLowPct() * 100,
                config.insightThresholds().consolidationHighPct() * 100));

        sb.append("\nProduce 2-4 insights about THIS category (JSON array only).");
        return sb.toString();
    }

    private String buildVendorPrompt() {
        VendorRationalizationDtos.DashboardSummary d = analyticsService.getDashboard();
        VendorRationalizationDtos.CategoryAnalyticsResponse cats = analyticsService.getCategoryAnalytics();

        StringBuilder sb = new StringBuilder();
        sb.append("## Portfolio overview\n\n");
        sb.append(String.format("Total vendors: %d across %d categories, $%.0fM addressable spend%n%n",
                d.totalVendors(), d.totalCategories(), d.totalSpend() / 1_000_000));

        sb.append("## Most fragmented categories (potential vendor sprawl)\n\n");
        sb.append("| Category | Vendors | Spend |\n|---|---|---|\n");
        cats.categories().stream()
                .sorted((a, b) -> Integer.compare(b.vendorCount(), a.vendorCount()))
                .limit(6)
                .forEach(c -> sb.append(String.format("| %s | %d | $%.1fM |%n",
                        c.category(), c.vendorCount(), c.totalSpend() / 1_000_000)));

        sb.append("\n## Most concentrated categories (potential dependency risk)\n\n");
        sb.append("| Category | Vendors | Spend |\n|---|---|---|\n");
        cats.categories().stream()
                .filter(c -> c.vendorCount() > 0 && c.vendorCount() <= 3 && c.totalSpend() > 5_000_000)
                .sorted((a, b) -> Double.compare(b.totalSpend(), a.totalSpend()))
                .limit(6)
                .forEach(c -> sb.append(String.format("| %s | %d | $%.1fM |%n",
                        c.category(), c.vendorCount(), c.totalSpend() / 1_000_000)));

        sb.append("\n## Top vendors by absolute spend\n\n");
        sb.append("| Vendor | Category | Spend |\n|---|---|---|\n");
        d.topVendors().stream().limit(MAX_VENDORS_IN_PROMPT).forEach(v ->
                sb.append(String.format("| %s | %s | $%.2fM |%n",
                        v.name(),
                        v.generalizedCategory() == null ? "—" : v.generalizedCategory(),
                        v.sumAmount() / 1_000_000)));

        sb.append("\nProduce 3-5 vendor anomaly insights now (JSON array only). " +
                  "Mix vendor sprawl, concentration risk, and any other patterns you spot.");
        return sb.toString();
    }

    private String buildSavingsPrompt() {
        VendorRationalizationDtos.SavingsOpportunitiesResponse s = analyticsService.getSavingsOpportunities();

        StringBuilder sb = new StringBuilder();
        sb.append("## Savings program summary\n\n");
        sb.append(String.format("Identified savings: $%.1fM%n", s.totalIdentifiedSavings() / 1_000_000));
        sb.append(String.format("Active opportunities: %d%n", s.activeOpportunities()));
        sb.append(String.format("Quick wins: %d%n", s.quickWins()));
        sb.append(String.format("Average confidence: %.1f%%%n%n", s.avgConfidence() * 100));

        sb.append("## Top opportunities (sorted by upper savings bound, top 10)\n\n");
        sb.append("| Opportunity | Category | Current spend | Savings low | Savings high | Confidence | Stage | Lever |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        s.opportunities().stream().limit(10).forEach(o -> sb.append(String.format(
                "| %s | %s | $%.1fM | $%.1fM | $%.1fM | %.0f%% | %s | %s |%n",
                o.name(), o.category(),
                o.currentSpend() / 1_000_000,
                o.estimatedSavingsLow() / 1_000_000,
                o.estimatedSavingsHigh() / 1_000_000,
                o.confidence() * 100,
                o.stage(), o.lever())));

        sb.append("\nProduce EXACTLY 3 insights in this order:\n");
        sb.append("  1. iconHint=\"quick-win\" — highest-confidence row with the strongest $ band\n");
        sb.append("  2. iconHint=\"risk\"      — biggest dependency / concentration play\n");
        sb.append("  3. iconHint=\"quick-win\" — the easiest small-ticket win\n");
        sb.append("Always cite the opportunity name and savings band ($X.XM–$Y.YM).");
        return sb.toString();
    }

    private String buildContractsPrompt() {
        VendorRationalizationDtos.DashboardSummary d = analyticsService.getDashboard();
        VendorRationalizationDtos.CategoryAnalyticsResponse cats = analyticsService.getCategoryAnalytics();

        StringBuilder sb = new StringBuilder();
        sb.append("## Contract risk inputs (inferred from vendor / spend distribution)\n\n");
        sb.append(String.format("Total vendors: %d, total spend $%.0fM%n%n",
                d.totalVendors(), d.totalSpend() / 1_000_000));

        sb.append("### Fragmented categories — most likely source of billing variance & duplicate invoices\n\n");
        sb.append("| Category | Vendors | Spend |\n|---|---|---|\n");
        cats.categories().stream()
                .filter(c -> c.vendorCount() >= 10)
                .sorted((a, b) -> Integer.compare(b.vendorCount(), a.vendorCount()))
                .limit(5)
                .forEach(c -> sb.append(String.format("| %s | %d | $%.1fM |%n",
                        c.category(), c.vendorCount(), c.totalSpend() / 1_000_000)));

        sb.append("\n### Single-vendor / concentrated categories — likely term-mismatch & volume-inconsistency exposure\n\n");
        sb.append("| Category | Vendors | Spend |\n|---|---|---|\n");
        cats.categories().stream()
                .filter(c -> c.vendorCount() > 0 && c.vendorCount() <= 3 && c.totalSpend() > 3_000_000)
                .sorted((a, b) -> Double.compare(b.totalSpend(), a.totalSpend()))
                .limit(5)
                .forEach(c -> sb.append(String.format("| %s | %d | $%.1fM |%n",
                        c.category(), c.vendorCount(), c.totalSpend() / 1_000_000)));

        sb.append("\nProduce 3-5 contract-compliance anomaly insights now (JSON array only). " +
                  "Phrase them as compliance risk types (billing variance, duplicate invoice exposure, " +
                  "term mismatch risk, volume inconsistency) but cite the real category names and numbers above.");
        return sb.toString();
    }

    // ── Response parsing ─────────────────────────────────────────────────

    private List<VendorRationalizationDtos.AiInsight> parseInsights(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = stripFences(raw.trim());
        try {
            VendorRationalizationDtos.AiInsight[] arr =
                    objectMapper.readValue(trimmed, VendorRationalizationDtos.AiInsight[].class);
            return List.of(arr);
        } catch (Exception e) {
            try {
                var node = objectMapper.readTree(trimmed);
                var arrNode = node.get("insights");
                if (arrNode != null && arrNode.isArray()) {
                    VendorRationalizationDtos.AiInsight[] arr =
                            objectMapper.treeToValue(arrNode, VendorRationalizationDtos.AiInsight[].class);
                    return List.of(arr);
                }
            } catch (Exception ignored) {
                // fall through
            }
            log.debug("[VRInsights] failed to parse model output as JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /** Strip ```json … ``` or ``` … ``` fences some models still emit. */
    private String stripFences(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            int closing = s.lastIndexOf("```");
            if (closing > 0) s = s.substring(0, closing);
        }
        return s.trim();
    }

    private record Cached(
            List<VendorRationalizationDtos.AiInsight> insights,
            long generatedAt,
            String modelRef) {}
}
