package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public WorkflowApiKeyRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void insert(WorkflowApiKeyRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_API_KEY.INSERT"),
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("name", row.name())
                        .addValue("keyPrefix", row.keyPrefix())
                        .addValue("keyHash", row.keyHash())
                        .addValue("ownerId", row.ownerId())
                        .addValue("processDefIds", row.processDefIds())
                        .addValue("createdAt", row.createdAt()));
    }

    public Optional<WorkflowApiKeyRow> findById(String id) {
        List<WorkflowApiKeyRow> rs = jdbc.query(sql.getQuery("WORKFLOW_API_KEY.FIND_BY_ID"),
                new MapSqlParameterSource("id", id), MAPPER);
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(0));
    }

    /**
     * Lookup candidate rows by the 12-char prefix at auth time. The caller
     * then verifies the full key hash against each candidate. We expect this
     * to typically return one row — prefix collisions are vanishingly rare
     * across 12 alphanumeric chars — but the hash check is the source of
     * truth.
     */
    public List<WorkflowApiKeyRow> findByPrefix(String prefix) {
        return jdbc.query(sql.getQuery("WORKFLOW_API_KEY.FIND_BY_PREFIX"),
                new MapSqlParameterSource("keyPrefix", prefix), MAPPER);
    }

    public List<WorkflowApiKeyRow> listByOwner(String ownerId) {
        return jdbc.query(sql.getQuery("WORKFLOW_API_KEY.LIST_BY_OWNER"),
                new MapSqlParameterSource("ownerId", ownerId), MAPPER);
    }

    /**
     * Find every API key (across all owners) whose stored scope JSON contains
     * the given def id as a substring. The caller MUST verify the membership
     * by parsing the JSON — this is just an O(n)-avoidance filter to skip
     * keys that obviously don't reference it.
     */
    public List<WorkflowApiKeyRow> listReferencingDef(String defId) {
        return jdbc.query(sql.getQuery("WORKFLOW_API_KEY.LIST_REFERENCING_DEF"),
                new MapSqlParameterSource("likePattern", "%" + defId + "%"), MAPPER);
    }

    /**
     * System-level scope rewrite. Used by workflow deletion to scrub the
     * deleted def id out of every key that referenced it. No ownership
     * check — this is invoked by a server-side cleanup, not by an end user.
     */
    public void setScope(String id, String processDefIds) {
        jdbc.update(sql.getQuery("WORKFLOW_API_KEY.SET_SCOPE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("processDefIds", processDefIds));
    }

    public boolean revoke(String id, String ownerId, long revokedAt) {
        int rows = jdbc.update(sql.getQuery("WORKFLOW_API_KEY.REVOKE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ownerId", ownerId)
                        .addValue("revokedAt", revokedAt));
        return rows > 0;
    }

    /**
     * Hard-delete a revoked key. Only succeeds when the row is owned by
     * {@code ownerId} AND already revoked — the SQL guard makes "purge an
     * active key" a no-op so the only way to remove a key is the two-step
     * revoke-then-purge flow.
     */
    public boolean purge(String id, String ownerId) {
        int rows = jdbc.update(sql.getQuery("WORKFLOW_API_KEY.PURGE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ownerId", ownerId));
        return rows > 0;
    }

    public void touchLastUsed(String id, long ts) {
        jdbc.update(sql.getQuery("WORKFLOW_API_KEY.TOUCH_LAST_USED"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("lastUsedAt", ts));
    }

    /**
     * Update editable fields (name, scope) on an active key. Returns true
     * when a row was actually updated (caller owns it and it isn't revoked).
     * Secret material is untouched — use {@link #rotate} for that.
     */
    public boolean update(String id, String ownerId, String name, String processDefIds) {
        int rows = jdbc.update(sql.getQuery("WORKFLOW_API_KEY.UPDATE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ownerId", ownerId)
                        .addValue("name", name)
                        .addValue("processDefIds", processDefIds));
        return rows > 0;
    }

    /**
     * Rotate the stored prefix + hash in place. Used by the regenerate flow:
     * old plaintext stops working immediately, new plaintext takes over,
     * scope and name are preserved. Returns true when a row was actually
     * updated (caller's ownership matches and the key isn't revoked).
     */
    public boolean rotate(String id, String ownerId, String newPrefix, String newHash) {
        int rows = jdbc.update(sql.getQuery("WORKFLOW_API_KEY.ROTATE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ownerId", ownerId)
                        .addValue("keyPrefix", newPrefix)
                        .addValue("keyHash", newHash));
        return rows > 0;
    }

    private static final RowMapper<WorkflowApiKeyRow> MAPPER = (rs, i) -> new WorkflowApiKeyRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("key_prefix"),
            rs.getString("key_hash"),
            rs.getString("owner_id"),
            rs.getString("process_def_ids"),
            rs.getLong("created_at"),
            (Long) rs.getObject("last_used_at"),
            (Long) rs.getObject("revoked_at"));
}
