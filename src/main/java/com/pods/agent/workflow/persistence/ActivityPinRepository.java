package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityPinRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public ActivityPinRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void upsert(ActivityPinRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_ACTIVITY_PIN.UPSERT"),
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("defId", row.defId())
                        .addValue("activityDefId", row.activityDefId())
                        .addValue("pinnedOutput", row.pinnedOutput())
                        .addValue("createdAt", row.createdAt())
                        .addValue("updatedAt", row.updatedAt())
                        .addValue("createdBy", row.createdBy()));
    }

    public List<ActivityPinRow> findByDef(String defId) {
        return jdbc.query(sql.getQuery("WORKFLOW_ACTIVITY_PIN.FIND_BY_DEF"),
                new MapSqlParameterSource("defId", defId), MAPPER);
    }

    public Optional<ActivityPinRow> findOne(String defId, String activityDefId) {
        List<ActivityPinRow> rs = jdbc.query(sql.getQuery("WORKFLOW_ACTIVITY_PIN.FIND_ONE"),
                new MapSqlParameterSource()
                        .addValue("defId", defId)
                        .addValue("activityDefId", activityDefId),
                MAPPER);
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(0));
    }

    public boolean delete(String defId, String activityDefId) {
        return jdbc.update(sql.getQuery("WORKFLOW_ACTIVITY_PIN.DELETE"),
                new MapSqlParameterSource()
                        .addValue("defId", defId)
                        .addValue("activityDefId", activityDefId)) > 0;
    }

    private static final RowMapper<ActivityPinRow> MAPPER = (rs, i) -> new ActivityPinRow(
            rs.getString("id"),
            rs.getString("def_id"),
            rs.getString("activity_def_id"),
            rs.getString("pinned_output"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getString("created_by"));
}
