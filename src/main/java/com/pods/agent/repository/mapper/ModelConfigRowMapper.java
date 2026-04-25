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
        return ModelConfig.builder()
                .providerId(rs.getString("provider_id"))
                .modelId(rs.getString("model_id"))
                .displayName(rs.getString("display_name"))
                .enabled(rs.getBoolean("enabled"))
                .hasKey(rs.getString("encrypted_key") != null)
                .baseUrl(rs.getString("base_url"))
                .source("db")
                .build();
    }
}
