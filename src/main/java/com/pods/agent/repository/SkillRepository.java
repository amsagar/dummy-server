package com.pods.agent.repository;

import com.pods.agent.domain.Skill;
import com.pods.agent.domain.SkillFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SkillRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public SkillRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public Skill save(Skill skill) {
        if (skill.getId() == null) skill.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("SKILL.INSERT"), new MapSqlParameterSource()
                .addValue("id", skill.getId())
                .addValue("name", skill.getName())
                .addValue("description", skill.getDescription())
                .addValue("enabled", skill.isEnabled())
                .addValue("createdAt", skill.getCreatedAt())
                .addValue("updatedAt", skill.getUpdatedAt()));
        return skill;
    }

    public List<Skill> findAll() {
        return jdbc.query(sql.getQuery("SKILL.FIND_ALL"), (rs, n) -> Skill.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .derivedManifestJson(safeColumn(rs, "derived_manifest_json"))
                .derivedManifestSourceHash(safeColumn(rs, "derived_manifest_source_hash"))
                .derivedManifestAt(safeLong(rs, "derived_manifest_at"))
                .build());
    }

    public Optional<Skill> findById(String id) {
        var list = jdbc.query(sql.getQuery("SKILL.FIND_BY_ID"), (rs, n) -> Skill.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .derivedManifestJson(safeColumn(rs, "derived_manifest_json"))
                .derivedManifestSourceHash(safeColumn(rs, "derived_manifest_source_hash"))
                .derivedManifestAt(safeLong(rs, "derived_manifest_at"))
                .build(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** Write the system-derived rule manifest produced by SkillManifestDeriver. */
    public void updateDerivedManifest(String skillId, String manifestJson, String sourceHash) {
        namedJdbc.update("""
                UPDATE agent.skills
                   SET derived_manifest_json        = :json,
                       derived_manifest_source_hash = :hash,
                       derived_manifest_at          = :ts,
                       updated_at                   = :ts
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", skillId)
                .addValue("json", manifestJson)
                .addValue("hash", sourceHash)
                .addValue("ts", System.currentTimeMillis()));
    }

    /** Clear the derived manifest (e.g. after the skill markdown is edited).
     *  The next successful turn re-derives it from a fresh trace. */
    public void clearDerivedManifest(String skillId) {
        namedJdbc.update("""
                UPDATE agent.skills
                   SET derived_manifest_json        = NULL,
                       derived_manifest_source_hash = NULL,
                       derived_manifest_at          = NULL,
                       updated_at                   = :ts
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", skillId)
                .addValue("ts", System.currentTimeMillis()));
    }

    private static String safeColumn(java.sql.ResultSet rs, String column) {
        try { return rs.getString(column); }
        catch (java.sql.SQLException ex) { return null; }
    }

    private static long safeLong(java.sql.ResultSet rs, String column) {
        try {
            long v = rs.getLong(column);
            return rs.wasNull() ? 0L : v;
        } catch (java.sql.SQLException ex) {
            return 0L;
        }
    }

    public void update(Skill skill) {
        skill.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("SKILL.UPDATE"), new MapSqlParameterSource()
                .addValue("id", skill.getId())
                .addValue("name", skill.getName())
                .addValue("description", skill.getDescription())
                .addValue("enabled", skill.isEnabled())
                .addValue("updatedAt", skill.getUpdatedAt()));
    }

    public void setEnabled(String id, boolean enabled) {
        namedJdbc.update(sql.getQuery("SKILL.SET_ENABLED"), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("enabled", enabled)
                .addValue("updatedAt", System.currentTimeMillis()));
    }

    public void delete(String id) {
        jdbc.update(sql.getQuery("SKILL.DELETE"), id);
    }

    public SkillFile saveFile(SkillFile file) {
        if (file.getId() == null) file.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        namedJdbc.update(sql.getQuery("SKILL_FILE.INSERT"), new MapSqlParameterSource()
                .addValue("id", file.getId())
                .addValue("skillId", file.getSkillId())
                .addValue("filePath", file.getFilePath())
                .addValue("blobPath", file.getBlobPath())
                .addValue("mimeType", file.getMimeType())
                .addValue("contentSha256", file.getContentSha256())
                .addValue("sizeBytes", file.getSizeBytes())
                .addValue("createdAt", file.getCreatedAt())
                .addValue("updatedAt", file.getUpdatedAt()));
        return file;
    }

    public List<SkillFile> findFilesBySkill(String skillId) {
        return jdbc.query(sql.getQuery("SKILL_FILE.FIND_BY_SKILL"), (rs, n) -> SkillFile.builder()
                .id(rs.getString("id"))
                .skillId(rs.getString("skill_id"))
                .filePath(rs.getString("file_path"))
                .blobPath(rs.getString("blob_path"))
                .mimeType(rs.getString("mime_type"))
                .contentSha256(rs.getString("content_sha256"))
                .sizeBytes(rs.getLong("size_bytes"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), skillId);
    }

    public Optional<SkillFile> findFileBySkillAndPath(String skillId, String filePath) {
        var list = jdbc.query(sql.getQuery("SKILL_FILE.FIND_BY_SKILL_AND_PATH"), (rs, n) -> SkillFile.builder()
                .id(rs.getString("id"))
                .skillId(rs.getString("skill_id"))
                .filePath(rs.getString("file_path"))
                .blobPath(rs.getString("blob_path"))
                .mimeType(rs.getString("mime_type"))
                .contentSha256(rs.getString("content_sha256"))
                .sizeBytes(rs.getLong("size_bytes"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), skillId, filePath);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<SkillFile> findFileById(String id) {
        var list = jdbc.query(sql.getQuery("SKILL_FILE.FIND_BY_ID"), (rs, n) -> SkillFile.builder()
                .id(rs.getString("id"))
                .skillId(rs.getString("skill_id"))
                .filePath(rs.getString("file_path"))
                .blobPath(rs.getString("blob_path"))
                .mimeType(rs.getString("mime_type"))
                .contentSha256(rs.getString("content_sha256"))
                .sizeBytes(rs.getLong("size_bytes"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateFile(SkillFile file) {
        file.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("SKILL_FILE.UPDATE"), new MapSqlParameterSource()
                .addValue("id", file.getId())
                .addValue("filePath", file.getFilePath())
                .addValue("blobPath", file.getBlobPath())
                .addValue("mimeType", file.getMimeType())
                .addValue("contentSha256", file.getContentSha256())
                .addValue("sizeBytes", file.getSizeBytes())
                .addValue("updatedAt", file.getUpdatedAt()));
    }

    public void deleteFile(String id) {
        jdbc.update(sql.getQuery("SKILL_FILE.DELETE"), id);
    }
}
