package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessDefRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public ProcessDefRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void upsert(ProcessDefRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_PROCESS_DEF.UPSERT"),
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("name", row.name())
                        .addValue("version", row.version())
                        .addValue("packageId", row.packageId())
                        .addValue("description", row.description())
                        .addValue("xpdlJson", row.xpdlJson())
                        .addValue("createdAt", row.createdAt())
                        .addValue("updatedAt", row.updatedAt()));
    }

    public Optional<ProcessDefRow> findById(String id) {
        List<ProcessDefRow> rs = jdbc.query(sql.getQuery("WORKFLOW_PROCESS_DEF.FIND_BY_ID"),
                new MapSqlParameterSource("id", id), RowMappers.PROCESS_DEF);
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(0));
    }

    public List<ProcessDefRow> findAll() {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_DEF.FIND_ALL"),
                new MapSqlParameterSource(), RowMappers.PROCESS_DEF);
    }

    public List<ProcessDefRow> findByName(String name) {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_DEF.FIND_BY_NAME"),
                new MapSqlParameterSource("name", name), RowMappers.PROCESS_DEF);
    }

    public int deleteById(String id) {
        return jdbc.update(sql.getQuery("WORKFLOW_PROCESS_DEF.DELETE_BY_ID"),
                new MapSqlParameterSource("id", id));
    }
}
