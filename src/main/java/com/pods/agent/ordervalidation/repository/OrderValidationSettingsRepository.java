package com.pods.agent.ordervalidation.repository;

import com.pods.agent.ordervalidation.model.OrderValidationUiSettings;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Singleton config row at id='default'. Allow-list arrays are stored as
 * JSON strings; null means "no restriction", empty array means "deny all".
 */
@Repository
public class OrderValidationSettingsRepository {

    private static final String ID = "default";
    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OrderValidationSettingsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public OrderValidationUiSettings load() {
        var rows = jdbc.query(
                "SELECT * FROM agent.order_validation_settings WHERE id = :id",
                new MapSqlParameterSource("id", ID),
                (rs, i) -> new OrderValidationUiSettings(
                        rs.getString("chat_model_ref"),
                        rs.getString("response_mode_id"),
                        rs.getString("workflow_id"),
                        parseList(rs.getString("allowed_skill_ids")),
                        parseList(rs.getString("allowed_rule_domain_ids")),
                        parseList(rs.getString("allowed_decision_tables"))));
        return rows.stream().findFirst().orElseGet(OrderValidationUiSettings::defaults);
    }

    public OrderValidationUiSettings save(OrderValidationUiSettings s) {
        // Legacy `response_mode` column (basic/detailed enum) is kept one
        // release as a safety net; derive a sensible value when we can't
        // recover one from the new id, so existing readers don't crash.
        String legacyResponseMode = legacyResponseModeFor(s.responseModeId());
        var p = new MapSqlParameterSource()
                .addValue("id", ID)
                .addValue("chat_model_ref", s.chatModelRef())
                .addValue("response_mode_id", s.responseModeId())
                .addValue("response_mode", legacyResponseMode)
                .addValue("workflow_id", s.workflowId())
                .addValue("allowed_skill_ids", writeList(s.allowedSkillIds()))
                .addValue("allowed_rule_domain_ids", writeList(s.allowedRuleDomainIds()))
                .addValue("allowed_decision_tables", writeList(s.allowedDecisionTables()))
                .addValue("updated_at", System.currentTimeMillis());
        jdbc.update("""
                INSERT INTO agent.order_validation_settings
                  (id, chat_model_ref, response_mode, response_mode_id, workflow_id,
                   allowed_skill_ids, allowed_rule_domain_ids, allowed_decision_tables,
                   updated_at)
                VALUES
                  (:id, :chat_model_ref, :response_mode, :response_mode_id, :workflow_id,
                   :allowed_skill_ids, :allowed_rule_domain_ids, :allowed_decision_tables,
                   :updated_at)
                ON CONFLICT (id) DO UPDATE SET
                  chat_model_ref = EXCLUDED.chat_model_ref,
                  response_mode = EXCLUDED.response_mode,
                  response_mode_id = EXCLUDED.response_mode_id,
                  workflow_id = EXCLUDED.workflow_id,
                  allowed_skill_ids = EXCLUDED.allowed_skill_ids,
                  allowed_rule_domain_ids = EXCLUDED.allowed_rule_domain_ids,
                  allowed_decision_tables = EXCLUDED.allowed_decision_tables,
                  updated_at = EXCLUDED.updated_at
                """, p);
        return load();
    }

    private static String legacyResponseModeFor(String responseModeId) {
        if (responseModeId == null) return "basic";
        // The legacy response_mode enum only has two values; map both known
        // detailed-flavor personas to 'detailed' and everything else to 'basic'.
        return ("ov-developers".equalsIgnoreCase(responseModeId)
                || "ov-detailed".equalsIgnoreCase(responseModeId)) ? "detailed" : "basic";
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, LIST_OF_STRING);
        } catch (Exception ex) {
            return null;
        }
    }

    private String writeList(List<String> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception ex) {
            return null;
        }
    }
}
