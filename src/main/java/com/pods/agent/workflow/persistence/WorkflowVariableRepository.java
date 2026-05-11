package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowVariableRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public WorkflowVariableRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void upsert(WorkflowVariableRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_VARIABLE.UPSERT"),
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("instId", row.instId())
                        .addValue("scope", row.scope())
                        .addValue("name", row.name())
                        .addValue("javaClass", row.javaClass())
                        .addValue("valueJson", row.valueJson())
                        .addValue("updatedAt", row.updatedAt()));
    }

    public List<WorkflowVariableRow> findByInstAndScope(String instId, String scope) {
        return jdbc.query(sql.getQuery("WORKFLOW_VARIABLE.FIND_BY_INST_AND_SCOPE"),
                new MapSqlParameterSource()
                        .addValue("instId", instId)
                        .addValue("scope", scope),
                RowMappers.VARIABLE);
    }
}
