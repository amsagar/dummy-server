package com.pods.agent.repository;

import com.pods.agent.domain.ModelConfig;
import com.pods.agent.repository.mapper.ModelConfigRowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Slf4j
public class ModelRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;
    private final ModelConfigRowMapper rowMapper = new ModelConfigRowMapper();

    public ModelRepository(JdbcTemplate jdbc,
                           NamedParameterJdbcTemplate namedJdbc,
                           SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public List<ModelConfig> findAll() {
        return jdbc.query(sql.getQuery("MODEL.FIND_ALL"), rowMapper);
    }

    public List<ModelConfig> findEnabled() {
        return jdbc.query(sql.getQuery("MODEL.FIND_ENABLED"), rowMapper);
    }

    public Optional<ModelConfig> findById(String providerID, String modelID) {
        List<ModelConfig> results = jdbc.query(
                sql.getQuery("MODEL.FIND_BY_ID"), rowMapper, providerID, modelID);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns the raw encrypted key + base URL for a model.
     * Only called internally by ModelProviderRouter — never exposed over HTTP.
     */
    public Optional<ModelCredentials> getCredentials(String providerID, String modelID) {
        List<ModelCredentials> results = jdbc.query(
                sql.getQuery("MODEL.GET_KEY"),
                (rs, n) -> new ModelCredentials(
                        rs.getString("encrypted_key"),
                        rs.getString("base_url")
                ),
                providerID, modelID
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void setEnabled(String providerID, String modelID, boolean enabled) {
        var params = new MapSqlParameterSource()
                .addValue("providerID", providerID)
                .addValue("modelId", modelID)
                .addValue("enabled", enabled);
        namedJdbc.update(sql.getQuery("MODEL.SET_ENABLED"), params);
        log.info("[ModelRepository] {}/{} enabled={}", providerID, modelID, enabled);
    }

    /**
     * Upsert a model row.
     * @param encryptedKey AES-256-GCM encrypted API key, or null to keep existing
     */
    public void upsert(ModelConfig model, String encryptedKey) {
        String kind = model.getModelKind() == null || model.getModelKind().isBlank() ? "chat" : model.getModelKind();
        var params = new MapSqlParameterSource()
                .addValue("providerID", model.getProviderId())
                .addValue("modelId", model.getModelId())
                .addValue("displayName", model.getDisplayName())
                .addValue("enabled", model.isEnabled())
                .addValue("encryptedKey", encryptedKey)
                .addValue("baseUrl", model.getBaseUrl())
                .addValue("modelKind", kind)
                .addValue("isDefault", model.isDefaultModel())
                .addValue("embeddingDimensions", model.getEmbeddingDimensions());
        namedJdbc.update(sql.getQuery("MODEL.UPSERT"), params);
    }

    public List<ModelConfig> findByKind(String kind) {
        return jdbc.query(sql.getQuery("MODEL.FIND_BY_KIND"), rowMapper, kind);
    }

    public Optional<ModelConfig> findEmbeddingDefault() {
        List<ModelConfig> results = jdbc.query(sql.getQuery("MODEL.FIND_EMBEDDING_DEFAULT"), rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void setDefault(String providerID, String modelID, String kind) {
        var clearParams = new MapSqlParameterSource().addValue("modelKind", kind);
        namedJdbc.update(sql.getQuery("MODEL.CLEAR_DEFAULT_FOR_KIND"), clearParams);
        var setParams = new MapSqlParameterSource()
                .addValue("providerID", providerID)
                .addValue("modelId", modelID)
                .addValue("modelKind", kind);
        namedJdbc.update(sql.getQuery("MODEL.SET_DEFAULT"), setParams);
        log.info("[ModelRepository] Default {} model = {}/{}", kind, providerID, modelID);
    }

    /** Tuple holding raw credentials (never leave the service layer). */
    public record ModelCredentials(String encryptedKey, String baseUrl) {
        public boolean hasKey() { return encryptedKey != null && !encryptedKey.isBlank(); }
    }
}
