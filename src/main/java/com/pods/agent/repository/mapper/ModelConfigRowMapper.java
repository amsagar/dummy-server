package com.pods.agent.repository.mapper;

import com.pods.agent.domain.ModelConfig;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps agent.supported_models rows to ModelConfig.
 * Note: encrypted_key is intentionally NOT mapped here — it is fetched
 * separately by ModelRepository.getEncryptedKey() and used internally only.
 */
public class ModelConfigRowMapper implements RowMapper<ModelConfig> {

    @Override
    public ModelConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
        String modelKind = "chat";
        boolean defaultModel = false;
        Integer embeddingDimensions = null;
        try { modelKind = rs.getString("model_kind"); if (modelKind == null) modelKind = "chat"; } catch (SQLException ignored) {}
        try { defaultModel = rs.getBoolean("is_default"); } catch (SQLException ignored) {}
        try {
            int v = rs.getInt("embedding_dimensions");
            if (!rs.wasNull()) embeddingDimensions = v;
        } catch (SQLException ignored) {}
        return ModelConfig.builder()
                .providerId(rs.getString("provider_id"))
                .modelId(rs.getString("model_id"))
                .displayName(rs.getString("display_name"))
                .enabled(rs.getBoolean("enabled"))
                .hasKey(rs.getString("encrypted_key") != null)
                .baseUrl(rs.getString("base_url"))
                .modelKind(modelKind)
                .defaultModel(defaultModel)
                .embeddingDimensions(embeddingDimensions)
                .source("db")
                .build();
    }
}
