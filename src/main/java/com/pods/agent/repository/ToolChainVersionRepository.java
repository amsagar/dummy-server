package com.pods.agent.repository;

import com.pods.agent.domain.ToolChainVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ToolChainVersionRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public ToolChainVersionRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public ToolChainVersion save(ToolChainVersion version) {
        if (version.getId() == null) version.setId(UUID.randomUUID().toString());
        if (version.getCreatedAt() == 0) version.setCreatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_VERSION.INSERT"), new MapSqlParameterSource()
                .addValue("id", version.getId())
                .addValue("toolChainId", version.getToolChainId())
                .addValue("version", version.getVersion())
                .addValue("graphJson", version.getGraphJson())
                .addValue("inputSchema", version.getInputSchema())
                .addValue("outputSchema", version.getOutputSchema())
                .addValue("responseMode", version.getResponseMode())
                .addValue("synthesisPrompt", version.getSynthesisPrompt())
                .addValue("intentsJson", version.getIntentsJson())
                .addValue("intentSignature", version.getIntentSignature())
                .addValue("structureSignature", version.getStructureSignature())
                .addValue("ragConfigJson", version.getRagConfigJson())
                .addValue("variablesJson", version.getVariablesJson())
                .addValue("published", version.isPublished())
                .addValue("createdBy", version.getCreatedBy())
                .addValue("createdAt", version.getCreatedAt()));
        return version;
    }

    public Optional<ToolChainVersion> findById(String id) {
        var rows = jdbc.query(sql.getQuery("TOOL_CHAIN_VERSION.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ToolChainVersion> findByChain(String toolChainId) {
        return jdbc.query(sql.getQuery("TOOL_CHAIN_VERSION.FIND_BY_CHAIN"), (rs, n) -> map(rs), toolChainId);
    }

    public Optional<ToolChainVersion> findByChainAndVersion(String toolChainId, int version) {
        var rows = namedJdbc.query(sql.getQuery("TOOL_CHAIN_VERSION.FIND_BY_CHAIN_AND_VERSION"),
                new MapSqlParameterSource()
                        .addValue("toolChainId", toolChainId)
                        .addValue("version", version),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ToolChainVersion> findPublished(String toolChainId) {
        var rows = namedJdbc.query(sql.getQuery("TOOL_CHAIN_VERSION.FIND_PUBLISHED"),
                new MapSqlParameterSource().addValue("toolChainId", toolChainId),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ToolChainVersion> findLatestDraft(String toolChainId) {
        var rows = namedJdbc.query(sql.getQuery("TOOL_CHAIN_VERSION.FIND_LATEST_DRAFT"),
                new MapSqlParameterSource().addValue("toolChainId", toolChainId),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ToolChainVersion> findBySignatures(String intentSignature, String structureSignature) {
        var rows = namedJdbc.query(sql.getQuery("TOOL_CHAIN_VERSION.FIND_BY_SIGNATURES"),
                new MapSqlParameterSource()
                        .addValue("intentSignature", intentSignature)
                        .addValue("structureSignature", structureSignature),
                (rs, n) -> map(rs));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Update an unpublished version row in place. No-op if the row is published —
     * the WHERE clause guards against accidental mutation of immutable releases.
     */
    public void updateDraft(ToolChainVersion version) {
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_VERSION.UPDATE_DRAFT"), new MapSqlParameterSource()
                .addValue("id", version.getId())
                .addValue("graphJson", version.getGraphJson())
                .addValue("inputSchema", version.getInputSchema())
                .addValue("outputSchema", version.getOutputSchema())
                .addValue("responseMode", version.getResponseMode())
                .addValue("synthesisPrompt", version.getSynthesisPrompt())
                .addValue("intentsJson", version.getIntentsJson())
                .addValue("intentSignature", version.getIntentSignature())
                .addValue("structureSignature", version.getStructureSignature())
                .addValue("ragConfigJson", version.getRagConfigJson())
                .addValue("variablesJson", version.getVariablesJson()));
    }

    /**
     * Replace the graph_json column on a single version row in place. Used by the runtime's
     * self-heal path to persist learned JSONata expressions for previously-llm_assisted args.
     * Allowed on published rows: the change is purely additive (it strengthens an expression
     * that was already present as a hint), so no version bump is required.
     */
    public void updateGraphJson(String versionId, String graphJson) {
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_VERSION.UPDATE_GRAPH_JSON"),
                new MapSqlParameterSource()
                        .addValue("id", versionId)
                        .addValue("graphJson", graphJson));
    }

    public void publish(String versionId, String toolChainId) {
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_VERSION.UNPUBLISH_CHAIN"),
                new MapSqlParameterSource().addValue("toolChainId", toolChainId));
        namedJdbc.update(sql.getQuery("TOOL_CHAIN_VERSION.SET_PUBLISHED"),
                new MapSqlParameterSource().addValue("id", versionId));
    }

    private ToolChainVersion map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ToolChainVersion.builder()
                .id(rs.getString("id"))
                .toolChainId(rs.getString("tool_chain_id"))
                .version(rs.getInt("version"))
                .graphJson(rs.getString("graph_json"))
                .inputSchema(rs.getString("input_schema"))
                .outputSchema(rs.getString("output_schema"))
                .responseMode(rs.getString("response_mode"))
                .synthesisPrompt(rs.getString("synthesis_prompt"))
                .intentsJson(rs.getString("intents_json"))
                .intentSignature(rs.getString("intent_signature"))
                .structureSignature(rs.getString("structure_signature"))
                .ragConfigJson(rs.getString("rag_config_json"))
                .variablesJson(rs.getString("variables_json"))
                .published(rs.getBoolean("is_published"))
                .createdBy(rs.getString("created_by"))
                .createdAt(rs.getLong("created_at"))
                .build();
    }
}
