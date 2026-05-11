package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditTrailRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public AuditTrailRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void insert(AuditTrailRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_AUDIT.INSERT"),
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("instId", row.instId())
                        .addValue("activityInstId", row.activityInstId())
                        .addValue("action", row.action())
                        .addValue("actor", row.actor())
                        .addValue("ts", row.ts())
                        .addValue("payloadJson", row.payloadJson()));
    }

    public List<AuditTrailRow> findByInstId(String instId) {
        return jdbc.query(sql.getQuery("WORKFLOW_AUDIT.FIND_BY_INST_ID"),
                new MapSqlParameterSource("instId", instId),
                RowMappers.AUDIT);
    }

    public Map<String, Long> transitionOutcomeCounts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                sql.getQuery("WORKFLOW_AUDIT.TRANSITION_OUTCOME_COUNTS"),
                new MapSqlParameterSource());
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.getOrDefault("outcome", "unknown"));
            Object count = row.get("c");
            long value = count instanceof Number n ? n.longValue() : 0L;
            out.put(key, value);
        }
        return out;
    }
}
