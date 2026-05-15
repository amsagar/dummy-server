package com.pods.agent.ruledomain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One compiled BPMN workflow for (skill, intent) — produced by an LLM on first
 * sight of an intent, then re-used by every subsequent request that the intent
 * matcher considers equivalent.
 *
 * <p>A row may be standalone (legacy: {@code domainGroupId == null}) or part
 * of a domain group (Phase 1+: many rules under one skill, fan-out from a
 * broad umbrella intent). Each rule carries its own BPMN, its own embedding,
 * and its own coverage manifest.
 *
 * <p>Status transitions:
 * <ul>
 *   <li>FAILED  — compilation rejected (XSD or post-validation)</li>
 *   <li>DRAFT   — compile succeeded, awaiting first successful execution</li>
 *   <li>ACTIVE  — promoted after N successful runs; eligible for cache hits</li>
 *   <li>DEPRECATED — skill/tool drift detected (error-rate auto-deprecation removed)</li>
 * </ul>
 *
 * <p>Match scopes:
 * <ul>
 *   <li>RULE — narrow intent ("check leg sequence for X"); orchestrator runs only this</li>
 *   <li>DOMAIN_FANOUT — broad umbrella intent ("validate order X"); orchestrator
 *       expands to all sibling RULE rows in the same {@code domainGroupId}</li>
 * </ul>
 *
 * <p>Coverage states (Phase 3):
 * <ul>
 *   <li>COMPLETE — manifest covers every condition the rule needs to handle</li>
 *   <li>PARTIAL — some branches uncovered; runtime evaluates inputs against
 *       manifest and falls back to LLM loop on misses</li>
 *   <li>PROVISIONAL — freshly compiled, never executed; treated as PARTIAL</li>
 *   <li>MERGE_CONFLICT — v1 and v2 traces contradicted; needs human review</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDomain {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String SCOPE_RULE = "RULE";
    public static final String SCOPE_DOMAIN_FANOUT = "DOMAIN_FANOUT";

    public static final String COVERAGE_COMPLETE = "COMPLETE";
    public static final String COVERAGE_PARTIAL = "PARTIAL";
    public static final String COVERAGE_PROVISIONAL = "PROVISIONAL";
    public static final String COVERAGE_MERGE_CONFLICT = "MERGE_CONFLICT";

    public static final String TRACE_LLM_PROSE = "LLM_PROSE";
    public static final String TRACE_LLM_TRACE = "LLM_TRACE";
    public static final String TRACE_HYBRID = "HYBRID";

    private String id;
    private String skillId;
    private String skillName;
    private String intentLabel;
    private String sourceHash;
    private String toolSignature;
    private String bpmnXml;
    private String flowableProcKey;
    /** Embedding stored separately via repository (pgvector column). Not held on the entity. */
    private String status;
    private int version;
    private int compileAttempts;
    private String lastError;
    private long createdAt;
    private long updatedAt;

    // ── Phase 0+: domain-group + coverage fields ───────────────────────────
    /** Group id this rule belongs to. {@code null} for legacy monolithic rows. */
    private String domainGroupId;
    /** Human-readable group name (e.g. "Pods-Order-Validation"). */
    private String domainGroupName;
    /** Rule name within the group (e.g. "leg-sequence-check"). */
    private String ruleName;
    /** Match scope — {@link #SCOPE_RULE} or {@link #SCOPE_DOMAIN_FANOUT}. */
    @Builder.Default
    private String matchScope = SCOPE_RULE;
    /** Coverage state — one of the COVERAGE_* constants. */
    @Builder.Default
    private String coverageState = COVERAGE_COMPLETE;
    /** JSON manifest of observed inputs / exercised branches / open questions. */
    private String coverageManifest;
    /** How the BPMN was produced — one of the TRACE_* constants. */
    private String traceSource;
    /** Turn id whose execution trace produced this BPMN (for LLM_TRACE / HYBRID). */
    private String compiledFromTurn;
    /** Key under which this rule's output is merged into the composite outcome
     *  (e.g. {@code "legSequence"} or {@code "serviceability"}). */
    private String resultKey;
}
