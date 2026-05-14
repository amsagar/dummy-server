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
 * Status transitions:
 *   FAILED  ← compilation rejected (XSD or post-validation)
 *   DRAFT   ← compile succeeded, awaiting first successful execution
 *   ACTIVE  ← promoted after N successful runs; eligible for cache hits
 *   DEPRECATED ← skill/tool drift detected, or error rate exceeded
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
}
