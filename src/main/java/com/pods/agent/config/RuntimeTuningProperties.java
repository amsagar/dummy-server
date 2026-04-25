package com.pods.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "pods.runtime")
public class RuntimeTuningProperties {
    private long summaryTokenThreshold = 2000;
    private int autoRoutingLowThreshold = 400;
    private int autoRoutingMediumThreshold = 1400;
    private int toolAutoPickMinScore = 30;
    private int toolAutoPickAmbiguityDelta = 10;
    private long hitlReplyTimeoutMs = 90_000;
    private long titleGenerationTimeoutMs = 12_000;
    private int toolLoopMaxSteps = 4;
    private long workerTaskTimeoutMs = 20_000;
    private int summaryRetainRecentMessages = 8;
    private int contextWindowUtilizationPercent = 85;
    private int minHistoryMessagesToKeep = 6;
    private boolean enablePromptWindowGuard = true;
    private boolean includeSkillContentInSystemPrompt = true;
    private int maxSkillContentChars = 12_000;
    private int maxSkillFilesPerSkill = 6;
    private boolean enableMessageSanitization = true;
    private boolean enableBudgetHints = true;
    private int budgetHintWarnPercent = 50;
    private int budgetHintElevatedPercent = 70;
    private int budgetHintCriticalPercent = 85;
    private boolean enableAnthropicPromptCachingHints = false;
    private double anthropicCharsPerToken = 3.5;
    private double openAiCharsPerToken = 4.0;
    private double ollamaCharsPerToken = 4.2;
    private boolean includeFullSkillFiles = false;
    private boolean includeMcpPromptHints = false;
    private boolean strictScopeOnly = true;
    private boolean allowCapabilitiesQueries = true;
    private String outOfScopeRefusalMessage = "I can’t answer this because it is outside my allowed skills/tools scope.";
    private boolean persistInternalEvents = false;
    private boolean enableExperimentalTools = false;
    private List<String> enabledExperimentalToolNames = new ArrayList<>();
    private List<String> allowedPermissionScopes = new ArrayList<>(List.of("filesystem", "shell", "web", "workflow", "memory", "integration", "http_proxy"));

    public long getSummaryTokenThreshold() {
        return summaryTokenThreshold;
    }

    public void setSummaryTokenThreshold(long summaryTokenThreshold) {
        this.summaryTokenThreshold = summaryTokenThreshold;
    }

    public int getAutoRoutingLowThreshold() {
        return autoRoutingLowThreshold;
    }

    public void setAutoRoutingLowThreshold(int autoRoutingLowThreshold) {
        this.autoRoutingLowThreshold = autoRoutingLowThreshold;
    }

    public int getAutoRoutingMediumThreshold() {
        return autoRoutingMediumThreshold;
    }

    public void setAutoRoutingMediumThreshold(int autoRoutingMediumThreshold) {
        this.autoRoutingMediumThreshold = autoRoutingMediumThreshold;
    }

    public int getToolAutoPickMinScore() {
        return toolAutoPickMinScore;
    }

    public void setToolAutoPickMinScore(int toolAutoPickMinScore) {
        this.toolAutoPickMinScore = toolAutoPickMinScore;
    }

    public int getToolAutoPickAmbiguityDelta() {
        return toolAutoPickAmbiguityDelta;
    }

    public void setToolAutoPickAmbiguityDelta(int toolAutoPickAmbiguityDelta) {
        this.toolAutoPickAmbiguityDelta = toolAutoPickAmbiguityDelta;
    }

    public long getHitlReplyTimeoutMs() {
        return hitlReplyTimeoutMs;
    }

    public void setHitlReplyTimeoutMs(long hitlReplyTimeoutMs) {
        this.hitlReplyTimeoutMs = hitlReplyTimeoutMs;
    }

    public long getTitleGenerationTimeoutMs() {
        return titleGenerationTimeoutMs;
    }

    public void setTitleGenerationTimeoutMs(long titleGenerationTimeoutMs) {
        this.titleGenerationTimeoutMs = titleGenerationTimeoutMs;
    }

    public int getToolLoopMaxSteps() {
        return toolLoopMaxSteps;
    }

    public void setToolLoopMaxSteps(int toolLoopMaxSteps) {
        this.toolLoopMaxSteps = toolLoopMaxSteps;
    }

    public long getWorkerTaskTimeoutMs() {
        return workerTaskTimeoutMs;
    }

    public void setWorkerTaskTimeoutMs(long workerTaskTimeoutMs) {
        this.workerTaskTimeoutMs = workerTaskTimeoutMs;
    }

    public int getSummaryRetainRecentMessages() {
        return summaryRetainRecentMessages;
    }

    public void setSummaryRetainRecentMessages(int summaryRetainRecentMessages) {
        this.summaryRetainRecentMessages = summaryRetainRecentMessages;
    }

    public int getContextWindowUtilizationPercent() {
        return contextWindowUtilizationPercent;
    }

    public void setContextWindowUtilizationPercent(int contextWindowUtilizationPercent) {
        this.contextWindowUtilizationPercent = contextWindowUtilizationPercent;
    }

    public int getMinHistoryMessagesToKeep() {
        return minHistoryMessagesToKeep;
    }

    public void setMinHistoryMessagesToKeep(int minHistoryMessagesToKeep) {
        this.minHistoryMessagesToKeep = minHistoryMessagesToKeep;
    }

    public boolean isEnablePromptWindowGuard() {
        return enablePromptWindowGuard;
    }

    public void setEnablePromptWindowGuard(boolean enablePromptWindowGuard) {
        this.enablePromptWindowGuard = enablePromptWindowGuard;
    }

    public boolean isIncludeSkillContentInSystemPrompt() {
        return includeSkillContentInSystemPrompt;
    }

    public void setIncludeSkillContentInSystemPrompt(boolean includeSkillContentInSystemPrompt) {
        this.includeSkillContentInSystemPrompt = includeSkillContentInSystemPrompt;
    }

    public int getMaxSkillContentChars() {
        return maxSkillContentChars;
    }

    public void setMaxSkillContentChars(int maxSkillContentChars) {
        this.maxSkillContentChars = maxSkillContentChars;
    }

    public int getMaxSkillFilesPerSkill() {
        return maxSkillFilesPerSkill;
    }

    public void setMaxSkillFilesPerSkill(int maxSkillFilesPerSkill) {
        this.maxSkillFilesPerSkill = maxSkillFilesPerSkill;
    }

    public boolean isEnableMessageSanitization() {
        return enableMessageSanitization;
    }

    public void setEnableMessageSanitization(boolean enableMessageSanitization) {
        this.enableMessageSanitization = enableMessageSanitization;
    }

    public boolean isEnableBudgetHints() {
        return enableBudgetHints;
    }

    public void setEnableBudgetHints(boolean enableBudgetHints) {
        this.enableBudgetHints = enableBudgetHints;
    }

    public int getBudgetHintWarnPercent() {
        return budgetHintWarnPercent;
    }

    public void setBudgetHintWarnPercent(int budgetHintWarnPercent) {
        this.budgetHintWarnPercent = budgetHintWarnPercent;
    }

    public int getBudgetHintElevatedPercent() {
        return budgetHintElevatedPercent;
    }

    public void setBudgetHintElevatedPercent(int budgetHintElevatedPercent) {
        this.budgetHintElevatedPercent = budgetHintElevatedPercent;
    }

    public int getBudgetHintCriticalPercent() {
        return budgetHintCriticalPercent;
    }

    public void setBudgetHintCriticalPercent(int budgetHintCriticalPercent) {
        this.budgetHintCriticalPercent = budgetHintCriticalPercent;
    }

    public boolean isEnableAnthropicPromptCachingHints() {
        return enableAnthropicPromptCachingHints;
    }

    public void setEnableAnthropicPromptCachingHints(boolean enableAnthropicPromptCachingHints) {
        this.enableAnthropicPromptCachingHints = enableAnthropicPromptCachingHints;
    }

    public double getAnthropicCharsPerToken() {
        return anthropicCharsPerToken;
    }

    public void setAnthropicCharsPerToken(double anthropicCharsPerToken) {
        this.anthropicCharsPerToken = anthropicCharsPerToken;
    }

    public double getOpenAiCharsPerToken() {
        return openAiCharsPerToken;
    }

    public void setOpenAiCharsPerToken(double openAiCharsPerToken) {
        this.openAiCharsPerToken = openAiCharsPerToken;
    }

    public double getOllamaCharsPerToken() {
        return ollamaCharsPerToken;
    }

    public void setOllamaCharsPerToken(double ollamaCharsPerToken) {
        this.ollamaCharsPerToken = ollamaCharsPerToken;
    }

    public boolean isIncludeFullSkillFiles() {
        return includeFullSkillFiles;
    }

    public void setIncludeFullSkillFiles(boolean includeFullSkillFiles) {
        this.includeFullSkillFiles = includeFullSkillFiles;
    }

    public boolean isIncludeMcpPromptHints() {
        return includeMcpPromptHints;
    }

    public void setIncludeMcpPromptHints(boolean includeMcpPromptHints) {
        this.includeMcpPromptHints = includeMcpPromptHints;
    }

    public boolean isStrictScopeOnly() {
        return strictScopeOnly;
    }

    public void setStrictScopeOnly(boolean strictScopeOnly) {
        this.strictScopeOnly = strictScopeOnly;
    }

    public boolean isAllowCapabilitiesQueries() {
        return allowCapabilitiesQueries;
    }

    public void setAllowCapabilitiesQueries(boolean allowCapabilitiesQueries) {
        this.allowCapabilitiesQueries = allowCapabilitiesQueries;
    }

    public String getOutOfScopeRefusalMessage() {
        return outOfScopeRefusalMessage;
    }

    public void setOutOfScopeRefusalMessage(String outOfScopeRefusalMessage) {
        this.outOfScopeRefusalMessage = outOfScopeRefusalMessage;
    }

    public boolean isPersistInternalEvents() {
        return persistInternalEvents;
    }

    public void setPersistInternalEvents(boolean persistInternalEvents) {
        this.persistInternalEvents = persistInternalEvents;
    }

    public boolean isEnableExperimentalTools() {
        return enableExperimentalTools;
    }

    public void setEnableExperimentalTools(boolean enableExperimentalTools) {
        this.enableExperimentalTools = enableExperimentalTools;
    }

    public List<String> getEnabledExperimentalToolNames() {
        return enabledExperimentalToolNames;
    }

    public void setEnabledExperimentalToolNames(List<String> enabledExperimentalToolNames) {
        this.enabledExperimentalToolNames = enabledExperimentalToolNames;
    }

    public List<String> getAllowedPermissionScopes() {
        return allowedPermissionScopes;
    }

    public void setAllowedPermissionScopes(List<String> allowedPermissionScopes) {
        this.allowedPermissionScopes = allowedPermissionScopes;
    }

}
