package com.pods.agent.service.tool;

import com.pods.agent.config.EmbeddingProviderRouter;
import com.pods.agent.config.RuntimeTuningProperties;
import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.ModelConfig;
import com.pods.agent.domain.ModelRef;
import com.pods.agent.repository.SqlQueryLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class ToolEmbeddingIndexService {

    public static final int MAX_DIM = 3072;
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;
    private final EmbeddingProviderRouter embeddingProviderRouter;
    private final RuntimeTuningProperties runtimeTuningProperties;
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tool-embed-sync");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean warmedUp = false;

    public ToolEmbeddingIndexService(JdbcTemplate jdbc,
                                     NamedParameterJdbcTemplate namedJdbc,
                                     SqlQueryLoader sql,
                                     EmbeddingProviderRouter embeddingProviderRouter,
                                     RuntimeTuningProperties runtimeTuningProperties) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
        this.embeddingProviderRouter = embeddingProviderRouter;
        this.runtimeTuningProperties = runtimeTuningProperties;
    }

    public record ScoredTool(String toolId, double score) {}

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void warmUp() {
        warmedUp = true;
    }

    public Set<String> allIds() {
        try {
            return new LinkedHashSet<>(jdbc.queryForList(sql.getQuery("TOOL_EMBEDDING.ALL_IDS"), String.class));
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] allIds failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    public void delete(String toolId) {
        if (toolId == null) return;
        namedJdbc.update(sql.getQuery("TOOL_EMBEDDING.DELETE"),
                new MapSqlParameterSource("toolId", toolId));
    }

    public void deleteAll(Collection<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) return;
        for (String id : toolIds) {
            delete(id);
        }
    }

    public void truncate() {
        try {
            jdbc.update(sql.getQuery("TOOL_EMBEDDING.TRUNCATE"));
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] truncate failed: {}", e.getMessage());
        }
    }

    public void syncFromCache(Collection<AgentTool> tools) {
        if (!runtimeTuningProperties.getToolRetrieval().isIndexSyncEnabled()) return;
        Runnable task = () -> doSync(tools);
        if (runtimeTuningProperties.getToolRetrieval().isIndexSyncAsync()) {
            CompletableFuture.runAsync(task, syncExecutor);
        } else {
            task.run();
        }
    }

    private void doSync(Collection<AgentTool> tools) {
        try {
            Set<String> oldIds = allIds();
            Set<String> newIds = new LinkedHashSet<>();
            for (AgentTool t : tools) if (t != null && t.getId() != null) newIds.add(t.getId());
            List<String> toDelete = new ArrayList<>();
            for (String id : oldIds) if (!newIds.contains(id)) toDelete.add(id);
            deleteAll(toDelete);
            // Guard against FK violations from race conditions (cleanup deleting tools
            // while this async sync is in flight).
            Set<String> existingIds = existingToolIds(newIds);
            List<AgentTool> toUpsert = new ArrayList<>();
            for (AgentTool t : tools) {
                if (t != null && t.getId() != null && existingIds.contains(t.getId())) toUpsert.add(t);
            }
            int upserted = upsertBatch(toUpsert);
            log.info("[ToolEmbeddingIndex] sync: removed={}, upserted={} (of {})", toDelete.size(), upserted, newIds.size());
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] sync failed: {}", e.getMessage());
        }
    }

    private Set<String> existingToolIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptySet();
        try {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", new ArrayList<>(ids));
            List<String> found = namedJdbc.queryForList(
                    "SELECT id FROM agent.agent_tools WHERE id IN (:ids)", params, String.class);
            return new LinkedHashSet<>(found);
        } catch (Exception e) {
            log.debug("[ToolEmbeddingIndex] existingToolIds check failed: {}", e.getMessage());
            return ids;
        }
    }

    public int upsertBatch(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        Optional<ModelConfig> defaultModel = embeddingProviderRouter.findDefault();
        if (defaultModel.isEmpty()) {
            log.info("[ToolEmbeddingIndex] No embedding model configured — tool retrieval disabled. Register one via the UI to enable smart routing.");
            return 0;
        }
        ModelConfig md = defaultModel.get();
        ModelRef ref = new ModelRef(md.getProviderId(), md.getModelId());
        EmbeddingModel embeddingModel;
        try {
            embeddingModel = embeddingProviderRouter.resolve(ref);
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] Failed to resolve embedding model: {}", e.getMessage());
            return 0;
        }

        int written = 0;
        List<AgentTool> pending = new ArrayList<>();
        List<String> pendingTexts = new ArrayList<>();
        List<String> pendingHashes = new ArrayList<>();

        for (AgentTool tool : tools) {
            if (tool == null || tool.getId() == null) continue;
            String text = embedText(tool);
            String hash = sha256(text);
            ExistingRow existing = findExisting(tool.getId());
            if (existing != null
                    && existing.contentHash.equals(hash)
                    && existing.modelProvider.equalsIgnoreCase(ref.providerID())
                    && existing.modelId.equalsIgnoreCase(ref.modelID())) {
                continue;
            }
            pending.add(tool);
            pendingTexts.add(text);
            pendingHashes.add(hash);
            if (pending.size() >= BATCH) {
                written += flush(pending, pendingTexts, pendingHashes, embeddingModel, ref);
                pending.clear();
                pendingTexts.clear();
                pendingHashes.clear();
            }
        }
        if (!pending.isEmpty()) {
            written += flush(pending, pendingTexts, pendingHashes, embeddingModel, ref);
        }
        return written;
    }

    public void upsert(AgentTool tool) {
        if (tool == null) return;
        upsertBatch(List.of(tool));
    }

    private int flush(List<AgentTool> tools, List<String> texts, List<String> hashes, EmbeddingModel embeddingModel, ModelRef ref) {
        int written = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < tools.size(); i++) {
            try {
                float[] vec = embeddingModel.embed(texts.get(i));
                int actualDim = vec == null ? 0 : vec.length;
                if (actualDim == 0) continue;
                float[] padded = padOrTruncate(vec, MAX_DIM);
                MapSqlParameterSource p = new MapSqlParameterSource()
                        .addValue("toolId", tools.get(i).getId())
                        .addValue("modelProvider", ref.providerID())
                        .addValue("modelId", ref.modelID())
                        .addValue("dimensions", actualDim)
                        .addValue("contentHash", hashes.get(i))
                        .addValue("embedding", toVectorLiteral(padded))
                        .addValue("updatedAt", now);
                namedJdbc.update(sql.getQuery("TOOL_EMBEDDING.UPSERT"), p);
                written++;
            } catch (Exception e) {
                log.warn("[ToolEmbeddingIndex] embed/upsert failed for tool {}: {}", tools.get(i).getId(), e.getMessage());
            }
        }
        return written;
    }

    private record ExistingRow(String contentHash, String modelProvider, String modelId, int dimensions) {}

    private ExistingRow findExisting(String toolId) {
        try {
            List<ExistingRow> rows = jdbc.query(sql.getQuery("TOOL_EMBEDDING.FIND_HASH"),
                    (rs, n) -> new ExistingRow(
                            rs.getString("content_hash"),
                            rs.getString("model_provider"),
                            rs.getString("model_id"),
                            rs.getInt("dimensions")
                    ),
                    toolId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public List<ScoredTool> searchTopK(String userText,
                                        int k,
                                        Set<String> mustIncludeIds,
                                        Map<String, Double> memorySignal,
                                        ModelRef embeddingModelRef) {
        return searchTopK(userText, k, mustIncludeIds, memorySignal, Set.of(), embeddingModelRef);
    }

    public List<ScoredTool> searchTopK(String userText,
                                        int k,
                                        Set<String> mustIncludeIds,
                                        Map<String, Double> memorySignal,
                                        Set<String> hostBoostedToolIds,
                                        ModelRef embeddingModelRef) {
        Set<String> mustInclude = mustIncludeIds == null ? Set.of() : mustIncludeIds;
        Set<String> hostBoosted = hostBoostedToolIds == null ? Set.of() : hostBoostedToolIds;
        if (embeddingModelRef == null) return Collections.emptyList();
        if (userText == null) userText = "";
        EmbeddingModel embeddingModel;
        try {
            embeddingModel = embeddingProviderRouter.resolve(embeddingModelRef);
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] resolve embedding model for search failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        float[] queryVec;
        int actualDim;
        try {
            queryVec = embeddingModel.embed(userText);
            actualDim = queryVec == null ? 0 : queryVec.length;
            if (actualDim == 0) return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] embed query failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        float[] padded = padOrTruncate(queryVec, MAX_DIM);
        double scoreFloor = runtimeTuningProperties.getToolRetrieval().getScoreFloor();
        double memWeight = runtimeTuningProperties.getToolRetrieval().getMemoryBoostWeight();
        int searchK = Math.max(k * 2, k);
        List<ScoredTool> raw;
        try {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("queryEmbedding", toVectorLiteral(padded))
                    .addValue("modelProvider", embeddingModelRef.providerID())
                    .addValue("modelId", embeddingModelRef.modelID())
                    .addValue("dimensions", actualDim)
                    .addValue("topK", searchK);
            raw = namedJdbc.query(sql.getQuery("TOOL_EMBEDDING.SEARCH_TOPK"), p,
                    (rs, n) -> new ScoredTool(rs.getString("tool_id"), rs.getDouble("similarity")));
        } catch (Exception e) {
            log.warn("[ToolEmbeddingIndex] search failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        double hostAffinityBoost = 0.25;
        Map<String, Double> reranked = new LinkedHashMap<>();
        for (ScoredTool s : raw) {
            double base = s.score();
            double mem = memorySignal == null ? 0.0 : memorySignal.getOrDefault(s.toolId(), 0.0);
            double boost = memWeight * Math.tanh(mem);
            double affinity = hostBoosted.contains(s.toolId()) ? hostAffinityBoost : 0.0;
            double finalScore = base + boost + affinity;
            if (mustInclude.contains(s.toolId()) || finalScore >= scoreFloor) {
                reranked.merge(s.toolId(), finalScore, Math::max);
            }
        }
        return reranked.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, k))
                .map(e -> new ScoredTool(e.getKey(), e.getValue()))
                .toList();
    }

    public void reindexAll(Collection<AgentTool> tools) {
        truncate();
        upsertBatch(new ArrayList<>(tools));
    }

    public boolean isWarmedUp() { return warmedUp; }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String embedText(AgentTool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(safe(tool.getName())).append("\n");
        sb.append("description: ").append(safe(tool.getDescription())).append("\n");
        sb.append("category: ").append(safe(tool.getDomainId())).append(" (").append(safe(tool.getSourceType())).append(")\n");
        sb.append("operation: ").append(safe(tool.getMethod())).append(" ").append(safe(tool.getEndpoint())).append("\n");
        sb.append("parameters: ").append(summarizeSchema(tool.getRequestSchema()));
        if (tool.getResponseSchema() != null && !tool.getResponseSchema().isBlank()) {
            sb.append("\nreturns: ").append(summarizeSchema(tool.getResponseSchema()));
        }
        return sb.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String summarizeSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return "(none)";
        String s = schemaJson.replaceAll("\\s+", " ");
        return s.length() > 600 ? s.substring(0, 600) : s;
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text == null ? 0 : text.hashCode());
        }
    }

    static float[] padOrTruncate(float[] in, int target) {
        if (in == null) return new float[target];
        if (in.length == target) return in;
        float[] out = new float[target];
        int copy = Math.min(in.length, target);
        System.arraycopy(in, 0, out, 0, copy);
        return out;
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
