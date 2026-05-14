package com.pods.agent.repository;

import com.pods.agent.domain.HitlInteraction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HitlInteractionRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public HitlInteractionRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public HitlInteraction save(HitlInteraction interaction) {
        if (interaction.getId() == null) interaction.setId(UUID.randomUUID().toString());
        if (interaction.getCreatedAt() == 0) interaction.setCreatedAt(System.currentTimeMillis());
        if (interaction.getStatus() == null || interaction.getStatus().isBlank()) interaction.setStatus("pending");
        namedJdbc.update(sql.getQuery("HITL.INSERT"), new MapSqlParameterSource()
                .addValue("id", interaction.getId())
                .addValue("sessionId", interaction.getSessionId())
                .addValue("turnId", interaction.getTurnId())
                .addValue("type", interaction.getType())
                .addValue("prompt", interaction.getPrompt())
                .addValue("status", interaction.getStatus())
                .addValue("responseText", interaction.getResponseText())
                .addValue("createdAt", interaction.getCreatedAt())
                .addValue("resolvedAt", interaction.getResolvedAt()));
        return interaction;
    }

    public Optional<HitlInteraction> findById(String id) {
        var list = jdbc.query(sql.getQuery("HITL.FIND_BY_ID"), (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<HitlInteraction> findPendingBySession(String sessionId) {
        return jdbc.query(sql.getQuery("HITL.FIND_PENDING_BY_SESSION"), (rs, n) -> map(rs), sessionId);
    }

    public List<HitlInteraction> findBySessionId(String sessionId) {
        return jdbc.query(sql.getQuery("HITL.FIND_BY_SESSION"), (rs, n) -> map(rs), sessionId);
    }

    public void resolve(String id, String status, String responseText) {
        namedJdbc.update(sql.getQuery("HITL.RESOLVE"), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("responseText", responseText)
                .addValue("resolvedAt", System.currentTimeMillis()));
    }

    public int deleteFromTime(String sessionId, long createdAt) {
        return namedJdbc.update(sql.getQuery("HITL.DELETE_FROM_TIME"), new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("createdAt", createdAt));
    }

    public int deleteById(String id) {
        return jdbc.update(sql.getQuery("HITL.DELETE_BY_ID"), id);
    }

    private HitlInteraction map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return HitlInteraction.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .turnId(rs.getString("turn_id"))
                .type(rs.getString("type"))
                .prompt(rs.getString("prompt"))
                .status(rs.getString("status"))
                .responseText(rs.getString("response_text"))
                .createdAt(rs.getLong("created_at"))
                .resolvedAt((Long) rs.getObject("resolved_at"))
                .build();
    }
}
