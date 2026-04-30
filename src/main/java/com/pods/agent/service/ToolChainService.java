package com.pods.agent.service;

import com.pods.agent.api.dto.ToolChainDtos;
import com.pods.agent.domain.ToolChain;
import com.pods.agent.domain.ToolChainVersion;
import com.pods.agent.repository.ToolChainRepository;
import com.pods.agent.repository.ToolChainVersionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ToolChainService {
    public record IntentMatch(String toolChainId, int version, String name, double score, List<String> matchedIntents) {}

    private final ToolChainRepository toolChainRepository;
    private final ToolChainVersionRepository toolChainVersionRepository;
    private final ObjectMapper objectMapper;

    public ToolChainService(ToolChainRepository toolChainRepository,
                            ToolChainVersionRepository toolChainVersionRepository,
                            ObjectMapper objectMapper) {
        this.toolChainRepository = toolChainRepository;
        this.toolChainVersionRepository = toolChainVersionRepository;
        this.objectMapper = objectMapper;
    }

    public ToolChain create(ToolChainDtos.ToolChainCreateRequest request, String createdBy) {
        ToolChain chain = ToolChain.builder()
                .name(request.getName())
                .description(request.getDescription())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .status("draft")
                .metadataJson(toJson(request.getMetadata()))
                .createdBy(createdBy)
                .build();
        return toolChainRepository.save(chain);
    }

    public ToolChain update(String id, ToolChainDtos.ToolChainCreateRequest request) {
        ToolChain existing = getRequired(id);
        existing.setName(request.getName() == null ? existing.getName() : request.getName());
        existing.setDescription(request.getDescription());
        if (request.getEnabled() != null) existing.setEnabled(request.getEnabled());
        if (request.getMetadata() != null) existing.setMetadataJson(toJson(request.getMetadata()));
        return toolChainRepository.update(existing);
    }

    public List<ToolChain> listAll() {
        return toolChainRepository.findAll();
    }

    public ToolChain getRequired(String id) {
        return toolChainRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ToolChain not found: " + id));
    }

    public Optional<ToolChainVersion> resolveVersion(String toolChainId, Integer requestedVersion) {
        if (requestedVersion != null) {
            return toolChainVersionRepository.findByChainAndVersion(toolChainId, requestedVersion);
        }
        return toolChainVersionRepository.findPublished(toolChainId)
                .or(() -> toolChainVersionRepository.findByChain(toolChainId).stream().findFirst());
    }

    public ToolChainVersion createVersion(String toolChainId,
                                          ToolChainDtos.ToolChainVersionRequest request,
                                          String createdBy) {
        ToolChain chain = getRequired(toolChainId);
        List<ToolChainVersion> versions = toolChainVersionRepository.findByChain(toolChainId);
        int version = request.getVersion() != null ? request.getVersion() : (versions.isEmpty() ? 1 : versions.get(0).getVersion() + 1);
        ToolChainVersion created = toolChainVersionRepository.save(ToolChainVersion.builder()
                .toolChainId(toolChainId)
                .version(version)
                .graphJson(request.getGraphJson())
                .inputSchema(request.getInputSchema())
                .outputSchema(request.getOutputSchema())
                .responseMode(request.getResponseMode() == null ? "hybrid" : request.getResponseMode())
                .synthesisPrompt(request.getSynthesisPrompt())
                .intentsJson(toJson(request.getIntents() == null ? List.of() : request.getIntents()))
                .ragConfigJson(toJson(request.getRagConfig() == null ? Map.of() : request.getRagConfig()))
                .published(false)
                .createdBy(createdBy)
                .build());
        chain.setCurrentVersion(version);
        toolChainRepository.update(chain);
        return created;
    }

    public ToolChainVersion publishVersion(String toolChainId, int version) {
        ToolChain chain = getRequired(toolChainId);
        ToolChainVersion target = toolChainVersionRepository.findByChainAndVersion(toolChainId, version)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + version));
        toolChainVersionRepository.publish(target.getId(), toolChainId);
        chain.setStatus("published");
        chain.setCurrentVersion(version);
        toolChainRepository.update(chain);
        return target;
    }

    public List<ToolChainVersion> listVersions(String toolChainId) {
        return toolChainVersionRepository.findByChain(toolChainId);
    }

    public void delete(String id) {
        toolChainRepository.delete(id);
    }

    public List<IntentMatch> findIntentMatches(String userText, double threshold, int limit) {
        if (userText == null || userText.isBlank()) return List.of();
        Set<String> userTokens = tokenize(userText);
        List<IntentMatch> matches = new ArrayList<>();
        for (ToolChain chain : toolChainRepository.findEnabled()) {
            Optional<ToolChainVersion> versionOpt = toolChainVersionRepository.findPublished(chain.getId())
                    .or(() -> toolChainVersionRepository.findByChain(chain.getId()).stream().findFirst());
            if (versionOpt.isEmpty()) continue;
            ToolChainVersion version = versionOpt.get();
            List<String> intents = readIntents(chain, version);
            double score = scoreIntent(userText, userTokens, chain, intents);
            if (score >= threshold) {
                List<String> matched = intents.stream()
                        .filter(i -> overlap(userTokens, tokenize(i)) > 0 || userText.toLowerCase(Locale.ROOT).contains(i.toLowerCase(Locale.ROOT)))
                        .limit(5)
                        .toList();
                matches.add(new IntentMatch(chain.getId(), version.getVersion(), chain.getName(), score, matched));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(IntentMatch::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public String generateDraftGraph(String prompt) {
        Map<String, Object> draft = Map.of(
                "nodes", List.of(
                        Map.of("id", "start", "type", "start", "label", "Start"),
                        Map.of("id", "task_1", "type", "tool", "label", "Primary step", "config", Map.of("toolName", "task")),
                        Map.of("id", "end", "type", "end", "label", "End")
                ),
                "edges", List.of(
                        Map.of("from", "start", "to", "task_1"),
                        Map.of("from", "task_1", "to", "end")
                ),
                "description", prompt == null ? "" : prompt
        );
        return toJson(draft);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readIntents(ToolChain chain, ToolChainVersion version) {
        List<String> out = new ArrayList<>();
        try {
            Object parsed = objectMapper.readValue(version.getIntentsJson() == null ? "[]" : version.getIntentsJson(), Object.class);
            if (parsed instanceof List<?> list) {
                for (Object row : list) {
                    if (row != null) out.add(String.valueOf(row));
                }
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty() && chain.getMetadataJson() != null && !chain.getMetadataJson().isBlank()) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(chain.getMetadataJson(), Map.class);
                Object intents = metadata.get("intents");
                if (intents instanceof List<?> list) {
                    for (Object row : list) {
                        if (row != null) out.add(String.valueOf(row));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            out.add(chain.getName());
            if (chain.getDescription() != null) out.add(chain.getDescription());
        }
        return out.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    private double scoreIntent(String userText, Set<String> userTokens, ToolChain chain, List<String> intents) {
        double bestIntent = 0.0;
        String normalizedUser = userText.toLowerCase(Locale.ROOT);
        for (String intent : intents) {
            Set<String> intentTokens = tokenize(intent);
            int overlap = overlap(userTokens, intentTokens);
            double jaccard = jaccard(userTokens, intentTokens);
            double phraseBoost = normalizedUser.contains(intent.toLowerCase(Locale.ROOT)) ? 0.45 : 0.0;
            bestIntent = Math.max(bestIntent, (overlap * 0.1) + (jaccard * 0.6) + phraseBoost);
        }
        double nameBoost = normalizedUser.contains(chain.getName().toLowerCase(Locale.ROOT)) ? 0.25 : 0.0;
        return Math.min(1.0, bestIntent + nameBoost);
    }

    private Set<String> tokenize(String input) {
        if (input == null || input.isBlank()) return Set.of();
        return Pattern.compile("[^a-z0-9]+")
                .splitAsStream(input.toLowerCase(Locale.ROOT))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private int overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int count = 0;
        for (String t : a) if (b.contains(t)) count++;
        return count;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        return union.isEmpty() ? 0.0 : ((double) intersection.size() / (double) union.size());
    }
}
