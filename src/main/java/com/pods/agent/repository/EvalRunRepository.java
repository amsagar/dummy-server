package com.pods.agent.repository;

import com.pods.agent.domain.EvalRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvalRunRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public EvalRunRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public EvalRun save(EvalRun run) {
        if (run.getId() == null) run.setId(UUID.randomUUID().toString());
        if (run.getStartedAt() == 0) run.setStartedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("EVAL_RUN.INSERT"), new MapSqlParameterSource()
                .addValue("id", run.getId())
                .addValue("name", run.getName())
                .addValue("status", run.getStatus())
                .addValue("datasetRef", run.getDatasetRef())
                .addValue("scoreSummary", run.getScoreSummary())
                .addValue("traceRef", run.getTraceRef())
                .addValue("startedAt", run.getStartedAt())
                .addValue("completedAt", run.getCompletedAt()));
        return run;
    }

    public List<EvalRun> findAll() {
        return jdbc.query(sql.getQuery("EVAL_RUN.FIND_ALL"), (rs, n) -> EvalRun.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .status(rs.getString("status"))
                .datasetRef(rs.getString("dataset_ref"))
                .scoreSummary(rs.getString("score_summary"))
                .traceRef(rs.getString("trace_ref"))
                .startedAt(rs.getLong("started_at"))
                .completedAt((Long) rs.getObject("completed_at"))
                .build());
    }

    public Optional<EvalRun> findById(String id) {
        var list = jdbc.query(sql.getQuery("EVAL_RUN.FIND_BY_ID"), (rs, n) -> EvalRun.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .status(rs.getString("status"))
                .datasetRef(rs.getString("dataset_ref"))
                .scoreSummary(rs.getString("score_summary"))
                .traceRef(rs.getString("trace_ref"))
                .startedAt(rs.getLong("started_at"))
                .completedAt((Long) rs.getObject("completed_at"))
                .build(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateStatus(String id, String status, String scoreSummary, String traceRef, Long completedAt) {
        namedJdbc.update(sql.getQuery("EVAL_RUN.UPDATE_STATUS"), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("scoreSummary", scoreSummary)
                .addValue("traceRef", traceRef)
                .addValue("completedAt", completedAt));
    }
}
