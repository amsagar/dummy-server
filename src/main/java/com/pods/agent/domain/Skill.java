package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private String id;
    private String name;
    private String description;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;
    /**
     * System-derived rule manifest JSON (Option B / Phase 4).
     * {@code null} for skills that haven't been observed yet, or whose
     * markdown was edited (which clears it for re-derivation). Authors
     * never edit this — it's produced by {@code SkillManifestDeriver}
     * after a successful LLM-loop turn.
     */
    private String derivedManifestJson;
    /** Hash of the skill markdown that produced the current
     *  {@link #derivedManifestJson}. When the markdown's hash drifts,
     *  the derived manifest is stale and gets cleared. */
    private String derivedManifestSourceHash;
    /** When the current derived manifest was produced (ms epoch). */
    private long derivedManifestAt;
}
