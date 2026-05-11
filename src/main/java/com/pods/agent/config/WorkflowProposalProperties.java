package com.pods.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the two-phase workflow proposal pipeline.
 *
 * <ul>
 *   <li>{@code maxBuildAttempts} — combined retry budget for the Phase-2
 *       builder loop. Each attempt is one full pass of (read draft from VFS
 *       → structural validation → alignment LLM judgement). The budget
 *       covers structural-fix and alignment-fix attempts together so a
 *       drafting agent that ping-pongs between fixing one and breaking the
 *       other still terminates.</li>
 *   <li>{@code classifierModel} / {@code builderModel} — optional per-phase
 *       model overrides. When both fields are blank the phase falls back to
 *       the {@code modelRef} stored on the proposal (the model used by the
 *       chat agent at the source turn), preserving the existing behaviour.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "pods.workflow.proposal")
public class WorkflowProposalProperties {

    private int maxBuildAttempts = 7;
    private ModelOverride classifierModel = new ModelOverride();
    private ModelOverride builderModel = new ModelOverride();
    private ModelOverride alignmentModel = new ModelOverride();

    public int getMaxBuildAttempts() {
        return maxBuildAttempts;
    }

    public void setMaxBuildAttempts(int maxBuildAttempts) {
        this.maxBuildAttempts = maxBuildAttempts;
    }

    public ModelOverride getClassifierModel() {
        return classifierModel;
    }

    public void setClassifierModel(ModelOverride classifierModel) {
        this.classifierModel = classifierModel;
    }

    public ModelOverride getBuilderModel() {
        return builderModel;
    }

    public void setBuilderModel(ModelOverride builderModel) {
        this.builderModel = builderModel;
    }

    public ModelOverride getAlignmentModel() {
        return alignmentModel;
    }

    public void setAlignmentModel(ModelOverride alignmentModel) {
        this.alignmentModel = alignmentModel;
    }

    public static class ModelOverride {
        private String providerId = "";
        private String modelId = "";

        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(String providerId) {
            this.providerId = providerId == null ? "" : providerId;
        }

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId == null ? "" : modelId;
        }

        public boolean isPresent() {
            return providerId != null && !providerId.isBlank()
                    && modelId != null && !modelId.isBlank();
        }
    }
}
