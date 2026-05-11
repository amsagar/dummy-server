package com.pods.agent.workflow.persistence;

import com.pods.agent.repository.SqlQueryLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
public class ProcessInstRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public ProcessInstRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void insert(ProcessInstRow row) {
        jdbc.update(sql.getQuery("WORKFLOW_PROCESS_INST.INSERT"), params(row));
    }

    public void updateState(String id,
                            String state,
                            Long endedAt,
                            String errorClass,
                            String errorMessage) {
        jdbc.update(sql.getQuery("WORKFLOW_PROCESS_INST.UPDATE_STATE"),
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("state", state)
                        .addValue("endedAt", endedAt)
                        .addValue("errorClass", errorClass)
                        .addValue("errorMessage", errorMessage));
    }

    public Optional<ProcessInstRow> findById(String id) {
        List<ProcessInstRow> rs = jdbc.query(sql.getQuery("WORKFLOW_PROCESS_INST.FIND_BY_ID"),
                new MapSqlParameterSource("id", id), RowMappers.PROCESS_INST);
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(0));
    }

    public List<ProcessInstRow> findByDefId(String defId, int limit, int offset) {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_INST.FIND_BY_DEF_ID"),
                new MapSqlParameterSource()
                        .addValue("defId", defId)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                RowMappers.PROCESS_INST);
    }

    public long countByDefId(String defId) {
        Long out = jdbc.queryForObject(sql.getQuery("WORKFLOW_PROCESS_INST.COUNT_BY_DEF_ID"),
                new MapSqlParameterSource("defId", defId), Long.class);
        return out == null ? 0L : out;
    }

    public int deleteByDefId(String defId) {
        return jdbc.update(sql.getQuery("WORKFLOW_PROCESS_INST.DELETE_BY_DEF_ID"),
                new MapSqlParameterSource("defId", defId));
    }

    public long countAll() {
        Long out = jdbc.queryForObject(sql.getQuery("WORKFLOW_PROCESS_INST.COUNT_ALL"),
                new MapSqlParameterSource(), Long.class);
        return out == null ? 0L : out;
    }

    public Map<String, Long> statusCounts() {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_INST.STATUS_COUNTS"),
                new MapSqlParameterSource(),
                (rs) -> {
                    java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
                    while (rs.next()) out.put(rs.getString("state"), rs.getLong("c"));
                    return out;
                });
    }

    public List<ProcessInstRow> findAll(int limit, int offset) {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_INST.FIND_ALL"),
                new MapSqlParameterSource()
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                RowMappers.PROCESS_INST);
    }

    /**
     * Cross-workflow filtered list. Each filter is nullable; null skips that
     * predicate. {@code state} matches a single state exactly (e.g.
     * {@code closed.completed}), while {@code stateLike} accepts a SQL LIKE
     * pattern (e.g. {@code open.%}) for prefix-based grouping.
     */
    public List<ProcessInstRow> listFiltered(String state,
                                             String stateLike,
                                             String defId,
                                             String requesterLike,
                                             Long fromTs,
                                             Long toTs,
                                             int limit,
                                             int offset) {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_INST.LIST_FILTERED"),
                filteredParams(state, stateLike, defId, requesterLike, fromTs, toTs)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                RowMappers.PROCESS_INST);
    }

    public List<ProcessInstRow> findChildren(String parentInstId) {
        return jdbc.query(sql.getQuery("WORKFLOW_PROCESS_INST.FIND_CHILDREN"),
                new MapSqlParameterSource("parentInstId", parentInstId),
                RowMappers.PROCESS_INST);
    }

    public long countFiltered(String state,
                              String stateLike,
                              String defId,
                              String requesterLike,
                              Long fromTs,
                              Long toTs) {
        Long out = jdbc.queryForObject(sql.getQuery("WORKFLOW_PROCESS_INST.COUNT_FILTERED"),
                filteredParams(state, stateLike, defId, requesterLike, fromTs, toTs),
                Long.class);
        return out == null ? 0L : out;
    }

    private static MapSqlParameterSource filteredParams(String state,
                                                        String stateLike,
                                                        String defId,
                                                        String requesterLike,
                                                        Long fromTs,
                                                        Long toTs) {
        return new MapSqlParameterSource()
                .addValue("state", isBlank(state) ? null : state)
                .addValue("stateLike", isBlank(stateLike) ? null : stateLike)
                .addValue("defId", isBlank(defId) ? null : defId)
                .addValue("requesterLike", isBlank(requesterLike) ? null : "%" + requesterLike + "%")
                .addValue("fromTs", fromTs)
                .addValue("toTs", toTs);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static MapSqlParameterSource params(ProcessInstRow r) {
        return new MapSqlParameterSource()
                .addValue("id", r.id())
                .addValue("defId", r.defId())
                .addValue("state", r.state())
                .addValue("startedAt", r.startedAt())
                .addValue("endedAt", r.endedAt())
                .addValue("requesterId", r.requesterId())
                .addValue("parentInstId", r.parentInstId())
                .addValue("dueAt", r.dueAt())
                .addValue("errorClass", r.errorClass())
                .addValue("errorMessage", r.errorMessage());
    }
}
