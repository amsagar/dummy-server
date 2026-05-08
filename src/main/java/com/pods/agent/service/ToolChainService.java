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
    public static final String ORIGIN_USER = "user";
    public static final String ORIGIN_SYSTEM_SUGGESTED = "system_suggested";
    public static final String APPROVAL_PENDING = "pending";
    public static final String APPROVAL_APPROVED = "approved";
    public static final String APPROVAL_REJECTED = "rejected";

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
                .origin(ORIGIN_USER)
                .approvalStatus(APPROVAL_APPROVED)
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

    /**
     * Refresh just the chain's display metadata. Called after the architect's "AI Create"
     * publishes a new version so the listing shows a meaningful Name and Description
     * derived from the actual graph instead of the user's first prompt echoed twice.
     */
    public ToolChain updateMetadata(String id, String name, String description) {
        ToolChain existing = getRequired(id);
        if (name != null && !name.isBlank()) existing.setName(name.trim());
        if (description != null && !description.isBlank()) existing.setDescription(description.trim());
        return toolChainRepository.update(existing);
    }

    public List<ToolChain> listAll() {
        return toolChainRepository.findAll();
    }

    public List<ToolChain> listAll(String origin) {
        if (origin == null || origin.isBlank()) return listAll();
        return toolChainRepository.findByOrigin(origin.toLowerCase(Locale.ROOT));
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
                .intentSignature(request.getIntentSignature())
                .structureSignature(request.getStructureSignature())
                .ragConfigJson(toJson(request.getRagConfig() == null ? Map.of() : request.getRagConfig()))
                .variablesJson(toJson(request.getVariables() == null ? List.of() : request.getVariables()))
                .published(false)
                .createdBy(createdBy)
                .build());
        chain.setCurrentVersion(version);
        toolChainRepository.update(chain);
        return created;
    }

    /**
     * Materialise the architect's most recent edit as a versioned draft. If a draft
     * row already exists for this chain (is_published=false), update it in place;
     * otherwise create a new row with the next version number. Either way the
     * chain's currentVersion is bumped to the draft's version (matching createVersion's
     * existing semantics — currentVersion reflects "latest version row created", not
     * "latest published"). The returned version is always unpublished.
     */
    public ToolChainVersion upsertDraftVersion(String toolChainId,
                                               ToolChainDtos.ToolChainVersionRequest request,
                                               String createdBy) {
        ToolChain chain = getRequired(toolChainId);
        Optional<ToolChainVersion> existingDraft = toolChainVersionRepository.findLatestDraft(toolChainId);
        if (existingDraft.isPresent()) {
            ToolChainVersion draft = existingDraft.get();
            draft.setGraphJson(request.getGraphJson());
            draft.setInputSchema(request.getInputSchema());
            draft.setOutputSchema(request.getOutputSchema());
            draft.setResponseMode(request.getResponseMode() == null ? "hybrid" : request.getResponseMode());
            draft.setSynthesisPrompt(request.getSynthesisPrompt());
            draft.setIntentsJson(toJson(request.getIntents() == null ? List.of() : request.getIntents()));
            draft.setIntentSignature(request.getIntentSignature());
            draft.setStructureSignature(request.getStructureSignature());
            draft.setRagConfigJson(toJson(request.getRagConfig() == null ? Map.of() : request.getRagConfig()));
            draft.setVariablesJson(toJson(request.getVariables() == null ? List.of() : request.getVariables()));
            toolChainVersionRepository.updateDraft(draft);
            if (chain.getCurrentVersion() == null || chain.getCurrentVersion() < draft.getVersion()) {
                chain.setCurrentVersion(draft.getVersion());
                toolChainRepository.update(chain);
            }
            return draft;
        }
        return createVersion(toolChainId, request, createdBy);
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

    public ToolChain approveSystemToolChain(String id, String approver, String comment) {
        ToolChain chain = getRequired(id);
        if (!ORIGIN_SYSTEM_SUGGESTED.equalsIgnoreCase(chain.getOrigin())) {
            throw new IllegalArgumentException("Only system suggested toolchains support approval workflow");
        }
        chain.setApprovalStatus(APPROVAL_APPROVED);
        chain.setApprovedBy(approver);
        chain.setApprovedAt(System.currentTimeMillis());
        mergeApprovalComment(chain, comment);
        return toolChainRepository.update(chain);
    }

    public ToolChain rejectSystemToolChain(String id, String approver, String comment) {
        ToolChain chain = getRequired(id);
        if (!ORIGIN_SYSTEM_SUGGESTED.equalsIgnoreCase(chain.getOrigin())) {
            throw new IllegalArgumentException("Only system suggested toolchains support approval workflow");
        }
        chain.setApprovalStatus(APPROVAL_REJECTED);
        chain.setApprovedBy(approver);
        chain.setApprovedAt(System.currentTimeMillis());
        mergeApprovalComment(chain, comment);
        return toolChainRepository.update(chain);
    }

    /**
     * Self-heal entry point: persist a learned JSONata expression for a single argument on a
     * specific node within an existing version. Idempotent — last-write-wins under concurrent
     * runs. Returns true when the graph was actually updated.
     *
     * The change is purely additive (it strengthens a previously llm_assisted expression with a
     * verified deterministic one), so we mutate graph_json in place without bumping the version
     * or changing approval state.
     */
    public boolean persistLearnedExpression(String versionId,
                                            String nodeId,
                                            String argName,
                                            String learnedExpr) {
        if (versionId == null || nodeId == null || argName == null || learnedExpr == null) return false;
        if (learnedExpr.isBlank()) return false;
        Optional<ToolChainVersion> versionOpt = toolChainVersionRepository.findById(versionId);
        if (versionOpt.isEmpty()) return false;
        ToolChainVersion version = versionOpt.get();
        try {
            Map<String, Object> graph = objectMapper.readValue(version.getGraphJson(), Map.class);
            if (!applyLearnedExpressionToGraph(graph, nodeId, argName, learnedExpr)) return false;
            String updated = objectMapper.writeValueAsString(graph);
            toolChainVersionRepository.updateGraphJson(versionId, updated);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean applyLearnedExpressionToGraph(Map<String, Object> graph,
                                                  String nodeId,
                                                  String argName,
                                                  String learnedExpr) {
        Object nodesObj = graph.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) return false;
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> node)) continue;
            if (!nodeId.equals(String.valueOf(node.get("id")))) continue;
            Object configObj = node.get("config");
            if (!(configObj instanceof Map<?, ?> config)) return false;
            Object mappingsObj = config.get("argMappings");
            if (!(mappingsObj instanceof Map<?, ?> mappings)) return false;
            Object existing = mappings.get(argName);
            Map<String, Object> nextMapping = new LinkedHashMap<>();
            if (existing instanceof Map<?, ?> em) {
                em.forEach((k, v) -> nextMapping.put(String.valueOf(k), v));
            } else if (existing instanceof String s && !s.isBlank()) {
                nextMapping.put("expr", s);
                nextMapping.put("policy", "strict");
            }
            nextMapping.put("expr", learnedExpr);
            // Once we've verified a deterministic expression, downgrade policy to strict so the
            // runtime stops invoking the LLM for this arg on subsequent runs.
            nextMapping.put("policy", "strict");
            nextMapping.put("learnedAt", System.currentTimeMillis());
            ((Map<String, Object>) mappings).put(argName, nextMapping);
            return true;
        }
        return false;
    }

    public Optional<ToolChain> findBySignatures(String intentSignature, String structureSignature) {
        if (intentSignature == null || intentSignature.isBlank() || structureSignature == null || structureSignature.isBlank()) {
            return Optional.empty();
        }
        return toolChainRepository.findBySignatures(intentSignature, structureSignature);
    }

    public ToolChain createSystemSuggested(String name,
                                           String description,
                                           String createdBy,
                                           String intentSignature,
                                           String structureSignature,
                                           Map<String, Object> metadata) {
        ToolChain chain = ToolChain.builder()
                .name(name)
                .description(description)
                .enabled(true)
                .status("draft")
                .origin(ORIGIN_SYSTEM_SUGGESTED)
                .approvalStatus(APPROVAL_PENDING)
                .intentSignature(intentSignature)
                .structureSignature(structureSignature)
                .metadataJson(toJson(metadata == null ? Map.of() : metadata))
                .createdBy(createdBy)
                .build();
        return toolChainRepository.save(chain);
    }

    public List<IntentMatch> findIntentMatches(String userText, double threshold, int limit) {
        if (userText == null || userText.isBlank()) return List.of();
        Set<String> userTokens = tokenize(userText);
        List<IntentMatch> matches = new ArrayList<>();
        for (ToolChain chain : toolChainRepository.findEnabled()) {
            if (ORIGIN_SYSTEM_SUGGESTED.equalsIgnoreCase(chain.getOrigin())
                    && !APPROVAL_APPROVED.equalsIgnoreCase(chain.getApprovalStatus())) {
                continue;
            }
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

    public Optional<IntentMatch> findBestIntentMatchForDedup(String userText, double threshold) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        Set<String> userTokens = tokenize(userText);
        IntentMatch best = null;
        for (ToolChain chain : toolChainRepository.findAll()) {
            Optional<ToolChainVersion> versionOpt = toolChainVersionRepository.findPublished(chain.getId())
                    .or(() -> toolChainVersionRepository.findByChain(chain.getId()).stream().findFirst());
            if (versionOpt.isEmpty()) continue;
            ToolChainVersion version = versionOpt.get();
            List<String> intents = readIntents(chain, version);
            double score = scoreIntent(userText, userTokens, chain, intents);
            if (score < threshold) continue;
            List<String> matched = intents.stream()
                    .filter(i -> overlap(userTokens, tokenize(i)) > 0
                            || userText.toLowerCase(Locale.ROOT).contains(i.toLowerCase(Locale.ROOT)))
                    .limit(5)
                    .toList();
            IntentMatch candidate = new IntentMatch(
                    chain.getId(),
                    version.getVersion(),
                    chain.getName(),
                    score,
                    matched
            );
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
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
    private void mergeApprovalComment(ToolChain chain, String comment) {
        if (comment == null || comment.isBlank()) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        try {
            Object parsed = objectMapper.readValue(chain.getMetadataJson() == null ? "{}" : chain.getMetadataJson(), Object.class);
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> metadata.put(String.valueOf(k), v));
            }
        } catch (Exception ignored) {
        }
        metadata.put("approvalComment", comment);
        chain.setMetadataJson(toJson(metadata));
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
