package com.pods.agent.service;

import com.pods.agent.domain.ModelConfig;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.config.RuntimeTuningProperties;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class ModelAutoRouterService {
    private final ModelRegistryService modelRegistryService;
    private final RuntimeTuningProperties runtimeTuningProperties;

    public ModelAutoRouterService(ModelRegistryService modelRegistryService,
                                  RuntimeTuningProperties runtimeTuningProperties) {
        this.modelRegistryService = modelRegistryService;
        this.runtimeTuningProperties = runtimeTuningProperties;
    }

    public ModelRef pickModel(String userMessage, int historySize, boolean hasParallelTasks) {
        List<ModelConfig> enabled = modelRegistryService.listEnabled();
        if (enabled.isEmpty()) return null;

        int complexity = computeComplexity(userMessage, historySize, hasParallelTasks);

        List<ModelConfig> sorted = enabled.stream()
                .sorted(Comparator.comparingInt(this::rank))
                .toList();

        ModelConfig choice;
        if (complexity < runtimeTuningProperties.getAutoRoutingLowThreshold()) {
            choice = sorted.get(0);
        } else if (complexity < runtimeTuningProperties.getAutoRoutingMediumThreshold()) {
            choice = sorted.get(Math.min(1, sorted.size() - 1));
        } else {
            choice = sorted.get(sorted.size() - 1);
        }
        return new ModelRef(choice.getProviderId(), choice.getModelId());
    }

    private int computeComplexity(String userMessage, int historySize, boolean hasParallelTasks) {
        if (userMessage == null) userMessage = "";
        String text = userMessage.trim();
        int words = text.isBlank() ? 0 : text.split("\\s+").length;
        int codeHints = countMatches(text.toLowerCase(), "```") * 200
                + countMatches(text.toLowerCase(), "json") * 35
                + countMatches(text.toLowerCase(), "api") * 20
                + countMatches(text.toLowerCase(), "parallel") * 90;
        int questionDepth = countMatches(text, "?") * 25;
        return text.length()
                + (words * 4)
                + questionDepth
                + codeHints
                + (historySize * 40)
                + (hasParallelTasks ? 700 : 0);
    }

    private int countMatches(String input, String needle) {
        if (input == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = input.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private int rank(ModelConfig m) {
        String id = (m.getModelId() + " " + m.getDisplayName()).toLowerCase();
        if (id.contains("mini") || id.contains("haiku") || id.contains("small")) return 1;
        if (id.contains("sonnet") || id.contains("medium")) return 2;
        if (id.contains("opus") || id.contains("gpt-4.1") || id.contains("large")) return 3;
        return 2;
    }
}
