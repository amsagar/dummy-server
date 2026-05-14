package com.pods.agent.ruledomain.repository;

import com.pods.agent.ruledomain.model.RuleDomain;
import com.pgvector.PGvector;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RuleDomainRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RuleDomainRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RuleDomain> ROW = (rs, i) -> RuleDomain.builder()
            .id(rs.getString("id"))
            .skillId(rs.getString("skill_id"))
            .skillName(rs.getString("skill_name"))
            .intentLabel(rs.getString("intent_label"))
            .sourceHash(rs.getString("source_hash"))
            .toolSignature(rs.getString("tool_signature"))
            .bpmnXml(rs.getString("bpmn_xml"))
            .flowableProcKey(rs.getString("flowable_proc_key"))
            .status(rs.getString("status"))
            .version(rs.getInt("version"))
            .compileAttempts(rs.getInt("compile_attempts"))
            .lastError(rs.getString("last_error"))
            .createdAt(rs.getLong("created_at"))
            .updatedAt(rs.getLong("updated_at"))
            .build();

    public RuleDomain save(RuleDomain d, float[] embedding) {
        if (d.getId() == null) d.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        if (d.getCreatedAt() == 0) d.setCreatedAt(now);
        d.setUpdatedAt(now);

        var params = new MapSqlParameterSource()
                .addValue("id", d.getId())
                .addValue("skill_id", d.getSkillId())
                .addValue("skill_name", d.getSkillName())
                .addValue("intent_label", d.getIntentLabel())
                .addValue("source_hash", d.getSourceHash())
                .addValue("tool_signature", d.getToolSignature())
                .addValue("bpmn_xml", d.getBpmnXml())
                .addValue("flowable_proc_key", d.getFlowableProcKey())
                .addValue("status", d.getStatus())
                .addValue("version", d.getVersion() == 0 ? 1 : d.getVersion())
                .addValue("compile_attempts", d.getCompileAttempts() == 0 ? 1 : d.getCompileAttempts())
                .addValue("last_error", d.getLastError())
                .addValue("created_at", d.getCreatedAt())
                .addValue("updated_at", d.getUpdatedAt())
                .addValue("embedding", embedding == null ? null : new PGvector(embedding));

        jdbc.update("""
                INSERT INTO agent.rule_domains
                  (id, skill_id, skill_name, intent_label, source_hash, tool_signature,
                   bpmn_xml, flowable_proc_key, status, version, compile_attempts,
                   last_error, created_at, updated_at, intent_embedding)
                VALUES
                  (:id, :skill_id, :skill_name, :intent_label, :source_hash, :tool_signature,
                   :bpmn_xml, :flowable_proc_key, :status, :version, :compile_attempts,
                   :last_error, :created_at, :updated_at, :embedding)
                ON CONFLICT (skill_id, intent_label, version) DO UPDATE SET
                  bpmn_xml = EXCLUDED.bpmn_xml,
                  flowable_proc_key = EXCLUDED.flowable_proc_key,
                  source_hash = EXCLUDED.source_hash,
                  tool_signature = EXCLUDED.tool_signature,
                  status = EXCLUDED.status,
                  compile_attempts = EXCLUDED.compile_attempts,
                  last_error = EXCLUDED.last_error,
                  updated_at = EXCLUDED.updated_at,
                  intent_embedding = EXCLUDED.intent_embedding
                """, params);
        return d;
    }

    public Optional<RuleDomain> findById(String id) {
        var rows = jdbc.query("SELECT * FROM agent.rule_domains WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW);
        return rows.stream().findFirst();
    }

    public List<RuleDomain> listAll() {
        return jdbc.query(
                "SELECT * FROM agent.rule_domains ORDER BY updated_at DESC",
                new MapSqlParameterSource(),
                ROW);
    }

    public List<RuleDomain> listBySkill(String skillId) {
        return jdbc.query(
                "SELECT * FROM agent.rule_domains WHERE skill_id = :sid ORDER BY updated_at DESC",
                new MapSqlParameterSource("sid", skillId),
                ROW);
    }

    public List<RuleDomain> listActiveForSkill(String skillId) {
        return jdbc.query(
                "SELECT * FROM agent.rule_domains WHERE skill_id = :sid AND status = 'ACTIVE' ORDER BY updated_at DESC",
                new MapSqlParameterSource("sid", skillId),
                ROW);
    }

    /**
     * Find the closest ACTIVE domain for this skill above the threshold.
     * Returns the row and the cosine similarity (1 - distance).
     */
    public Optional<Match> findBestMatch(String skillId, float[] embedding, double threshold) {
        if (embedding == null) return Optional.empty();
        var params = new MapSqlParameterSource()
                .addValue("sid", skillId)
                .addValue("v", new PGvector(embedding));

        var rows = jdbc.query("""
                SELECT *, 1.0 - (intent_embedding <=> :v) AS similarity
                FROM agent.rule_domains
                WHERE skill_id = :sid
                  AND status = 'ACTIVE'
                  AND intent_embedding IS NOT NULL
                ORDER BY intent_embedding <=> :v
                LIMIT 1
                """, params, (rs, i) -> {
            RuleDomain d = ROW.mapRow(rs, i);
            double sim = rs.getDouble("similarity");
            return new Match(d, sim);
        });
        return rows.stream().filter(m -> m.similarity() >= threshold).findFirst();
    }

    public void updateStatus(String id, String status, String lastError) {
        jdbc.update("""
                UPDATE agent.rule_domains
                SET status = :status, last_error = :err, updated_at = :now
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("err", lastError)
                .addValue("now", System.currentTimeMillis()));
    }

    public int latestVersion(String skillId, String intentLabel) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0)
                FROM agent.rule_domains
                WHERE skill_id = :sid AND intent_label = :il
                """, new MapSqlParameterSource()
                .addValue("sid", skillId)
                .addValue("il", intentLabel),
                Integer.class);
        return v == null ? 0 : v;
    }

    public record Match(RuleDomain domain, double similarity) {}
}
