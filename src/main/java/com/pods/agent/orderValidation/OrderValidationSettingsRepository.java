package com.pods.agent.orderValidation;

import com.pods.agent.repository.SqlQueryLoader;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Singleton-row store for the order-validation UI's global settings: which
 * chat model to use, which response mode the AI assistant runs in, and
 * which workflow id the assistant targets when starting validations.
 *
 * <p>One row, primary key {@code 'singleton'}. Lazily created on first
 * write — readers get {@link Optional#empty()} until that happens.
 */
@Repository
public class OrderValidationSettingsRepository {

    public record Settings(String chatModelRef, String responseMode, String workflowId, long updatedAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public OrderValidationSettingsRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public Optional<Settings> find() {
        return jdbc.query(sql.getQuery("ORDER_VALIDATION_SETTINGS.SELECT"), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Settings(
                    rs.getString("chat_model_ref"),
                    rs.getString("response_mode"),
                    rs.getString("workflow_id"),
                    rs.getLong("updated_at")));
        });
    }

    public Settings upsert(String chatModelRef, String responseMode, String workflowId) {
        long now = Instant.now().toEpochMilli();
        String mode = responseMode == null || responseMode.isBlank() ? "basic" : responseMode;
        jdbc.update(sql.getQuery("ORDER_VALIDATION_SETTINGS.UPSERT"),
                new MapSqlParameterSource()
                        .addValue("chatModelRef", chatModelRef)
                        .addValue("responseMode", mode)
                        .addValue("workflowId", workflowId)
                        .addValue("updatedAt", now));
        return new Settings(chatModelRef, mode, workflowId, now);
    }
}
