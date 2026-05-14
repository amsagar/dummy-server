package com.pods.agent.service;

import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.service.SkillRegistryService.SkillSnapshot;
import com.pods.agent.service.tool.ToolEmbeddingIndexService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class CatalogSearchService {
    private final ToolRegistryService toolRegistryService;
    private final SkillRegistryService skillRegistryService;
    private final ToolEmbeddingIndexService toolEmbeddingIndexService;
    private final EmbeddingProviderRouter embeddingProviderRouter;
    private final Map<String, float[]> skillEmbeddingCache = new ConcurrentHashMap<>();

    public CatalogSearchService(ToolRegistryService toolRegistryService,
                                SkillRegistryService skillRegistryService,
                                ToolEmbeddingIndexService toolEmbeddingIndexService,
                                EmbeddingProviderRouter embeddingProviderRouter) {
        this.toolRegistryService = toolRegistryService;
        this.skillRegistryService = skillRegistryService;
        this.toolEmbeddingIndexService = toolEmbeddingIndexService;
        this.embeddingProviderRouter = embeddingProviderRouter;
    }

    public List<Map<String, Object>> searchTools(String query,
                                                 int topK,
                                                 boolean includeMcp,
                                                 boolean includeFramework) {
        String q = query == null ? "" : query.trim();
        int k = Math.max(1, Math.min(topK, 50));
        List<AgentTool> candidates = toolRegistryService.getEnabledTools().stream()
                .filter(t -> t != null && t.getId() != null && t.getName() != null)
                .filter(t -> includeMcp || !isMcpTool(t))
                .filter(t -> includeFramework || !isFrameworkTool(t))
                .toList();
        if (candidates.isEmpty()) return List.of();

        Map<String, AgentTool> byId = new LinkedHashMap<>();
        for (AgentTool tool : candidates) {
            byId.put(tool.getId(), tool);
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        ModelRef embeddingRef = resolveDefaultEmbeddingRef();
        if (embeddingRef != null && toolEmbeddingIndexService != null && q != null && !q.isBlank()) {
            List<ToolEmbeddingIndexService.ScoredTool> semantic = toolEmbeddingIndexService.searchTopK(
                    q,
                    Math.max(k * 2, 20),
                    Set.of(),
                    Map.of(),
                    embeddingRef);
            for (ToolEmbeddingIndexService.ScoredTool hit : semantic) {
                AgentTool tool = byId.get(hit.toolId());
                if (tool == null) continue;
                scores.put(tool.getId(), hit.score());
            }
        }

        for (AgentTool tool : candidates) {
            double lexical = lexicalScore(q, toolText(tool));
            if (lexical <= 0 && q != null && !q.isBlank()) continue;
            scores.merge(tool.getId(), lexical * 0.25, Double::sum);
        }

        List<Map.Entry<String, Double>> ordered = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k)
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ordered) {
            AgentTool tool = byId.get(entry.getKey());
            if (tool == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tool.getId());
            row.put("name", tool.getName());
            row.put("description", tool.getDescription());
            row.put("sourceType", tool.getSourceType());
            row.put("executionKind", tool.getExecutionKind());
            row.put("host", tool.getHost());
            row.put("mcp", isMcpTool(tool));
            row.put("score", round(entry.getValue()));
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> searchSkills(String query, int topK) {
        String q = query == null ? "" : query.trim();
        int k = Math.max(1, Math.min(topK, 50));
        List<SkillSnapshot> skills = skillRegistryService.getEnabledSkills();
        if (skills.isEmpty()) return List.of();

        Map<String, Double> scores = new LinkedHashMap<>();
        ModelRef embeddingRef = resolveDefaultEmbeddingRef();
        EmbeddingModel embeddingModel = resolveEmbeddingModel(embeddingRef);
        float[] queryVec = null;
        if (embeddingModel != null && q != null && !q.isBlank()) {
            try {
                queryVec = embeddingModel.embed(q);
            } catch (Exception ignored) {
                queryVec = null;
            }
        }

        for (SkillSnapshot snapshot : skills) {
            if (snapshot == null || snapshot.skill() == null || snapshot.skill().getName() == null) continue;
            String skillName = snapshot.skill().getName();
            String text = skillText(snapshot);
            double lexical = lexicalScore(q, text);
            double score = lexical * 0.25;
            if (queryVec != null) {
                float[] skillVec = resolveSkillEmbedding(snapshot, text, embeddingRef, embeddingModel);
                if (skillVec != null && skillVec.length > 0) {
                    score += cosine(queryVec, skillVec);
                }
            }
            if ((q == null || q.isBlank()) || score > 0) {
                scores.put(skillName, score);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k)
                .forEach(entry -> {
                    SkillSnapshot snapshot = skillRegistryService.getEnabledSkillByName(entry.getKey());
                    if (snapshot == null || snapshot.skill() == null) return;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", snapshot.skill().getId());
                    row.put("name", snapshot.skill().getName());
                    row.put("description", snapshot.skill().getDescription());
                    row.put("fileCount", snapshot.files() == null ? 0 : snapshot.files().size());
                    row.put("score", round(entry.getValue()));
                    out.add(row);
                });
        return out;
    }

    private boolean isMcpTool(AgentTool tool) {
        if (tool == null) return false;
        String name = tool.getName() == null ? "" : tool.getName().toLowerCase(Locale.ROOT);
        String source = tool.getSourceType() == null ? "" : tool.getSourceType().toLowerCase(Locale.ROOT);
        return name.startsWith("mcp_") || source.contains("mcp");
    }

    private boolean isFrameworkTool(AgentTool tool) {
        if (tool == null) return false;
        String source = tool.getSourceType() == null ? "" : tool.getSourceType().toLowerCase(Locale.ROOT);
        return "framework_default".equals(source);
    }

    private String toolText(AgentTool tool) {
        StringBuilder sb = new StringBuilder();
        if (tool.getName() != null) sb.append(tool.getName()).append(' ');
        if (tool.getDescription() != null) sb.append(tool.getDescription()).append(' ');
        if (tool.getExecutionKind() != null) sb.append(tool.getExecutionKind()).append(' ');
        if (tool.getSourceType() != null) sb.append(tool.getSourceType()).append(' ');
        if (tool.getEndpoint() != null) sb.append(tool.getEndpoint()).append(' ');
        if (tool.getHost() != null) sb.append(tool.getHost());
        return sb.toString();
    }

    private String skillText(SkillSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        if (snapshot.skill().getName() != null) sb.append(snapshot.skill().getName()).append(' ');
        if (snapshot.skill().getDescription() != null) sb.append(snapshot.skill().getDescription()).append(' ');
        if (snapshot.files() != null && snapshot.files().containsKey("SKILL.md")) {
            String md = snapshot.files().get("SKILL.md");
            if (md != null && !md.isBlank()) {
                sb.append(md, 0, Math.min(md.length(), 1200));
            }
        }
        return sb.toString();
    }

    private float[] resolveSkillEmbedding(SkillSnapshot snapshot,
                                          String text,
                                          ModelRef embeddingRef,
                                          EmbeddingModel embeddingModel) {
        if (snapshot == null || snapshot.skill() == null || embeddingModel == null || embeddingRef == null) return null;
        String key = embeddingRef.providerID() + "/" + embeddingRef.modelID()
                + ":" + snapshot.skill().getName() + ":" + sha256(text);
        float[] cached = skillEmbeddingCache.get(key);
        if (cached != null) return cached;
        try {
            float[] vec = embeddingModel.embed(text);
            if (vec != null && vec.length > 0) {
                skillEmbeddingCache.put(key, vec);
                return vec;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ModelRef resolveDefaultEmbeddingRef() {
        try {
            ModelConfig cfg = embeddingProviderRouter.findDefault().orElse(null);
            if (cfg == null || cfg.getProviderId() == null || cfg.getModelId() == null) return null;
            return new ModelRef(cfg.getProviderId(), cfg.getModelId());
        } catch (Exception ignored) {
            return null;
        }
    }

    private EmbeddingModel resolveEmbeddingModel(ModelRef ref) {
        if (ref == null) return null;
        try {
            return embeddingProviderRouter.resolve(ref);
        } catch (Exception ignored) {
            return null;
        }
    }

    private double lexicalScore(String query, String corpus) {
        if (query == null || query.isBlank() || corpus == null || corpus.isBlank()) return 0.0;
        Set<String> qTokens = tokenize(query);
        if (qTokens.isEmpty()) return 0.0;
        Set<String> cTokens = tokenize(corpus);
        if (cTokens.isEmpty()) return 0.0;
        int matches = 0;
        for (String token : qTokens) {
            if (cTokens.contains(token)) matches++;
        }
        return matches / (double) qTokens.size();
    }

    private Set<String> tokenize(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] parts = Pattern.compile("[^a-z0-9]+").split(normalized);
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part != null && part.length() > 1) out.add(part);
        }
        return out;
    }

    private double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0.0;
        double dot = 0.0;
        double an = 0.0;
        double bn = 0.0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            an += a[i] * a[i];
            bn += b[i] * b[i];
        }
        if (an <= 0 || bn <= 0) return 0.0;
        return dot / (Math.sqrt(an) * Math.sqrt(bn));
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

