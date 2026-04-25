package com.pods.agent.repository;

import com.pods.agent.domain.SessionContextState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SessionContextStateRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sql;

    public SessionContextStateRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sql = sql;
    }

    public void upsert(SessionContextState state) {
        state.setUpdatedAt(System.currentTimeMillis());
        namedJdbc.update(sql.getQuery("SESSION_CONTEXT.UPSERT"), new MapSqlParameterSource()
                .addValue("sessionId", state.getSessionId())
                .addValue("runtimeMode", state.getRuntimeMode())
                .addValue("modelSelectionMode", state.getModelSelectionMode())
                .addValue("modelRef", state.getModelRef())
                .addValue("stateJson", state.getStateJson())
                .addValue("rollingSummary", state.getRollingSummary())
                .addValue("summaryTokens", state.getSummaryTokens())
                .addValue("updatedAt", state.getUpdatedAt()));
    }

    public Optional<SessionContextState> findBySessionId(String sessionId) {
        var list = jdbc.query(sql.getQuery("SESSION_CONTEXT.FIND_BY_SESSION"), (rs, n) -> SessionContextState.builder()
                .sessionId(rs.getString("session_id"))
                .runtimeMode(rs.getString("runtime_mode"))
                .modelSelectionMode(rs.getString("model_selection_mode"))
                .modelRef(rs.getString("model_ref"))
                .stateJson(rs.getString("state_json"))
                .rollingSummary(rs.getString("rolling_summary"))
                .summaryTokens(rs.getLong("summary_tokens"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), sessionId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
